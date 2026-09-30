#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外网依赖的分段连通性探针（ADR-62 待办 #9/#10 取证）。

背景：仓库里长期挂着一条未归因的"环境抖动" —— `HttpConnectTimeoutException` 与
`PKIX path building failed` 交替出现（JEV / LLM / embedding 都中过），
但**没人把抖动拆到阶段上**（DNS？TCP？TLS？），于是每次只能记"未归因"。

本探针把一次连接拆成三段分别计时：
  DNS 解析 → TCP 连接 → TLS 握手
N 次采样后报每段的 min/p50/p95/max 与失败数。
⛔ 不改任何配置、不发业务请求；只回答"抖动发生在哪一段"。

用法：python scripts/probe_network_stages.py [--n 20] [--hosts a,b]
"""
import argparse
import socket
import ssl
import statistics
import sys
import time

DEFAULT_HOSTS = ["api.deepseek.com", "api.siliconflow.cn", "api.typesafe.ai"]


def probe(host: str, n: int) -> dict:
    dns, tcp, tls = [], [], []
    fails = {"dns": 0, "tcp": 0, "tls": 0}
    ip = None
    for _ in range(n):
        t0 = time.perf_counter()
        try:
            ip = socket.gethostbyname(host)
            dns.append((time.perf_counter() - t0) * 1000)
        except Exception:
            fails["dns"] += 1
            continue
        t1 = time.perf_counter()
        try:
            with socket.create_connection((host, 443), timeout=10) as s:
                tcp.append((time.perf_counter() - t1) * 1000)
                t2 = time.perf_counter()
                try:
                    ctx = ssl.create_default_context()
                    with ctx.wrap_socket(s, server_hostname=host):
                        tls.append((time.perf_counter() - t2) * 1000)
                except Exception as e:  # noqa: BLE001
                    fails["tls"] += 1
                    if fails["tls"] == 1:
                        print(f"    [首个 TLS 失败] {type(e).__name__}: {str(e)[:90]}")
        except Exception as e:  # noqa: BLE001
            fails["tcp"] += 1
            if fails["tcp"] == 1:
                print(f"    [首个 TCP 失败] {type(e).__name__}: {str(e)[:90]}")
    return {"ip": ip, "dns": dns, "tcp": tcp, "tls": tls, "fails": fails}


def stat(xs):
    if not xs:
        return "  (无样本)"
    s = sorted(xs)
    p95 = s[min(len(s) - 1, int(len(s) * 0.95))]
    return f"min {s[0]:7.1f}  p50 {statistics.median(s):7.1f}  p95 {p95:7.1f}  max {s[-1]:7.1f}  ms"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--hosts", default=",".join(DEFAULT_HOSTS))
    args = ap.parse_args()
    hosts = [h.strip() for h in args.hosts.split(",") if h.strip()]

    print(f"分段连通性探针  n={args.n}/host")
    for h in hosts:
        r = probe(h, args.n)
        print(f"\n== {h}  (解析到 {r['ip']})")
        print(f"   DNS  {stat(r['dns'])}   失败 {r['fails']['dns']}")
        print(f"   TCP  {stat(r['tcp'])}   失败 {r['fails']['tcp']}")
        print(f"   TLS  {stat(r['tls'])}   失败 {r['fails']['tls']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
