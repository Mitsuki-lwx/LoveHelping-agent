#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
性能/并发压测（2026-09-06 立项；2026-09-15 Phase 6 修正）。

用法：
  ADMIN_API_KEY=xxx python scripts/loadtest.py --base http://localhost:PORT/api [选项]

模式（关键：两者测的不是同一件事）：
  * 单用户（默认 --users 1）：所有并发线程共用 1 个 token
      → 测的是**每用户突发桶**（app.rate-limit.burst-capacity），不是全局容量
      → 同一用户同时并发才会耗尽令牌；顺序请求因桶按 refill-per-second 回填而永不耗尽
  * 多用户（--users N，建议 N == 并发数）：N 个独立用户各发 1 请求
      → 测的才是**全局容量**（app.online.max-inflight 闸门 / 网关并发 / 厂商上限）

选项：
  --levels 8,20,50     并发阶梯（默认 8,20,50）
  --users N            独立用户数（默认 1；=并发数即"每用户一个请求"）
  --warmup N           预热轮数（默认 1，结果不计入统计；冷启动 JIT/首次调用会污染首档）
  --endpoint sync|sse  压测端点（默认 sync；sse 为流式聊天，额外测首 token 延迟）
  --prompt "..."       压测用 prompt（默认轻量问候）
  --timeout 120        单请求超时秒数

输出：每档 成功 / 过载拒(4003) / 限流拒(429) / 错误、延迟中位数与 P95、墙钟、拒绝率。
      验收口径见 docs/phase6-concurrency/spec.md §4。
"""
import argparse
import json
import statistics
import threading
import time
import urllib.parse
import urllib.request

# 过载(4003)与限流(429)的拒绝文案。SSE 下拒绝仍是 HTTP 200 的流内 event:error，
# 所以只判 HTTP 状态会把拒绝误记成成功——必须匹配文案。
OVERLOAD_HINTS = ("当前咨询较多", "系统繁忙", "4003")
RATELIMIT_HINTS = ("请求过于频繁", "今日调用次数已用完")


def register(base, seq=0):
    req = urllib.request.Request(
        base + "/auth/register",
        data=json.dumps({"username": "load_%d_%d" % (int(time.time() * 1000), seq),
                         "password": "Passw0rd!123"}).encode(),
        headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]


def _classify(body):
    if any(h in body for h in OVERLOAD_HINTS):
        return "overloaded"
    if any(h in body for h in RATELIMIT_HINTS):
        return "rate_limited"
    return None


def one_sync(base, token, prompt, idx, timeout):
    url = base + "/Love_app/chat/sync?" + urllib.parse.urlencode(
        {"prompt": prompt, "chatId": "load_%d_%d" % (int(time.time() * 1000), idx)})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    t0 = time.time()
    try:
        body = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "replace")
        dt = time.time() - t0
        rejected = _classify(body)
        if rejected:
            return rejected, dt
        return ("ok", dt) if '"code":200' in body else ("err", dt)
    except Exception:
        return "err", time.time() - t0


def one_sse(base, token, prompt, idx, timeout):
    url = base + "/Love_app/chat/sse?" + urllib.parse.urlencode(
        {"prompt": prompt, "chatId": "load_%d_%d" % (int(time.time() * 1000), idx)})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    t0 = time.time()
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        first = None
        for line in resp:
            s = line.decode("utf-8", "replace").strip()
            if not s.startswith("data:") or len(s) <= 5:
                continue
            body = s[5:].strip()
            rejected = _classify(body)
            if rejected:
                return rejected, (time.time() - t0 if first is None else first)
            if first is None and body:
                first = time.time() - t0  # 首 token 延迟
        return "ok", (first if first is not None else time.time() - t0)
    except Exception:
        return "err", time.time() - t0


def run_level(base, tokens, level, endpoint, prompt, timeout, label=""):
    fn = one_sse if endpoint == "sse" else one_sync
    res = {"ok": 0, "overloaded": 0, "rate_limited": 0, "err": 0}
    firsts, totals = [], []
    lock = threading.Lock()

    def worker(i):
        token = tokens[i % len(tokens)]  # --users < 并发 时轮转复用
        status, lat = fn(base, token, prompt, i, timeout)
        with lock:
            res[status] += 1
            firsts.append(lat)

    ths = [threading.Thread(target=worker, args=(i,)) for i in range(level)]
    t0 = time.time()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    wall = time.time() - t0
    firsts.sort()

    def pct(a, p):
        return a[max(0, int(len(a) * p) - 1)] if a else 0

    rejected = res["overloaded"] + res["rate_limited"]
    total = max(1, level)
    print("并发 %-3d 用户 %-3d | 成功 %-3d 过载拒 %-3d 限流拒 %-3d 错误 %-3d | 首字节 中位 %.2fs P95 %.2fs "
          "| 墙钟 %.0fs | 拒绝率 %.0f%%%s"
          % (level, len(tokens), res["ok"], res["overloaded"], res["rate_limited"], res["err"],
             statistics.median(firsts) if firsts else 0, pct(firsts, 0.95), wall,
             rejected / total * 100, label))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:12753/api")
    ap.add_argument("--levels", default="8,20,50")
    ap.add_argument("--users", type=int, default=1, help="独立用户数；=并发数即测全局容量")
    ap.add_argument("--warmup", type=int, default=1, help="预热轮数（不计入统计）")
    ap.add_argument("--endpoint", default="sync", choices=["sync", "sse"])
    ap.add_argument("--prompt", default="你好")
    ap.add_argument("--timeout", type=int, default=120)
    args = ap.parse_args()

    levels = [int(x) for x in args.levels.split(",") if x.strip()]
    max_level = max(levels) if levels else 1
    user_count = max(1, min(args.users, max_level)) if args.users > 1 else 1
    tokens = [register(args.base, i) for i in range(user_count)]

    mode = ("单用户（测每用户突发桶，非全局容量）" if user_count == 1
            else "多用户（%d 个独立用户，测全局容量）" % user_count)
    path = "/Love_app/chat/sse" if args.endpoint == "sse" else "/Love_app/chat/sync"
    print("压测端点: %s%s | 模式: %s | prompt=%s" % (args.base, path, mode, args.prompt[:20]))
    print("拒绝识别: 过载=4003(当前咨询较多/系统繁忙) 限流=429(请求过于频繁)；SSE 下均为流内 event:error")
    print("-" * 118)

    for w in range(max(0, args.warmup)):
        run_level(args.base, tokens, min(4, max_level), args.endpoint, args.prompt, args.timeout,
                  label="  ← 预热轮（不计入统计）")

    for lvl in levels:
        run_level(args.base, tokens, lvl, args.endpoint, args.prompt, args.timeout)

    print("-" * 118)
    print("验收口径（docs/phase6-concurrency/spec.md §4）：闸门满时按有界排队（最多等 app.online.wait-ms），")
    print("  到点仍无额度才拒绝，且拒绝必须带可读说明与 retryAfterSec；任何情况下不得出现 5xx。")


if __name__ == "__main__":
    main()
