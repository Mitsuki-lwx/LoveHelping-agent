#!/usr/bin/env python3
"""真实业务链路的 prompt cache 命中实测（**不是**连发同一 prompt 的理想化探测）。

为什么必须单独测（ADR-48 之后的遗留问题）：
  ADR-48 记录 space-bunny-alpha 的 cache 命中 **2948/2950（99.9%）**，
  但那是 `probe_real_prompt_speed.py --repeat 3` **连发同一 prompt** 测出来的。
  **真实业务链路每次的 prompt 都不一样**：
    - 用户输入（每次不同）
    - MessageChatMemoryAdvisor 注入的历史消息（每轮增长）
    - RetrievalAugmentationAdvisor 注入的 RAG 片段（每次检索结果不同）
  → **"理想化探测命中" ≠ "真实链路命中"**。静态前缀若被动态内容打断，
    命中率会塌到 0，而**现有所有自动化都不会发现**（没有一条断言看 cached_tokens）。

  ⚠️ 上一轮刚踩过一个同形的坑（"生效端点是什么"只能靠推断），
     所以这里坚持：**结论必须有运行期可查询的证据**，不能靠"结构上应该能命中"。

怎么量：
  真实链路的 usage **不走 SSE 事件**（实测：SSE 只有 message/advice/error 三种事件，
  没有 usage 事件），所以本脚本**不解析响应体**，而是：
    ① 记下调用前的 `llm.tokens{type="cached"}` 计数
    ② 发一条**内容不同**的真实提问
    ③ 记下调用后的计数，差值就是这次请求命中的 cached token 数
  数据来源是 `LlmGateway.usage()` 埋的 `llm.cache.hit.ratio` / `llm.tokens{type="cached"}`。

  ⚠️ **前提断言（不能省）**：必须先确认「请求真的打到了 OpenRouter 且该端点返回 cached_tokens」。
     本地 yml 的字面值会压过环境变量 → 启动日志里必须有
     `[ADR-48] 降级链 primary 级实际生效端点：… | stealth/space-bunny-alpha`。

用法：
  bash logs/probe_real_chain_cache.sh          # 一条命令起服务 + 跑探针 + 收尾
  python scripts/probe_real_chain_cache.py --base http://127.0.0.1:PORT/api --repeat 5
"""
import argparse
import json
import statistics
import sys
import time
import uuid
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import register, sse  # noqa: E402


def metrics(base):
    """读 prometheus 指标，返回 llm 相关行的字典。"""
    with urllib.request.urlopen(base + "/actuator/prometheus", timeout=20) as r:
        text = r.read().decode("utf-8", "replace")
    out = {}
    for line in text.splitlines():
        if line.startswith("#") or " " not in line:
            continue
        key, _, val = line.rpartition(" ")
        # ⚠️ 必须按**键名**过滤，不能按行尾：Prometheus 的 `_created` 行是
        # `llm_tokens_total{...}_created 1.75e9`，**结尾是数字不是 "_created "**，
        # 写成 endswith("_created ") 永不成立 → _created 会被当 cached 计数算进去
        # （差值法恰好不受影响，因为它恒定；但"全程汇总"那个数会偏大，
        #   且首次出现那一轮的差值会被污染）。这坑是量具自己的，不是被测对象的。
        if key.endswith("_created"):
            continue
        try:
            out[key] = float(val)
        except ValueError:
            pass
    return out


def cached_tokens(m):
    """所有 provider 的 cached token 总和。"""
    return sum(v for k, v in m.items()
               if k.startswith('llm_tokens_total') and 'type="cached"' in k)


def prompt_tokens(m):
    return sum(v for k, v in m.items()
               if k.startswith('llm_tokens_total') and 'type="prompt"' in k)

# 内容互不相同的真实提问（**不能用同一句连发**，那测的是理想化场景）
QUESTIONS = [
    "我和对象吵架后冷战三天了，谁都不说话，我该先开口吗？",
    "他总是忘记我生日，我有点难过但说不出口，该怎么表达？",
    "异地半年了，感觉感情变淡了，我该怎么判断还值不值得坚持？",
    "他前任总联系我，说只是朋友，但我很不舒服，这算越界吗？",
    "我们准备结婚，但两家对婚礼花费有分歧，怎么谈才不伤感情？",
    "他一吵架就摔门，我有点怕他，这种关系正常吗？",
    "我总忍不住翻他手机查记录，我知道这不对但控制不住，怎么办？",
    "他父母反对我们在一起，说我配不上他，我该坚持还是分手？",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="形如 http://127.0.0.1:PORT/api")
    ap.add_argument("--repeat", type=int, default=5, help="发多少条**内容不同**的请求")
    ap.add_argument("--output")
    args = ap.parse_args()

    # ---- 前提断言 1：真的打到 OpenRouter 且该端点返回 cache 字段 ----
    m0 = metrics(args.base)
    endpoints = {k: v for k, v in m0.items() if k.startswith("llm_endpoint_configured_total")}
    print("  前提断言：生效端点（来自应用自报指标）")
    for k in sorted(endpoints):
        print("    ", k.replace('llm_endpoint_configured_total{', '').rstrip('}'), "=", endpoints[k])
    if not endpoints:
        print("  ❌ 拿不到 llm_endpoint_configured 指标 → 量具不可信，先确认服务已启动")
        return 2
    if not any('stealth/space-bunny-alpha' in k for k in endpoints):
        print("  ❌ 主端点不是 space-bunny-alpha → 量的不是目标端点的 cache，退出")
        return 2
    if cached_tokens(m0) > 0:
        print("  ⚠️ 服务已跑过并产生过 cached token —— 计数差值法会把它算进第一轮。"
              "重启后再跑更干净。")

    user, token = register(args.base)
    rows = []
    for i in range(args.repeat):
        q = QUESTIONS[i % len(QUESTIONS)]
        cid = "cache_" + uuid.uuid4().hex
        # 每轮都取**全量快照再算差值**：既要 cached 增量，也要 prompt 增量（当分母）。
        # 少取 prompt 就只能算累计占比，看不出"第一轮冷启动 vs 后续稳态"的差别 ——
        # 而这个差别恰恰是判断静态前缀是否稳定的关键。
        m_pre = metrics(args.base)
        before = cached_tokens(m_pre)
        p_before = prompt_tokens(m_pre)
        t0 = time.time()
        try:
            r = sse(args.base, "/Love_app/chat/sse", {"prompt": q, "chatId": cid}, token)
            m_post = metrics(args.base)
            after = cached_tokens(m_post)
            p_delta = prompt_tokens(m_post) - p_before
            rows.append({"question": q, "ok": r["success"], "chars": len(r["text"]),
                         "seconds": round(time.time() - t0, 1),
                         "cached_delta": after - before, "prompt_delta": p_delta,
                         "hit_ratio": round((after - before) / p_delta, 4) if p_delta > 0 else None,
                         "trace_id": r.get("trace_id"),
                         "text_head": r["text"][:120]})
            ratio_s = (f"{rows[-1]['hit_ratio']:.1%}" if rows[-1]["hit_ratio"] is not None else "  n/a")
            print(f"  第{i+1}轮  {rows[-1]['seconds']:>5}s  {len(r['text']):>4}字  "
                  f"prompt+{p_delta:<6.0f} cached={rows[-1]['cached_delta']:<7.0f} 命中={ratio_s}",
                  flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"  第{i+1}轮  异常 {type(e).__name__}: {e}", flush=True)
            rows.append({"question": q, "ok": False, "chars": 0,
                         "seconds": round(time.time() - t0, 1),
                         "cached_delta": 0, "prompt_delta": 0, "hit_ratio": None,
                         "trace_id": None, "text_head": ""})

    m1 = metrics(args.base)
    ok = [r for r in rows if r["ok"]]
    fail = [r for r in rows if not r["ok"]]
    p_all, c_all = prompt_tokens(m1), cached_tokens(m1)
    print()
    print(f"  轮数={len(rows)}  成功={len(ok)}  失败={len(fail)}")
    if fail:
        # 超时失败与"答案质量"是两件事，不能混进命中率分母，也不能悄悄跳过：
        # 90.x s 恰好撞 total-timeout-ms=90000，说明这是**稳定性**问题（另记），
        # 但它的 cached 增量必然是 0，计入会把命中率压低。
        print(f"  [注意] 失败轮的耗时：{[r['seconds'] for r in fail]}")
        print(f"         ⚠️ 若耗时≈90s 则撞 total-timeout-ms —— 这是**稳定性**问题，"
              f"不是 cache 问题；失败轮已从命中率统计中排除。")
    print(f"  全程 prompt tokens = {p_all:.0f}  cached = {c_all:.0f}"
          + (f"  累计占比 = {c_all/max(1.0, p_all):.1%}" if p_all else ""))
    # 命中率在**这里**算，不在应用里埋 gauge：分母逐次变化，瞬时 gauge 极易被读成"稳定命中率"。
    # 量具侧用同一窗口的 prompt/cached 差值算，分母同源、可复算。

    if all(r["cached_delta"] == 0 for r in ok) and ok:
        print()
        print("  [全0] 这个结果**不能**直接读成「缓存没命中」—— 两种可能：")
        print("     (1) 上游确实没返回 cached_tokens（该模型/该 provider 无此能力）")
        print("     (2) 反射路径没对上（Usage.getNativeUsage() 装的不是 OpenAiApi.Usage）")
        print("     → 查 langfuse span 的 usage_details.cache_read，或直接 curl 上游看 usage 字段。")
        print("     [禁止] 据此宣称「缓存已修好」或「缓存坏了」—— 这是量具失效，不是结论。")
    elif ok:
        hits = [r["cached_delta"] for r in ok]
        ratios = [r["hit_ratio"] for r in ok if r["hit_ratio"] is not None]
        print()
        print(f"  cached 增量 min/中位/max = {min(hits):.0f} / "
              f"{statistics.median(hits):.0f} / {max(hits):.0f}")
        if ratios:
            print(f"  逐轮命中占比 min/中位 = {min(ratios):.1%} / {statistics.median(ratios):.1%}")
        med = statistics.median(hits)
        # ⚠️ 判据用**占比**而非绝对值：prompt 越长，cached 绝对值自然越大。
        # 只看绝对值会把「prompt 变长」误读成「缓存变好」。
        med_r = statistics.median(ratios) if ratios else 0.0
        if med_r >= 0.5:
            print("  [OK] **内容不同**仍高命中 → 静态前缀没被打断，缓存这件事**不用做**。")
        elif med_r > 0:
            print("  [WARN] 部分命中 → 静态前缀**有**被打断的位置，值得查（advisor 注入顺序）。")
        else:
            print("  [BAD] 基本不命中 → 动态内容插到了静态前缀**前面**，这是真实缺陷，需修 prompt 结构。")
        if len(ok) >= 3 and ratios and ratios[0] == 0.0 and max(ratios) > 0:
            print(f"  [注意] 首轮命中 0%、后续 >0% → 典型冷启动，"
                  f"稳态命中才是有效指标（首轮 {ratios[0]:.1%}，后续中位 "
                  f"{statistics.median(ratios[1:]):.1%}）。")

    if args.output:
        Path(args.output).write_text(json.dumps(
            {"repeat": args.repeat, "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n  已写入 {args.output}")


if __name__ == "__main__":
    main()
