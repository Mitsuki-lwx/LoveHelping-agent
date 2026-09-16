"""直连上游速率探测：把"并发"变量排除掉，单独测 bigmodel 的**速率**约束（不打印密钥）。

背景（2026-09-16 30 分钟长压）：闸门 24 = 厂商**并发**上限，在途从未越界，
但日志里仍出现 616 次 `429 Too Many Requests` 与 198 次 `code 1302 速率限制`。
spec §5 的 S10 只考虑了并发维度，因此需要单独证明**速率维度**是否独立存在。

设计要点：**严格串行**（并发恒为 1），只改变"每秒发多少个请求"。
并发 = 1 时若仍出现 429，则该 429 只能来自速率配额，与并发无关。

**两代用法（2026-09-16 追加）**：
- 小请求（默认）：测**请求速率 RPM** 是否受限。
- `--pad-tokens 2000`：把 prompt 撑大，测**token 速率 TPM** 是否受限。
  这一维很关键：soak 的应用侧 prompt 吞吐 ≈62K TPM，而小请求探测只有 ~8K TPM，
  两者差 7.6 倍 —— 恰好与"soak 拒率 25% vs 小请求探测 3.4%"的 8 倍差距吻合。
  故必须把 RPM 与 TPM 分开测，否则会把 TPM 限制误判成并发限制。

用法：
    python scripts/vendor_rate_probe.py --levels 30,60 --per-level 20
    python scripts/vendor_rate_probe.py --levels 60 --per-level 30 --pad-tokens 2000
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
FILLER = "The quick brown fox jumps over the lazy dog. "


def read_key(yml_path, section):
    text = Path(yml_path).read_text(encoding="utf-8")
    idx = text.find(section)
    if idx < 0:
        raise SystemExit("section not found: " + section)
    match = re.search(r"api-key:\s*(\S+)", text[idx:])
    if not match:
        raise SystemExit("api-key not found after " + section)
    return match.group(1)


def build_prompt(pad_tokens):
    """把用户消息撑到约 pad_tokens 个 token（英文 1 token ≈ 4 字符）。"""
    if pad_tokens <= 0:
        return "你好"
    repeat = max(1, (pad_tokens * 4) // len(FILLER))
    return "请只回复'好'。以下是背景材料：" + FILLER * repeat


def one_call(opener, key, timeout, pad_tokens):
    body = json.dumps({"model": MODEL, "messages": [{"role": "user", "content": build_prompt(pad_tokens)}],
                       "max_tokens": 8, "stream": False}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key})
    start = time.perf_counter()
    tokens = 0
    try:
        with opener.open(req, timeout=timeout) as response:
            raw = response.read()
            kind, detail = "ok", ""
            try:
                tokens = int(json.loads(raw).get("usage", {}).get("total_tokens", 0) or 0)
            except ValueError:
                tokens = 0
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
    return kind, detail, (time.perf_counter() - start), tokens


def run_level(opener, key, rpm, per_level, timeout, pad_tokens):
    """严格串行：每次请求完成后再 sleep，使长期速率趋近 rpm。"""
    interval = 60.0 / rpm
    counts, codes, latencies, tokens_total = {}, {}, [], 0
    wall_start = time.perf_counter()
    for i in range(per_level):
        tick = time.perf_counter()
        kind, detail, elapsed, tokens = one_call(opener, key, timeout, pad_tokens)
        counts[kind] = counts.get(kind, 0) + 1
        latencies.append(elapsed)
        tokens_total += tokens
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
        "pad_tokens": pad_tokens,
        "achieved_rpm": round(achieved, 1),
        "ok": ok,
        "rejected": rejected,
        "reject_rate": round(rejected / per_level, 4),
        "latency_ms_p50": round(sorted(latencies)[len(latencies) // 2] * 1000),
        "tokens_total": tokens_total,
        "tpm": round(tokens_total / wall * 60.0, 1) if wall else None,
        "kinds": counts,
        "vendor_codes": codes,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--yml", default=str(ROOT / "target/classes/application-local.yml"))
    parser.add_argument("--levels", default="30,60,120")
    parser.add_argument("--per-level", type=int, default=20)
    parser.add_argument("--pad-tokens", type=int, default=0,
                        help="把 prompt 撑到约 N 个 token（0 = 小请求，测 RPM）")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    key = read_key(args.yml, "openai:")
    ctx = ssl.create_default_context()
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))

    print("直连 %s (%s)，**严格串行（并发=1）**，每档 %d 发，pad_tokens=%d"
          % (URL, MODEL, args.per_level, args.pad_tokens))
    print("%-10s %-11s %-6s %-8s %-9s %-9s %-10s %s"
          % ("目标RPM", "实测RPM", "ok", "被拒", "拒率", "实测TPM", "延迟p50", "厂商码"))
    rows = []
    for rpm in [int(x) for x in args.levels.split(",")]:
        row = run_level(opener, key, rpm, args.per_level, args.timeout, args.pad_tokens)
        rows.append(row)
        print("%-10d %-11.1f %-6d %-8d %-9.1f%% %-9s %-10s %s" % (
            row["target_rpm"], row["achieved_rpm"], row["ok"], row["rejected"],
            row["reject_rate"] * 100, row["tpm"],
            str(row["latency_ms_p50"]) + "ms",
            json.dumps(row["vendor_codes"], ensure_ascii=False)))
        time.sleep(5)

    print("汇总: " + json.dumps(rows, ensure_ascii=False))
    if args.output:
        Path(args.output).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已写入 " + args.output)
