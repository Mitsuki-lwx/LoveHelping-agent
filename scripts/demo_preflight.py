#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""公网 demo 上线前自检（ops/demo/README.md §3 的机器版本）。

只做**非破坏性**探测：一次健康检查、一次无凭据的管理端点访问、一次登录失败计数
（用随机不存在账号，不碰真实用户）、两个 actuator 探测。

用法：
    python scripts/demo_preflight.py --base https://love.example.com/api
    python scripts/demo_preflight.py --base http://localhost:8088/api --allow-http
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

OK, FAIL, WARN = "✅", "❌", "⚠️"


def request(url: str, *, method: str = "GET", body: dict | None = None, timeout: int = 15):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 - 自检要能报告"连不上"
        return None, str(e)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="例如 https://love.example.com/api")
    ap.add_argument("--allow-http", action="store_true", help="允许明文 HTTP（仅本机自测）")
    args = ap.parse_args()
    base = args.base.rstrip("/")

    failures, warnings = [], []

    def check(name, ok, detail=""):
        print(f"  {OK if ok else FAIL} {name}" + (f"  —— {detail}" if detail else ""))
        if not ok:
            failures.append(name)

    def warn(name, detail=""):
        print(f"  {WARN} {name}" + (f"  —— {detail}" if detail else ""))
        warnings.append(name)

    print(f"demo 自检：{base}")

    # 1. HTTPS
    if base.startswith("https://"):
        check("站点使用 HTTPS", True)
    elif args.allow_http:
        warn("站点使用明文 HTTP", "仅限本机；发给别人前必须上 HTTPS")
    else:
        check("站点使用 HTTPS", False, "公网 demo 不要用明文 HTTP")

    # 2. 健康
    status, body = request(f"{base}/actuator/health")
    up = status == 200 and '"UP"' in body
    check("健康检查 = UP", up, f"HTTP {status}")

    # 3. 管理端点无凭据应被拒（不是 200，也不是 500）
    status, body = request(f"{base}/admin/analytics/overview")
    denied = status in (401, 403) or '"code":403' in body or '"code":401' in body
    check("管理端点无凭据被拒", denied, f"HTTP {status} {body[:60]}")

    # 4. actuator 敏感端点不可匿名读
    for path in ("/actuator/env", "/actuator/beans"):
        status, _ = request(f"{base}{path}")
        check(f"{path} 不可匿名读", status in (401, 403, 404, None), f"HTTP {status}")

    # 5. 登录节流必须是 ENFORCE：随机不存在账号连错 6 次，第 6 次应 429
    probe_user = "preflight_probe_do_not_exist"
    last_status, last_body = None, ""
    for _ in range(6):
        last_status, last_body = request(
            f"{base}/auth/login", method="POST", body={"username": probe_user, "password": "wrong"}
        )
    throttled = '"code":429' in last_body or last_status == 429
    check("登录节流生效（第 6 次 429）", throttled, f"HTTP {last_status} {last_body[:80]}")
    if not throttled:
        warn("可能处于 shadow 模式", "shadow 只判定不拦截，公开前应切 ENFORCE")

    print()
    if failures:
        print(f"{FAIL} 未通过 {len(failures)} 项：{', '.join(failures)}")
        return 1
    print(f"{OK} 全部通过" + (f"（{len(warnings)} 条提醒）" if warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
