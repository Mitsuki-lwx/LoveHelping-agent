"""探测上游厂商（硅基流动）的并发上限 —— 直接对 API 打并发阶梯。

## 为什么需要这个

ADR-41 把 `app.rag.rerank.max-concurrent` 提到 16，但"16 安全吗、还能不能再高"**应用侧压测答不了**：
应用有整机闸门（`max-concurrent-calls=24`），到不了厂商真实的限流点。
这条同时回答长期挂着的那个问题——"提高吞吐天花板必须先提厂商配额"，到底该提多少。

## 与生产路径的关系

- 超时用 **30s**（比生产的 `timeout-ms=5000` 宽）→ 目的是**区分"限流"与"我自己超时了"**，
  用生产超时会把网络抖动误判成限流。
- payload 用**真实形状**：rerank = 1 query + 20 条真实知识块（每条截断到与
  `max-candidate-chars=1000` 一致）；embedding = 1 条 query（在线路径的形状）。
- 只统计：成功 / 429 / 其他 4xx-5xx / 超时 / 连接错误，**分开计数**。

用法：
    python scripts/probe_upstream_concurrency.py --target rerank --levels 1,2,4,8,16,24,32
    python scripts/probe_upstream_concurrency.py --target embedding --levels 1,4,8,16,32,48
"""
import argparse
import concurrent.futures as cf
import io
import json
import os
import re
import time
import urllib.error
import urllib.request

SF_BASE = "https://api.siliconflow.cn/v1"
EMBED_MODEL = "Qwen/Qwen3-Embedding-0.6B"
RERANK_MODEL = "Qwen/Qwen3-Reranker-8B"
QUERY = "我们冷战了，该怎么开口沟通？"


def load_key():
    """Key 只从环境变量读；没有再尝试从 gitignored 的本地配置读（不打印）。"""
    key = os.environ.get("SF_API_KEY")
    if key:
        return key
    path = r"D:\java\lwx-ai-agent\target\classes\application-local.yml"
    if os.path.exists(path):
        text = io.open(path, encoding="utf-8").read()
        # ⚠️ 必须**先定位到 siliconflow 块**再取 api-key：该 yml 里 dashscope / openai 等
        # 也各有一条 `api-key:`，直接搜第一个会拿到别家的 key。
        # 实测踩到：首版就是这样，请求发得出去但服务端回 401（code 30014 Token 校验失败），
        # 看起来像"被限流了"，其实是拿错了凭据。
        m = re.search(r"^[ \t]*siliconflow:[ \t]*$((?:\n[ \t]+.*)+)", text, re.M)
        if m:
            k = re.search(r"api-key:\s*(sk-\S+)", m.group(1))
            if k:
                return k.group(1)
    raise SystemExit("SF_API_KEY 未配置（环境变量或 application-local.yml 的 siliconflow 块）")


def real_candidates(limit=20, cut=1000):
    """取真实知识块，形状与 `top-n=20` + `max-candidate-chars=1000` 一致。"""
    try:
        import psycopg2
    except ImportError:
        raise SystemExit("需要 psycopg2（用托管 python 运行）")
    c = psycopg2.connect(user="postgres", password="123456", dbname="postgres",
                         host="127.0.0.1", port=5432)
    cur = c.cursor()
    cur.execute("SELECT content FROM vector_store "
                "WHERE COALESCE(metadata->>'source','') NOT IN ('memory','evolution') LIMIT %s", (limit,))
    rows = [(r[0] or "")[:cut] for r in cur.fetchall()]
    c.close()
    if not rows:
        raise SystemExit("知识库取不到候选")
    return rows


def make_payload(target, docs):
    if target == "rerank":
        return f"{SF_BASE}/rerank", {
            "model": RERANK_MODEL, "query": QUERY, "documents": docs, "top_n": 5,
        }
    if target == "embedding":
        return f"{SF_BASE}/embeddings", {
            "model": EMBED_MODEL, "input": [QUERY], "encoding_format": "float",
        }
    raise SystemExit("unknown target: " + target)


def endpoints(target, docs):
    """返回 [(url, payload), ...]。

    `mixed` = embedding 与 rerank **交替、同时**发出 —— 这是生产里最真实的形状：
    一次检索先要 embedding（query 向量）再要 rerank（精排），两者**共用同一个 API key**。
    若厂商按 key 限总配额，mixed 会比单打任一通道更早触限。
    """
    if target == "rerank":
        return [make_payload("rerank", docs)]
    if target == "embedding":
        return [make_payload("embedding", [])]
    if target == "mixed":
        return [make_payload("embedding", []), make_payload("rerank", docs)]
    raise SystemExit("unknown target: " + target)


def one_call(url, payload, key, timeout):
    """单次调用，返回 (类别, 状态码, 耗时ms, 备注)。类别分开计数，不与"低风险"混。"""
    body = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key})
    # 显式绕过代理：实测本机有代理时 urllib 可能走代理导致 connect 超时
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    t0 = time.time()
    try:
        with opener.open(req, timeout=timeout) as r:
            raw = r.read()
            ms = (time.time() - t0) * 1000
            if r.status != 200:
                return f"http_{r.status}", r.status, ms, ""
            json.loads(raw)
            return "ok", r.status, ms, ""
    except urllib.error.HTTPError as e:
        ms = (time.time() - t0) * 1000
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:120]
        except Exception:
            pass
        return f"http_{e.code}", e.code, ms, detail
    except Exception as e:
        ms = (time.time() - t0) * 1000
        return type(e).__name__, None, ms, str(e)[:80]


def percentile(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    idx = min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1))))
    return round(s[idx], 1)


def run_level(level, eps, key, timeout, waves):
    """每档跑 waves 波，每波同时发 level 个请求（模拟瞬时并发）。
    eps 有多个端点时按轮转分配 → 每波里各端点的量近似相等。"""
    all_rows = []
    for _ in range(waves):
        with cf.ThreadPoolExecutor(max_workers=level) as ex:
            futs = [ex.submit(one_call, eps[i % len(eps)][0], eps[i % len(eps)][1], key, timeout)
                    for i in range(level)]
            all_rows.extend(f.result() for f in cf.as_completed(futs))
    buckets = {}
    for kind, code, ms, note in all_rows:
        b = buckets.setdefault(kind, {"n": 0, "ms": [], "note": ""})
        b["n"] += 1
        b["ms"].append(ms)
        if note and not b["note"]:
            b["note"] = note
    return {
        "level": level,
        "total": len(all_rows),
        "buckets": {k: {"n": v["n"], "p50": percentile(v["ms"], 50), "p95": percentile(v["ms"], 95),
                        "note": v["note"]} for k, v in buckets.items()},
        "ok": buckets.get("ok", {}).get("n", 0),
        "ok_p50": percentile(buckets.get("ok", {}).get("ms", []), 50),
        "ok_p95": percentile(buckets.get("ok", {}).get("ms", []), 95),
        "rate_limited": buckets.get("http_429", {}).get("n", 0),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", choices=["rerank", "embedding", "mixed"], default="rerank")
    ap.add_argument("--levels", default="1,2,4,8,16,24,32")
    ap.add_argument("--waves", type=int, default=2, help="每档跑几波（每波同时发 level 个）")
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--output", default="")
    args = ap.parse_args()

    key = load_key()
    docs = real_candidates() if args.target in ("rerank", "mixed") else []
    eps = endpoints(args.target, docs)
    levels = [int(x) for x in args.levels.split(",") if x.strip()]
    est = sum(levels) * args.waves

    print("=" * 78)
    print("上游并发探测  target=%s  levels=%s  waves=%d  单档请求数=level×waves" % (args.target, levels, args.waves))
    for u, p in eps:
        shape = ("候选 %d 条 / 总字符 %d" % (len(p["documents"]), sum(len(d) for d in p["documents"]))
                 if "documents" in p else "1 条 query")
        print("payload: %s  %s" % (u, shape))
    print("超时 %.0fs（刻意宽于生产 5s：目的是区分「限流」与「我自己超时了」）" % args.timeout)
    print("预计总请求 ≈ %d 次%s" % (est, "（≈ ¥%.2f，按 ¥0.0017/次估）" % (est * 0.0017) if args.target == "rerank" else ""))
    print("=" * 78)
    print("%-8s %-8s %-8s %-10s %-12s %-12s %s" % ("并发", "成功", "429", "其他失败", "p50(ms)", "p95(ms)", "非成功明细"))
    print("-" * 78)

    results = []
    for lv in levels:
        r = run_level(lv, eps, key, args.timeout, args.waves)
        results.append(r)
        others = {k: v for k, v in r["buckets"].items() if k != "ok"}
        detail = "; ".join("%s×%d%s" % (k, v["n"], (" " + v["note"][:40]) if v["note"] else "")
                           for k, v in sorted(others.items())) or "—"
        print("%-8d %-8d %-8d %-10d %-12s %-12s %s" % (
            lv, r["ok"], r["rate_limited"], r["total"] - r["ok"] - r["rate_limited"],
            r["ok_p50"], r["ok_p95"], detail))
        time.sleep(1)

    # 判读：第一档出现 429 或任何非成功 → 即为该并发下的上限
    broke = [r["level"] for r in results if r["ok"] < r["total"]]
    print("-" * 78)
    if not broke:
        print("结论：直到 %d 并发**全部成功、无 429** → 未探到上限，可再往上试。" % levels[-1])
    else:
        first = broke[0]
        print("结论：**%d 并发开始出现非成功**（首个拐点）；%d 及以下全部成功。"
              % (first, max([r["level"] for r in results if r["ok"] == r["total"]], default=0)))
        print("      ⚠️ 注意区分原因：429 才是限流；超时/连接错误属链路问题（处置不同）。")
    if args.output:
        io.open(args.output, "w", encoding="utf-8").write(json.dumps(
            {"target": args.target, "levels": levels, "waves": args.waves,
             "timeout_s": args.timeout, "results": results}, ensure_ascii=False, indent=2))
        print("已写 %s" % args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
