"""检索层评测（Context Precision/Recall）——用 retrieval-ground-truth.json 的用例实测。

用法：python scripts/retrieval_eval.py --base http://localhost:5612/api
依赖：应用在跑（含 RAG 检索日志 RAG_RETRIEVAL）、MySQL/Redis 可达、注册接口可用。
原理：每条用例串行发 /chat/sse（唯一 chatId），请求前后对比 app 日志新增的
RAG_RETRIEVAL 行（advisor 线程无 chatId，串行时新增行即本请求），提取 file= 列表，
与 expect_docs 比对算 Precision@5 与 Recall。
"""
import argparse, io, json, os, re, sys, time, urllib.request, urllib.parse

LOG = os.path.join(os.environ.get("TEMP", "/tmp"), "main-app.log")

def read_log():
    with io.open(LOG, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5612/api")
    ap.add_argument("--cases", default=os.path.join(os.path.dirname(__file__), "retrieval-ground-truth.json"))
    args = ap.parse_args()
    gt = json.load(io.open(args.cases, encoding="utf-8"))["cases"]

    user = "gteval_%d" % int(time.time())
    req = urllib.request.Request(args.base + "/auth/register",
        data=json.dumps({"username": user, "password": "Passw0rd!123"}).encode(),
        headers={"Content-Type": "application/json"})
    token = json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]

    precisions, recalls = [], []
    print("%-8s %-28s %-5s %-5s %s" % ("case", "期望文档", "P@5", "Recall", "命中top5"))
    for c in gt:
        lines_before = len(read_log())
        url = args.base + "/Love_app/chat/sse?" + urllib.parse.urlencode(
            {"prompt": c["question"], "chatId": c["id"]})
        r = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
        urllib.request.urlopen(r, timeout=180).read()
        time.sleep(1.2)  # 等日志落盘
        added = read_log()[lines_before:]
        hit = [l for l in added if "RAG_RETRIEVAL" in l]
        files = []
        if hit:
            for m in re.finditer(r"file=([^ |\]]+)", hit[-1]):
                files.append(m.group(1))
        # files 里含中文文件名（GBK 终端显示乱码但读文件是 UTF-8）
        exp = c["expect_docs"]
        hits_top5 = [f for f in files[:5] if any(k in f for k in exp)]
        p = len(hits_top5) / 5.0
        rec = 1.0 if hits_top5 else 0.0  # 每例期望 1 篇（gt_07 两篇判 or）
        precisions.append(p); recalls.append(rec)
        exp_s = "/".join(exp)
        print("%-8s %-28s %-5.2f %-5.2f %s" % (c["id"], exp_s[:28], p, rec,
              ", ".join(f[:18] for f in files[:5])[:60]))
    if precisions:
        print("\nContext Precision@5 均值: %.2f | Recall 均值: %.2f" %
              (sum(precisions)/len(precisions), sum(recalls)/len(recalls)))

if __name__ == "__main__":
    main()
