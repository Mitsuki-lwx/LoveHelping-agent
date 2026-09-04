"""检索层评测（Context Precision/Recall）——直接调 admin 检索端点（2026-09-04 v2）。

v2 变更：不再嗅探应用日志（依赖单实例/日志路径，多实例混写会假阴性），改为
GET /admin/rag/retrieve（admin 鉴权）直接跑 retriever 拿父文档命中列表——
绕过 classify 路由（simple 分支不检索的波动问题消失），且同文档多块在父级去重，
P@5 口径更准（每文件一票）。

用法：ADMIN_API_KEY=xxx python scripts/retrieval_eval.py --base http://localhost:PORT/api
"""
import argparse, io, json, os, re, time, urllib.request, urllib.parse

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:12753/api")
    ap.add_argument("--cases", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "retrieval-ground-truth.json"))
    ap.add_argument("--admin-key", default=os.environ.get("ADMIN_API_KEY", ""))
    args = ap.parse_args()
    if not args.admin_key:
        raise SystemExit("需要 ADMIN_API_KEY 环境变量或 --admin-key")
    gt = json.load(io.open(args.cases, encoding="utf-8"))["cases"]

    def retrieve(q):
        url = args.base + "/admin/rag/retrieve?" + urllib.parse.urlencode({"query": q})
        r = urllib.request.Request(url, headers={"X-Admin-Key": args.admin_key})
        return json.loads(urllib.request.urlopen(r, timeout=60).read())["hits"]

    recalls, mrrs, skipped = [], [], 0
    print("%-8s %-28s %-6s %-6s %s" % ("case", "期望文档", "Recall", "MRR@5", "命中top5"))
    for c in gt:
        try:
            files = retrieve(c["question"])
        except Exception as e:
            print("%-8s %-28s ERROR %s" % (c["id"], "/".join(c["expect_docs"])[:28], str(e)[:60]))
            skipped += 1
            continue
        exp = c["expect_docs"]
        rank = next((i + 1 for i, f in enumerate(files[:5]) if any(k in f for k in exp)), None)
        rec = 1.0 if rank else 0.0
        mrr = 1.0 / rank if rank else 0.0
        recalls.append(rec); mrrs.append(mrr)
        print("%-8s %-28s %-6.2f %-6.2f %s" % (c["id"], "/".join(exp)[:28], rec, mrr,
              ", ".join(f[:18] for f in files[:5])[:58]))
        time.sleep(0.3)
    if recalls:
        print("\nRecall@5 均值: %.2f | MRR@5 均值: %.3f (n=%d%s)" %
              (sum(recalls)/len(recalls), sum(mrrs)/len(mrrs),
               len(recalls), ", skipped=%d" % skipped if skipped else ""))

if __name__ == "__main__":
    main()
