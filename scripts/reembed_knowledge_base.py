#!/usr/bin/env python3
"""
Task 4：把知识库块的 embedding 原地换成硅基流动向量（Phase 8 / ADR-36）。

## 为什么是"原地重嵌入"而不是"重新加载文档 + 重新切分"

库里 439 个知识块已经带着**精确的 chunk 文本**与完整 metadata（filename / chunk_index /
doc_hash / chunk / category / status / tenantId）。原地重嵌入意味着：

  * 切分算法零改动 —— 不存在"重建后块数与生产不一致"的风险；
  * metadata 零改动 —— doc_hash 不变，不会触发下一次启动的增量重建；
  * 块文本零改动 —— 检索口径可比，评测结果可归因于**换模型**而非换切分。

代价是绕过了 ParentChildDocumentTransformer。但该切分器已是 overlap 扁平切块
（TARGET=400 / OVERLAP=80，见其 javadoc），重建并不会得到不同的结果——所以这个"绕过"
没有实际损失，反而消除了不确定性。

## 硬约束

1. **绝不触碰 source=memory / evolution 的行**（345 行用户对话记忆）。
   只 UPDATE 知识库块，且 WHERE 子句显式排除这两类。
2. 全程只 UPDATE embedding 列，不 DELETE、不 INSERT。
3. 维度必须 1024，否则拒绝写入。
4. 支持 --dry-run 与 --limit，先小批量验证再全量。

用法：
    SF_API_KEY=xxx python scripts/reembed_knowledge_base.py --dry-run
    SF_API_KEY=xxx python scripts/reembed_knowledge_base.py --limit 20
    SF_API_KEY=xxx python scripts/reembed_knowledge_base.py
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------- 配置

DB = dict(user="postgres", password="123456", database="postgres",
          host="127.0.0.1", port=5432)

SF_BASE = os.environ.get("SF_BASE_URL", "https://api.siliconflow.cn")
SF_MODEL = os.environ.get("SF_EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B")
EXPECTED_DIMS = 1024
BATCH = 16
MAX_CHARS = 8000

# 默认口径：显式排除用户记忆与演化块——这是本脚本最重要的一行。
#
# 2026-09-21 扩展 `--scope`：把**用户记忆**也纳入重嵌入。
# 为什么必须补这一步（实测，不是推测）：
#   `MemoryVectorStore.searchMemory()` 走 `vectorStore.similaritySearch(query)`，是**纯向量相似度**。
#   只重嵌入知识块后，查询用新模型嵌入、而 375 行记忆仍是旧厂商向量（两空间 cos≈0，近正交），
#   实测「用记忆自己的原文去搜」→ 最近 15 条**全是知识块、0 条记忆**，过滤 userId 后返回 0 条
#   —— 记忆语义检索**静默失效**（不报错、返回空）。对照组：记忆之间在旧空间自相似度仍是 1.00/0.94/0.92。
#
# 安全性：本操作**只写 embedding 列**，content / metadata 全不动，且 main() 里对 content 做
# 前后 SHA-256 断言——任何一字节变化都会判失败并非零退出。
SCOPE_WHERE = {
    "knowledge": "COALESCE(metadata->>'source','') NOT IN ('memory','evolution')",
    "memory": "metadata->>'source' = 'memory'",
    "all": "TRUE",
}
SCOPE_LABEL = {"knowledge": "知识块", "memory": "用户记忆行", "all": "全部行"}
KNOWLEDGE_WHERE = SCOPE_WHERE["knowledge"]  # 向后兼容旧引用


def http_post(path: str, payload: dict, api_key: str, timeout: int = 60) -> dict:
    req = urllib.request.Request(
        SF_BASE.rstrip("/") + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
        },
        method="POST",
    )
    # 显式绕过本机代理（本机 Clash 以 fake-ip 劫持 DNS，会打断 TLS）
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def embed(texts: list[str], api_key: str, retries: int = 4) -> list[list[float]]:
    """带指数退避的嵌入调用。上游偶发超时，重试比失败更划算。"""
    delay = 2.0
    last: Exception | None = None
    for attempt in range(retries):
        try:
            body = http_post("/v1/embeddings", {
                "model": SF_MODEL,
                "input": [t[:MAX_CHARS] for t in texts],
                "encoding_format": "float",
            }, api_key)
            data = sorted(body["data"], key=lambda r: r["index"])
            vectors = [r["embedding"] for r in data]
            if len(vectors) != len(texts):
                raise ValueError(f"count mismatch: {len(vectors)} != {len(texts)}")
            for v in vectors:
                if len(v) != EXPECTED_DIMS:
                    raise ValueError(f"dim mismatch: {len(v)} != {EXPECTED_DIMS}")
            return vectors
        except Exception as exc:  # noqa: BLE001
            last = exc
            if attempt < retries - 1:
                print(f"    retry {attempt+1}/{retries-1} after {delay:.0f}s: {exc}",
                      file=sys.stderr, flush=True)
                time.sleep(delay)
                delay *= 2
    raise RuntimeError(f"embed failed after {retries} attempts: {last}")


def vec_literal(values: list[float]) -> str:
    """pgvector 文本字面量。"""
    return "[" + ",".join(f"{v:.7g}" for v in values) + "]"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true",
                    help="只读一遍待重嵌入的行数与文本，不调 API、不写库")
    ap.add_argument("--limit", type=int, default=0,
                    help="只处理前 N 行（小批量验证用）")
    ap.add_argument("--batch", type=int, default=BATCH)
    ap.add_argument("--scope", choices=sorted(SCOPE_WHERE), default="knowledge",
                    help="重嵌入范围。knowledge=知识块（默认，历史行为）；"
                         "memory=用户记忆（只改 embedding 列，content 由哈希断言守住）；all=全部")
    args = ap.parse_args()

    api_key = os.environ.get("SF_API_KEY", "").strip()
    if not args.dry_run and not api_key:
        print("ERROR: 需要环境变量 SF_API_KEY", file=sys.stderr)
        return 2

    import pg8000.native
    con = pg8000.native.Connection(**DB)

    def scalar(sql: str):
        rows = con.run(sql)
        return rows[0][0] if rows else None

    # ---- 基线快照（前后对比用）
    baseline = {
        "total": scalar("SELECT COUNT(*) FROM vector_store"),
        "memory": scalar("SELECT COUNT(*) FROM vector_store "
                         "WHERE metadata->>'source' = 'memory'"),
        "evolution": scalar("SELECT COUNT(*) FROM vector_store "
                            "WHERE metadata->>'source' = 'evolution'"),
        "knowledge": scalar(f"SELECT COUNT(*) FROM vector_store WHERE {KNOWLEDGE_WHERE}"),
        "null_emb": scalar("SELECT COUNT(*) FROM vector_store WHERE embedding IS NULL"),
    }
    print("=== 基线 ===")
    for k, v in baseline.items():
        print(f"  {k:12s} = {v}")

    limit_sql = f" LIMIT {args.limit}" if args.limit > 0 else ""
    where = SCOPE_WHERE[args.scope]
    label = SCOPE_LABEL[args.scope]
    rows = con.run(
        f"SELECT id::text, content FROM vector_store WHERE {where} "
        f"ORDER BY id{limit_sql}"
    )
    ids = [r[0] for r in rows]
    texts = [r[1] or "" for r in rows]
    # 内容指纹：重嵌入**只许改 embedding 列**，这个哈希前后必须一致
    before_fingerprint = hashlib.sha256("\x1f".join(texts).encode("utf-8")).hexdigest()
    print(f"\n待重嵌入{label} = {len(ids)}（scope={args.scope}）")
    print(f"内容指纹(前) = {before_fingerprint[:16]}")

    if args.dry_run:
        print("dry-run：不调用 API、不写库。样本文本前 80 字：")
        for i in range(min(3, len(texts))):
            print(f"  [{ids[i][:8]}] {texts[i][:80]!r}")
        con.close()
        return 0

    if not ids:
        print("没有待处理行，退出。")
        con.close()
        return 0

    # ---- 分批嵌入并原地更新
    t0 = time.time()
    done = 0
    for start in range(0, len(ids), args.batch):
        end = min(len(ids), start + args.batch)
        batch_ids = ids[start:end]
        batch_texts = texts[start:end]
        vectors = embed(batch_texts, api_key)

        # 逐行 UPDATE：pgvector 的 UPDATE 需要显式类型转换
        for row_id, vec in zip(batch_ids, vectors):
            con.run(
                "UPDATE vector_store SET embedding = CAST(:v AS vector) WHERE id = CAST(:i AS uuid)",
                v=vec_literal(vec), i=row_id,
            )
        done = end
        elapsed = time.time() - t0
        rate = done / elapsed if elapsed > 0 else 0
        print(f"  [{done}/{len(ids)}] {elapsed:.1f}s ({rate:.1f} 块/s)", flush=True)

    # ---- 收尾校验
    after = {
        "total": scalar("SELECT COUNT(*) FROM vector_store"),
        "memory": scalar("SELECT COUNT(*) FROM vector_store "
                         "WHERE metadata->>'source' = 'memory'"),
        "evolution": scalar("SELECT COUNT(*) FROM vector_store "
                            "WHERE metadata->>'source' = 'evolution'"),
        "knowledge": scalar(f"SELECT COUNT(*) FROM vector_store WHERE {KNOWLEDGE_WHERE}"),
        "null_emb": scalar("SELECT COUNT(*) FROM vector_store WHERE embedding IS NULL"),
        "dims": scalar("SELECT vector_dims(embedding) FROM vector_store LIMIT 1"),
    }
    print("\n=== 完成后 ===")
    for k, v in after.items():
        print(f"  {k:12s} = {v}")

    # ---- 断言（不满足即非零退出，避免"看起来成功"）
    problems = []
    if after["memory"] < baseline["memory"]:
        problems.append(f"用户记忆行数下降: {baseline['memory']} -> {after['memory']}")
    # 原地重嵌入只改 embedding 列，**行数前后必须相等**。
    # 2026-09-21 修正：原写法是
    #     != (baseline["knowledge"] if not args.limit else len(ids))
    # 但 after["knowledge"] 统计的是**全量**知识块，而 --limit 20 时 len(ids)==20
    # → 小批量路径**永远不可能通过自己的校验**（把校验训练成"可以忽略"；
    #   实测踩到：--limit 20 报 "知识块数变化: 439 -> 439"）。
    if after["knowledge"] != baseline["knowledge"]:
        problems.append(f"知识块数变化: {baseline['knowledge']} -> {after['knowledge']}")
    if after["total"] != baseline["total"]:
        problems.append(f"总行数变化: {baseline['total']} -> {after['total']}")
    if after["null_emb"] != 0:
        problems.append(f"存在 NULL 向量: {after['null_emb']}")
    if after["dims"] != EXPECTED_DIMS:
        problems.append(f"维度异常: {after['dims']}")

    # 内容完整性：重嵌入只改 embedding 列 —— content 必须逐字节不变。
    # 这条在 --scope memory 时尤其重要（那是用户数据），但对所有 scope 都查。
    after_texts = [
        (con.run("SELECT content FROM vector_store WHERE id = CAST(:i AS uuid)", i=i)[0][0] or "")
        for i in ids
    ]
    after_fingerprint = hashlib.sha256("\x1f".join(after_texts).encode("utf-8")).hexdigest()
    print(f"内容指纹(后) = {after_fingerprint[:16]}")
    if after_fingerprint != before_fingerprint:
        problems.append("content 发生变化（重嵌入只应改 embedding 列）")

    con.close()
    if problems:
        print("\n!!! 校验失败 !!!")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"\nOK：{label} 已全部重嵌入为 {SF_MODEL}（{EXPECTED_DIMS} 维），"
          f"content 指纹未变（{after_fingerprint[:16]}）。")
    if args.scope != "memory":
        print(f"    用户记忆 {after['memory']} 行未受影响。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
