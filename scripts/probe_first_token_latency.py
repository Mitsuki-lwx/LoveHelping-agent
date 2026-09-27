#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""主端点首字节（time-to-first-token, TTFT）延迟分布 —— 为 attempt-timeout 定值取证。

为什么需要它（docs/03 ADR-48 / phase17 checklist D3）：
  当前 `app.llm.first-byte-timeout-ms=45000`，而 phase17 实测正常 TTFT p50≈2.2s / p99≈3.2s。
  phase16 又观测到「连发 3 次尝试连第一个 token 都没吐」的故障。
  → 45s 到底该调多低，取决于 TTFT 分布的**尾巴**长什么样、慢的是排队还是彻底不出。
  在没有分布数据前调参 = 拿一个相关数字当因果（本项目已因此错 4 次）。

量具纪律（phase17 踩过的坑，必须遵守）：
  ⛔ 禁止 `resp.read(4096)` —— 它会尽量读满才返回，SSE 响应只有几百字节
     → 永远阻塞到流结束，记到的「首帧时刻」其实是结束时刻（phase17 结论翻转的根因）。
     本脚本一律用 `read1()`：有多少给多少。
  ⛔ 失败样本要单独计数，不能从统计里悄悄丢掉 —— 尾延迟决策最怕的就是幸存者偏差。

用法：
  OPENAI_API_KEY=... OPENAI_BASE_URL=... OPENAI_MODEL=... \
    python scripts/probe_first_token_latency.py --repeat 20 --concurrency 1
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

# 与 ChatExecutor 的线上提示词同量级，长度可比；不含用户原文。
SYSTEM = (
    "你是一个资深的恋爱与关系心理顾问。请用中文回答，语气温暖但不谄媚。"
    "回答要具体、可执行，避免空泛安慰。"
    "请严格按「三牌 + 为什么有效 + 对方可能反应」的结构输出。"
)
USER = "我和女朋友最近总是因为谁洗碗谁做饭吵架，她觉得我做得少，我该怎么破？"

BASE = (os.environ.get("OPENAI_BASE_URL") or "").rstrip("/")
KEY = os.environ.get("OPENAI_API_KEY") or ""
MODEL = os.environ.get("OPENAI_MODEL") or ""


def one_call(timeout_s: int):
    """发一次流式请求，返回 (ttft_s|None, total_s|None, error|None)。"""
    payload = {
        "model": MODEL,
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": USER}],
        "stream": True,
        "max_tokens": 512,
    }
    # 应用侧 base-url 配的是 https://openrouter.ai/api，由 Spring AI 拼 "/v1/chat/completions"。
    # 这里显式补 /v1 —— 漏掉会得到 20/20 全 HTTP 404，量具自身变成噪声源。
    base = BASE if BASE.endswith("/v1") else BASE + "/v1"
    req = urllib.request.Request(
        base + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + KEY},
    )
    t0 = time.perf_counter()
    ttft = None
    chunks = 0
    try:
        resp = urllib.request.urlopen(req, timeout=timeout_s)
        while True:
            # ⛔ read1，不是 read —— 见模块 docstring
            b = resp.read1(4096)
            if not b:
                break
            if ttft is None:
                ttft = time.perf_counter() - t0
            chunks += 1
        resp.close()
        return ttft, time.perf_counter() - t0, None
    except urllib.error.HTTPError as e:
        try:
            body = e.read()[:200].decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            body = ""
        return None, None, "HTTP %d %s" % (e.code, body)
    except Exception as e:  # noqa: BLE001 —— 失败原因必须留痕
        return None, time.perf_counter() - t0, type(e).__name__


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    k = max(0, min(len(xs) - 1, int(round(p / 100.0 * (len(xs) - 1)))))
    return xs[k]


def summarize(label, samples):
    ok = [s["ttft"] for s in samples if s["ttft"] is not None]
    bad = [s for s in samples if s["error"]]
    print("\n--- %s ---" % label)
    print("  样本 %d  成功 %d  失败 %d" % (len(samples), len(ok), len(bad)))
    for b in bad:
        print("  ✗ 失败样本 error=%s elapsed=%.1fs" % (b["error"], b["total"] or -1))
    if ok:
        print("  TTFT  min=%.2f  p50=%.2f  p90=%.2f  p99=%.2f  max=%.2f (s)"
              % (min(ok), statistics.median(ok), pct(ok, 90), pct(ok, 99), max(ok)))
        # 决策依据：1.5~2 倍 p99 与 45s 当前值放在一起看
        print("  1.5*p99=%.1fs  2*p99=%.1fs  当前 first-byte-timeout=45s"
              % (pct(ok, 99) * 1.5, pct(ok, 99) * 2))
    return ok, bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeat", type=int, default=20)
    ap.add_argument("--concurrency", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=120, help="单次 HTTP 超时(s)")
    ap.add_argument("--warmup", type=int, default=2, help="预热轮次，不计入统计")
    ap.add_argument("--output")
    a = ap.parse_args()

    for name, v in (("OPENAI_BASE_URL", BASE), ("OPENAI_API_KEY", KEY), ("OPENAI_MODEL", MODEL)):
        if not v:
            print("FATAL: 缺环境变量 %s" % name)
            sys.exit(2)
    print("endpoint=%s  model=%s  repeat=%d  concurrency=%d  warmup=%d"
          % (BASE, MODEL, a.repeat, a.concurrency, a.warmup))

    for i in range(a.warmup):
        one_call(a.timeout)
        print("  warmup %d/%d" % (i + 1, a.warmup), flush=True)

    samples = []
    if a.concurrency <= 1:
        for i in range(a.repeat):
            s = {"ttft": None, "total": None, "error": None}
            s["ttft"], s["total"], s["error"] = one_call(a.timeout)
            samples.append(s)
            print("  [%2d/%2d] ttft=%s total=%.2fs err=%s"
                  % (i + 1, a.repeat,
                     "%.2f" % s["ttft"] if s["ttft"] else "None",
                     s["total"] or -1, s["error"]), flush=True)
    else:
        import concurrent.futures as cf
        with cf.ThreadPoolExecutor(max_workers=a.concurrency) as ex:
            futs = [ex.submit(one_call, a.timeout) for _ in range(a.repeat)]
            for i, f in enumerate(futs):
                ttft, total, err = f.result()
                samples.append({"ttft": ttft, "total": total, "error": err})
                print("  [%2d/%2d] ttft=%s total=%.2fs err=%s"
                      % (i + 1, a.repeat, "%.2f" % ttft if ttft else "None",
                         total or -1, err), flush=True)

    ok, bad = summarize("TTFT 分布", samples)
    out = {
        "endpoint": BASE, "model": MODEL,
        "repeat": a.repeat, "concurrency": a.concurrency,
        "ok_count": len(ok), "error_count": len(bad),
        "p50": statistics.median(ok) if ok else None,
        "p90": pct(ok, 90), "p99": pct(ok, 99), "max": max(ok) if ok else None,
        "errors": [b["error"] for b in bad],
    }
    if a.output:
        with open(a.output, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
        print("\n写出 %s" % a.output)
    print("JSON " + json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
