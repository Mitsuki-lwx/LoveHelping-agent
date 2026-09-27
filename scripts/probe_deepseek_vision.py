#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验证 DeepSeek 官方是否真的能看图（VisionChatClient 改端点后的前提）。

## 为什么需要它

`app.llm.vision-model` 原值 `mimo-v2.5` 是**别的厂商**的视觉模型，而
`VisionChatClient` 读的是 `spring.ai.openai.base-url` + `api-key`（自建 RestClient，
**绕过 LlmGateway**）。ADR-51 把 base-url 切到 `https://api.deepseek.com` 之后，
这个客户端会拿 `mimo-v2.5` 去打 DeepSeek —— 而实测发现 DeepSeek 对**未知模型名
不报错、静默别名到 `deepseek-flash`**（同 `deepseek-chat` / `deepseek-reasoner`）。
所以"能跑通"不等于"跑对了"，必须实测图像通道。

## 方法

用纯标准库现场生成一张**纯红色 PNG**（zlib + struct，不依赖 PIL），
以 `image_url → data URL(base64)` 发送（与 `VisionChatClient` 的报文格式逐字一致），
问"这张图是什么颜色"。

判据：
- 答"红" → 视觉通道真的通
- 答不出来 / 400 → 视觉通道不通，`vision-model` 不能就这么切

## 用法

    DEEPSEEK_API_KEY=sk-xxx python scripts/probe_deepseek_vision.py
    DEEPSEEK_API_KEY=sk-xxx python scripts/probe_deepseek_vision.py --model mimo-v2.5

**密钥只从环境变量读，不落盘、不打印。** 纯只读探测。
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import struct
import sys
import urllib.error
import urllib.request
import zlib


def solid_png(w: int, h: int, rgb: tuple[int, int, int]) -> bytes:
    """纯标准库生成纯色 PNG（无第三方依赖）。"""
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)  # 8bit truecolor RGB
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def post(url: str, payload: dict, key: str, timeout: int = 60):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:  # noqa: BLE001
            return e.code, {"_raw": body[:400]}
    except Exception as e:  # noqa: BLE001
        return None, {"_err": f"{type(e).__name__}: {e}"}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", default=os.environ.get("DEEPSEEK_API_KEY", ""))
    ap.add_argument("--base", default=os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
    ap.add_argument("--model", default="deepseek-flash")
    args = ap.parse_args()

    if not args.key.strip():
        print("ERROR: 需要环境变量 DEEPSEEK_API_KEY（或用 --key）", file=sys.stderr)
        return 2

    png = solid_png(32, 32, (255, 0, 0))  # 纯红
    data_url = "data:image/png;base64," + base64.b64encode(png).decode()
    url = args.base.rstrip("/") + "/v1/chat/completions"   # VisionChatClient 的拼法

    print(f"endpoint = {url}")
    print(f"model    = {args.model}")
    print(f"key      = <SET len={len(args.key)}>")
    print(f"image    = 32x32 纯红 PNG, {len(png)} bytes")
    print()

    payload = {
        "model": args.model,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": "这张图是什么颜色？只回答颜色名。"},
            {"type": "image_url", "image_url": {"url": data_url}},
        ]}],
        "max_tokens": 64,
    }
    status, body = post(url, payload, args.key)
    print(f"status = {status}")
    if status == 200:
        msg = body["choices"][0]["message"]
        print(f"响应 model = {body.get('model')}   （⚠️ 不等于请求的 model 就说明被静默别名了）")
        print(f"回答 = {msg.get('content')!r}")
        ok = "红" in (msg.get("content") or "") or "red" in (msg.get("content") or "").lower()
        print()
        if ok:
            print("✅ 视觉通道可用 —— 图像真的被看到了")
        else:
            print("⚠️ 200 但答不出颜色 —— 图像可能被丢弃（格式不被识别）")
    else:
        print(f"body = {json.dumps(body, ensure_ascii=False)[:500]}")
        print()
        print("❌ 视觉通道不可用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
