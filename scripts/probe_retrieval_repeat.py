#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""逐例重复调用 `/admin/rag/retrieve`，量化检索链路的**逐例可复现性**。

**为什么需要它**：45 例评测三轮不是逐字节相同的 —— 但"不稳定"是个模糊说法，
决策需要知道：**不稳定的到底是哪几例、翻转幅度多大、翻转率多少**。
本探针把这件事变成数字（一次 app 实例内、公开数据、只读）。

用法：
    ADMIN_API_KEY=xxx python scripts/probe_retrieval_repeat.py \
        --base http://127.0.0.1:PORT/api --ids gt_24,gt_34,gt_01 --times 10

输出每个用例的"top5 顺序 → 出现次数"，以及 gold 文档的排名分布。
"""
import argparse, collections, io, json, os, time, urllib.parse, urllib.request


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="如 http://127.0.0.1:1234/api")
    ap.add_argument("--cases", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                    "retrieval-ground-truth.json"))
    ap.add_argument("--ids", required=True, help="逗号分隔的用例 id")
    ap.add_argument("--times", type=int, default=10)
    ap.add_argument("--admin-key", default=os.environ.get("ADMIN_API_KEY", ""))
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    if not a.admin_key:
        raise SystemExit("需要 ADMIN_API_KEY")

    gt = {c["id"]: c for c in json.load(io.open(a.cases, encoding="utf-8"))["cases"]}
    report = []
    for cid in a.ids.split(","):
        c = gt[cid]
        exp = c["expect_docs"]
        orders, ranks = collections.Counter(), collections.Counter()
        first_seen = []
        for _ in range(a.times):
            url = a.base + "/admin/rag/retrieve?" + urllib.parse.urlencode({"query": c["question"]})
            req = urllib.request.Request(url, headers={"X-Admin-Key": a.admin_key})
            hits = json.loads(urllib.request.urlopen(req, timeout=60).read())["hits"]
            top5 = hits[:5]
            key = " > ".join(h[:16] for h in top5)
            if key not in orders:
                first_seen.append(key)
            orders[key] += 1
            pos = next((i + 1 for i, f in enumerate(top5) if any(k in f for k in exp)), None)
            ranks[pos] += 1
            time.sleep(0.2)
        print("== %s  「%s」  x%d" % (cid, c["question"][:24], a.times))
        print("   命中排名分布: " + ", ".join(
            ("rank%s×%d" % (k if k else "无", v)) for k, v in sorted(ranks.items(), key=lambda x: (x[0] or 99)))
        )
        for k in first_seen:
            print("   %3d/%d  %s" % (orders[k], a.times, k))
        report.append({"id": cid, "question": c["question"], "times": a.times,
                       "rank_dist": {str(k): v for k, v in ranks.items()},
                       "orders": {k: v for k, v in orders.items()}})
    if a.out:
        io.open(a.out, "w", encoding="utf-8").write(json.dumps(report, ensure_ascii=False, indent=2))
        print("已写入 %s" % a.out)


if __name__ == "__main__":
    main()
