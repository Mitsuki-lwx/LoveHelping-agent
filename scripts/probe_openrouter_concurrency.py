#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""OpenRouter 并发上限阶梯探测（绕过应用，直连上游）。

为什么要测：应用闸门 max-inflight=24 是**按 bigmodel 实测上限 24 定的**。
换 provider 后这个数就不作数了 —— 必须重测，否则闸门要么形同虚设（限流打上游）、
要么过度保守（白白浪费上游容量）。同时要区分 429（限流）与 5xx（真故障）。

安全：凭据只从环境变量读。
用法：OPENROUTER_API_KEY=sk-or-v1-xxx python scripts/probe_openrouter_concurrency.py --model qwen/qwen-plus
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("OPENROUTER_BASE", "https://openrouter.ai/api/v1").rstrip("/")

SYSTEM = "你是恋爱帮帮帮的恋爱顾问，语气温柔但不谄谀。"
USER = "我和女朋友最近总是因为谁洗碗谁做饭吵架，她觉得我做得少，我该怎么破？"


def key() -> str:
    k = (os.environ.get("OPENROUTER_API_KEY") or "").strip()
    if not k or k.startswith("${"):
        print("FATAL: OPENROUTER_API_KEY 未注入", file=sys.stderr)
        sys.exit(2)
    return k


def one(model: str) -> dict:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": USER},
        ],
        "max_tokens": 800,
        "temperature": 0.7,
    }
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + key(),
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/lwx-ai-agent",
            "X-Title": "lwx-ai-agent-probe",
        },
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            body = json.loads(r.read().decode("utf-8"))
        u = body.get("usage") or {}
        return {
            "ok": True,
            "s": round(time.time() - t0, 2),
            "completion": u.get("completion_tokens"),
            "cost": u.get("cost"),
        }
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return {"ok": False, "code": e.code, "s": round(time.time() - t0, 2), "err": raw[:200]}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "code": -1, "s": round(time.time() - t0, 2), "err": "%s: %s" % (type(e).__name__, e)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen/qwen-plus")
    ap.add_argument("--steps", default="8,16,24,32,48,64", help="逗号分隔的并发档位")
    args = ap.parse_args()

    print("模型: %s" % args.model)
    print("阶梯: %s（每档同时发 N 个相同请求）" % args.steps)
    print()

    for n in [int(x) for x in args.steps.split(",")]:
        t0 = time.time()
        with ThreadPoolExecutor(max_workers=n) as ex:
            res = list(ex.map(lambda _: one(args.model), range(n)))
        wall = time.time() - t0
        ok = sum(1 for r in res if r["ok"])
        r429 = sum(1 for r in res if not r["ok"] and r.get("code") == 429)
        other = [r for r in res if not r["ok"] and r.get("code") != 429]
        lat = sorted(r["s"] for r in res if r["ok"])
        p50 = lat[len(lat) // 2] if lat else 0
        p95 = lat[int(len(lat) * 0.95) - 1] if lat else 0
        cost = sum(r.get("cost") or 0 for r in res if r["ok"])
        line = "  并发 %-3d 成功 %2d/%-2d  429=%-2d 其他失败=%-2d  wall=%.1fs p50=%.1fs p95=%.1fs cost=$%.5f" % (
        n, ok, n, r429, len(other), wall, p50, p95, cost
        )
        print(line)
        for r in other[:2]:
            print("       非429失败: code=%s %s" % (r["code"], r["err"][:160]))
        sys.stdout.flush()


if __name__ == "__main__":
    main()
