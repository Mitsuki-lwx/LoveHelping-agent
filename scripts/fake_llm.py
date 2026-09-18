"""Fault-injection stand-in for the OpenAI-compatible upstream.

FAKE_MODE=slow       -> delay then stream chunks (long in-flight requests)
FAKE_MODE=slow_error -> delay then 500 (long in-flight *and* drives retry)
FAKE_MODE=error      -> immediately 500
FAKE_MODE=ok         -> stream immediately
FAKE_MODE=hang       -> never returns (occupies a concurrency permit forever)

GET  /stats -> concurrency statistics (max simultaneous upstream requests).
GET  /reset -> zero the counters and return the state that was cleared.

  这个"假上游侧看到的最大并发"就是**厂商侧真实在途**，
  用它可以直接验证 ADC（每请求持有的并发许可）是否把账记准。

⚠️ `/reset` 是**必需**的，不是可选便利：`max` 是**进程内累计最大值**，
   不做重置的话，先跑的那个用例会把 max 顶高，后跑的用例**结构上不可能**超过它，
   对照实验直接失效（2026-09-18 的第一次 permit-cost 实验就是这么报废的：
   两份产物 `fake_stats.max_at` 完全相同，都是 4）。

⚠️ 放在 `scripts/` 而非 `logs/`：`logs/` 被 gitignore，放那里实验无法复现
   （本仓库既有约定：探测/注入脚本必须可复现）。
"""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE = os.environ.get("FAKE_MODE", "slow")
DELAY = float(os.environ.get("FAKE_DELAY", "30"))

_lock = threading.Lock()
_state = {"current": 0, "max": 0, "total": 0, "max_at": None}


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
    """清零计数器并返回清零前的快照（便于事后核对确实重置过）。"""
    with _lock:
        previous = dict(_state)
        _state.update({"current": 0, "max": 0, "total": 0, "max_at": None})
        return previous


def _json(handler, payload):
    body = json.dumps(payload).encode()
    handler.send_response(200)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _error_body(handler, code=500):
    body = b'{"error":{"message":"injected upstream failure","type":"server_error"}}'
    try:
        handler.send_response(code)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(body)))
        handler.end_headers()
        handler.wfile.write(body)
    except Exception:
        pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path.startswith("/stats"):
            _json(self, _snapshot())
            return
        if self.path.startswith("/reset"):
            _json(self, {"reset": True, "previous": _reset()})
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        self.rfile.read(length)
        if self.path.startswith("/reset"):
            _json(self, {"reset": True, "previous": _reset()})
            return
        _enter()
        try:
            if MODE == "hang":
                # 永不返回（直到客户端断开）：用于"占住并发许可"的故障注入。
                # 注意 slow 模式会在 DELAY 后成功返回，占位请求随即结束，占不住许可。
                time.sleep(900)
                return
            if MODE == "error":
                _error_body(self)
                return
            if MODE == "slow_error":
                time.sleep(DELAY)
                _error_body(self)
                return

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            time.sleep(DELAY)

            def emit(payload):
                data = ("data: " + json.dumps(payload) + "\n\n").encode()
                self.wfile.write(("%x\r\n" % len(data)).encode() + data + b"\r\n")
                self.wfile.flush()

            for i in range(3):
                emit({"id": "fake", "object": "chat.completion.chunk", "created": 0, "model": "fake",
                      "choices": [{"index": 0, "delta": {"content": "fake-token-%d " % i}, "finish_reason": None}]})
            emit({"id": "fake", "object": "chat.completion.chunk", "created": 0, "model": "fake",
                  "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
            tail = b"data: [DONE]\n\n"
            self.wfile.write(("%x\r\n" % len(tail)).encode() + tail + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except Exception:
            pass
        finally:
            _leave()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(os.environ.get("FAKE_PORT", "19080"))
    print("[FAKE] listening on 127.0.0.1:%d mode=%s delay=%ss" % (port, MODE, DELAY), flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
