"""直连上游并发探测：固定并发档位**持续**压，找出 429 真正出现的并发点（不打印密钥）。

背景（2026-09-16）：
- §8.8 的并发阶梯（1/4/8/16/32/64/96）是**短时突发**，结论"有效上限 ≈24"。
- 30 分钟长压里，闸门 24 全程未越界，却仍有 616 次 429。
- 速率探测（严格串行）在 30/60/120 RPM 下 **0 拒绝** → 说明不是纯请求速率上限。

本脚本补上缺的一环：**持续**并发（每个 worker 完成一个立刻发下一个，与 soak 同构），
逐档跑固定时长，看 429 从哪一档开始出现、拒率多少。
据此判断"闸门取 24 = 厂商上限本身"是否就是零余量问题。

用法：
    python logs/vendor_concurrency_probe.py --levels 16,20,24,28 --seconds 45
"""

import argparse
import json
import re
import ssl
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
MODEL = "glm-4-flash"


def read_key(yml_path, section):
    text = Path(yml_path).read_text(encoding="utf-8")
    idx = text.find(section)
    if idx < 0:
        raise SystemExit("section not found: " + section)
    match = re.search(r"api-key:\s*(\S+)", text[idx:])
    if not match:
        raise SystemExit("api-key not found after " + section)
    return match.group(1)


def one_call(opener, key, timeout):
    body = json.dumps({"model": MODEL, "messages": [{"role": "user", "content": "你好"}],
                       "max_tokens": 8, "stream": False}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key})
    start = time.perf_counter()
    try:
        with opener.open(req, timeout=timeout) as response:
            response.read()
            kind, code = "ok", ""
    except urllib.error.HTTPError as error:
        raw = error.read()[:200].decode("utf-8", "replace")
        try:
            code = str(json.loads(raw).get("error", {}).get("code", ""))
        except ValueError:
            code = ""
        kind = "http_%d" % error.code
    except Exception as error:
        kind, code = "exception", type(error).__name__
    return kind, code, (time.perf_counter() - start)


def run_level(key, workers, seconds, timeout):
    ctx = ssl.create_default_context()
    lock = threading.Lock()
    counts, codes, latencies = {}, {}, []
    stop_at = time.perf_counter() + seconds

    def worker():
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))
        while time.perf_counter() < stop_at:
            kind, code, elapsed = one_call(opener, key, timeout)
            with lock:
                counts[kind] = counts.get(kind, 0) + 1
                latencies.append(elapsed)
                if code:
                    codes[code] = codes.get(code, 0) + 1

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(workers)]
    start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - start
    total = sum(counts.values())
    ok = counts.get("ok", 0)
    return {
        "workers": workers,
        "seconds": round(wall, 1),
        "total": total,
        "ok": ok,
        "rejected": total - ok,
        "reject_rate": round((total - ok) / total, 4) if total else None,
        "throughput_rps": round(total / wall, 2) if wall else None,
        "throughput_rpm": round(total / wall * 60, 1) if wall else None,
        "latency_ms_p50": round(sorted(latencies)[len(latencies) // 2] * 1000) if latencies else None,
        "kinds": counts,
        "vendor_codes": codes,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--yml", default=str(ROOT / "target/classes/application-local.yml"))
    parser.add_argument("--levels", default="16,20,24,28")
    parser.add_argument("--seconds", type=float, default=45.0)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    key = read_key(args.yml, "openai:")
    print("直连 %s (%s)，**持续并发**（每 worker 完成即发下一个），每档 %.0fs"
          % (URL, MODEL, args.seconds))
    print("%-8s %-8s %-7s %-8s %-9s %-11s %-10s %s"
          % ("并发", "总发", "ok", "被拒", "拒率", "实测RPM", "延迟p50", "厂商码"))
    rows = []
    for level in [int(x) for x in args.levels.split(",")]:
        row = run_level(key, level, args.seconds, args.timeout)
        rows.append(row)
        print("%-8d %-8d %-7d %-8d %-9s %-11s %-10s %s" % (
            row["workers"], row["total"], row["ok"], row["rejected"],
            ("%.1f%%" % (row["reject_rate"] * 100)) if row["reject_rate"] is not None else "-",
            row["throughput_rpm"], str(row["latency_ms_p50"]) + "ms",
            json.dumps(row["vendor_codes"], ensure_ascii=False)))
        time.sleep(8)

    print("汇总: " + json.dumps(rows, ensure_ascii=False))
    if args.output:
        Path(args.output).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已写入 " + args.output)
