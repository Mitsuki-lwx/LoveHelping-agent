#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
性能/并发压测（2026-09-06 立项）：docs/09 §6 性能测试场景的执行工具。

用法：
  ADMIN_API_KEY=xxx python scripts/loadtest.py --base http://localhost:PORT/api [选项]

选项：
  --levels 8,20,50,100   并发阶梯（默认 8,20,50）
  --endpoint sync|sse    压测端点（默认 sync；sse 为流式聊天，测首 token 延迟）
  --prompt "..."         压测用 prompt（默认轻量问候）
  --timeout 120          单请求超时秒数

输出：每个阶梯统计 成功 / 闸门拒(4003) / 其他错误 / 首字节延迟中位数与 P95 / 总耗时，
     并提示 docs/09 §6 的验收标准（P95 首 token < 1.5s、错误率 < 1%）。
"""
import argparse
import json
import os
import statistics
import threading
import time
import urllib.parse
import urllib.request

GATE_LIMIT_HINT = 8  # app.online.max-inflight 默认（OnlineLoadTracker）


def register(base):
    req = urllib.request.Request(
        base + "/auth/register",
        data=json.dumps({"username": "load_%d" % int(time.time() * 1000),
                         "password": "Passw0rd!123"}).encode(),
        headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]


def one_sync(base, token, prompt, idx, timeout):
    url = base + "/Love_app/chat/sync?" + urllib.parse.urlencode(
        {"prompt": prompt, "chatId": "load_%d_%d" % (int(time.time() * 1000), idx)})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    t0 = time.time()
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        body = resp.read().decode("utf-8", "replace")
        dt = time.time() - t0
        if '"code":200' in body and "4003" not in body and "稍等一下" not in body:
            return "ok", dt
        if "4003" in body or "稍等一下" in body:
            return "busy", dt
        return "err", dt
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
            if s.startswith("data:") and len(s) > 5:
                body = s[5:].strip()
                if "4003" in body or "稍等一下" in body:
                    return "busy", (time.time() - t0 if first is None else first)
                if first is None:
                    first = time.time() - t0  # 首 token 延迟
        return "ok", (first if first is not None else time.time() - t0)
    except Exception:
        return "err", time.time() - t0


def run_level(base, token, level, endpoint, prompt, timeout):
    fn = one_sse if endpoint == "sse" else one_sync
    res = {"ok": 0, "busy": 0, "err": 0}
    lats = []
    lock = threading.Lock()

    def worker(i):
        status, lat = fn(base, token, prompt, i, timeout)
        with lock:
            res[status] += 1
            lats.append(lat)

    ths = [threading.Thread(target=worker, args=(i,)) for i in range(level)]
    t0 = time.time()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    dur = time.time() - t0
    lats.sort()
    med = lats[len(lats) // 2] if lats else 0
    p95 = lats[int(len(lats) * 0.95) - 1] if lats else 0
    total = level
    err_rate = (res["err"] + res["busy"]) / total * 100 if total else 0
    print("并发 %-4d | 成功 %-4d 闸门拒 %-4d 错误 %-4d | 延迟 中位 %.2fs P95 %.2fs | 墙钟 %.0fs | 拒绝率 %.0f%%"
          % (level, res["ok"], res["busy"], res["err"], med, p95, dur, err_rate))
    return res, med, p95, err_rate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:12753/api")
    ap.add_argument("--levels", default="8,20,50")
    ap.add_argument("--endpoint", default="sync", choices=["sync", "sse"])
    ap.add_argument("--prompt", default="你好")
    ap.add_argument("--timeout", type=int, default=120)
    args = ap.parse_args()

    token = register(args.base)
    print("压测端点: %s/%s | 闸门上限参考: max-inflight=%d | prompt=%s"
          % (args.base, args.endpoint, GATE_LIMIT_HINT, args.prompt[:20]))
    print("-" * 96)
    for lvl in [int(x) for x in args.levels.split(",") if x.strip()]:
        run_level(args.base, token, lvl, args.endpoint, args.prompt, args.timeout)
    print("-" * 96)
    print("验收对照（docs/09 §6）：P95 首 token < 1.5s、错误率 < 1%（闸门拒计入错误率）。")
    print("注：闸门拒不是故障——是容量保护；若要让高并发下拒绝率 < 1%，需调高 max-inflight")
    print("    并确认 LLM 厂商并发上限（见 docs/09 §6 性能工程计划）。")


if __name__ == "__main__":
    main()
