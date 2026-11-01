#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""登录节流 shadow 报告（change login-throttle-shadow / task 2.2）。

从两处读证据：
  1. 应用日志里的 `[login-throttle] would_block ...` 行（shadow 模式产生）
  2. Prometheus 的 `auth_throttle_shadow_block_total`

输出：按维度/账号汇总"本来会被拦"的量，并按当前阈值给出上线建议。

用法：
    python scripts/shadow_throttle_report.py --log app.log --prometheus http://localhost:8088/api/actuator/prometheus
    python scripts/shadow_throttle_report.py --log app.log            # 只看日志
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.request
from collections import Counter
from pathlib import Path

# [login-throttle] would_block dimension=user user_hash=abc123 ip=1.2.3.4 retryAfterSec=30
LINE = re.compile(
    r"\[login-throttle\] would_block dimension=(?P<dim>\w+) user_hash=(?P<user>\S+) "
    r"ip=(?P<ip>\S+) retryAfterSec=(?P<retry>\d+)"
)


def parse_log(path: Path) -> tuple[Counter, Counter, Counter, int]:
    by_dim: Counter = Counter()
    by_user: Counter = Counter()
    by_ip: Counter = Counter()
    total = 0
    if not path.exists():
        print(f"⛔ 日志不存在：{path}", file=sys.stderr)
        return by_dim, by_user, by_ip, total
    with path.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = LINE.search(line)
            if not m:
                continue
            total += 1
            by_dim[m.group("dim")] += 1
            by_user[m.group("user")] += 1
            by_ip[m.group("ip")] += 1
    return by_dim, by_user, by_ip, total


def fetch_metric(url: str) -> dict[str, float]:
    out: dict[str, float] = {}
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            for line in resp.read().decode("utf-8", "replace").splitlines():
                if line.startswith("auth_throttle_shadow_block_total"):
                    key = line.split("{", 1)[1].split("}", 1)[0] if "{" in line else "total"
                    out[key] = float(line.rsplit(" ", 1)[1])
    except Exception as exc:  # noqa: BLE001 - 报告工具，失败要能看见原因
        print(f"⚠️  指标读取失败（{url}）：{exc}", file=sys.stderr)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="app.log")
    ap.add_argument("--prometheus", help="例如 http://localhost:8088/api/actuator/prometheus")
    args = ap.parse_args()

    by_dim, by_user, by_ip, total = parse_log(Path(args.log))

    print("=" * 56)
    print("登录节流 shadow 报告")
    print("=" * 56)
    if total == 0:
        print("shadow 期没有命中记录：要么没开 SHADOW，要么阈值确实宽松。")
        print("⛔ 这两种情况在日志里长得一样 —— 先去确认启动横幅是 mode=SHADOW。")
        return 0

    print(f"would_block 总数        : {total}")
    print(f"按维度                  : {dict(by_dim)}")
    print(f"受影响账号数（哈希去重）: {len(by_user)}")
    print(f"受影响 IP 数            : {len(by_ip)}")
    print(f"最频繁的 5 个账号        : {by_user.most_common(5)}")

    if args.prometheus:
        metrics = fetch_metric(args.prometheus)
        print(f"Prometheus 计数          : {metrics}")
        if metrics and abs(sum(metrics.values()) - total) > 0:
            print("⚠️  日志与指标不一致 —— 可能有请求没打日志，或日志被轮转")

    print("-" * 56)
    print("怎么读这份报告：")
    print("  · 受影响账号里若出现**真实用户**（不是压测/脚本账号），当前阈值就在误伤；")
    print("  · 若全是脚本账号，可以维持 ENFORCE，但别把阈值继续调紧；")
    print("  · 切换前记得：shadow 期写过的退避键仍然有效，切 ENFORCE 可能立刻锁一批。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
