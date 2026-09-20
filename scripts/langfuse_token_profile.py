"""按 observation 名称聚合 token 用量 —— 回答"钱花在哪、哪些是分类型调用"。

只读 Langfuse 公开 API；凭据走环境变量。
"""
import base64
import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import json_request


def main():
    host = os.environ.get("LANGFUSE_HOST", "http://127.0.0.1:3000")
    auth = "Basic " + base64.b64encode(
        (os.environ["LANGFUSE_PUBLIC_KEY"] + ":" + os.environ["LANGFUSE_SECRET_KEY"]).encode()).decode()
    hours = int(os.environ.get("HOURS", "6"))
    since = (datetime.now(timezone.utc) - timedelta(hours=hours)).strftime("%Y-%m-%dT%H:%M:%SZ")

    agg = defaultdict(lambda: {"n": 0, "in": 0, "out": 0})
    page = 1
    total = 0
    while page <= 10:
        path = ("/api/public/observations?limit=100&page=%d&type=GENERATION&fromStartTime=%s" % (page, since))
        status, body = json_request(host, path, headers={"Authorization": auth})
        if status != 200:
            print("query failed", status, str(body)[:200])
            return 2
        data = body.get("data", [])
        total += len(data)
        for obs in data:
            name = obs.get("name") or "<unnamed>"
            usage = obs.get("usageDetails") or obs.get("usage") or {}
            row = agg[name]
            row["n"] += 1
            row["in"] += int(usage.get("input") or usage.get("promptTokens") or 0)
            row["out"] += int(usage.get("output") or usage.get("completionTokens") or 0)
        if len(data) < 100:
            break
        page += 1

    rows = sorted(agg.items(), key=lambda kv: -(kv[1]["in"] + kv[1]["out"]))
    print("窗口=%dh  generation 观测=%d" % (hours, total))
    print("%-34s %6s %12s %12s" % ("observation", "count", "input_tok", "output_tok"))
    for name, row in rows:
        print("%-34s %6d %12d %12d" % (name[:34], row["n"], row["in"], row["out"]))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
