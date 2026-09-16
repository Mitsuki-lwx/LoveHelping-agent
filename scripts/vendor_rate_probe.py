"""直连上游速率探测：把"并发"变量排除掉，单独测 bigmodel 的**速率**配额（不打印密钥）。

背景（2026-09-16 30 分钟长压）：闸门 24 = 厂商**并发**上限，在途从未越界，
但日志里仍出现 616 次 `429 Too Many Requests` 与 198 次 `code 1302 速率限制`。
spec §5 的 S10 只考虑了并发维度，因此需要单独证明**速率维度**是否独立存在。

设计要点：**严格串行**（并发恒为 1），只改变"每秒发多少个请求"。
并发 = 1 时若仍出现 429，则该 429 只能来自速率配额，与并发无关。

用法：
    python logs/vendor_rate_probe.py --levels 30,60,120 --per-level 20
"""

import argparse
import json
import re
import ssl
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
            kind = "ok"
            detail = ""
    except urllib.error.HTTPError as error:
        raw = error.read()[:200].decode("utf-8", "replace")
        code = ""
        try:
            code = str(json.loads(raw).get("error", {}).get("code", ""))
        except ValueError:
            pass
        kind = "http_%d" % error.code
        detail = ("code=%s " % code if code else "") + raw[:120]
    except Exception as error:
        kind = "exception"
        detail = "%s: %s" % (type(error).__name__, str(error)[:100])
    return kind, detail, (time.perf_counter() - start)


def run_level(opener, key, rpm, per_level, timeout):
    """严格串行：每次请求完成后再 sleep，使长期速率趋近 rpm。"""
    interval = 60.0 / rpm
    counts, codes, latencies, wall_start = {}, {}, [], time.perf_counter()
    for i in range(per_level):
        tick = time.perf_counter()
        kind, detail, elapsed = one_call(opener, key, timeout)
        counts[kind] = counts.get(kind, 0) + 1
        latencies.append(elapsed)
        if kind.startswith("http_") and detail:
            key_code = detail.split(" ")[0]
            codes[key_code] = codes.get(key_code, 0) + 1
        if i < per_level - 1:
            rest = interval - (time.perf_counter() - tick)
            if rest > 0:
                time.sleep(rest)
    wall = time.perf_counter() - wall_start
    achieved = per_level / wall * 60.0
    ok = counts.get("ok", 0)
    rejected = per_level - ok
    return {
        "target_rpm": rpm,
        "per_level": per_level,
        "achieved_rpm": round(achieved, 1),
        "ok": ok,
        "rejected": rejected,
        "reject_rate": round(rejected / per_level, 4),
        "latency_ms_p50": round(sorted(latencies)[len(latencies) // 2] * 1000),
        "kinds": counts,
        "vendor_codes": codes,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--yml", default=str(ROOT / "target/classes/application-local.yml"))
    parser.add_argument("--levels", default="30,60,120")
    parser.add_argument("--per-level", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    key = read_key(args.yml, "openai:")
    ctx = ssl.create_default_context()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))

    print("直连 %s (%s)，**严格串行（并发=1）**，每档 %d 发" % (URL, MODEL, args.per_level))
    print("%-10s %-12s %-6s %-10s %-9s %s" % ("目标RPM", "实测RPM", "ok", "被拒", "拒率", "厂商码"))
    rows = []
    for rpm in [int(x) for x in args.levels.split(",")]:
        row = run_level(opener, key, rpm, args.per_level, args.timeout)
        rows.append(row)
        print("%-10d %-12.1f %-6d %-10d %-9.1f%% %s" % (
            row["target_rpm"], row["achieved_rpm"], row["ok"], row["rejected"],
            row["reject_rate"] * 100, json.dumps(row["vendor_codes"], ensure_ascii=False)))
        time.sleep(5)

    print("汇总: " + json.dumps(rows, ensure_ascii=False))
    if args.output:
        Path(args.output).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已写入 " + args.output)
