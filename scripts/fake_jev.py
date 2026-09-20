#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Jev（System One）的故障注入替身 —— 生产模拟用。

为什么需要它：`JevClient` 是**新增的同步外部依赖**，串在首字延迟上。
把 base-url 指到一个不存在的端口只能测"立即拒绝"，测不出**最危险的"慢"**（线程被占住）。
本替身提供有界的慢、无限挂起与错误返回。

    FAKE_JEV_MODE=ok         -> 立即返回一个合法 noul（概率取 FAKE_JEV_PROB）
    FAKE_JEV_MODE=slow       -> 延迟 FAKE_JEV_DELAY 秒后返回（默认 30s，远大于客户端 timeout）
    FAKE_JEV_MODE=slow_error -> 延迟后返回 500（长在途 + 触发错误分支）
    FAKE_JEV_MODE=error      -> 立即 500
    FAKE_JEV_MODE=hang       -> 永不返回（占住请求线程直到客户端超时）

    GET /stats  -> 上游侧看到的并发统计（current / max / total）+ 各模式计数
    GET /reset  -> 清零并返回清零前的快照

⚠️ `/reset` 是必需的，不是便利：`max` 是**进程内累计值**，
   不重置的话先跑的那轮会把 max 顶高，后跑的那轮**结构上不可能**超过它，对照实验直接失效
   （`fake_llm.py` 的注释里记录了 2026-09-18 那次报废的实验）。

⚠️ 放在 `scripts/` 而非 `logs/`：`logs/` 被 gitignore，放那里实验无法复现。
"""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE = os.environ.get("FAKE_JEV_MODE", "ok")
DELAY = float(os.environ.get("FAKE_JEV_DELAY", "30"))
PROB = float(os.environ.get("FAKE_JEV_PROB", "0.97"))

_lock = threading.Lock()
_state = {"current": 0, "max": 0, "total": 0, "max_at": None, "by_mode": {}}


def _enter():
    with _lock:
        _state["current"] += 1
        _state["total"] += 1
        if _state["current"] > _state["max"]:
            _state["max"] = _state["current"]
            _state["max_at"] = time.time()


def _leave():
    with _lock:
        _state["current"] -= 1


def _snapshot():
    with _lock:
        return dict(_state)


def _reset():
    with _lock:
        previous = dict(_state)
        _state.update({"current": 0, "max": 0, "total": 0, "max_at": None, "by_mode": {}})
        return previous


def _body(probability):
    return json.dumps({
        "model": "jev-fake",
        "answers": {"self_harm": {"type": "noul", "noul": probability}},
        "usage": {"input_tokens": 300, "output_tokens": 6},
    }).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静音，避免把压测日志刷爆
        pass

    def _send(self, status, payload, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path.startswith("/stats"):
            self._send(200, json.dumps(_snapshot()).encode())
        elif self.path.startswith("/reset"):
            self._send(200, json.dumps({"previous": _reset()}).encode())
        elif self.path.startswith("/health"):
            self._send(200, b'{"ok":true}')
        else:
            self._send(404, b'{"error":"not found"}')

    def do_POST(self):
        if not self.path.startswith("/v1/systemone"):
            self._send(404, b'{"error":"not found"}')
            return
        _enter()
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            with _lock:
                _state["by_mode"][MODE] = _state["by_mode"].get(MODE, 0) + 1

            if MODE == "ok":
                self._send(200, _body(PROB))
            elif MODE == "error":
                self._send(500, b'{"error":"injected failure"}')
            elif MODE == "slow":
                time.sleep(DELAY)
                self._send(200, _body(PROB))
            elif MODE == "slow_error":
                time.sleep(DELAY)
                self._send(500, b'{"error":"injected failure after delay"}')
            elif MODE == "hang":
                # 永不返回：占住线程直到客户端放弃
                while True:
                    time.sleep(3600)
            else:
                self._send(500, b'{"error":"unknown mode"}')
        except (BrokenPipeError, ConnectionResetError):
            pass  # 客户端超时断开是预期结果
        finally:
            _leave()


def main():
    port = int(os.environ.get("FAKE_JEV_PORT", "19090"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("fake jev on 127.0.0.1:%d mode=%s delay=%ss prob=%s" % (port, MODE, DELAY, PROB), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
