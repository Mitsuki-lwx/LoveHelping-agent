"""排查：空名 trace 内部 span 的父子关系，判断它是否本来就与 HTTP 根 span 不同 trace。"""
import base64
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import json_request

host = os.environ.get("LANGFUSE_HOST", "http://127.0.0.1:3000")
auth = "Basic " + base64.b64encode(
    (os.environ["LANGFUSE_PUBLIC_KEY"] + ":" + os.environ["LANGFUSE_SECRET_KEY"]).encode()).decode()
minutes = int(os.environ.get("WINDOW_MINUTES", "20"))
since = (datetime.now(timezone.utc) - timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
_, body = json_request(host, "/api/public/traces?limit=100&fromTimestamp=" + since, headers={"Authorization": auth})
data = body.get("data", [])
print("total=%d unnamed=%d" % (len(data), sum(1 for t in data if not (t.get("name") or ""))))
for trace in data:
    if trace.get("name"):
        continue
    _, detail = json_request(host, "/api/public/traces/" + trace["id"], headers={"Authorization": auth})
    obs = detail.get("observations", [])
    print("---", detail.get("timestamp"), trace["id"])
    for o in obs:
        print("     name=%-28s id=%-34s parent=%s" % (o.get("name"), o.get("id"), o.get("parentObservationId")))
