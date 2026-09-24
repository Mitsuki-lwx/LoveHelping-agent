# -*- coding: utf-8 -*-
"""生成 A/B 用的查询集（两组）+ 机械测 pool60。

- A 组（16 条，具体话题、口语化、目标文档已知）：测"目标文档是否被挤出去"。
- B 组（8 条，泛化日常问法，**不指派目标**）：这类最容易招记忆（记忆行本身就是
  本领域日常对话摘要），所以按**返回条数**客观衡量旧实现被挤掉多少候选 —— 不需要 gold。

产出：scripts/benefit-query-set.json（retrieval_eval 兼容 + group 字段）
       outputs/benefit-screen-all.json（pool60 明细）
"""
import io, json, os, re, sys

import psycopg2
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')

YML = r'D:\java\lwx-ai-agent\target\classes\application-local.yml'
m = re.search(r"siliconflow:\s*\n\s*api-key:\s*(\S+)", io.open(YML, encoding='utf-8').read())
KEY = m.group(1) if m else os.environ.get('SF_API_KEY')


def embed(texts):
    req = urllib.request.Request(
        "https://api.siliconflow.cn/v1/embeddings",
        data=json.dumps({"model": "Qwen/Qwen3-Embedding-0.6B", "input": texts,
                         "encoding_format": "float"}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with op.open(req, timeout=60) as r:
        return [x["embedding"] for x in sorted(json.loads(r.read())["data"], key=lambda x: x["index"])]


def lit(v):
    return "[" + ",".join("%.7g" % x for x in v) + "]"


A = json.load(io.open('outputs/benefit-screen60.json', encoding='utf-8'))
B_QUERIES = [
    "我们最近总吵架，我该怎么办",
    "他不太理我了，是不是不喜欢我了",
    "怎么跟他表达我的感受",
    "我们在一起两年了，感觉越来越没话说",
    "他家里人不喜欢我，我该怎么处理",
    "我总是患得患失，怎么办",
    "他好像对别人比对我热情",
    "我们异地了，怎么维持",
]
cases = ([{"id": c["id"], "question": c["question"], "expect_key": c["expect_key"], "group": "A_具体话题"}
          for c in A]
         + [{"id": "g%02d" % (i + 1), "question": q, "expect_key": None, "group": "B_泛化日常"}
            for i, q in enumerate(B_QUERIES)])

con = psycopg2.connect(user='postgres', password='123456', dbname='postgres',
                       host='127.0.0.1', port=5432)
cur = con.cursor()
vecs = embed([c["question"] for c in cases])

print("%-4s %-9s %-8s %-7s %s" % ("id", "group", "pool60", "旧可用", "query"))
print("-" * 100)
for c, v in zip(cases, vecs):
    cur.execute("SELECT COALESCE(metadata->>'source','(knowledge)') FROM vector_store "
                "ORDER BY embedding <=> %s::vector LIMIT 60", (lit(v),))
    rows = [r[0] for r in cur.fetchall()]
    c["pool60"] = sum(1 for s in rows if s not in ('memory', 'evolution'))
    if c["expect_key"]:
        cur.execute("SELECT metadata->>'filename' FROM vector_store "
                    "WHERE COALESCE(metadata->>'source','') NOT IN ('memory','evolution') "
                    "ORDER BY embedding <=> %s::vector LIMIT 60", (lit(v),))
        c["new_rank60"] = next((i + 1 for i, (f, ) in enumerate(cur.fetchall())
                                if c["expect_key"] in (f or '')), None)
    print("%-4s %-9s %-8d %-7d %s" % (c["id"], c["group"], c["pool60"], min(c["pool60"], 20), c["question"][:38]))

starved = [c for c in cases if c["pool60"] < 20]
print()
print("pool60 < 20（旧实现候选不足生产窗口）= %d / %d ；其中 < 5 = %d"
      % (len(starved), len(cases), sum(1 for c in starved if c["pool60"] < 5)))
print("  A 组（具体话题）中受损 %d/%d ；B 组（泛化日常）中受损 %d/%d"
      % (sum(1 for c in cases if c["group"].startswith('A') and c["pool60"] < 20),
         sum(1 for c in cases if c["group"].startswith('A')),
         sum(1 for c in cases if c["group"].startswith('B') and c["pool60"] < 20),
         sum(1 for c in cases if c["group"].startswith('B'))))

json.dump(cases, io.open('outputs/benefit-screen-all.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)
json.dump({"description": "ADR-43 受益场景验证集（2026-09-24）。A 组按文档标题人工指派目标；"
                          "B 组泛化日常问法不指派目标（只比对返回条数与集合差异）。"
                          "附机械预筛 pool60 / new_rank60。",
           "cases": [{"id": c["id"], "question": c["question"],
                      "expect_docs": [c["expect_key"]] if c["expect_key"] else [],
                      "group": c["group"], "pool60": c["pool60"],
                      "note": "pool60=%d new_rank60=%s" % (c["pool60"], c.get("new_rank60"))}
                     for c in cases]},
          io.open('scripts/benefit-query-set.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)
print('已写 scripts/benefit-query-set.json（%d 例）' % len(cases))
con.close()
