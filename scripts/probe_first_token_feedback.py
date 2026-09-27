#!/usr/bin/env python3
"""量两件事，都是 phase17 的验收依据：

1. **占位事件是否真的第一个到**（白屏是否被消除）—— 逐帧记录到达时刻
2. **首字节延迟（TTFT）分布** —— 决定 attempt-timeout 该定多少的唯一依据

⛔ 为什么必须单列一个脚本：MockMvc 单测只能证明"事件顺序对"，
   证明不了"在真实链路上、上游慢的时候，用户真的不再白屏"。
   phase16 教训：单测全绿 ≠ 真实链路被覆盖。

量法：
  ① 用 raw socket 逐帧读，记录**每个事件的首字节到达时刻**（不是等整个响应结束）
     → 白屏时间 = 第一个 `event:status` 的到达时刻
  ② 结束后读 prometheus 的 `llm_time_to_first_token` 分位（流式与同步都有埋点）

⚠️ 前提断言：必须先确认生效端点是 space-bunny-alpha（否则量的不是目标端点的 TTFT）。
"""
import argparse
import json
import http.client
import socket
import statistics
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import register  # noqa: E402

QUESTIONS = [
    "我和对象吵架后冷战三天了，谁都不说话，我该先开口吗？",
    "我们准备结婚，但两家对婚礼花费有分歧，怎么谈才不伤感情？",  # phase16 测出必超时的题
    "他总是忘记我生日，我有点难过但说不出口，该怎么表达？",
    "异地半年了，感觉感情变淡了，我该怎么判断还值不值得坚持？",
    "他一吵架就摔门，我有点怕他，这种关系正常吗？",
]


def metrics(base):
    with urllib.request.urlopen(base + "/actuator/prometheus", timeout=20) as r:
        text = r.read().decode("utf-8", "replace")
    out = {}
    for line in text.splitlines():
        if line.startswith("#") or " " not in line:
            continue
        key, _, val = line.rpartition(" ")
        if key.endswith("_created"):
            continue
        try:
            out[key] = float(val)
        except ValueError:
            pass
    return out


def stream_timeline(host, port, path, headers):
    """逐帧读 SSE，返回 [(到达秒, 事件名, 载荷前80字)]。

    ⛔ 用 http.client 而不是手写 socket：第一版手写 HTTP 请求头，
       拼串时踩了 bytes/str 混用的 TypeError，且"逐字节读状态行"的循环
       在多字节时永不退出。**协议解析不要自己写** —— 标准库能做且不会错。
    """
    t0 = time.time()
    events = []
    conn = http.client.HTTPConnection(host, port, timeout=180)
    try:
        conn.request("GET", path, headers={**headers, "Accept": "text/event-stream"})
        resp = conn.getresponse()
        # ⭐ **响应头到达时刻**单独记：它能区分两种完全不同的情况 ——
        #   ① 响应头就慢 → 服务端在第一个元素之前就没吐字节（WebFlux 的行为）
        #   ② 响应头快、第一帧慢 → 服务端内部逻辑慢
        # 第一版把两者混在一起（都从 t0 算），结果误判成"占位没立即发出"。
        events.append((time.time() - t0, "_headers", f"HTTP {resp.status}"))
        buf = b""
        while True:
            try:
                chunk = resp.read(4096)
            except socket.timeout:
                events.append((time.time() - t0, "TIMEOUT", ""))
                break
            if not chunk:
                break
            buf += chunk
            # SSE 帧分隔符可能是 \n\n 也可能是 \r\n\r\n，统一化后再切
            buf = buf.replace(b"\r\n", b"\n")
            while b"\n\n" in buf:
                frame, _, buf = buf.partition(b"\n\n")
                txt = frame.decode("utf-8", "replace")
                if not txt.strip():
                    continue
                name = "message"
                for ln in txt.splitlines():
                    if ln.startswith("event:"):
                        name = ln.split(":", 1)[1].strip()
                        break
                payload = " ".join(l.split(":", 1)[1].strip()
                                   for l in txt.splitlines() if l.startswith("data:"))
                events.append((time.time() - t0, name, payload[:80]))
    finally:
        conn.close()
    return events, t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--output")
    args = ap.parse_args()
    base = f"http://{args.host}:{args.port}/api"

    m0 = metrics(base)
    eps = [k for k in m0 if k.startswith("llm_endpoint_configured_total")]
    print("  前提断言：生效端点")
    for k in sorted(eps):
        print("    ", k.replace("llm_endpoint_configured_total{", "").rstrip("}"))
    if not any("space-bunny-alpha" in k for k in eps):
        print("  [中止] 主端点不是 space-bunny-alpha → 量的不是目标端点")
        return 2

    user, token = register(base)
    rows = []
    for i in range(args.repeat):
        q = QUESTIONS[i % len(QUESTIONS)]
        from urllib.parse import quote
        path = f"/api/Love_app/chat/sse?prompt={quote(q)}&chatId=phase17_{int(time.time()*1000)}_{i}"
        try:
            evs, _ = stream_timeline(args.host, args.port, path, {"Authorization": f"Bearer {token}"})
        except Exception as e:  # noqa: BLE001
            print(f"  第{i+1}轮 异常 {type(e).__name__}: {e}")
            continue
        # 响应头时刻要先存下来再过滤 —— 它是判断"白屏在哪"的关键分界
        hdr_at = next((t for t, n, _ in evs if n == "_headers"), None)
        evs = [e for e in evs if e[1] != "_headers"]
        if not evs:
            print(f"  第{i+1}轮 只有响应头(@{hdr_at if hdr_at is None else f'{hdr_at:.2f}'}s)，无 SSE 事件")
            continue
        first_at, first_name, first_pay = evs[0]
        body_at = next((t for t, n, _ in evs if n == "message"), None)
        row = {"question": q, "first_event": first_name,
               "headers_at": round(hdr_at, 2) if hdr_at is not None else None,
               "first_event_at": round(first_at, 2),
               "first_event_payload": first_pay, "first_body_at": round(body_at, 2) if body_at else None,
               "blank_seconds": round(body_at, 2) if body_at else None,
               "events": [{"t": round(t, 2), "name": n, "p": p} for t, n, p in evs[:6]]}
        rows.append(row)
        h = f"{hdr_at:.2f}" if hdr_at is not None else "n/a"
        b = f"{body_at:.2f}" if body_at is not None else "none"
        print(f"  第{i+1}轮  响应头@{h}s  首个事件={first_name} @{first_at:.2f}s  首个正文@{b}s"
              f"  事件数={len(evs)}", flush=True)

    m1 = metrics(base)
    print()
    if rows:
        blanks = [r["blank_seconds"] for r in rows if r["blank_seconds"] is not None]
        print(f"  占位事件是否总是第一个："
              f"{sum(1 for r in rows if r['first_event'] == 'status')}/{len(rows)}")
        hdrs = [r["headers_at"] for r in rows if r.get("headers_at") is not None]
        if hdrs:
            print(f"  响应头到达 min/中位/max = {min(hdrs):.2f} / "
                  f"{statistics.median(hdrs):.2f} / {max(hdrs):.2f} s")
            print("  [读法] 响应头时刻 ≈ 首帧时刻 → 服务端在第一个元素前就没吐字节；"
                  "响应头远早于首帧 → 是内部逻辑慢。两者处置完全不同。")
        if blanks:
            print(f"  白屏时间（= 首个正文到达时刻）min/中位/max = "
                  f"{min(blanks):.2f} / {statistics.median(blanks):.2f} / {max(blanks):.2f} s")
        print("  [读法] 白屏时间 = 用户盯着空白的时长。占位事件把"
              "「0 秒就知道在忙」变成了「有东西可看」，但白屏时间本身不变。")
    print()
    print("  网关侧 TTFT 埋点（llm_time_to_first_token）：")
    ttft = {k: v for k, v in m1.items() if "time_to_first_token" in k}
    if not ttft:
        print("    [无] 埋点没生效？检查是否跑的是本轮改动的代码")
    for k in sorted(ttft):
        print(f"    {k} = {ttft[k]}")
    if args.output:
        Path(args.output).write_text(json.dumps(
            {"rows": rows, "ttft_metrics": ttft}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n  已写入 {args.output}")


if __name__ == "__main__":
    main()
