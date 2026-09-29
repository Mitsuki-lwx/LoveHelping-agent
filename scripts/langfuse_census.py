#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Langfuse 普查（ADR-62 取证）：把实例里的 trace/observation 摊开找可提升项。

为什么需要它：本仓多轮 ADR（48/51/58/60/61）反复栽在同一件事上 ——
**"生效的是什么"在观测里查不到**（换模型/换端点/换措辞都得靠自报行或推断）。
Langfuse 里有 6 万+ 真实 trace，正好用它把"观测面本身缺什么"查实。

凭据从环境变量读（`LANGFUSE_BASE_URL` / `PUBLIC_KEY` / `SECRET_KEY`）。
⚠️ v3 自托管：用 **legacy** `/api/public/observations` 与 `/api/public/traces`
（`/api/public/v2/observations` 只在 v4 write mode 下存在，本实例返回 NotFound）。

用法：python scripts/langfuse_census.py [--from 2026-09-24] [--to 2026-09-25] [--limit 200]
"""
import argparse
import base64
import collections
import json
import os
import sys
import urllib.error
import urllib.request


def client():
    host = (os.environ.get("LANGFUSE_BASE_URL") or os.environ.get("LANGFUSE_HOST") or "").rstrip("/")
    pk, sk = os.environ.get("LANGFUSE_PUBLIC_KEY"), os.environ.get("LANGFUSE_SECRET_KEY")
    if not host or not pk or not sk:
        raise SystemExit("缺 LANGFUSE_BASE_URL / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY")
    token = base64.b64encode(f"{pk}:{sk}".encode()).decode()

    def get(path):
        req = urllib.request.Request(host + path, headers={"Authorization": "Basic " + token})
        try:
            return json.load(urllib.request.urlopen(req, timeout=60))
        except urllib.error.HTTPError as e:
            raise SystemExit(f"HTTP {e.code} on {path}: {e.read(200).decode('utf-8', 'replace')}")

    return get


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="t_from", default="2026-09-24T00:00:00Z")
    ap.add_argument("--to", dest="t_to", default="2026-09-25T00:00:00Z")
    ap.add_argument("--limit", type=int, default=100)  # API 上限 100
    ap.add_argument("--trace", help="改为一树模式：打印该 trace 的观测树与耗时")
    args = ap.parse_args()
    if args.trace:
        return dump_trace(args.trace)
    get = client()

    q = f"&fromStartTime={args.t_from}&toStartTime={args.t_to}"
    d = get(f"/api/public/observations?limit={args.limit}{q}")
    obs = d.get("data", [])
    meta = d.get("meta", {})
    print(f"=== 观测普查 {args.t_from} ~ {args.t_to} ===")
    print(f"取回 {len(obs)} / 总计 {meta.get('totalItems')}（页 {meta.get('page')}/{meta.get('totalPages')}）")
    if not obs:
        print("该窗口无观测。")
        return 0

    by_name = collections.Counter()
    no_model = collections.Counter()
    levels = collections.Counter()
    types = collections.Counter()
    usage_hit = collections.Counter()
    for o in obs:
        n = o.get("name") or "<no-name>"
        by_name[n] += 1
        types[o.get("type") or "?"] += 1
        levels[o.get("level") or "DEFAULT"] += 1
        if not o.get("model"):
            no_model[n] += 1
        u = o.get("usage") or {}
        if u.get("input") or u.get("output") or u.get("total"):
            usage_hit[n] += 1

    print("\n--- 按 name ---")
    for n, c in by_name.most_common(20):
        print(f"  {c:>4}  {n:<42} 无model={no_model[n]:>4}  有usage={usage_hit[n]:>4}")
    print("\n--- type 分布 ---", dict(types))
    print("--- level 分布 ---", dict(levels))

    print("\n--- level 非 DEFAULT 的样本（最该看的） ---")
    bad = [o for o in obs if (o.get("level") or "DEFAULT") != "DEFAULT"]
    for o in bad[:10]:
        print(f"  [{o.get('level')}] {o.get('name')} | {o.get('statusMessage') or ''}"[:160])

    print("\n--- 最慢 10 条 ---")
    def ms(o):
        st, et = o.get("startTime"), o.get("endTime")
        if not st or not et:
            return -1
        from datetime import datetime
        f = "%Y-%m-%dT%H:%M:%S.%fZ"
        try:
            return (datetime.strptime(et, f) - datetime.strptime(st, f)).total_seconds() * 1000
        except Exception:
            return -1
    for o in sorted(obs, key=ms, reverse=True)[:10]:
        print(f"  {ms(o):>9.0f}ms  {o.get('name')}  model={o.get('model')}  type={o.get('type')}")

    # 盲区核对：这几个本该出现的外部依赖，在观测里有没有对应 name
    print("\n--- 盲区核对（预期缺失项）---")
    for probe in ("jev", "vision", "embedding", "rerank", "llm", "rag."):
        hit = [n for n in by_name if probe in (n or "").lower()]
        print(f"  '{probe}' 相关 name: {hit or '**无**'}")
    return 0




def dump_trace(trace_id):
    """打印一条 trace 的观测树（含各自耗时与父级占比）—— 用于"这段时间花在哪"。"""
    get = client()
    d = get("/api/public/traces/" + trace_id)
    obs = d.get("observations") or []
    from datetime import datetime
    f = "%Y-%m-%dT%H:%M:%S.%fZ"

    def rng(o):
        try:
            return (datetime.strptime(o["endTime"], f) - datetime.strptime(o["startTime"], f)).total_seconds() * 1000
        except Exception:
            return -1.0

    t0 = min((o["startTime"] for o in obs if o.get("startTime")), default=None)
    print(f"=== trace {trace_id} name={d.get('name')!r} obs={len(obs)} ===")
    for o in sorted(obs, key=lambda x: x.get("startTime") or ""):
        off = -1
        if t0 and o.get("startTime"):
            try:
                off = (datetime.strptime(o["startTime"], f) - datetime.strptime(t0, f)).total_seconds() * 1000
            except Exception:
                pass
        print(f"  +{off:>8.0f}ms  {rng(o):>8.0f}ms  {(o.get('level') or 'DEFAULT'):<8} "
              f"{o.get('type'):<11} {o.get('name')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
