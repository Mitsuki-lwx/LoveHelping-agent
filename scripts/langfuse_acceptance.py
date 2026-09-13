#!/usr/bin/env python3
"""ADR-24 平台级验收：发真实请求 → 从 Langfuse 平台查询 trace/observation。

与 scripts/e2e-smoke.sh 的区别：本脚本验证的是「trace 真的进了 Langfuse 平台」，
而不是「本地单测收到了 payload」。ADR-24 明确要求前者。

用法：
    LANGFUSE_HOST=http://localhost:3000 \
    LANGFUSE_PUBLIC_KEY=pk-lf-... LANGFUSE_SECRET_KEY=sk-lf-... \
    ADMIN_API_KEY=... python scripts/langfuse_acceptance.py --base http://localhost:<app-port>/api

凭据只从环境变量读取，绝不落盘。

判定项：
  1. 近期出现 name=chat 的 trace，且 sessionId/userId 已伪名化（不含原始 chatId/用户名）
  2. trace 详情含子 observation（LLM/图节点），数量 > 0
  3. 任一 observation 的属性中不含用户正文标记（deny-by-default 生效）
"""

import argparse
import base64
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def _req(url, headers, timeout=30, method="GET"):
    req = urllib.request.Request(url, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8", "replace"))


def lf_get(host, auth, path, params=None, timeout=30):
    url = host.rstrip("/") + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    return _req(url, {"Authorization": auth}, timeout)


def app_post(base, path, payload, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(payload).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


def sse_read(base, path, params, token, timeout=120, read_bytes=0):
    """读 SSE。read_bytes>0 时只读该字节数（用于快速取样/主动断连）。"""
    url = base + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    out = b""
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if read_bytes:
            return resp.read(read_bytes).decode("utf-8", "replace")
        while True:
            chunk = resp.read(4096)
            if not chunk:
                break
            out += chunk
    return out.decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="应用 API 基址，如 http://localhost:2375/api")
    ap.add_argument("--host", default=os.environ.get("LANGFUSE_HOST", "http://localhost:3000"))
    ap.add_argument("--public-key", default=os.environ.get("LANGFUSE_PUBLIC_KEY", ""))
    ap.add_argument("--secret-key", default=os.environ.get("LANGFUSE_SECRET_KEY", ""))
    ap.add_argument("--admin-key", default=os.environ.get("ADMIN_API_KEY", ""))
    ap.add_argument("--settle", type=float, default=18.0, help="等待导出落库的秒数")
    ap.add_argument("--lookback-min", type=int, default=10, help="只查最近 N 分钟的 trace")
    args = ap.parse_args()

    if not args.public_key or not args.secret_key:
        raise SystemExit("需要 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY 环境变量（或 --public-key/--secret-key）")
    auth = "Basic " + base64.b64encode(
        (args.public_key + ":" + args.secret_key).encode()).decode()

    checks = []

    # 0. 平台可达 + 凭据有效
    st, body = lf_get(args.host, auth, "/api/public/projects")
    ok = st == 200 and body.get("data")
    checks.append(("平台可达且凭据有效", ok, f"HTTP {st} projects={[p.get('name') for p in body.get('data', [])]}"))
    if not ok:
        print(json.dumps({"checks": checks}, ensure_ascii=False, indent=2))
        raise SystemExit(1)

    # 1. 发真实请求（标记串用于隐私核对）
    ts = int(time.time())
    user = "lfacc_%d" % ts
    marker_a = "LFVERIFYALPHA%d" % ts
    marker_b = "LFVERIFYBRAVO%d" % ts
    app_post(args.base, "/auth/register", {"username": user, "password": "Passw0rd!"})
    token = app_post(args.base, "/auth/login", {"username": user, "password": "Passw0rd!"})["token"]

    chat_id = "lfacc_conv_%d" % ts
    r1 = sse_read(args.base, "/Love_app/chat/sse",
                  {"prompt": marker_a + " 我们总是因为小事吵架，怎么办？", "chatId": chat_id}, token)
    r2 = sse_read(args.base, "/Love_app/chat/sse/rag",
                  {"prompt": marker_b + " 彩礼在什么情况下可以要求返还？", "chatId": chat_id + "_rag"}, token)
    checks.append(("真实请求已发出", True,
                   f"sse len={len(r1)} rag len={len(r2)}"))

    print("[info] 等待 %ss 让 BatchSpanProcessor 导出落库..." % args.settle)
    time.sleep(args.settle)

    # 2. 平台侧查询 trace（最近 N 分钟）
    frm = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - args.lookback_min * 60))
    st, listing = lf_get(args.host, auth, "/api/public/traces",
                         {"limit": 50, "fromTimestamp": frm})
    traces = listing.get("data", []) if isinstance(listing, dict) else []
    names = sorted({t.get("name") for t in traces})
    checks.append(("平台可查到本应用的 trace", len(traces) > 0,
                   f"近 {args.lookback_min} 分钟 {len(traces)} 条, names={names}"))

    # 选一条本应用产生的 trace（name=chat 优先，且 session 已伪名化）
    mine = [t for t in traces if t.get("name") == "chat"]

    def looks_pseudonymous(v):
        return bool(v) and not re.search(r"\d{10,}", v) and "lfacc_" not in v

    sess_ok = all(looks_pseudonymous(t.get("sessionId") or "") for t in mine) if mine else False
    user_ok = all(looks_pseudonymous(t.get("userId") or "") for t in mine) if mine else False
    checks.append(("sessionId/userId 已伪名化（不含原始 chatId/用户名）", bool(mine) and sess_ok and user_ok,
                   f"样例 sessionId={[t.get('sessionId') for t in mine[:2]]} userId={[t.get('userId') for t in mine[:2]]}"))

    # 3. 取 trace 详情，核对子 observation
    obs_count = 0
    obs_names = []
    detail_blob = ""
    if mine:
        tid = mine[0]["id"]
        st, detail = lf_get(args.host, auth, "/api/public/traces/" + tid)
        obs = detail.get("observations", []) or []
        obs_count = len(obs)
        obs_names = sorted({(o.get("name") or o.get("type") or "?") for o in obs})[:12]
        detail_blob = json.dumps(detail, ensure_ascii=False)
    checks.append(("trace 详情含子 observation", obs_count > 0,
                   f"observations={obs_count} 样例={obs_names[:8]}"))

    # 4. 隐私：平台侧不应出现用户正文与原始标识
    st, all_blob_parts = lf_get(args.host, auth, "/api/public/traces", {"limit": 50, "fromTimestamp": frm})
    blob = json.dumps(all_blob_parts, ensure_ascii=False) + detail_blob
    leaks = [m for m in (marker_a, marker_b, user, chat_id) if m in blob]
    checks.append(("平台侧无用户正文/原始标识泄漏", not leaks,
                   ("泄漏项=" + ",".join(leaks)) if leaks else "marker/username/chatId 均未出现"))

    print()
    print("=" * 78)
    passed = 0
    for name, ok, ev in checks:
        print("[%s] %s\n      证据: %s" % ("PASS" if ok else "FAIL", name, ev))
        passed += 1 if ok else 0
    print("=" * 78)
    print("平台级验收: %d/%d 通过" % (passed, len(checks)))
    if mine:
        print("样例 trace: id=%s name=%s latency=%s sessionId=%s userId=%s"
              % (mine[0].get("id"), mine[0].get("name"), mine[0].get("latency"),
                 mine[0].get("sessionId"), mine[0].get("userId")))
        print("平台链接: %s/trace/%s" % (args.host.rstrip("/"), mine[0].get("id")))
    return 0 if passed == len(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
