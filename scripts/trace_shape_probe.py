"""一次性排查：列出最近窗口内每个 trace 的构成（名字 + 观测名），看有没有孤立子 span。"""
import argparse
import base64
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import json_request


def main():
    host = os.environ.get("LANGFUSE_HOST", "http://127.0.0.1:3000")
    pk = os.environ["LANGFUSE_PUBLIC_KEY"]
    sk = os.environ["LANGFUSE_SECRET_KEY"]
    auth = "Basic " + base64.b64encode((pk + ":" + sk).encode()).decode()
    minutes = int(os.environ.get("WINDOW_MINUTES", "6"))
    since = (datetime.now(timezone.utc) - timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
    status, body = json_request(host, "/api/public/traces?limit=100&fromTimestamp=" + since,
                                headers={"Authorization": auth})
    data = body.get("data", []) if status == 200 else []
    unnamed = [t for t in data if not (t.get("name") or "")]
    print("window_since=%s total=%d unnamed=%d" % (since, len(data), len(unnamed)))
    rows = []
    for trace in data:
        _, detail = json_request(host, "/api/public/traces/" + trace["id"], headers={"Authorization": auth})
        rows.append((detail.get("timestamp"), detail.get("name"),
                     [o.get("name") for o in detail.get("observations", [])][:6]))
    for row in sorted(rows):
        print(" ", row[0], repr(row[1]), row[2])
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())
