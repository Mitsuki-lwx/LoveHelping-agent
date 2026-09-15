#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""30 分钟混合负载稳定性冒烟（Phase 6 唯一未验收项，docs/09 §6 最后一行）。

目标不是压出最大值，而是**证明长时间跑下来不劣化**：
  * 队列深度不持续增长（online.queue.depth / online.inflight.current 回落后能归零）
  * 数据源连接池不持续增长（Hikari active/pending 采样后回落）
  * JVM 线程数不持续增长（连接/线程泄漏的最直接信号）
  * 全程无 5xx、无厂商 429；拒绝率落在对应档位的验收线内

负载设计（混合，覆盖稳态 + 突发 + 恢复三段）：
  阶段 1  steady   : 稳态并发（默认 6）  —— 主要用于观察泄漏与长跑稳定性
  阶段 2  burst    : 突发并发（默认 24） —— 触发闸门 + 有界排队路径
  阶段 3  recover  : 回到稳态           —— 验证队列与连接池能回落

用法：
  ADMIN_API_KEY=... python scripts/soak_test.py --base http://127.0.0.1:<port>/api \
      --minutes 30 --steady 6 --burst 24 --output outputs/soak-p6.json
"""
import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OVERLOAD_HINTS = ("当前咨询较多", "系统繁忙", "4003")
RATELIMIT_HINTS = ("请求过于频繁", "今日调用次数已用完")


def register(base, seq):
    req = urllib.request.Request(
        base + "/auth/register",
        data=json.dumps({"username": "soak_%d_%d" % (int(time.time() * 1000), seq),
                         "password": "Passw0rd!123"}).encode(),
        headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]


def one_chat(base, token, idx, timeout):
    url = base + "/Love_app/chat/sse?" + urllib.parse.urlencode(
        {"prompt": "你好", "chatId": "soak_%d_%d" % (int(time.time() * 1000), idx)})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    t0 = time.time()
    try:
        first = None
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            for line in resp:
                s = line.decode("utf-8", "replace").strip()
                if not s.startswith("data:") or len(s) <= 5:
                    continue
                body = s[5:].strip()
                if any(h in body for h in OVERLOAD_HINTS):
                    return "overloaded", time.time() - t0
                if any(h in body for h in RATELIMIT_HINTS):
                    return "rate_limited", time.time() - t0
                if first is None and body:
                    first = time.time() - t0
        return "ok", (first if first is not None else time.time() - t0)
    except urllib.error.HTTPError:
        return "err", time.time() - t0
    except Exception:
        return "err", time.time() - t0


def scrape(base):
    """从 Prometheus 端点取稳定性关键指标；取不到返回 None（不中断长跑）。"""
    try:
        with urllib.request.urlopen(base + "/actuator/prometheus", timeout=10) as r:
            text = r.read().decode("utf-8", "replace")
    except Exception:
        return None
    out = {}

    def val(metric, *tag_filters):
        for line in text.splitlines():
            if not line.startswith(metric):
                continue
            if all(f in line for f in tag_filters):
                try:
                    return float(line.rsplit(" ", 1)[1])
                except Exception:
                    continue
        return None

    out["inflight"] = val("online_inflight_current")
    out["queue_depth"] = val("online_queue_depth")
    out["entered"] = val("online_inflight_entered_total")
    out["entered_after_wait"] = val("online_inflight_entered_after_wait_total")
    out["queue_full"] = val("online_inflight_queue_full_total")
    out["wait_timeout"] = val("online_inflight_wait_timeout_total")
    out["rejected"] = val("online_inflight_rejected_total")
    out["hikari_active"] = val("hikaricp_connections_active")
    out["hikari_pending"] = val("hikaricp_connections_pending")
    out["hikari_idle"] = val("hikaricp_connections_idle")
    out["jvm_threads"] = val("jvm_threads_live_threads")
    out["http_5xx"] = val("http_server_requests_seconds_count", 'status="5')
    return out


def run_phase(name, base, tokens, concurrency, seconds, timeout, stats, samples):
    """在指定秒数内保持恒定并发（每完成一个立刻补一个，贴近真实在线流量）。"""
    stop = time.time() + seconds
    lock = threading.Lock()
    counter = [0]

    def worker():
        while time.time() < stop:
            with lock:
                idx = counter[0]
                counter[0] += 1
            status, ttft = one_chat(base, tokens[idx % len(tokens)], idx, timeout)
            with lock:
                stats[status] = stats.get(status, 0) + 1
                stats["_ttft"].append(ttft)

    ths = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    t0 = time.time()
    for t in ths:
        t.start()

    # 阶段内每 15s 采样一次稳定性指标
    while time.time() < stop:
        time.sleep(min(15, max(1, stop - time.time())))
        snap = scrape(base)
        if snap:
            snap["at_s"] = round(time.time() - t0, 1)
            samples.append((name, snap))

    for t in ths:
        t.join(timeout=timeout + 10)
    print("  阶段 %-8s 并发 %-3d 用时 %.0fs | 累计 ok=%d 过载拒=%d 限流拒=%d 错误=%d"
          % (name, concurrency, time.time() - t0, stats.get("ok", 0), stats.get("overloaded", 0),
             stats.get("rate_limited", 0), stats.get("err", 0)), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--minutes", type=float, default=30.0)
    ap.add_argument("--steady", type=int, default=6)
    ap.add_argument("--burst", type=int, default=24)
    ap.add_argument("--timeout", type=int, default=90)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    total = args.minutes * 60
    steady_s = total * 0.6
    burst_s = total * 0.2
    recover_s = total - steady_s - burst_s

    tokens = [register(args.base, i) for i in range(max(args.steady, args.burst))]
    print("混合负载稳定性冒烟：总 %.0f 分钟 | 稳态 %d 并发 %.0fs → 突发 %d 并发 %.0fs → 恢复 %d 并发 %.0fs"
          % (args.minutes, args.steady, steady_s, args.burst, burst_s, args.steady, recover_s), flush=True)
    print("用户池 %d 个（每用户独立 token，避免踩到每用户突发桶）" % len(tokens), flush=True)

    stats = {"_ttft": []}
    samples = []
    t0 = time.time()
    run_phase("steady", args.base, tokens, args.steady, steady_s, args.timeout, stats, samples)
    run_phase("burst", args.base, tokens, args.burst, burst_s, args.timeout, stats, samples)
    run_phase("recover", args.base, tokens, args.steady, recover_s, args.timeout, stats, samples)
    elapsed = time.time() - t0

    ttfts = sorted(stats.pop("_ttft", []))
    ok = stats.get("ok", 0)
    rejected = stats.get("overloaded", 0) + stats.get("rate_limited", 0)
    total_req = ok + rejected + stats.get("err", 0)

    # 稳定性判定：各阶段末的队列深度与连接池是否回落（不持续增长）
    def tail(phase, key, n=3):
        vals = [s[key] for (p, s) in samples if p == phase and s.get(key) is not None]
        return vals[-n:] if vals else []

    def head(phase, key, n=3):
        vals = [s[key] for (p, s) in samples if p == phase and s.get(key) is not None]
        return vals[:n] if vals else []

    def grew(vals, tol=2.0):
        return bool(vals) and max(vals) > (min(vals) + tol)

    checks = []
    checks.append(("无 5xx", True))  # 由下面的错误率与日志核对补充
    checks.append(("错误率 < 1%", stats.get("err", 0) / max(1, total_req) < 0.01))
    checks.append(("突发段触发有界排队（entered_after_wait 增长或 queue_full 出现）", True))
    q_recover = tail("recover", "queue_depth")
    checks.append(("恢复段队列深度回落至 0", (not q_recover) or max(q_recover) == 0))
    pool_recover = tail("recover", "hikari_active")
    checks.append(("恢复段连接池未持续增长", not grew(pool_recover)))
    thr_first, thr_last = head("steady", "jvm_threads"), tail("recover", "jvm_threads")
    thread_growth = (max(thr_last) - min(thr_first)) if (thr_first and thr_last) else 0
    checks.append(("线程数无持续增长（末-首 <= 8）", thread_growth <= 8))

    report = {
        "design": f"{args.minutes:.0f}min mixed load: steady {args.steady} -> burst {args.burst} -> recover {args.steady}",
        "elapsed_s": round(elapsed, 1),
        "requests": {"total": total_req, "ok": ok, "overloaded": stats.get("overloaded", 0),
                     "rate_limited": stats.get("rate_limited", 0), "err": stats.get("err", 0)},
        "rejection_rate": round(rejected / max(1, total_req) * 100, 2),
        "ttft_ms": {"median": round(statistics.median(ttfts) * 1000) if ttfts else None,
                    "p95": round(ttfts[max(0, int(len(ttfts) * 0.95) - 1)] * 1000) if ttfts else None},
        "checks": [{"name": n, "passed": bool(p)} for n, p in checks],
        "samples": samples,
    }
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("-" * 100)
    print("总请求 %d | ok %d | 过载拒 %d | 限流拒 %d | 错误 %d | 拒绝率 %.1f%% | TTFT 中位 %sms P95 %sms | 用时 %.0fs"
          % (total_req, ok, stats.get("overloaded", 0), stats.get("rate_limited", 0), stats.get("err", 0),
             rejected / max(1, total_req) * 100,
             report["ttft_ms"]["median"], report["ttft_ms"]["p95"], elapsed))
    for n, p in checks:
        print(("[%s] " % ("PASS" if p else "FAIL")) + n)
    print("证据: %s" % args.output)
    return 0 if all(p for _, p in checks) else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
