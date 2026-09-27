#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""定位降级链 fallback 级（DashScope qwen-plus）为何没能救回请求（ADR-48 遗留）。

## 背景（2026-09-27 19:52 A 臂）

A 臂（primary = OpenRouter stealth/space-bunny-alpha，当日免费额度已耗尽）跑 6 轮，
**6/6 全部返回用户可见的 5000「AI 服务暂时不可用」** —— 降级链一次都没救回来。
omp 当时记下「0 dashscope 命中」，但那条结论**不可信**，原因有三：

1. `RestFallbackChatModel` **整个类零日志**（裸 java.net.http.HttpClient，端点常量从不打印，
   失败只抛 RestClientResponseException）→ 它的调用与失败在日志里**完全不可见**。
   唯一一个失败不留痕的 tier，恰好就是被查的那个 —— 「没日志」被读成了「没调用」。
2. `app.logging.throttle`（max-per-window=3 / window-ms=10000）把 MessageAggregator 的
   重复错误**抑制了 31 条**（7+7+7+10），所以日志里的 429/400 条数**不是真实次数**。
3. 代码顺序上 fallback 恒在 last-resort **之前**被尝试（`degradeTiers()` 首位 +
   `degradingStream` 无条件先试 tierIndex=1）→ 若 last-resort 被到达，fallback 必已被尝试。

## 本脚本要回答的问题

**fallback 级本身是否可用？** 即：用项目自己的 key 直接打 DashScope 原生端点，
看它到底返回什么。若这里是 4xx，那 A 臂全败的根因就在 fallback 级，
与 bigmodel 400 是两个独立缺陷。

## 用法

    eval "$(python scripts/prod_env_from_local.py 2>/dev/null)"
    DASHSCOPE_API_KEY="$DASHSCOPE_API_KEY" python scripts/probe_dashscope_fallback.py

**密钥只从环境变量读，不落盘、不打印（只打印长度）。** 纯只读探测，不修改任何东西。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

NATIVE = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation"
COMPAT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"


def post(url: str, payload: dict, key: str, timeout: int = 40):
    """返回 (status, body_text)。不抛异常，让调用方看状态码。"""
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    # 显式绕过本机代理：本机 Clash 以 fake-ip 劫持 DNS，直连才稳
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", default=os.environ.get("DASHSCOPE_API_KEY", ""))
    ap.add_argument("--model", default="qwen-plus")
    ap.add_argument("--timeout", type=int, default=40)
    args = ap.parse_args()

    if not args.key.strip():
        print("ERROR: 需要环境变量 DASHSCOPE_API_KEY（或用 --key）", file=sys.stderr)
        return 2

    print(f"key      = <SET len={len(args.key)}>")
    print(f"model    = {args.model}")
    print()

    msg = [{"role": "user", "content": "说一句话"}]

    # ---- 变体：一次只改一个变量 ----
    # ① 精确复刻 RestFallbackChatModel.payload() 的报文形状（model/input/parameters）
    native_body = {"model": args.model, "input": {"messages": msg},
                   "parameters": {"result_format": "message"}}
    # ② 同上但 result_format=text（排除 result_format 被拒）
    native_text = {"model": args.model, "input": {"messages": msg},
                   "parameters": {"result_format": "text"}}
    # ③ 原生端点 + 无 parameters（排除 parameters 整体被拒）
    native_noparam = {"model": args.model, "input": {"messages": msg}}
    # ④ OpenAI 兼容端点（对照组：同一 key 在兼容通道是否可用）
    compat_body = {"model": args.model, "messages": msg, "stream": False}

    variants = [
        (f"① 原生端点 · {args.model} · result_format=message（= RestFallbackChatModel 的报文）",
         NATIVE, native_body),
        (f"② 原生端点 · {args.model} · result_format=text", NATIVE, native_text),
        (f"③ 原生端点 · {args.model} · 无 parameters", NATIVE, native_noparam),
        (f"④ 兼容端点（对照）· {args.model} · 非流式", COMPAT, compat_body),
    ]

    results = []
    for label, url, payload in variants:
        status, body = post(url, payload, args.key, args.timeout)
        ok = "✅" if status == 200 else ("❌" if status else "⚠️")
        print(f"{ok} {label}")
        print(f"     status={status}")
        excerpt = (body or "").replace("\n", " ")[:500]
        print(f"     body={excerpt}")
        print()
        results.append((label, status, excerpt))

    print("=" * 60)
    good = [l for l, s, _ in results if s == 200]
    bad = [l for l, s, _ in results if s and s != 200]
    if not good:
        print("⛔ 没有任何变体返回 200 —— **fallback 级本身不可用**，A 臂全败的根因就在这一级。")
        print("   先看 ① 的 body：若为 401/403 是 key 问题；400 是报文/模型名；429 是配额。")
    elif len(good) == len(results):
        print("✅ 所有变体都 200 —— fallback 级**可用**。")
        print("   → 那么 A 臂全败不能归因于 fallback 不可用，需回到真实链路取指标复验")
        print("     （llm_call_total{provider=\"fallback\"} 是否为 0）。")
    else:
        print(f"⚠️ 部分可用。200：{good}")
        print(f"   非 200：{bad}")
        print("   → 对比两组差异的那**一个变量**（端点 / result_format / 模型名）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
