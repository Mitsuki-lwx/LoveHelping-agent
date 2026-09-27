#!/usr/bin/env python3
"""对照实验：SSE 的两条返回路径，哪条能让首帧**真正立即**到达客户端。

为什么必须做这个实验（2026-09-27 实测踩的坑）：
  我先在 `Flux<ServerSentEvent>` 返回值上做占位事件，单测 7/7 全绿，
  真实链路实测「占位 @28.25s、正文 @28.25s」—— **两者同刻，白屏一点没消除**。
  改成 `Flux.create` 里先 `sink.next(占位)` 仍然无效：
  真实链路「响应头 @0.01s、首帧 @10~35s」→ 说明**响应头早早发出，
  但第一个元素之前服务端不写任何字节**，reactive 返回值管线在这里被缓冲了。

  假说：项目是 spring-boot-starter-web（MVC，非 WebFlux），
  返回 `Flux<ServerSentEvent>` 时走 reactive 适配器，**首帧前会等**；
  而 `SseEmitter` 是 servlet 原生容器，`send()` 立即写并 flush。

  ⛔ 这是假说，必须实测。两条路径各打一次，比首帧时刻。

判据：谁的「首帧时刻」更小就改谁。若两条都慢 → 假说错，瓶颈在别处
（此时才该去查前置工作本身，如 RAG/rerank）。
"""
import http.client
import statistics
import sys
import time
from urllib.parse import quote

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from verification_support import register  # noqa: E402

PORT = int(sys.argv[1])
REPEAT = int(sys.argv[2]) if len(sys.argv) > 2 else 3
Q = "异地半年了，感觉感情变淡了，我该怎么判断还值不值得坚持？"


def timeline(path, token):
    t0 = time.time()
    out = {"headers_at": None, "first_frame_at": None, "first_name": None,
           "first_payload": "", "n_frames": 0}
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=180)
    try:
        conn.request("GET", path, headers={"Authorization": f"Bearer {token}",
                                           "Accept": "text/event-stream"})
        resp = conn.getresponse()
        out["headers_at"] = round(time.time() - t0, 3)
        out["headers"] = {k.lower(): v for k, v in resp.getheaders()
                          if k.lower() in ("content-type", "content-encoding",
                                           "transfer-encoding", "content-length")}
        buf = b""
        while True:
            try:
                # ⛔⛔ 必须用 read1，不能用 read(4096)。
                # read(amt) 会**尽量读满 amt 才返回**；SSE 响应总共只有几百字节，
                # 永远读不满 4096 → 它一直阻塞到流结束才返回。
                # 于是"首帧时刻"被记成"整条响应结束时刻"（11~13s / 36s），
                # 我据此判定"占位事件无效"—— **那是量具坏了，不是占位坏了**。
                # read1() 返回当前缓冲区里已有的全部字节，不等补满。
                chunk = resp.read1(4096)
            except Exception:  # noqa: BLE001
                break
            if not chunk:
                break
            buf += chunk.replace(b"\r\n", b"\n")
            while b"\n\n" in buf:
                frame, _, buf = buf.partition(b"\n\n")
                txt = frame.decode("utf-8", "replace")
                if not txt.strip():
                    continue
                out["n_frames"] += 1
                if out["first_frame_at"] is None:
                    out["first_frame_at"] = round(time.time() - t0, 3)
                    name = "message"
                    for ln in txt.splitlines():
                        if ln.startswith("event:"):
                            name = ln.split(":", 1)[1].strip()
                            break
                    out["first_name"] = name
                    out["first_payload"] = " ".join(
                        l.split(":", 1)[1].strip() for l in txt.splitlines()
                        if l.startswith("data:"))[:70]
    finally:
        conn.close()
    return out


def main():
    base = f"http://127.0.0.1:{PORT}/api"
    _, token = register(base)
    q = quote(Q)
    # ⛔ chatId 必须**每轮唯一**。上一版把 URL 写进 dict 字面量，
    # 两条 f-string 在构造时一次性求值 → 同一 arm 的 REPEAT 轮**共用一个 threadId**
    # → RedisSaver 按 threadId 恢复 checkpoint，GRAPH_PATH 逐轮累积成 3/6/9。
    # 我当时把它读成"图在自转"，那是把探针 bug 当成了产品缺陷。
    arms = ["A flux_serverSentEvent", "B sse_emitter"]
    res = {}
    for name in arms:
        ep = "/api/Love_app/chat/sse" if name.startswith("A") else "/api/Love_app/chat/sse_emitter"
        print(f"\n{name}")
        rows = []
        for i in range(REPEAT):
            cid = f"ab_{name[0]}_{int(time.time()*1000)}_{i}"
            path = f"{ep}?prompt={q}&chatId={cid}"
            try:
                r = timeline(path, token)
            except Exception as e:  # noqa: BLE001
                print(f"  第{i+1}轮 失败 {type(e).__name__}: {e}")
                continue
            rows.append(r)
            print(f"  第{i+1}轮  响应头@{r['headers_at']}s  首帧@{r['first_frame_at']}s"
                  f"  事件名={r['first_name']}  总帧数={r['n_frames']}"
                  f"  载荷={r['first_payload']!r}", flush=True)
            if i == 0:
                print(f"        响应头关键字段: {r.get('headers')}", flush=True)
        if rows:
            ff = [r["first_frame_at"] for r in rows if r["first_frame_at"] is not None]
            res[name] = {
                "first_frame_median": round(statistics.median(ff), 2) if ff else None,
                "first_name_always_status": all(r["first_name"] == "status" for r in rows),
            }
    print("\n=== 结论 ===")
    for k, v in res.items():
        print(f"  {k:<28} 首帧中位={v['first_frame_median']}s  "
              f"首帧都是 status={v['first_name_always_status']}")
    if len(res) == 2:
        a = res["A flux_serverSentEvent"]["first_frame_median"]
        b = res["B sse_emitter"]["first_frame_median"]
        if a is not None and b is not None:
            print(f"\n  B 比 A {'快' if b < a else '慢'} {abs(a-b):.2f}s")
            print("  → 改 " + ("B (SseEmitter)" if b < a else "A (Flux)"))
        ok = [v["first_frame_median"] for v in res.values() if v["first_frame_median"] is not None]
        if ok and max(ok) <= 1.0:
            print("  ✅ 首帧 <=1s：占位事件**真的立即到达客户端**，白屏已消除。")
            print("     此前 11~13s / 36s 的读数是 read(4096) 量具缺陷，不是产品行为。")
        else:
            print("  ⛔ 首帧仍 >1s → 占位确实被缓冲，继续查返回类型 / 前置工作。")


if __name__ == "__main__":
    main()
