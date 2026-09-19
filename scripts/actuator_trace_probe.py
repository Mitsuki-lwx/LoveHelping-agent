"""T3 验证：在 Langfuse 平台侧反查 /actuator 抓取有没有产生 trace。

只读平台公开 API，按时间窗拉取最近的 trace 列表——**不看日志**（日志可能来自旁路）。
凭据只从环境变量读，不落盘。
"""
import argparse
import base64
import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import json_request  # noqa: E402


def main(args):
    public = os.environ.get("LANGFUSE_PUBLIC_KEY", "")
    secret = os.environ.get("LANGFUSE_SECRET_KEY", "")
    if not public or not secret:
        raise SystemExit("LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY required")
    auth = "Basic " + base64.b64encode((public + ":" + secret).encode()).decode()

    since = datetime.now(timezone.utc) - timedelta(minutes=args.window_minutes)
    path = ("/api/public/traces?limit=100&fromTimestamp="
            + since.strftime("%Y-%m-%dT%H:%M:%SZ"))
    status, body = json_request(args.host, path, headers={"Authorization": auth})
    if status != 200:
        print(json.dumps({"error": status, "body": body}, ensure_ascii=False))
        return 2

    traces = body.get("data", [])
    names = [(t.get("name") or "") for t in traces]
    actuator = [t for t, n in zip(traces, names) if "actuator" in n.lower()]
    report = {
        "host": args.host,
        "window_minutes": args.window_minutes,
        "since": since.isoformat(),
        "app_port": args.app_port,
        "total_traces": len(traces),
        "actuator_traces": len(actuator),
        "actuator_examples": [{"id": t.get("id"), "name": t.get("name"), "timestamp": t.get("timestamp")}
                              for t in actuator[:5]],
        "name_histogram": {n: names.count(n) for n in sorted(set(names))},
    }
    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("total_traces=%d actuator_traces=%d" % (len(traces), len(actuator)))
    print("names=" + json.dumps(report["name_histogram"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.environ.get("LANGFUSE_HOST", "http://127.0.0.1:3000"))
    parser.add_argument("--window-minutes", type=int, default=5)
    parser.add_argument("--app-port", default="")
    parser.add_argument("--output")
    sys.exit(main(parser.parse_args()))
