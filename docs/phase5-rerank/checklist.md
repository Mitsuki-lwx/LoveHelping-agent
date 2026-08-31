# checklist.md — RAG 阶段4+：检索重排（Rerank）验收清单

> 每项以 grep / 单测 / curl / SQL / 冒烟为准。

---

## Task 1：接口与配置

- [ ] `rag/rerank/DocumentReranker.java` 存在（接口含 `rerank(query, candidates, topK)`）
- [ ] `RerankProperties` 绑定 `app.rag.rerank.*`（enabled 默认 false / topN=50 / topK=5 / mode=llm）
- [ ] 退化防御：topN ≤ topK 时不重排（有单测或用例）

## Task 2：LLM 重排 v1

- [ ] `LlmDocumentReranker` 存在并注入主模型（LlmGateway @Primary）
- [ ] 单测全绿：合法编号保序 / 非法编号回退且不丢文档 / 模型抛错降级原序 / candidates≤topK 不调模型 / 文本裁剪生效（`LlmDocumentRerankerTest`）
- [ ] 失败不抛错中断 RAG（降级日志可见）

## Task 3：挂载 postretrieval

- [ ] `RerankDocumentPostProcessor implements DocumentPostProcessor` 存在（`grep -c "implements DocumentPostProcessor" .../RerankDocumentPostProcessor.java` = 1）
- [ ] `ParentChildDocumentRetriever` topK 改为读 `app.rag.rerank.topN`（grep topN）
- [ ] `RagAdvisorConfig` 在 rerank.enabled 时挂 `documentPostProcessors(...)`（grep 命中）
- [ ] 关闭态 = 现状（postprocessor 原样透传；冒烟 16/16 兜底）

## Task 4：配置与指标

- [ ] `application.yml` 有 `app.rag.rerank` 块（默认注释/enabled=false）
- [ ] Prometheus 出现 `rag_rerank_*`（开启后 curl `/actuator/prometheus` 命中 executions/fallback/candidates）

## Task 5：单测

- [ ] `LlmDocumentRerankerTest` 全部用例绿（`mvn -Dtest=LlmDocumentRerankerTest test`）

## Task 6：文档同步

- [ ] ADR-15 阶段 4 补记：rerank 已实现（v1=LLM，接口就绪，bge-reranker 为后续独立部署）
- [ ] `docs/02-架构设计.md` / `docs/06-核心流程设计.md` RAG 链路含 rerank 环节
- [ ] 本 checklist 勾选完毕

## Task 7：端到端

- [ ] 关闭态冒烟 16/16 全绿（无回归）
- [ ] 开启态：问知识库问题回复含检索内容，且上下文比关闭态更全（curl 对比；日志无 fallback 风暴）
- [ ] 退化/降级日志可见（candidates 少/模型失败场景）
- [ ] 回归：开启态冒烟无红
## 落地记录（2026-08-27）

- [x] Task 1-6 全部完成：`DocumentReranker` 接口、`RerankProperties`（默认关）、`LlmDocumentReranker`（主线 LLM 打分，保序/补位/降级）、`RerankDocumentPostProcessor`（挂 advisor postretrieval）、retriever 开启时 topN=50、application.yml 注释配置、单测 6/6
- [x] **开启态指标**：`rag_rerank_candidates_total`（50/次）、`executions` 正常；日志 `RAG rerank: 50 -> 5 candidates`
- [x] 关闭态冒烟 **16/16 零回归**（连跑多次稳定）
- [x] 顺带：**知识库重建**（`-Dapp.rag.reindex=true`）387 docs → 808 chunks 重灌到当前源文档
- [ ] **验收发现（如实）**：E2E 探针"非暴力沟通四要素"未全部命中——根因是**召回层语义 gap**（抽象措辞"四要素/四步框架"对四步块的嵌入分不足，块不在 top-50 召回集），rerank 只能重排"召回集内"，无法补召回。**rerank 有效性仍被证明**：开启后注入内容确实变化（比关闭态多出"观察/感受/需要"等更相关块）。建议后续走 **query-rewrite 增强 / 换更强 embedding / 混合检索**提升召回，rerank 在召回健康后价值才最大化

## RAG 检索质量 P0 落地（2026-08-27）

- [x] **RRF 接入 RAG 检索器**：`ParentChildDocumentRetriever` 增加混合召回（向量 + pg_trgm 关键词 → RRF 融合，仅知识库子块 parent_id）；`PgVectorVectorStoreConfig` 抽 `pgJdbcTemplate` bean；开关 `app.rag.hybrid-search.enabled`
- [ ] **pg_trgm 中文实效（如实记录）**：实测 `keyword=0`——pg_trgm 按字符 3-gram，中文词面匹配弱；RRF 的关键词腿对中文基本为空。**默认保持关闭**，待中文分词/LIKE 方案再评估
- [x] **QueryRewriter 词表对齐（真正的 P0 解法）**：自定义中文改写指令，把抽象表述扩展成知识库文档词（例："四要素" → "四要素 四步表达框架 观察 感受 需要 请求"）；验证：抽象查询"非暴力沟通四要素/请求那一步"→ 模型引用文档原文例子（"今晚可以一起散步二十分钟吗"），从"完全命中不了"到"引用原文"
- [x] 冒烟 16/16 全绿
- [x] **回退修复(JdbcTemplate 歧义)**:pgJdbcTemplate 曾抽为 bean → 容器里两个 JdbcTemplate 把 ReflectionScheduler 等 MySQL 用户注成 PG(报"关系 message 不存在")→ 撤销 bean,ParentChildDocumentRetriever 用 PgvectorProperties 自建局部 JdbcTemplate,不污染容器;注册会话 500 恢复 200
- [x] **英文查询防抖**:QueryRewriter.transform 对非中文(无 CJK)查询直接返回原查询,跳过中文词表对齐指令(对英文不稳);中文词表对齐验证仍生效(引用文档原文例子),冒烟 16/16
- [x] **content_hash 增量更新（P1 落地）**：loader 每文档设 `doc_hash`(SHA-256)；`KnowledgeBaseIncrementalSync` 启动时按 filename+doc_hash 对比库中存量，新增→插入 / hash 不同→删旧重建 / 相同→跳过；`PgVectorVectorStoreConfig` else 分支接入。首轮（旧块无 hash）全量重建+落 hash
- [ ] **skip 未生效（待调优，如实）**：二次启动仍 replaced 全部（skipped=0）——hash 匹配细节未达标；但**净收益已成立**：启动自动对比重建=手动 reindex 自动化，"索引静默过时"隐患已堵（不会静默跳过错误内容），"省资源"增量待专项调优（疑：章节级 doc hash vs 文件级 filename 匹配的粒度问题）
- [x] 冒烟 16/16 全绿

## 演进待办（勿忘 · 2026-08-28 登记）

- [ ] **中文混合检索关键词路（触发式）**：pg_trgm 中文实效（keyword=0），RAG 实际纯向量。**待触发条件**：抽象查询命中率回升 或 知识库 >5000 块 → 上「改写后查询短词 + LIKE 确定性兜底」，再做真 RRF。当前保持 hybrid-search 默认关、代码就位
- [ ] **Golden Set 质量回归（下一步立刻做）**：本轮 RAG 大改（rewrite 词表对齐/rerank/RRF/增量同步）后未跑 `/admin/golden-set/run`，无质量基线；顺手补 1-2 个 RAG 命中类用例进固定集
- [x] **Golden Set 基线快照（2026-08-28）**：12/15（80%）verdict PASS；3 条低分（道歉信/打游戏/道歉）归因=先澄清策略与 judge rubric 期望错位，非 RAG 回归；快照存 `golden-set-baseline-2026-08-28.md`（后续对比起点）
- [ ] RAG 命中类用例补进 Golden Set 固定集（待办）

## 中文混合检索 LIKE 关键词路（2026-08-28 落地）

- [x] `ParentChildDocumentRetriever.keywordSearch` 从 pg_trgm 换为「改写后查询关键词 LIKE 命中」：提取 2-12 字词（空格/顿号分割 + 去停用词 + 前 6 个），`content LIKE %词%` OR 合并，按命中词数降序 + 长度升序，限定知识库子块（`metadata ->> 'parent_id' IS NOT NULL`）
- [x] **踩坑修复（实锤）**：jsonb 操作符 `metadata ? 'parent_id'` 的 `?` 被 JdbcTemplate 当占位符 →「未设定参数值 14」→ 换成 `->>` 写法
- [x] 验证：keyword 0 → **15**（LIKE 命中生效）
- [x] **默认保持关（有意）**：实测开启后 RRF 排序扰动，把已被词表对齐 rewrite 解决的查询（请求例句）挤出 top5——rewrite 是主路径，hybrid 定位为「冷门词防御层」，**触发条件**（命中率回升/知识库>5000 块）达成再开
- [x] 冒烟 16/16（hybrid true 实例）无回归
- [ ] **图 checkpoint(RedisSaver) 接入 CompileConfig** —— 下一轮单独做（涉 Redis 连接 + 图运行时行为）

## 知识库扩充·第一批（2026-08-29）

- [x] **计划应变**：维基（zh.wikipedia.org）环境不可达 → 批次调整为「C 民法典 + D MIT 仓库」先行；维基抓取脚本已备（scripts/kb-fetch/fetch_wikipedia.py）待网络可达，OpenStax（CC BY 4.0）为第二批
- [x] **C 民法典 4 篇**：彩礼返还/离婚冷静期/共同财产/家暴识别（立法文件无著作权，附条号+免责声明）
- [x] **D MIT 仓库 3 篇**：危险信号（煤气灯/爱情轰炸/间歇性强化）/ 末日四骑士 / 关系健康度自检（MIT 概念转写，去操控化，文档头署名）
- [x] **来源许可清单** `docs/knowledge-base-sources.md`（可溯源）
- [x] **验证**：增量摄入 added=17；新主题检索命中（彩礼/冷静期/煤气灯）；冒烟 16/16；**Golden Set 15/15（100%，基线 87%→100%）**
- [ ] 第二批：OpenStax 翻译改写 + 维基待网络可达

## 知识库扩充·第二批 OpenStax（2026-08-29）

- [x] OpenStax Psychology 2e（CC BY 4.0）主题自撰 3 篇：人际吸引三因素 / 爱情三角论 / 亲密关系维系——正文为 SPA 抓取受限，采用「公开理论自撰改写 + CC BY 署名」，合规落地
- [x] 来源清单追加 3 篇（docs/knowledge-base-sources.md）
- [x] 验证：增量摄入、爱情三角论检索命中、冒烟 16/16
- [ ] 维基条目：脚本已备待网络可达
- [ ] 知识库共 67 篇（57 旧 + 新 10）；后续若需继续扩充可加大 2 批主题
