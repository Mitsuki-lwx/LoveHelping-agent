"""直连上游**突发**探测：验证"熔断恢复惊群"假设（不打印密钥）。

背景（2026-09-16，ADR-31 待证项）：
- 直连**平稳** 24 并发 → 拒率 3.4%（`scripts/vendor_concurrency_probe.py`）。
- soak（应用闸门 24）的网关调用却有 ~25% 被 429，高约 8 倍。
- 领先假设：熔断长时间打开（rejected 24670/24620）→ 半开瞬间 24 个请求**同时**涌入 →
  瞬时并发越过厂商容忍 → 429 → 熔断再开，形成正反馈。
  **平稳 24 与突发 24 不是同一工况**，所以要用"同时发起"复现。

用法：
    python scripts/vendor_burst_probe.py --modes steady24,burst24,burst32 --seconds 45
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


def make_opener():
    ctx = ssl.create_default_context()
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))


def one_call(opener, key, timeout):
    body = json.dumps({"model": MODEL, "messages": [{"role": "user", "content": "你好"}],
                       "max_tokens": 8, "stream": False}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with opener.open(req, timeout=timeout) as response:
            response.read()
            return "ok", ""
    except urllib.error.HTTPError as error:
        raw = error.read()[:200].decode("utf-8", "replace")
        try:
            code = str(json.loads(raw).get("error", {}).get("code", ""))
        except ValueError:
            code = ""
        return "http_%d" % error.code, code
    except Exception as error:
        return "exception", type(error).__name__


def collect(lock, counts, codes, kind, code):
    with lock:
        counts[kind] = counts.get(kind, 0) + 1
        if code:
            codes[code] = codes.get(code, 0) + 1


def run_steady(key, size, seconds, timeout):
    """平稳：size 个 worker 连续压，每个完成即发下一个。"""
    counts, codes, lock = {}, {}, threading.Lock()
    stop_at = time.perf_counter() + seconds

    def worker():
        opener = make_opener()
        while time.perf_counter() < stop_at:
            kind, code = one_call(opener, key, timeout)
            collect(lock, counts, codes, kind, code)

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(size)]
    start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return time.perf_counter() - start, counts, codes


def run_burst(key, size, seconds, timeout, idle):
    """突发：每隔 idle 秒，size 个请求**同时**发起（Barrier 对齐），等全部结束再等下一轮。"""
    counts, codes, lock = {}, {}, threading.Lock()
    stop_at = time.perf_counter() + seconds
    rounds = 0

    while time.perf_counter() < stop_at:
        barrier = threading.Barrier(size)

        def worker():
            opener = make_opener()
            barrier.wait()
            kind, code = one_call(opener, key, timeout)
            collect(lock, counts, codes, kind, code)

        threads = [threading.Thread(target=worker, daemon=True) for _ in range(size)]
        cycle_start = time.perf_counter()
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        rounds += 1
        rest = idle - (time.perf_counter() - cycle_start)
        if rest > 0:
            time.sleep(rest)
    return rounds, counts, codes


def summarize(name, wall, counts, codes, extra=None):
    total = sum(counts.values())
    ok = counts.get("ok", 0)
    rejected = total - ok
    row = {
        "mode": name,
        "seconds": round(wall, 1),
        "total": total,
        "ok": ok,
        "rejected": rejected,
        "reject_rate": round(rejected / total, 4) if total else None,
        "throughput_rpm": round(total / wall * 60, 1) if wall else None,
        "kinds": counts,
        "vendor_codes": codes,
    }
    if extra:
        row.update(extra)
    return row


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--yml", default=str(ROOT / "target/classes/application-local.yml"))
    parser.add_argument("--modes", default="steady24,burst24,burst32")
    parser.add_argument("--seconds", type=float, default=45.0)
    parser.add_argument("--idle", type=float, default=5.0, help="突发模式下每轮之间的静默秒数")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    key = read_key(args.yml, "openai:")
    print("直连 %s (%s)；突发模式 = %d 请求同时发起后静默 %.0fs（模拟熔断恢复惊群）"
          % (URL, MODEL, 0, args.idle))
    print("%-14s %-7s %-7s %-8s %-9s %-11s %s"
          % ("模式", "总发", "ok", "被拒", "拒率", "实测RPM", "厂商码"))
    rows = []
    for mode in args.modes.split(","):
        mode = mode.strip()
        if mode.startswith("steady"):
            size = int(mode[len("steady"):])
            wall, counts, codes = run_steady(key, size, args.seconds, args.timeout)
            row = summarize(mode, wall, counts, codes, {"size": size, "idle": None})
        elif mode.startswith("burst"):
            size = int(mode[len("burst"):])
            rounds, counts, codes = run_burst(key, size, args.seconds, args.timeout, args.idle)
            row = summarize(mode, args.seconds, counts, codes, {"size": size, "idle": args.idle,
                                                                "rounds": rounds})
        else:
            raise SystemExit("unknown mode: " + mode)
        rows.append(row)
        print("%-14s %-7d %-7d %-8d %-9s %-11s %s" % (
            row["mode"], row["total"], row["ok"], row["rejected"],
            ("%.1f%%" % (row["reject_rate"] * 100)) if row["reject_rate"] is not None else "-",
            row["throughput_rpm"], json.dumps(row["vendor_codes"], ensure_ascii=False)))
        time.sleep(8)

    print("汇总: " + json.dumps(rows, ensure_ascii=False))
    if args.output:
        Path(args.output).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已写入 " + args.output)
