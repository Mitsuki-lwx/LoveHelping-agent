#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""视觉链路定向探针（ADR-62 / F3）：确认 `vision.call` span 真会被产生。

为什么需要它：`VisionChatClient` 是**绕网关**的路径（既有 P3），
既没有既有装置覆盖，主回归 `run_phase22_e2e.sh` 也**不含图像用例** ——
所以 F3 加完 span 后，冒烟根本验证不到它。本脚本补这条路：
真实上传一张 1×1 PNG → 走 `/Love_app/chat/sse?mediaIds=<id>` → 让 GraphVisionNode 真正调用 VisionPort。

⛔ 判据不在本脚本：脚本只负责**把链路走通**。
   "span 是否产生"必须去 Langfuse 查（`name=vision.call`）——
   因为导出侧还有一张 attributes 白名单（ADR-62 §八），本脚本看不到那一层。

用法：python scripts/probe_vision_span.py --base http://127.0.0.1:8088/api
"""
import argparse
import io
import sys
import urllib.request
import uuid
import zlib
import struct
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import register, sse  # noqa: E402


def tiny_png() -> bytes:
    """1×1 透明 PNG（手工构造，避免依赖 Pillow）。"""
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)
    raw = b"\x00" + b"\x00\x00\x00\x00"          # 一行、RGBA 全 0
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def upload(base: str, token: str) -> dict:
    boundary = "----probe" + uuid.uuid4().hex
    png = tiny_png()
    body = (("--%s\r\n" % boundary).encode()
            + b'Content-Disposition: form-data; name="file"; filename="p.png"\r\n'
            + b"Content-Type: image/png\r\n\r\n" + png + b"\r\n"
            + ("--%s--\r\n" % boundary).encode())
    req = urllib.request.Request(base.rstrip("/") + "/media/upload", data=body, method="POST",
                                headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                                         "Authorization": "Bearer " + token})
    import json
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    args = ap.parse_args()

    user, token = register(args.base)
    up = upload(args.base, token)
    data = up.get("data") or {}
    mid = data.get("id") or data.get("mediaId")
    print(f"上传结果：success={up.get('success')} id={mid} name={data.get('originalName') or data.get('fileName')}")
    if not mid:
        print("⛔ 没拿到 mediaId，无法继续（上传契约可能变了）")
        return 1

    cid = "vis_" + uuid.uuid4().hex
    r = sse(args.base, "/Love_app/chat/sse",
            {"prompt": "帮我看看这张图里有什么", "chatId": cid, "mediaIds": str(mid)}, token)
    body = (r.get("text") or "")
    print(f"SSE：success={r['success']} errors={r['errors'][:1]} 正文={len(body)}字")
    print(f"  前 120 字：{body[:120]!r}")
    print()
    print("→ 现在去 Langfuse 查 name='vision.call'（本脚本看不到导出侧白名单那一层）")
    return 0 if r["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
