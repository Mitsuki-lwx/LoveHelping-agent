#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""定位 bigmodel last-resort 的 400 Bad Request 根因（ADR-48 遗留）。

## 背景（2026-09-27）

三级降级链的**最低一级** bigmodel `glm-4-flash` 在真实链路里返回 **400 Bad Request**：

    org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest:
    400 Bad Request from POST https://open.bigmodel.cn/api/paas/v4/chat/completions

⛔ URL 是**正确**的（`/api/paas/v4/chat/completions`，不是 404 的那个 `/v1/...`），
所以问题在**请求体/参数**，不在地址。而 Spring AI 不打印 400 的响应体，
日志里只有状态行 —— 必须**自己发请求**才能看到上游怎么说。

同期另有一个**装置问题**（已排除，勿混淆）：
把 bigmodel 当 primary 时（走 `spring.ai.openai.*` 自动配置、未设 completions-path）
得到的是 **404**（URL 被拼成 `/api/paas/v4/v1/chat/completions`）。
两者是不同缺陷，本脚本只查 400 那个。

## 方法：变量隔离

一次只改一个变量，逐项对比状态码，**不要一次改多个**（那样只会知道"某个组合不行"）。
Spring AI 的 `OpenAiChatModel` 在流式时会带 `stream` / `stream_options`，
并可能带上 ChatClient 默认 options 里的 `max_tokens` —— 这三者都是候选嫌疑。

## 用法

    BIGMODEL_API_KEY=xxx python scripts/probe_last_resort_400.py
    BIGMODEL_API_KEY=xxx python scripts/probe_last_resort_400.py --model glm-4-flash-250414

**密钥只从环境变量读，不落盘、不打印。** 本脚本不修改任何东西，纯只读探测。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4"
DEFAULT_MODEL = "glm-4-flash"


def post(url: str, payload: dict, key: str, timeout: int = 30):
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
    ap.add_argument("--base", default=os.environ.get("BIGMODEL_BASE_URL", DEFAULT_BASE))
    ap.add_argument("--model", default=os.environ.get("BIGMODEL_MODEL", DEFAULT_MODEL))
    ap.add_argument("--key", default=os.environ.get("BIGMODEL_API_KEY", ""))
    ap.add_argument("--timeout", type=int, default=30)
    args = ap.parse_args()

    if not args.key.strip():
        print("ERROR: 需要环境变量 BIGMODEL_API_KEY（或用 --key）", file=sys.stderr)
        return 2

    base = args.base.rstrip("/")
    # BigModelLastResortConfig 显式设了 completionsPath("/chat/completions")
    url = base + "/chat/completions"
    print(f"endpoint = {url}")
    print(f"model    = {args.model}")
    print(f"key      = <SET len={len(args.key)}>")
    print()

    msg = [{"role": "user", "content": "说一句话"}]

    # ---- 变体：一次只改一个变量 ----
    variants = [
        ("① 最小请求（非流式）", {"model": args.model, "messages": msg, "stream": False}),
        ("② 最小请求（流式，无 stream_options）",
         {"model": args.model, "messages": msg, "stream": True}),
        ("③ 流式 + stream_options.include_usage（Spring AI 可能带）",
         {"model": args.model, "messages": msg, "stream": True,
          "stream_options": {"include_usage": True}}),
        ("④ 非流式 + max_tokens=32768（OpenRouter 402 提到过这个数）",
         {"model": args.model, "messages": msg, "stream": False, "max_tokens": 32768}),
        ("⑤ 非流式 + max_tokens=4095（智谱常见上限）",
         {"model": args.model, "messages": msg, "stream": False, "max_tokens": 4095}),
        ("⑥ 非流式 + temperature=0.7（确认常规参数无碍）",
         {"model": args.model, "messages": msg, "stream": False, "temperature": 0.7}),
    ]

    results = []
    for label, payload in variants:
        status, body = post(url, payload, args.key, args.timeout)
        ok = "✅" if status == 200 else ("❌" if status else "⚠️")
        print(f"{ok} {label}")
        print(f"     status={status}")
        # 截断 body 防刷屏；400 的 body 是**关键证据**，务必打印
        excerpt = (body or "").replace("\n", " ")[:400]
        print(f"     body={excerpt}")
        print()
        results.append((label, status, excerpt))

    # ---- 归因：只有 200 与 400 的差异才说明问题 ----
    print("=" * 60)
    bad = [l for l, s, _ in results if s == 400]
    good = [l for l, s, _ in results if s == 200]
    if not bad:
        print("没有 400 —— 说明根因不在请求体，需回到链路侧查（或 key 权限问题）。")
    elif len(good) == len(results) - len(bad) and good:
        print(f"400 只出现在：{bad}")
        print(f"200 出现在：{good}")
        print("→ 对比两组差异的那**一个变量**，即为根因。")
    else:
        print(f"400 出现在：{bad}")
        print("⚠️ 其余变体也非 200 —— 可能 key/模型名本身有问题，先看 ① 的 body。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
