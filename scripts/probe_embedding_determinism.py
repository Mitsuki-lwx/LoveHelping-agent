#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""探测 embedding 通道对**同一文本**是否逐次返回相同向量（确定性）。

**为什么需要它**：45 例评测里，有 2 例跨轮次翻转（top1↔top3、top5↔出局）。
翻转的可能来源有两条，必须先分开：
  ① 上游 embedding 服务对同一输入返回的向量有微小抖动 →
     查询向量每次略有不同 → 近并列的文档换位；
  ② 检索侧（pgvector HNSW 近似索引 / 关键词通道并列项）自身的顺序不确定。
本探针只回答 ① —— 它不需要启动应用。

用法：SF_API_KEY=xxx python scripts/probe_embedding_determinism.py [文本] [次数]
"""
import hashlib, io, json, math, os, sys, urllib.request

URL = "https://api.siliconflow.cn/v1/embeddings"
MODEL = os.environ.get("SF_EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B")
GT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "retrieval-ground-truth.json")


def embed(text, key):
    body = json.dumps({"model": MODEL, "input": text, "encoding_format": "float"}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Authorization": "Bearer " + key, "Content-Type": "application/json"})
    r = json.loads(urllib.request.urlopen(req, timeout=60).read())
    return r["data"][0]["embedding"]


def cos(a, b):
    s = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)); nb = math.sqrt(sum(y * y for y in b))
    return s / (na * nb)


def main():
    key = os.environ.get("SF_API_KEY")
    if not key:
        raise SystemExit("需要 SF_API_KEY")
    text = sys.argv[1] if len(sys.argv) > 1 else None
    if not text:
        cid = "gt_34"
        for c in json.load(io.open(GT, encoding="utf-8"))["cases"]:
            if c["id"] == cid:
                text = c["question"]
        print("（未给文本，用 ground truth %s 的问题）" % cid)
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 5

    print("model=%s  dim(期望 1024)" % MODEL)
    vecs = []
    for i in range(n):
        v = embed(text, key)
        vecs.append(v)
        raw = json.dumps(v).encode()
        print("  第%d次: dim=%d  md5(json)=%s  前3位=%s"
              % (i + 1, len(v), hashlib.md5(raw).hexdigest()[:16],
                 [round(x, 8) for x in v[:3]]))
    base = vecs[0]
    print("\n与第 1 次的比较：")
    worst = 1.0
    for i, v in enumerate(vecs[1:], 2):
        c = cos(base, v)
        worst = min(worst, c)
        same = all(a == b for a, b in zip(base, v))
        print("  第%d次: cos=%.12f  逐元素完全相同=%s" % (i, c, same))
    print("\n结论：%s" % ("确定性（逐次逐元素相同）" if worst == 1.0 and
          all(all(a == b for a, b in zip(base, v)) for v in vecs[1:])
          else "⚠️ **非确定性**：同一文本多次调用返回的向量不同（最小 cos=%.12f）" % worst))


if __name__ == "__main__":
    main()
