"""Shared standard-library HTTP/SSE helpers for real verification, not model mocks."""
import hashlib
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

# Local test traffic must not pass through an unrelated system HTTP proxy.
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
FAILURE_TEXT = ("AI 服务暂时不可用", "系统繁忙", "请求过于频繁", "当前咨询较多", "AI 服务繁忙")


def open_request(request, timeout=30):
    host = urllib.parse.urlsplit(request.full_url).hostname
    if host in ("127.0.0.1", "localhost", "::1"):
        return LOCAL_HTTP.open(request, timeout=timeout)
    return urllib.request.urlopen(request, timeout=timeout)


def json_request(base, path, payload=None, token=None, headers=None, method=None, timeout=30):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base.rstrip("/") + path,
        data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode(), headers=h, method=method)
    try:
        with open_request(req, timeout) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        try:
            body = json.loads(error.read())
        except ValueError:
            body = {"code": error.code, "message": "non_json_response"}
        return error.code, body


def register(base):
    user = "verify_" + uuid.uuid4().hex[:16]
    password = secrets.token_urlsafe(24)
    status, registration = json_request(base, "/auth/register", {"username": user, "password": password})
    if status != 200 or not registration.get("success"):
        raise RuntimeError("Synthetic user registration failed")
    status, login = json_request(base, "/auth/login", {"username": user, "password": password})
    token = login.get("token") or (login.get("data") or {}).get("token")
    if status != 200 or not token:
        raise RuntimeError("Synthetic user login failed")
    return user, token


def sse(base, path, params, token, timeout=100, accept="text/event-stream", cancel_after=None):
    trace_id = secrets.token_hex(16)
    h = {"Accept": accept, "traceparent": "00-" + trace_id + "-" + secrets.token_hex(8) + "-01"}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base.rstrip("/") + path + "?" + urllib.parse.urlencode(params), headers=h)
    start = time.perf_counter()
    events, data, kind = [], [], "message"
    with open_request(req, timeout) as response:
        status = response.status
        for raw in response:
            line = raw.decode("utf-8", "strict").rstrip("\r\n")
            if line.startswith("event:"):
                kind = line[6:].strip()
            elif line.startswith("data:"):
                text = line[5:]
                data.append(text[1:] if text.startswith(" ") else text)
            elif not line and data:
                events.append({"event": kind, "data": "\n".join(data), "at_ms": round((time.perf_counter()-start)*1000, 2)})
                kind, data = "message", []
                if cancel_after and len(events) >= cancel_after:
                    break
    if data:
        events.append({"event": kind, "data": "\n".join(data), "at_ms": round((time.perf_counter()-start)*1000, 2)})
    text = "".join(e["data"] for e in events if e["event"] == "message")
    errors = [e["data"] for e in events if e["event"] == "error"]
    success = status == 200 and bool(text.strip()) and not errors and not any(h in text for h in FAILURE_TEXT)
    return {"trace_id": trace_id, "status": status, "success": success, "text": text, "errors": errors,
            "events": events, "duration_ms": round((time.perf_counter()-start)*1000, 2),
            "ttft_ms": next((e["at_ms"] for e in events if e["event"] == "message" and e["data"].strip()), None)}


def opaque(value):
    return hashlib.sha256(value.encode()).hexdigest()[:24]
