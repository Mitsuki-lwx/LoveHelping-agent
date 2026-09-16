"""直连上游并发探测：固定并发档位**持续**压，找出 429 真正出现的并发点（不打印密钥）。

背景（2026-09-16）：
- §8.8 的并发阶梯（1/4/8/16/32/64/96）是**短时突发**，结论"有效上限 ≈24"。
- 30 分钟长压里，闸门 24 全程未越界，却仍有 616 次 429。
- 速率探测（严格串行）在 30/60/120 RPM 下 **0 拒绝**；把 prompt 撑到 2000 token
  在并发 1 下跑到 **74K TPM 仍 0 拒绝** → RPM 与 TPM 单独都不是瓶颈。

本脚本补上缺的一环：**持续**并发（每个 worker 完成一个立刻发下一个，与 soak 同构），
逐档跑固定时长，看 429 从哪一档开始出现、拒率多少。
`--pad-tokens` 用于补测"**并发 × 大请求**"这一格（应用真实工况是 24 并发 + 高 token 率，
而小请求探测只有 ~8K TPM，两者不可直接比较）。

用法：
    python scripts/vendor_concurrency_probe.py --levels 16,20,24,28 --seconds 45
    python scripts/vendor_concurrency_probe.py --levels 24 --seconds 45 --pad-tokens 2000
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
    if pad_tokens <= 0:
        return "你好"
    repeat = max(1, (pad_tokens * 4) // len(FILLER))
    return "请只回复'好'。以下是背景材料：" + FILLER * repeat


def one_call(opener, key, timeout, pad_tokens, stream=False):
    payload = {"model": MODEL,
               "messages": [{"role": "user", "content": build_prompt(pad_tokens)}],
               "max_tokens": 8, "stream": stream}
    body = json.dumps(payload).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key})
    start = time.perf_counter()
    tokens = 0
    try:
        with opener.open(req, timeout=timeout) as response:
            raw = response.read()
            kind, code = "ok", ""
            if not stream:
                try:
                    tokens = int(json.loads(raw).get("usage", {}).get("total_tokens", 0) or 0)
                except ValueError:
                    tokens = 0
            else:
                # 流式：必须真的读到 [DONE] 才算成功（只拿到 200 头不算）
                if b"[DONE]" not in raw and b'"finish_reason"' not in raw:
                    kind = "stream_truncated"
    except urllib.error.HTTPError as error:
        raw = error.read()[:200].decode("utf-8", "replace")
        try:
            code = str(json.loads(raw).get("error", {}).get("code", ""))
        except ValueError:
            code = ""
        kind = "http_%d" % error.code
    except Exception as error:
        kind, code = "exception", type(error).__name__
    return kind, code, (time.perf_counter() - start), tokens


def run_level(key, workers, seconds, timeout, pad_tokens, stream=False):
    ctx = ssl.create_default_context()
    lock = threading.Lock()
    counts, codes, latencies = {}, {}, []
    tokens_box = [0]
    stop_at = time.perf_counter() + seconds

    def worker():
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))
        while time.perf_counter() < stop_at:
            kind, code, elapsed, tokens = one_call(opener, key, timeout, pad_tokens, stream)
            with lock:
                counts[kind] = counts.get(kind, 0) + 1
                latencies.append(elapsed)
                tokens_box[0] += tokens
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
        "pad_tokens": pad_tokens,
        "stream": stream,
        "seconds": round(wall, 1),
        "total": total,
        "ok": ok,
        "rejected": total - ok,
        "reject_rate": round((total - ok) / total, 4) if total else None,
        "throughput_rps": round(total / wall, 2) if wall else None,
        "throughput_rpm": round(total / wall * 60, 1) if wall else None,
        "tpm": round(tokens_box[0] / wall * 60, 1) if wall else None,
        "latency_ms_p50": round(sorted(latencies)[len(latencies) // 2] * 1000) if latencies else None,
        "kinds": counts,
        "vendor_codes": codes,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--yml", default=str(ROOT / "target/classes/application-local.yml"))
    parser.add_argument("--levels", default="16,20,24,28")
    parser.add_argument("--seconds", type=float, default=45.0)
    parser.add_argument("--pad-tokens", type=int, default=0,
                        help="把 prompt 撑到约 N 个 token（0 = 小请求）")
    parser.add_argument("--stream", action="store_true", help="用 SSE 流式请求（应用聊天走的就是流式）")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", default="")
    args = parser.parse_args()

    key = read_key(args.yml, "openai:")
    print("直连 %s (%s)，**持续并发**（每 worker 完成即发下一个），每档 %.0fs，pad_tokens=%d"
          % (URL, MODEL, args.seconds, args.pad_tokens))
    print("%-8s %-7s %-7s %-8s %-9s %-10s %-10s %-10s %s"
          % ("并发", "总发", "ok", "被拒", "拒率", "实测RPM", "实测TPM", "延迟p50", "厂商码"))
    rows = []
    for level in [int(x) for x in args.levels.split(",")]:
        row = run_level(key, level, args.seconds, args.timeout, args.pad_tokens, args.stream)
        rows.append(row)
        print("%-8d %-7d %-7d %-8d %-9s %-10s %-10s %-10s %s" % (
            row["workers"], row["total"], row["ok"], row["rejected"],
            ("%.1f%%" % (row["reject_rate"] * 100)) if row["reject_rate"] is not None else "-",
            row["throughput_rpm"], row["tpm"], str(row["latency_ms_p50"]) + "ms",
            json.dumps(row["vendor_codes"], ensure_ascii=False)))
        time.sleep(8)

    print("汇总: " + json.dumps(rows, ensure_ascii=False))
    if args.output:
        Path(args.output).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已写入 " + args.output)
