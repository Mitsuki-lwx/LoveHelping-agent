#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""30 分钟混合负载长稳压测（Phase 6 收尾，docs/phase6-soak）。

设计（见 docs/phase6-soak/spec.md §3）：
  连续 worker 持续发起**混合**请求，不做批次同步——批次同步会让并发永远等于 worker 数，
  有界队列根本用不上。这里默认 --workers 32（= 闸门 24 + 队列 8），使在途稳定贴住闸门、
  队列长期非零，这样"队列深度是否随时间漂移"才是一个可观测的量。

  每个 worker 独立用户（token），避免落到"每用户突发桶"（那是防脚本维度，不是容量）。

每 --sample-sec 从 /actuator/prometheus 采样；负载结束后继续 --cool-down 秒采样，
用于验证 permit 是否全部回收（in-flight 归零）。最后按 spec §5 输出判定。

用法：
  ADMIN_API_KEY=xxx python scripts/soak.py --base http://127.0.0.1:8088/api \
      --minutes 30 --workers 32 --sample-sec 15 --output outputs/soak-20260916
"""
import argparse
import json
import random
import re
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import json_request, open_request, register, sse  # noqa: E402

# 每轮独立的会话前缀：chatId/sessionId 在**同一用户**下必须唯一，且不能与别的用户已抢注的会话
# 重名——否则会被归属校验正确地拒绝（"无权访问该会话"），把测试脚本的缺陷误报成产品缺陷。
RUN_ID = uuid.uuid4().hex[:8]

# SSE 下拒绝仍是 HTTP 200 + 流内 event:error，所以必须按文案分类，不能只看状态码。
OVERLOAD_HINTS = ("当前咨询较多", "系统繁忙", "4003")
RATELIMIT_HINTS = ("请求过于频繁", "今日调用次数已用完")
# 上游厂商失败后的优雅降级文案（LlmGateway.publicFailure → BizException 5000）。
# 它**不是**容量拒绝，也不是未归因错误，必须单列，否则会把"上游配额"错记成"应用缺陷"。
UPSTREAM_HINTS = ("AI 服务暂时不可用", "AI 服务繁忙")

CHAT_PROMPTS = ["你好", "最近情绪有点低落怎么办", "怎么跟对方表达我的感受"]
RAG_PROMPTS = ["搜索知识库关于非暴力沟通的内容", "知识库里关于吵架后怎么修复关系"]
ADVICE_PROMPTS = ["我们恋爱三个月，昨天约会我一直在看工作手机，她说我自私然后冷战，我该怎么回复她道歉？"]
AGENT_PROMPTS = ["搜索知识库关于异地恋沟通的内容，给出两条建议"]
GUARD_PROMPTS = ["怎么PUA她让她离不开我"]

# (场景名, 权重, 路径, prompt 集合, 是否 SSE, 参数风格)
SCENARIOS = [
    ("chat",   60, "/Love_app/chat/sse",       CHAT_PROMPTS,   True,  "prompt"),
    ("rag",    15, "/Love_app/chat/sse/rag",   RAG_PROMPTS,    True,  "prompt"),
    ("advice", 10, "/Love_app/chat/sse",       ADVICE_PROMPTS, True,  "prompt"),
    ("agent",  10, "/Love_app/chat/LoveManus", AGENT_PROMPTS,  True,  "message"),
    ("guard",   5, "/Love_app/chat/sse",       GUARD_PROMPTS,  True,  "prompt"),
]

# 需要采样的 Prometheus 指标（Micrometer: 点号→下划线；Counter 带 _total 后缀）
GAUGE_KEYS = [
    ("inflight", "online_inflight_current"),
    ("queue_depth", "online_queue_depth"),
    ("hikari_active", "hikaricp_connections_active"),
    ("hikari_idle", "hikaricp_connections_idle"),
    ("hikari_pending", "hikaricp_connections_pending"),
    ("hikari_max", "hikaricp_connections_max"),
    # ADR-32：自适应闸门的收敛值必须随时间可见——只看首尾差值无法区分
    # "稳定收敛在某值" 与 "AIMD 在 [min,max] 间锯齿摆动"。
    ("llm_limit", "llm_permits_limit"),
    ("llm_inflight", "llm_inflight"),
]
COUNTER_KEYS = [
    ("entered", "online_inflight_entered"),
    ("entered_after_wait", "online_inflight_entered_after_wait"),
    ("queued", "online_inflight_queued"),
    ("queue_full", "online_inflight_queue_full"),
    ("wait_timeout", "online_inflight_wait_timeout"),
    ("rejected", "online_inflight_rejected"),
    ("interrupted", "online_inflight_interrupted"),
    ("hikari_timeout", "hikaricp_connections_timeout_total"),
]


def parse_prometheus(text):
    """把 /actuator/prometheus 文本解析成 {metric_base: [(labels, value), ...]}。"""
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            head, value = line.rsplit(" ", 1)
            val = float(value)
        except ValueError:
            continue
        name, labels = head, ""
        if "{" in head:
            name, labels = head.split("{", 1)
            labels = labels.rstrip("}")
        base = name[:-6] if name.endswith("_total") else name
        out.setdefault(base, []).append((labels, val))
    return out


def pick_metric(parsed, base):
    """优先精确名，其次带 _total；多个序列求和（如 JVM 各内存池）。

    注意 parse_prometheus 已把 `xxx_total` 归一化成 `xxx` 作为 key，
    所以请求名自带 `_total` 时（如 hikaricp_connections_timeout_total）必须先剥后缀，
    否则永远查不到 → 指标静默为 None → 判定被真空通过（2026-09-16 修）。
    """
    candidates = [base]
    if base.endswith("_total"):
        candidates.append(base[:-len("_total")])
    else:
        candidates.append(base + "_total")
    for key in candidates:
        if key in parsed:
            return sum(v for _, v in parsed[key])
    return None


def sample_metrics(base):
    req = urllib.request.Request(base.rstrip("/") + "/actuator/prometheus", headers={"Accept": "text/plain"})
    try:
        with open_request(req, timeout=15) as response:
            parsed = parse_prometheus(response.read().decode("utf-8", "replace"))
    except Exception as error:  # 采样失败必须显式记录，不能静默跳过
        return {"sample_error": str(error)}
    row = {"ts": time.time()}
    for alias, name in GAUGE_KEYS + COUNTER_KEYS:
        row[alias] = pick_metric(parsed, name)
    row["heap_used"] = sum(v for labels, v in parsed.get("jvm_memory_used_bytes", []) if 'area="heap"' in labels) or None
    # 注意：G1 的 Eden/Survivor 用 -1 表示"未定义上限"，必须过滤，否则求和会带上哨兵值。
    heap_max_values = [v for labels, v in parsed.get("jvm_memory_max_bytes", [])
                       if 'area="heap"' in labels and v >= 0]
    row["heap_max"] = sum(heap_max_values) if heap_max_values else None
    row["gc_pause_count"] = (sum(v for _, v in parsed.get("jvm_gc_pause_seconds_count", []))
                             if "jvm_gc_pause_seconds_count" in parsed else None)
    return row


def sample_llm_counters(base):
    """抓取 llm_* 计数器（保留标签），用于首尾比对——这是"降级链/熔断/用量"唯一的硬证据。"""
    req = urllib.request.Request(base.rstrip("/") + "/actuator/prometheus", headers={"Accept": "text/plain"})
    try:
        with open_request(req, timeout=15) as response:
            text = response.read().decode("utf-8", "replace")
    except Exception as error:
        return {"sample_error": str(error)}
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("llm_") or " " not in line:
            continue
        head, value = line.rsplit(" ", 1)
        try:
            out[head] = float(value)
        except ValueError:
            continue
    return out


def counter_delta(before, after):
    keys = sorted(set(before) | set(after))
    return {k: round(after.get(k, 0.0) - before.get(k, 0.0), 3) for k in keys
            if abs(after.get(k, 0.0) - before.get(k, 0.0)) > 1e-9}


def llm_attribution(delta):
    """把 llm_call_total{provider,outcome} 的增量整理成 {provider: {outcome: n}}。

    这是"上游到底挂了什么"唯一的硬证据：应用侧只看到 5000 优雅降级文案，
    而计数器能区分 primary/fallback 各自的 success/fail/timeout/rejected。
    """
    out = {}
    for key, value in delta.items():
        if not key.startswith("llm_call_total"):
            continue
        provider = re.search(r'provider="([^"]+)"', key)
        outcome = re.search(r'outcome="([^"]+)"', key)
        if provider and outcome:
            out.setdefault(provider.group(1), {})[outcome.group(1)] = value
    return out


def classify(response):
    """把一次调用的结果归类。

    ok / overloaded(闸门 4003) / rate_limited(429 限流) / upstream(上游失败后的优雅降级)
    / http5xx / err(剩余未归因)
    """
    blob = (response.get("text") or "") + " " + " ".join(response.get("errors") or [])
    if any(h in blob for h in OVERLOAD_HINTS):
        return "overloaded"
    if any(h in blob for h in RATELIMIT_HINTS):
        return "rate_limited"
    if any(h in blob for h in UPSTREAM_HINTS):
        return "upstream"
    if response.get("success"):
        return "ok"
    return "err"


def call_once(base, token, scenario, seq):
    name, _, path, prompts, _is_sse, arg_style = scenario
    prompt = random.choice(prompts)
    if arg_style == "message":
        params = {"message": prompt, "sessionId": "%s_%s_%d" % (RUN_ID, name, seq)}
    else:
        params = {"prompt": prompt, "chatId": "%s_%s_%d" % (RUN_ID, name, seq)}
    started = time.perf_counter()
    try:
        response = sse(base, path, params, token, timeout=120)
    except urllib.error.HTTPError as error:
        return {"scenario": name, "kind": "http5xx" if error.code >= 500 else "err",
                "http": error.code, "detail": str(error), "duration_ms": round((time.perf_counter() - started) * 1000, 1)}
    except Exception as error:
        return {"scenario": name, "kind": "err", "http": None, "detail": type(error).__name__ + ": " + str(error)[:200],
                "duration_ms": round((time.perf_counter() - started) * 1000, 1)}
    return {"scenario": name, "kind": classify(response), "http": response.get("status"),
            "ttft_ms": response.get("ttft_ms"), "duration_ms": response.get("duration_ms"),
            "chars": len(response.get("text") or ""), "errors": (response.get("errors") or [])[:1],
            "detail": "" if response.get("success") else (response.get("text") or "")[:120]}


def weighted_plan():
    plan = []
    for scenario in SCENARIOS:
        plan.extend([scenario] * scenario[1])
    return plan


def fmt_row(row):
    return "|".join("%s=%s" % (k, row.get(k)) for k in
                    ("ts", "inflight", "llm_limit", "llm_inflight", "queue_depth",
                     "hikari_active", "hikari_idle", "hikari_pending", "heap_used"))


def summarize(samples, requests, args, started_at, load_ended_at):
    """按 spec §5 判定。预热段 = 前 5 分钟，稳态段 = 之后。"""
    clean = [s for s in samples if s.get("sample_error") is None and s.get("inflight") is not None]
    def phase(rows, lo, hi):
        return [r for r in rows if lo <= (r["ts"] - started_at) < hi]
    warm, steady = phase(clean, 0, args.warmup_sec), phase(clean, args.warmup_sec, 10 ** 9)
    def values(rows, key):
        return [r[key] for r in rows if r.get(key) is not None]
    q = values(steady, "queue_depth")
    third = max(1, len(q) // 3)
    q_head = statistics.mean(q[:third]) if q else 0
    q_tail = statistics.mean(q[-third:]) if q else 0
    after_load = [r for r in clean if r["ts"] >= load_ended_at]
    inflight_after = values(after_load, "inflight")

    # 采样必须够用才允许判定（spec §4：空采样即整轮失败，不得静默通过）
    heap = values(steady, "heap_used")
    heap_head = statistics.mean(heap[:third]) if heap else 0
    heap_tail = statistics.mean(heap[-third:]) if heap else 0
    heap_max = values(steady, "heap_max")
    heap_max = heap_max[-1] if heap_max else None
    active = values(steady, "hikari_active")
    active_peak = max(active) if active else None
    active_baseline = active[0] if active else None
    pool_max = values(steady, "hikari_max")
    pool_max = pool_max[-1] if pool_max else None
    active_after = values(after_load, "hikari_active")
    pending = values(steady, "hikari_pending")
    pending_p95 = (statistics.quantiles(pending, n=20)[18] if len(pending) >= 20
                   else (max(pending) if pending else None))
    timeouts = values(steady, "hikari_timeout")
    timeout_delta = (timeouts[-1] - timeouts[0]) if len(timeouts) >= 2 else None

    kinds = {}
    per_scenario = {}
    http5xx = 0
    for r in requests:
        kinds[r["kind"]] = kinds.get(r["kind"], 0) + 1
        scen = per_scenario.setdefault(r["scenario"], {})
        scen[r["kind"]] = scen.get(r["kind"], 0) + 1
        if r["kind"] == "http5xx":
            http5xx += 1
    total = max(1, len(requests))
    reject_rows = [r for r in requests if r["kind"] in ("overloaded", "rate_limited")]
    # S2：拒绝必须是「有说明的」——流内 event:error 且带可读文案；
    # 裸状态码拒绝（http != 200 或文案为空）一律判失败。
    bare_rejects = [r for r in reject_rows
                    if r.get("http") != 200 or not ((r.get("errors") or [""])[0] or "").strip()]
    rejected = len(reject_rows)
    err = kinds.get("err", 0)
    upstream = kinds.get("upstream", 0)

    # S9 口径修订（2026-09-19，见 docs/phase6-soak/spec.md §5.2）：
    # ADR-32 之后闸门是 AIMD 自适应，"在途 = 24"不再是**可持续**工作点
    # （24 并发本身就会触发厂商 429 → 乘性收缩 → 有效天花板降到 12 上下）。
    # 因此判据必须按「过订阅倍数 = offered 并发 / 稳态收敛天花板」分档，
    # 而不是按固定 worker 数——否则固定 32 worker 在任何自适应实现下都必然"不通过"。
    llm_limit = values(steady, "llm_limit")
    limit_end = llm_limit[-1] if llm_limit else None
    limit_mean = statistics.mean(llm_limit) if llm_limit else None
    oversub = (args.workers / limit_mean) if limit_mean else None
    # S9a/S9b 互斥分档（见下方 verdict 注释）。oversub 为 None 时两者都不适用，
    # 但此时**两条都判失败**（证据缺失不得真空通过），故此处只表达"适用性"。
    s9a_applies = oversub is not None and oversub <= 1.0
    s9b_applies = oversub is not None and oversub > 1.0
    # 三态，别写成 `s9x_applies and ...`（不适用时会误判为失败，实测踩过两次）：
    #   证据缺失（oversub is None）→ 失败
    #   本档适用                   → 按断言判
    #   本档不适用                 → 自动通过
    s9a_pass = (oversub is not None) and ((rejected / total <= 0.20) if s9a_applies else True)
    s9b_pass = (oversub is not None) and (
        (len(bare_rejects) == 0 and http5xx == 0 and rejected > 0) if s9b_applies else True)

    heap_ok = bool(heap) and heap_tail <= heap_head * 1.3 and (heap_max is None or max(heap) < heap_max)
    active_ok = (active_peak is not None and pool_max is not None and active_peak < pool_max
                 and (not active_after or active_baseline is None
                      or active_after[-1] <= active_baseline + 2))

    verdict = {
        "s1_5xx": {"value": http5xx, "pass": http5xx == 0},
        "s2_explained_rejects": {"rejects": rejected, "bare_rejects": len(bare_rejects),
                                 "pass": not bare_rejects},
        "s3_queue_no_drift": {"head_mean": round(q_head, 2), "tail_mean": round(q_tail, 2),
                              "pass": q_tail <= q_head + 2},
        "s4_queue_peak": {"peak": max(q) if q else 0, "saturated_ratio": round(sum(1 for v in q if v >= 24) / len(q), 3) if q else 0,
                          "pass": (max(q) if q else 0) <= 24 and (sum(1 for v in q if v >= 24) / len(q) if q else 0) < 0.2},
        # S5/S6/S7：采样缺失一律判失败（原来固定 pass:true，属真空通过）
        "s5_hikari_pending_p95": {"p95": pending_p95, "samples": len(pending),
                                  "pass": pending_p95 is not None and pending_p95 == 0},
        "s6_hikari_timeout": {"delta": timeout_delta, "samples": len(timeouts),
                              "pass": timeout_delta is not None and timeout_delta == 0},
        "s7_hikari_active": {"peak": active_peak, "pool_max": pool_max, "baseline": active_baseline,
                             "after_load": active_after[-1] if active_after else None, "pass": active_ok},
        "s8_inflight_zero": {"last": inflight_after[-1] if inflight_after else None,
                             "samples_after_load": len(inflight_after),
                             "pass": bool(inflight_after) and inflight_after[-1] == 0},
        # S9 拆两条（口径修订 2026-09-19，spec §5.2）。原「固定 32 worker 下拒绝率 ≤ 20%」
        # 与 ADR-32 的自适应闸门互相矛盾：32 worker 在收敛到 12 时就是 2.7 倍过订阅，
        # 高拒绝率是**背压的设计行为**，不是缺陷。故按过订阅倍数分档：
        #   S9a（额定档位，offered ≤ 收敛值）→ 设拒绝率门禁
        #   S9b（过载，offered > 收敛值）→ **不设拒绝率门禁**，只断言"降级可读且有界"
        # 两者互斥：未适用的一条**自动通过**（applies=false → pass=true），
        # 否则会把它当成失败（实测踩过：8 worker 那轮 s9b 误判 pass=false）。
        # 但 oversub 算不出来（缺 llm_permits_limit 采样）时**两条都判失败**——
        # 同 S5/S6/S7 纪律，不得因证据缺失而真空通过。
        "s9a_rated_reject_rate": {
            "applies": s9a_applies,
            "oversubscription": round(oversub, 2) if oversub is not None else None,
            "limit_mean": round(limit_mean, 2) if limit_mean is not None else None,
            "workers": args.workers,
            "rejected": rejected, "total": total, "rate": round(rejected / total, 4),
            "pass": s9a_pass,
            "note": ("额定档位（未过订阅）下闸门不得误伤：硬拒率 ≤ 20%" if oversub is not None
                     else "缺少 llm_permits_limit 采样，无法分档 → 判失败（不得真空通过）"),
        },
        "s9b_overload_graceful": {
            "applies": s9b_applies,
            "oversubscription": round(oversub, 2) if oversub is not None else None,
            "limit_mean": round(limit_mean, 2) if limit_mean is not None else None,
            "limit_end": limit_end,
            "workers": args.workers,
            "rejected": rejected, "total": total, "rate": round(rejected / total, 4),
            "bare_rejects": len(bare_rejects), "http5xx": http5xx,
            # 只断言"降级可读且有界"：拒绝必须带可读文案（S2）、不得出现 5xx（S1）、
            # 且确实发生了背压（否则这一轮并没有真正过载，结论无意义）。
            "pass": s9b_pass,
            "note": ("过载下高拒绝率是背压的设计行为；本项不设拒绝率门禁，只断言可读+有界"
                     if oversub is not None
                     else "缺少 llm_permits_limit 采样，无法分档 → 判失败（不得真空通过）"),
        },
        # S10 原判据是"厂商 429 = 0"，其前提是"闸门 24 对齐厂商并发上限即可不触发 429"。
        # 实测暴露：厂商还按**速率**（bigmodel code 1302 / dashscope Throttling.RateQuota）限流，
        # 持续满载会触发，因此该断言按**突发**口径成立、按**持续**口径不成立——据实判 fail
        # 并单独记录，不调参掩盖（见 docs/09 §8.9）。
        "s10_vendor_429": {"upstream_degraded": upstream, "rate": round(upstream / total, 4),
                           "note": "上游 429 → 重试耗尽 → 优雅降级为可读文案；按持续口径该断言不成立",
                           "pass": upstream == 0},
        "s11_heap": {"head_mean": round(heap_head), "tail_mean": round(heap_tail),
                     "max": max(heap) if heap else None, "max_heap": heap_max,
                     "note": "判据=稳态尾段均值 ≤ 首段均值×1.3 且峰值未贴顶（不单调增长）",
                     "pass": heap_ok},
        "s12_err_rate": {"err": err, "rate": round(err / total, 4), "pass": err / total <= 0.01},
        # S13：稳态段采样为空必须整轮失败（spec §4）
        "s13_evidence": {"samples": len(samples), "clean": len(clean), "warmup": len(warm),
                         "steady": len(steady), "requests": len(requests),
                         "pass": len(steady) >= 80},
        "s14_upstream_attributed": {"upstream": upstream, "rate": round(upstream / total, 4),
                                    "note": "上游失败已归因（非未说明拒绝）；计入拒绝率口径外单独报告",
                                    "pass": True},
    }
    return {"started_at": started_at, "duration_min": args.minutes, "workers": args.workers,
            "warmup_samples": len(warm), "steady_samples": len(steady),
            "kinds": kinds, "per_scenario": per_scenario, "http5xx": http5xx,
            "ttft_ms": {"median": round(statistics.median([r["ttft_ms"] for r in requests if r.get("ttft_ms")]), 1)
                        if any(r.get("ttft_ms") for r in requests) else None},
            "reject_rate": round(rejected / total, 4), "upstream_rate": round(upstream / total, 4),
            "oversubscription": round(oversub, 2) if oversub is not None else None,
            "llm_limit_mean": round(limit_mean, 2) if limit_mean is not None else None,
            "llm_limit_end": limit_end,
            "verdict": verdict,
            "app_side_pass": all(v.get("pass", True) for k, v in verdict.items() if k != "s10_vendor_429"),
            "all_pass": all(v.get("pass", True) for v in verdict.values())}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8088/api")
    parser.add_argument("--minutes", type=float, default=30.0)
    parser.add_argument("--workers", type=int, default=32, help="连续 worker 数（闸门 24 + 队列 8）")
    parser.add_argument("--sample-sec", type=float, default=15.0)
    parser.add_argument("--warmup-sec", type=float, default=300.0,
                        help="预热段秒数（默认 300 = spec §5 的正式门禁口径；短程逻辑验证可调小，"
                             "但正式验收必须用默认值，且需 sample-sec 配合使稳态采样 ≥ 80 点）")
    parser.add_argument("--cool-down", type=float, default=120.0, help="负载结束后继续采样秒数（验证 permit 回收）")
    parser.add_argument("--output", required=True, help="证据文件前缀，实际写 -samples.jsonl / -requests.jsonl / -summary.json")
    args = parser.parse_args()

    print("[soak] 注册 %d 个独立用户 …" % args.workers, flush=True)
    tokens = [register(args.base)[1] for _ in range(args.workers)]
    print("[soak] 用户就绪；负载 %s 分钟 / workers=%d / 采样 %ss" % (args.minutes, args.workers, args.sample_sec), flush=True)
    llm_before = sample_llm_counters(args.base)

    samples, requests, req_lock, stop = [], [], threading.Lock(), threading.Event()
    started_at = time.time()
    load_end = started_at + args.minutes * 60

    def sampler():
        while not stop.is_set():
            row = sample_metrics(args.base)
            row["ts"] = time.time()
            with req_lock:
                samples.append(row)
            print("[sample] " + fmt_row(row), flush=True)
            stop.wait(args.sample_sec)

    def worker(index):
        seq = 0
        plan = weighted_plan()
        while time.time() < load_end and not stop.is_set():
            seq += 1
            result = call_once(args.base, tokens[index], random.choice(plan), index * 100000 + seq)
            result["ts"] = time.time()
            with req_lock:
                requests.append(result)
            if index == 0:
                print("[req] %s" % json.dumps(result, ensure_ascii=False), flush=True)

    sampler_thread = threading.Thread(target=sampler, daemon=True)
    sampler_thread.start()
    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(args.workers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    load_ended_at = time.time()
    print("[soak] 负载结束，冷却采样 %.0fs（验证 in-flight 归零）…" % args.cool_down, flush=True)
    time.sleep(args.cool_down)
    stop.set()
    sampler_thread.join(timeout=30)

    prefix = Path(args.output)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    Path(str(prefix) + "-samples.jsonl").write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in samples), encoding="utf-8")
    Path(str(prefix) + "-requests.jsonl").write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in requests), encoding="utf-8")
    summary = summarize(samples, requests, args, started_at, load_ended_at)
    summary["llm_counters_delta"] = counter_delta(llm_before, sample_llm_counters(args.base))
    summary["llm_attribution"] = llm_attribution(summary["llm_counters_delta"])
    Path(str(prefix) + "-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[soak] LLM 计数器增量: " + json.dumps(summary["llm_counters_delta"], ensure_ascii=False), flush=True)
    print("[soak] 上游归因: " + json.dumps(summary["llm_attribution"], ensure_ascii=False), flush=True)
    print("[soak] 汇总: " + json.dumps({k: v for k, v in summary.items() if k != "per_scenario"}, ensure_ascii=False), flush=True)
    return 0 if summary["all_pass"] else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
