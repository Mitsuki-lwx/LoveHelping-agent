#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用**线上真实负载**测生成速率 —— 这是定 attempt-timeout-ms 的唯一依据。

为什么不用随手写的小 prompt：
  ChatExecutor.SYSTEM_PROMPT 实测 4996 字符（68 行），再加 RAG 上下文 + 记忆 + 行动卡，
  线上真实输入远大于探测脚本里随手写的两句。用小 prompt 测出的 tok/s 会**高估**能力，
  然后 timeout 就会定得过小 —— 这正是上一轮 25000 装不下 300 token 的成因。

脚本直接从 ChatExecutor.java 里提取真实 SYSTEM_PROMPT，**不手抄、不近似**。

安全：凭据只从环境变量读。
用法：OPENROUTER_API_KEY=sk-or-v1-xxx python scripts/probe_real_prompt_speed.py
"""
import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("OPENROUTER_BASE", "https://openrouter.ai/api/v1").rstrip("/")
JAVA = os.path.join("src", "main", "java", "cn", "lwx", "lwxaiagent",
                    "infrastructure", "orchestration", "ChatExecutor.java")

# RAG 上下文样例：模拟 topK=8 的知识块 + 记忆注入（按线上 token 量级）
RAG_SAMPLE = "\n\n".join(
    "[知识片段 %d] %s" % (i, "亲密关系中的边界感需要双方共同维护，通过具体的日常小事来表达，而不是期待对方读心。"
                        "常见误区是用试探代替表达，例如反复询问对方是否爱自己，这反而会加重对方的压力。"
                        "有效的做法是把需求具体化：不说你要关心我，而说今天下班回家时我需要你先说一句话再去做别的事。" * 3)
    for i in range(1, 9)
)


def key() -> str:
    k = (os.environ.get("OPENROUTER_API_KEY") or "").strip()
    if not k or k.startswith("${"):
        print("FATAL: OPENROUTER_API_KEY 未注入", file=sys.stderr)
        sys.exit(2)
    return k


def real_system_prompt() -> str:
    if not os.path.exists(JAVA):
        print("FATAL: 找不到 %s" % JAVA)
        sys.exit(2)
    src = open(JAVA, encoding="utf-8").read()
    m = re.search(r'SYSTEM_PROMPT\s*=\s*"""(.*?)"""', src, re.S)
    if not m:
        print("FATAL: 在 %s 里没匹配到 SYSTEM_PROMPT 三引号常量" % JAVA)
        sys.exit(2)
    return m.group(1).strip()


def call(model: str, system: str, user: str, max_tokens: int) -> dict:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "max_tokens": max_tokens,
        "temperature": 0.7,
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
        with urllib.request.urlopen(req, timeout=240) as r:
            body = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"ok": False, "err": "HTTP %s: %s" % (e.code, e.read().decode("utf-8", "replace")[:300])}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "err": "%s: %s" % (type(e).__name__, e)}
    u = body.get("usage") or {}
    d = u.get("prompt_tokens_details") or {}
    el = time.time() - t0
    comp = u.get("completion_tokens") or 0
    msg = (body.get("choices") or [{}])[0].get("message") or {}
    return {
        "ok": True,
        "elapsed": round(el, 2),
        "prompt": u.get("prompt_tokens"),
        "cached": d.get("cached_tokens"),
        "completion": comp,
        "tps": round(comp / el, 1) if el and comp else None,
        "finish": (body.get("choices") or [{}])[0].get("finish_reason"),
        "chars": len((msg.get("content") or "").strip()),
        "cost": u.get("cost"),
        "provider": body.get("provider"),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen/qwen-plus")
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--with-rag", action="store_true", help="拼上模拟 RAG 上下文（线上形态）")
    ap.add_argument("--max-tokens", type=int, default=1024)
    args = ap.parse_args()

    sysp = real_system_prompt()
    full_system = sysp + ("\n\n【记忆与知识上下文】\n" + RAG_SAMPLE if args.with_rag else "")
    user = "我最近老是因为这种小事跟我对象吵架，我不知道该怎么说才能不吵起来"

    print("模型: %s" % args.model)
    print("SYSTEM_PROMPT 来自 %s" % JAVA)
    print("  纯 system: %d 字符" % len(sysp))
    print("  本次实际: %d 字符%s" % (len(full_system), "（含模拟 RAG top8 上下文）" if args.with_rag else ""))
    print()
    runs = []
    for i in range(1, args.repeat + 1):
        r = call(args.model, full_system, user, args.max_tokens)
        if not r["ok"]:
            print("  第%d次 FAIL: %s" % (i, r["err"]))
            continue
        runs.append(r)
        print("  第%d次  %6.2fs  prompt=%-6s cached=%-6s completion=%-5s finish=%-8s %s tok/s  回答%d字  $%.6f  [%s]"
              % (i, r["elapsed"], r["prompt"], r["cached"], r["completion"], r["finish"],
                 r["tps"], r["chars"], r["cost"] or 0, r["provider"]))
        sys.stdout.flush()

    if not runs:
        print("\n没有成功样本，不下结论。")
        return
    ts = [r["tps"] for r in runs if r["tps"]]
    els = [r["elapsed"] for r in runs]
    comps = [r["completion"] for r in runs]
    print()
    print("  -> n=%d  耗时 中位=%.2fs 最大=%.2fs  completion 中位=%d 最大=%d"
          % (len(runs), statistics.median(els), max(els), statistics.median(comps), max(comps)))
    if ts:
        print("  -> tok/s 中位=%.1f 最低=%.1f" % (statistics.median(ts), min(ts)))
    worst_tps = min(ts) if ts else 0
    if worst_tps:
        print()
        print("  按**最低** tok/s=%.1f 推算，attempt-timeout-ms 需要装下：" % worst_tps)
        for target in (300, 500, 800, 1200):
            print("     %5d completion tokens -> %6.1fs" % (target, target / worst_tps))


if __name__ == "__main__":
    main()
