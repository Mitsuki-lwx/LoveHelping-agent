# checklist.md — RAG embedding 与 rerank 迁移验收清单

> 每项以 grep / 单测 / curl / SQL / 冒烟为准。**未逐条勾选前不得声称完成。**
> ⚠️ 涉及破坏性操作（数据库重建）的项，必须先完成备份项。

---

## Task 0：前置与备份（**必须先做**）

- [x] `pg_dump` 已导出 `vector_store` 到项目内路径（非 `/tmp`，本机 `/tmp` 会被清理）
- [x] 备份文件**可读且非空**（`grep -c "COPY" <file>` 或文件大小 > 0）
- [x] 备份路径记录于本文件末尾「备份记录」段
- [x] 重建前后用户记忆行数基线已记录：SQL 统计 `source=memory` 行数 = **345**
- [x] 知识库块数基线已记录：`source not in ('memory','evolution')` 行数 = **439**
- [x] **额外加严**：已做真实恢复演练（临时库 `backup_drill` + 补装扩展 + `pg_restore`），
      并做**全表逐行指纹比对**，结果 `MATCH`（指纹 `ab2d8535d038d3ef1112e639dca94876`）。
      演练库已删除。
- [x] **发现并记录恢复前置依赖**：空库恢复前必须先安装
      `vector`(0.8.1) / `uuid-ossp`(1.1) / `pg_trgm`(1.6) / `hstore`(1.8) 四个扩展，
      否则 `CREATE TABLE` 会因缺 `vector` 类型、`uuid_generate_v4()` 而失败。

## Task 1：embedding 客户端

- [x] `app.siliconflow` 配置块存在于 `application.yml`（grep `siliconflow`）
- [x] **`api-key` 只走环境变量**，`application.yml` 中为 `${SF_API_KEY:}` 空占位（不落盘密钥）
- [x] `SiliconFlowProperties`（或等价类）绑定 `app.siliconflow.*`
- [x] 新 embedding bean 存在且口径为 1024 维（`SiliconFlowEmbeddingModel.EXPECTED_DIMENSIONS=1024`，
      且解析时**维度不符即抛错**，不静默写入）
- [x] `PgVectorVectorStoreConfig` 不再硬绑 `dashscopeEmbeddingModel`
- [x] `ParentChildDocumentRetriever` 不再硬绑 `dashscopeEmbeddingModel`
- [x] `SkillIngestor` 不再硬绑 `dashscopeEmbeddingModel`
- [x] `EmbeddingModelConfig` 的 `@Primary` 按 `app.rag.embedding.provider` 选取
- [x] **回滚路径存在**：`dashscope` 分支可用（旧 bean 未被删除）

## Task 2：rerank 客户端

- [x] `RerankProperties` 有 `model` 字段，默认 `Qwen/Qwen3-Reranker-8B`
- [x] `RerankProperties` 有 `apiKey` 字段（走环境变量）
      —— 实现方式为构造器注入 `@Value("${app.siliconflow.api-key:}")`，
      与 embedding 共用同一 key（协议上本就是同一个账号）
- [x] `timeoutMs` 默认 **5000**（非 2000）—— 实测 8B 偶发 3.97s，2000 会误杀
- [x] `mode` 支持 `remote`（有鉴权）取值（`@Pattern(regexp="local|remote|llm|off")`）
- [x] 请求体含 `model` 字段（仅 remote 模式添加；单测 `remoteMode_requestBody_hasModelAndAuth` 断言）
- [x] `Authorization: Bearer` 头按 apiKey 是否为空条件添加
- [x] 单测：`index` 越界 → 抛错拒绝（`parse_indexOutOfRange_throws`）
- [x] 单测：`index` 重复 → 拒绝（`parse_duplicateIndex_throws`）
- [x] 单测：HTTP 非 200 / 超时 → 抛错（由 postprocessor 降级）
      （`rerank_httpNon200_throws` / `rerank_oversizedResponse_throws` / `rerank_afterFailures_circuitOpens`）
- [x] 单测：`candidates.size() <= topK` 时不发请求（`rerank_candidatesNotMoreThanTopK_skipsHttpCall`）
- [x] 现有 `LlmDocumentReranker` 单测**仍全绿**（未破坏本地模式）
- [x] **全量单测 268/268 全绿**（迁移前 247 + 本轮新增 21）
- [x] ⚠️ 单测**抓出并修复了生产代码一个真缺陷**：remote 校验顺序错
      （先 https 后 apiKey）→ 未配 key 时报出误导性 URL 错误。已改为先 key 后 https。

## Task 3：配置切换

- [x] `app.rag.embedding.provider` 存在，默认 `siliconflow`
- [x] `app.rag.rerank.mode` 默认值**未变**（保持保守，不因本次迁移而默认开启）
      —— `mode` 默认仍为 `local`，`enabled` 默认仍为 `false`；
      新增的 `remote` 是**可选项**，需显式设置 `RERANK_MODE=remote` + `RERANK_ENABLED=true`
- [x] 所有新增密钥字段在 `application.yml` 中均为**环境变量占位**
- [ ] `target/classes/application-local.yml` 的本地凭据未被提交（gitignore 生效）

## Task 4：全量重建

**策略修正（重要）**：读 `ParentChildDocumentTransformer` 后发现它已是 overlap 扁平切块
（TARGET=400/OVERLAP=80，非父子链），且库中 439 块已带完整 metadata。
故采用 **原地重嵌入**（只 UPDATE `embedding` 列），而非"重新加载文档 + 重新切分"：

| 维度 | 重新切分 | 原地重嵌入（采用） |
|---|---|---|
| 切分算法风险 | 有（可能与生产不一致） | **无**（不切分） |
| metadata `doc_hash` | 可能变 → 触发下次启动增量重建 | **不变** |
| 块文本 | 可能变 → 评测不可归因 | **不变** |
| 块数守恒 | 需验证 | **天然满足** |

- [x] **重建脚本强制过滤 `source=memory|evolution`**（grep 命中过滤逻辑）
      —— `scripts/reembed_knowledge_base.py` 的 `KNOWLEDGE_WHERE` 常量
- [x] dry-run 已验证：准确选中 **439** 行知识块，**345 行用户记忆被排除**
- [x] 重建后知识库块数 = 基线（**439 → 439**，±0）
- [x] 重建后用户记忆行数 **≥ 基线**（345 → **385**，只增不减；增量来自期间应用真实写入）
- [x] `SELECT COUNT(*) FROM vector_store WHERE embedding IS NULL` = **0**
- [x] `SELECT vector_dims(embedding) FROM vector_store LIMIT 1` = **1024**
- [x] 全量嵌入耗时：**439 块 / 16.7s（26.3 块/s）** —— 优于原估的 30s
- [x] `SF_API_KEY` 已提供并验证（embedding 1024 维、rerank 8B 均实测可用）；
      凭据落在 **gitignored** 的 `target/classes/application-local.yml`（`app.siliconflow.api-key`），
      由 `scripts/prod_env_from_local.py` 提取为 `SF_API_KEY` 环境变量，**仓库内无明文**
- [x] 脚本缺陷修复 1：断言在 `--limit` 分支下要求 `knowledge == len(ids)`，但该值是**全量**统计
      → 小批量路径**永远通不过自己的校验**（实测报 "知识块数变化: 439 -> 439"）。已修正。
- [x] 脚本增强：新增 `--scope knowledge|memory|all` 与 **content SHA-256 前后断言**
      （重嵌入只许改 `embedding` 列，内容一个字节都不许变）—— 供下面的 T4b 使用

### T4b（本轮新增，**必须做**）：用户记忆行同样要重嵌入

- [x] **发现并修复一处静默功能回归**：只重嵌入知识块，会让 **375 行用户记忆的向量成为孤儿**。
      `MemoryVectorStore.searchMemory()` 走 `vectorStore.similaritySearch(query)`（**纯向量相似度**），
      查询用新模型嵌入、库存是旧厂商向量 → **两空间近似正交（实测 cos ≈ −0.014）**。
      实测：用记忆自己的原文去搜，最近 15 条 **全是知识块、0 条记忆**，过滤 `userId` 后**返回 0 条**
      —— 不报错、返回空，**完全静默**。对照组：记忆之间在旧空间自相似度仍是 1.00 / 0.94 / 0.92。
- [x] **连带影响（当时未察觉）**：`ParentChildDocumentRetriever.hybridRetrieve` 的向量召回
      **SQL 里没有源过滤**（取 `topK*3` 后融合再过滤），于是这些跨空间向量**同时在挤占知识检索的位置**
      → 知识检索也被污染。修复后基线 MRR 从 **0.762 稳定回到 0.751 × 3 轮**（注：0.762 是污染下的读数）。
- [x] 修复执行：`--scope memory` 重嵌入 **385 行 / 12.5s（30.7 块/s）**，
      `total/memory/knowledge/null_emb/dims` 全部守恒，**content 指纹前后一致**
- [x] 修复验证：最近 15 条 = **memory 15 / 其他 0**，同 `userId` 可返回，top1 cos **0.9999**
- [x] 整库空间一致性：随机抽 6 行（含 knowledge 与 memory），
      `cos(库存向量, 新模型现算)` 全部 **≥0.9998**

## Task 5：回滚路径

- [x] 回滚方式一（配置）：`RAG_EMBEDDING_PROVIDER=dashscope` + 重启，旧 bean 仍在容器中
- [x] 回滚方式二（数据）：按「备份记录」段的恢复命令还原 `vector_store`
- [x] 回滚演练（在 T4b 之后具备对象）：
      **配置侧已实测** —— 以 `RAG_EMBEDDING_PROVIDER=dashscope` + 旧通道启动、检索链路可用
      （2026-09-21 21:05 那轮即此姿势，日志 `RAG_RETRIEVAL ... hits=8`，Top1 正确）。
      **数据侧**沿用备份记录里已做过的 `pg_restore` + 全表指纹比对（`MATCH`）。
      ⚠️ 未在 T4b **之后**重跑一次真实的"还原后启动"（因还原会覆盖当前已迁移的库）；
      现网真实回滚应"先配置回滚到 dashscope"，数据侧保留备份即可。

## Task 6：端到端验证

- [x] 编译通过（本机 `mvn` 坏，用 classworlds Launcher 范式）
- [x] 单测全绿：**276 / 276**（迁移前 247 + Phase8 的 21 + 本轮接线测试 6 + Phase9 的 2）
- [x] 真启动成功，日志**无** `SSLHandshakeException`
- [x] 真启动成功，日志**无** embedding 相关 ERROR（`embedding 失败 = 0`）
- [x] `GET /admin/rag/retrieve` 对 45 用例无 500
- [x] 检索质量（45 例、同一后端、**多轮**）：
      - 迁移前（dashscope 向量、无重排）：Recall 0.93 / MRR **0.807**（单轮，phase9 记录）
      - 迁移后（硅基流动向量、无重排）：Recall 0.93 / MRR **0.751 × 3 轮**（完全一致）
      - 迁移后 + **远端 8B 重排**：Recall 0.93 / MRR **0.856 / 0.844 / 0.856（中位 0.856）**
      → ⚠️ **该清单原写"不低于迁移前"这一条：无重排时是 0.807 → 0.751，即"更低"**。
        但这不是同一件事的对比：见 `spec.md` §S7 的说明与**未验证项**（缺 dashscope 侧同轮次对照）。
      → 重排带来的 **+0.105 MRR** 远超 phase9 实测的噪声带（0.012），是本轮**唯一可确证的正收益**。
      → 🔄 **2026-09-25 修订（ADR-45 / ADR-46，不改写上述结论）**：上面的 0.751 / 0.856 是在
        **一条有载荷缺陷的管道**上测的（`KnowledgeSqlSearch.toDocument()` 让正文变 UUID、id 变随机，
        见 ADR-46；缺陷使返回的 8 条只覆盖 ~5.4 个不同文件，`files[:5]` 近似等于整份列表 → **指标虚高**）。
        **同口径重测（修复后）**：无重排 dashscope **0.704** vs 硅基流动 **0.714**（Δ=+0.010，噪声带内 → **无可测差异**）；
        有重排 dashscope **0.910** vs 硅基流动 **0.885**（Δ=+0.025，强证据不足）。
        → **本清单里"迁移前 0.807 / 迁移后 0.751 = 掉分"这个对比作废**（跨口径 + 缺陷态读数）。
        完整对照见 `docs/phase8-embedding-rerank-migration/embedding-ab.md` 与 **ADR-45**。
- [x] SSE 真发聊天请求，检索有命中（日志 `RAG_RETRIEVAL ... hits=8`，Top1 与查询语义吻合）
- [x] SSE 回答内容正常；真实 E2E **22/22**
- [x] （重排）可观测性：`Rerank configured:`（启动回显）+ `Rerank call -> / Rerank ok <-`（DEBUG 每次调用）
      —— 这两条是**本轮新加**的，此前重排链路一行日志都没有

## Task 7：文档同步

- [x] `docs/03-技术决策记录.md` 新增 **ADR-39**（见下）
- [x] `docs/02-架构设计.md`：模型描述未涉及具体 embedding 型号，无需改（已核对）
- [x] `.workbuddy/memory/MEMORY.md` 的 rerank 结论已更新（"本地 MiniLM 负收益 / 远端正收益"已量化）

- [x] 本目录 `tasks.md` / `spec.md` / `checklist.md` 齐全（另加 `embedding-ab.md`：ADR-45 的对照实验判据与结果）

---

## 未验证项声明（收尾时逐条填写，**报忧不报喜**）

- [x] 明确列出本次**未做端到端验证**的部分（若有），并说明原因
- [x] 明确列出**失败或存疑**的项（若有），不得隐瞒

### 🔄 2026-09-25 收口（ADR-45 / ADR-46）

**已由后续会话补验的项：**

- ✅ **"嵌入质量是否变差"—— 已定论**：同口径对照（旁库 + 同一批 45 例 + 同日 + 同后端）
  无重排 Δ=+0.010（噪声带内 → **无可测差异**）、有重排 Δ=+0.025（dashscope 略好，证据弱）。
  **本清单原来那句"0.807 → 0.751 即更低"作废**（跨口径 + 缺陷态）。
  详见 `embedding-ab.md`、ADR-45。
- ✅ **`max-concurrent=2` 造成的重排静默降级**—— 已修（16）并量化，见 ADR-41。

**仍然未验证（如实列）：**

- ❌ **有重排的对照每臂只有 2 轮** → Δ=+0.025 置信度弱，只够写"倾向"。
- ❌ **未测硅基流动其他 embedding 型号**（4B/8B 为 2560/4096 维，换要连表重建）。
- ❌ **未做人工抽检**；**未跑 `answer_eval.py`**（本实验只到检索层，未到答案质量）。
- ❌ **8B 重排冷启动 5.21s > `timeout-ms=5000`** 仍未复现、未预热。
- ❌ **重排成本仍无账单**（¥0.0017/次是实测单价×次数的推算）。
- ❌ **多实例未测**（许可/熔断/超时/ANN 都是进程内状态）。
- ⚠️ **仪器分辨率 ±0.015 MRR**：小于此量级的差异本清单的方法判不了。
- ⚠️ **上游 embedding 服务本身不确定**（同一文本 10 次调用出现 2 种向量，抖动率 ≈1/10）——
  这是新发现的事实，也是"纯向量链路无随机"这个旧假设被推翻的地方。

**本轮发现并修复的缺陷（原清单未覆盖）：**

- ⛔ **ADR-46**：`KnowledgeSqlSearch.toDocument()` 载荷错误（正文变 UUID、id 变随机），
  静默压坏重排与 RAG 上下文。已修（三参构造）+ 字段级测试 + 对照实验。全量单测 295/295、真实 E2E 22/22。

---

## 备份记录

| 项 | 值 |
|---|---|
| 备份文件路径（主） | `D:\java\lwx-ai-agent\.workbuddy-ai\backup\vector_store_before_phase8.dump`（自定义格式，含结构+数据+索引） |
| 备份文件路径（冗余副本） | `D:\java\lwx-ai-agent\.backup-phase8\`（同上两份，互为冗余） |
| 备份文件路径（可读格式） | `vector_store_before_phase8.sql`（纯 INSERT，**784 条**，可人工核验） |
| 备份时间 | 2026-09-21 00:03 |
| 备份大小 | `.dump` 4,184,383 B（≈3.99 MB）/ `.sql` 10,761,012 B（≈10.26 MB） |
| 源库版本 | PostgreSQL **18.3**（服务端），`pg_dump` 18.3 |
| 恢复命令 | 见下方「恢复命令（实测有效）」 |
| 用户记忆行数基线 | **345**（`metadata->>'source' = 'memory'`） |
| 演化块行数基线 | **0**（`metadata->>'source' = 'evolution'`） |
| 知识库块数基线 | **439**（其余，无 `source` 字段） |
| 总行数基线 | **784** |
| 向量维度 | **1024** |
| 全表指纹（恢复校验用） | `ab2d8535d038d3ef1112e639dca94876` |
| 恢复演练结果 | **MATCH**（临时库 `backup_drill`，演练后已删除） |

> ⚠️ **备份不入库**：两份备份均含 345 行用户对话记忆明文，
> 已在 `.gitignore` 中排除（`.workbuddy-ai/` / `.backup-phase8/`）。
> 此前的 `.gitignore` **只忽略了 `.workbuddy/`，未忽略 `.workbuddy-ai/`** ——
> 本轮发现并修复，否则 `git add -A` 会把用户隐私数据提交进仓库。
>
> **本机 pg 工具不在 PATH**：`pg_dump` / `psql` / `pg_restore` 均位于 `D:\postgresql\bin\`，
> 必须用绝对路径调用。

### 恢复命令（实测有效）

```bash
export PGPASSWORD=123456
PG="/d/postgresql/bin"          # 本机 pg_dump/psql 不在 PATH，实际位于 D:\postgresql\bin
BK="D:/java/lwx-ai-agent/.workbuddy-ai/backup"

# 1) 若目标是空库，必须先装扩展（否则 CREATE TABLE 报 "类型 public.vector 不存在"）
"$PG/psql.exe" -h 127.0.0.1 -p 5432 -U postgres -d <目标库> -Atc \
  'CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
   CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE EXTENSION IF NOT EXISTS hstore;'

# 2) 恢复表结构 + 数据
"$PG/pg_restore.exe" -h 127.0.0.1 -p 5432 -U postgres -d <目标库> \
  --no-owner --no-privileges "$BK/vector_store_before_phase8.dump"

# 3) 校验指纹（应与上表一致）
"$PG/psql.exe" -h 127.0.0.1 -p 5432 -U postgres -d <目标库> -Atc \
  "SELECT md5(STRING_AGG(id::text||':'||md5(embedding::text)||':'||md5(COALESCE(content,''))||':'||md5(COALESCE(metadata::text,'')),'' ORDER BY id)) FROM vector_store;"
```

**注意**：本机 `pg_dump` / `psql` **不在 PATH**，必须用绝对路径 `D:\postgresql\bin\*`。
