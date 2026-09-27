#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""OpenRouter prompt cache 命中实测。

⚠️ 上一轮的教训：bigmodel 的 usage 里**根本没有** cache 字段，所以"字段存在"不等于"有缓存能力"。
   这里必须实测**同一段长前缀连发两次，看第二次 cached_tokens 是否 > 0**。

安全：凭据只从环境变量读。
用法：OPENROUTER_API_KEY=sk-or-v1-xxx python scripts/probe_openrouter_cache.py [--model M]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("OPENROUTER_BASE", "https://openrouter.ai/api/v1").rstrip("/")

# 静态前缀：模拟 ChatExecutor.SYSTEM_PROMPT 的规模（线上约 6000 字符）
PREFIX = (
    "你是'恋爱帮帮帮'的恋爱顾问，语气温柔但不谄媚。你的任务是给出可执行的三张行动牌，"
    "每张牌都要说明为什么有效、对方可能的反应，以及一个可以直接说出口的开场白。"
    "你从不评判用户，不给脱离现实的建议，遇到明显危险信号要优先提醒。"
) * 40


def key() -> str:
    k = (os.environ.get("OPENROUTER_API_KEY") or "").strip()
    if not k or k.startswith("${"):
        print("FATAL: OPENROUTER_API_KEY 未注入", file=sys.stderr)
        sys.exit(2)
    return k


def one_shot(model: str, n_repeat: int) -> dict:
    """同一段前缀发 n_repeat 次，返回每次的 prompt/cached tokens。"""
    sysmsg = PREFIX
    if n_repeat > 1:
        # 第 2 次起在末尾追加不同尾巴，制造"前缀相同、总长不同"的真实形态
        sysmsg = PREFIX + ("\n补充说明编号%d：这是第%d次请求。" % (n_repeat, n_repeat))
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": sysmsg},
            {"role": "user", "content": "我该怎么说？"},
        ],
        "max_tokens": 16,
        "temperature": 0,
    }
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + key(),
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/lwx-ai-agent",
            "X-Title": "lwx-ai-agent-probe",
        },
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"ok": False, "err": "HTTP %s: %s" % (e.code, e.read().decode("utf-8", "replace")[:300])}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "err": "%s: %s" % (type(e).__name__, e)}
    u = body.get("usage") or {}
    d = u.get("prompt_tokens_details") or {}
    return {
        "ok": True,
        "elapsed": round(time.time() - t0, 2),
        "prompt": u.get("prompt_tokens"),
        "cached": d.get("cached_tokens"),
        "cache_write": d.get("cache_write_tokens"),
        "cost": u.get("cost"),
        "provider": body.get("provider"),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen/qwen3-32b")
    ap.add_argument("--repeat", type=int, default=3, help="连发几次")
    ap.add_argument("--chars", type=int, default=0, help="打印前缀字符数")
    args = ap.parse_args()

    print("模型: %s" % args.model)
    print("静态前缀: %d 字符（约 %d tokens 量级）" % (len(PREFIX), len(PREFIX) // 2))
    print()
    hits, costs = 0, []
    print("=== 连发 %d 次同一前缀（看 cached_tokens 是否从 0 变正）===" % args.repeat)
    for i in range(1, args.repeat + 1):
        r = one_shot(args.model, i)
        if not r["ok"]:
            print("  第%d次 FAIL: %s" % (i, r["err"]))
            break
        print(
            "  第%d次  %5.2fs  prompt_tokens=%-6s cached_tokens=%-6s cache_write=%-6s cost=%s  provider=%s"
            % (i, r["elapsed"], r["prompt"], r["cached"], r["cache_write"], r["cost"], r["provider"])
        )
        if r["cached"]:
            hits += 1
        if r["cost"] is not None:
            costs.append(r["cost"])

    print()
    if costs:
        print("  -> cache 命中 %d/%d 次（%.0f%%）" % (hits, args.repeat, 100.0 * hits / args.repeat))
        print("  -> cost 首=%.8f 末=%.8f 最低=%.8f" % (costs[0], costs[-1], min(costs)))

    print()
    print("=== 对照：换一个完全不同的前缀（若上面 cached 一直是 0，说明能力不存在而非没命中）===")
    r = one_shot(args.model, 1)
    print("  %s" % json.dumps(r, ensure_ascii=False))


if __name__ == "__main__":
    main()
