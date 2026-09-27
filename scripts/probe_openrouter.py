#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""OpenRouter 能力探测（Task #86）。

目的：用**实测**数据决定三件事，不能拍脑袋 ——
  1. 生成速率（tok/s）        -> 定 attempt-timeout-ms
  2. usage 里有没有 cache 字段 -> 确认有无 prompt cache
  3. 是否支持 tools/tool_calls -> 能不能承接 AgentLlmNode 的工具链

安全：**凭据只从环境变量读**，不落盘、不回显、不写进任何文件。
用法：
  OPENROUTER_API_KEY=sk-or-v1-xxx python scripts/probe_openrouter.py [--model M] [--speed]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("OPENROUTER_BASE", "https://openrouter.ai/api/v1").rstrip("/")

# ChatExecutor 要求"三牌 + 为什么有效 + 对方可能反应"，正常回答 300~500 tokens。
# 这里用一段真实的恋爱顾问 prompt，让长度和线上可比。
SYSTEM = (
    "你是恋爱帮帮帮的恋爱顾问，语气温柔但不谄媚。"
    "回答必须包含三张行动牌，每张牌说明为什么有效、对方可能的反应。"
)
USER = "我和女朋友最近总是因为谁洗碗谁做饭吵架，她觉得我做得少，我该怎么破？"


def _key() -> str:
    k = (os.environ.get("OPENROUTER_API_KEY") or "").strip()
    if not k or k.startswith("${") or k.startswith("sk-or-v1-xxx"):
        print("FATAL: OPENROUTER_API_KEY 未正确注入", file=sys.stderr)
        sys.exit(2)
    return k


def call(path: str, payload: dict, timeout: int = 180) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + _key(),
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/lwx-ai-agent",
            "X-Title": "lwx-ai-agent-probe",
        },
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:600]
        return {"_http_status": e.code, "_error": detail, "_elapsed": time.time() - t0}
    except Exception as e:  # noqa: BLE001
        return {"_error": f"{type(e).__name__}: {e}", "_elapsed": time.time() - t0}
    body["_elapsed"] = time.time() - t0
    return body


def get_models() -> list:
    req = urllib.request.Request(
        BASE + "/models", headers={"Authorization": "Bearer " + _key()}
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = json.loads(r.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        print("  /models 失败：%s: %s" % (type(e).__name__, e))
        return []
    rows = []
    for m in data.get("data", []):
        rows.append((m.get("id", ""), m.get("context_length", 0), m.get("pricing", {})))
    rows.sort()
    return rows


def probe(model: str, with_tools: bool = False) -> dict:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": USER},
        ],
        "temperature": 0.7,
        "max_tokens": 1024,
    }
    if with_tools:
        payload["tools"] = [
            {
                "type": "function",
                "function": {
                    "name": "search_knowledge",
                    "description": "检索恋爱知识库",
                    "parameters": {
                        "type": "object",
                        "properties": {"query": {"type": "string"}},
                        "required": ["query"],
                    },
                },
            }
        ]
        payload["tool_choice"] = "auto"

    r = call("/chat/completions", payload)
    out = {"model": model, "tools": with_tools}

    if "_error" in r or "_http_status" in r:
        out["ok"] = False
        out["status"] = r.get("_http_status")
        out["error"] = r.get("_error", "")[:400]
        out["elapsed"] = round(r.get("_elapsed", 0), 2)
        return out

    usage = r.get("usage") or {}
    comp = usage.get("completion_tokens") or 0
    el = r.get("_elapsed", 0)
    msg = (r.get("choices") or [{}])[0].get("message") or {}

    out["ok"] = True
    out["elapsed"] = round(el, 2)
    out["prompt_tokens"] = usage.get("prompt_tokens")
    out["completion_tokens"] = comp
    out["total_tokens"] = usage.get("total_tokens")
    out["tok_per_s"] = round(comp / el, 1) if el > 0 and comp else None
    out["usage_keys"] = sorted(usage.keys())
    out["provider"] = r.get("provider")
    out["model_returned"] = r.get("model")
    out["has_reasoning"] = bool(msg.get("reasoning"))
    out["finish_reason"] = (r.get("choices") or [{}])[0].get("finish_reason")
    out["usage_raw"] = usage
    if with_tools:
        tc = msg.get("tool_calls")
        out["tool_calls_returned"] = bool(tc)
        out["tool_call_name"] = (tc[0]["function"]["name"] if tc else None)
    else:
        txt = (msg.get("content") or "").strip()
        out["reply_chars"] = len(txt)
        out["reply_head"] = txt[:120].replace("\n", " / ")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", action="append", default=[], help="可重复")
    ap.add_argument("--speed", action="store_true", help="同一模型连测 3 次取中位")
    ap.add_argument("--list", action="store_true", help="只列模型")
    ap.add_argument("--tools", action="store_true", help="测 tool_calls")
    args = ap.parse_args()

    print("=== 1. 可用模型（OpenRouter /models）===")
    models = get_models()
    print("  共 %d 个" % len(models))
    # 只打印可能相关的：免费/便宜的、以及名字里带 qwen/gloss/gpt/claude/gemini/deepseek 的
    for mid, ctx, pricing in models:
        low = mid.lower()
        if any(t in low for t in ("qwen", "glm", "deepseek", "gemini", "gpt-4o-mini", "llama")):
            p = pricing.get("prompt", "?")
            print("  %-52s ctx=%-8s prompt=%s" % (mid, ctx, p))
    if args.list:
        return

    targets = args.model or [
        "qwen/qwen3-235b-a22b:free",
        "qwen/qwen3-32b",
        "deepseek/deepseek-chat",
        "google/gemini-2.0-flash-exp:free",
    ]

    print()
    print("=== 2. 生成速率 / usage 字段 ===")
    for m in targets:
        r = probe(m, with_tools=args.tools)
        if not r["ok"]:
            print("  [FAIL] %-40s %s" % (m, r.get("error", "")[:200]))
            continue
        cache_bits = [k for k in r["usage_keys"] if "cach" in k.lower() or "detail" in k.lower()]
        print(
            "  [OK]   %-40s %6.2fs  prompt=%-6s completion=%-5s  %s tok/s  provider=%s"
            % (
                m,
                r["elapsed"],
                r["prompt_tokens"],
                r["completion_tokens"],
                r["tok_per_s"],
                r.get("provider"),
            )
        )
        print("         usage 字段全集: %s" % ", ".join(r["usage_keys"]))
        print("         含 cache/details 的字段: %s" % (cache_bits or "无"))
        if r.get("usage_raw"):
            print("         usage 原文: %s" % json.dumps(r["usage_raw"], ensure_ascii=False)[:400])
        if args.tools:
            print("         tool_calls 返回: %s / name=%s" % (r.get("tool_calls_returned"), r.get("tool_call_name")))
        if r.get("reply_chars"):
            print("         回答 %d 字，开头: %s" % (r["reply_chars"], r["reply_head"]))

    if args.speed:
        print()
        print("=== 3. 连测 3 次取中位（定 timeout 用）===")
        for m in targets:
            runs = []
            for i in range(3):
                r = probe(m)
                if r["ok"] and r.get("tok_per_s"):
                    runs.append((r["elapsed"], r["completion_tokens"], r["tok_per_s"]))
                    print("  %-40s 第%d次 %6.2fs  completion=%-5s %s tok/s"
                          % (m, i + 1, r["elapsed"], r["completion_tokens"], r["tok_per_s"]))
            if len(runs) >= 2:
                runs.sort()
                mid = runs[len(runs) // 2]
                print("  -> %s 中位: %6.2fs / %d tok / %s tok/s"
                      % (m, mid[0], mid[1], mid[2]))


if __name__ == "__main__":
    main()
