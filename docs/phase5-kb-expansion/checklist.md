# checklist.md — Phase 5 知识库扩充验收清单

> 每项以 文件存在 / grep / 脚本输出 为准，一行一条可独立判定。
> 验收时间：2026-09-11

---

## Task 1：方案文档

- [x] `docs/phase5-kb-expansion/tasks.md` 存在且含缺口分析表（G1–G11）
- [x] `docs/phase5-kb-expansion/spec.md` 存在且含 45 篇逐篇规格表
- [x] `docs/phase5-kb-expansion/checklist.md` 存在（本文件）

## Task 2：批次一 16 篇

- [x] 同性伴侣关系：出柜家庭压力与关系韧性_same-sex-relationship-resilience.md
- [x] 性取向探索期的自我认同与沟通_sexual-orientation-exploration.md
- [x] 非传统关系模式的边界与同意_open-or-polyamorous-boundaries.md
- [x] 跨文化恋爱：价值观差异的磨合_cross-cultural-relationship.md
- [x] 跨国异国恋：时差签证与长期规划_cross-border-long-distance.md
- [x] 年龄差关系：权力平衡与外界眼光_age-gap-relationship.md
- [x] 因工作长期分居：如何维持联结_work-separation-couples.md
- [x] 要不要孩子：生育决策的深度对话_decision-to-have-children.md
- [x] 不孕不育的情感冲击与伴侣支持_infertility-emotional-support.md
- [x] 流产后如何一起走过_miscarriage-grief-together.md
- [x] 孕期关系变化：准父母的准备_pregnancy-relationship-changes.md
- [x] 育儿期婚姻满意度下滑的应对_parenting-marriage-satisfaction.md
- [x] 伴侣成瘾：支持与底线_partner-addiction-boundaries.md
- [x] 伴侣抑郁：识别陪伴与自我保护_partner-depression-support.md
- [x] 伴侣重性心理问题：关系的现实应对_partner-serious-mental-illness.md
- [x] 自伤与自杀风险：如何回应并求助_suicide-risk-response.md

## Task 3：批次二 13 篇

- [x] 分手的哀悼：如何走出失恋_grief-after-breakup.md
- [x] 如何体面地提出分手_how-to-end-a-relationship-kindly.md
- [x] 复合：重建而不是回到过去_reconciliation-after-breakup.md
- [x] 离婚后的自我重建与共同抚养_post-divorce-rebuilding.md
- [x] 与新关系里的前任问题：边界怎么设_ex-partner-boundaries.md
- [x] 原生家庭创伤如何影响亲密关系_family-of-origin-trauma.md
- [x] 情感忽视：被忽视长大的孩子如何在关系里被爱_childhood-emotional-neglect.md
- [x] 伴侣与原生家庭过度纠缠：设立健康距离_enmeshment-with-family-of-origin.md
- [x] 讨好型人格：在关系里练习说不_people-pleasing-in-relationships.md
- [x] 社交媒体与恋爱：边界嫉妒与隐私_social-media-and-relationships.md
- [x] 网恋与交友软件：从线上到线下的安全与判断_online-dating-safety.md
- [x] 精神出轨：边界判定与关系修复_emotional-affair.md
- [x] 伴侣的手机与隐私：信任还是控制_phone-privacy-trust.md

## Task 4：批次三 16 篇

- [x] 债务与关系：一方负债怎么办_debt-in-a-relationship.md
- [x] 婚前协议：谈清楚不是不信任_prenuptial-agreement-talk.md
- [x] 家务与决策权：关系中的权力平衡_power-balance-household.md
- [x] 财务模式选择：AA制共同账户还是混合_financial-model-for-couples.md
- [x] 黄昏恋与中老年再婚_late-life-remarriage.md
- [x] 再婚家庭中的继子女关系_stepchildren-relationship.md
- [x] 丧偶后重新开始：允许自己再爱_grief-and-new-love.md
- [x] 政见与价值观分歧：伴侣如何共处_political-value-differences.md
- [x] 关系中的性同意与沟通_sexual-consent-communication.md
- [x] 宗教信仰不同的伴侣相处_interfaith-relationship.md
- [x] 纪念日与仪式感：处理期待落差_anniversary-expectations.md
- [x] 如何选择心理咨询师与伴侣治疗_choosing-a-couples-therapist.md
- [x] 单身力：享受独处而不焦虑_single-life-fulfillment.md
- [x] 关系中的个人空间：亲密与独立的平衡_personal-space-in-relationship.md
- [x] 一起变好还是各自精彩：共同成长_growing-together-in-relationship.md
- [x] 关系倦怠的日常修复：把好意说出口_small-kindness-habits.md

## Task 5：合规与入库

- [x] 每篇新文档正文 ≥ 800 字（实测区间 1213–1876 字，45 篇合计 5.6 万字）
- [x] 法律/医疗/危机类文档文末含免责或求助资源声明（已抽查，全部含）
- [x] `suicide-risk-response.md` 含「12356」（并标注国家卫健委来源）
- [x] `docs/knowledge-base-sources.md` 新增第三批来源表（11 个缺口域分类归并 45 篇）
- [x] `ls src/main/resources/document/*.md | wc -l` = 131
- [x] 入库后向量块数 > 251（实测 **439** 块，131 篇）

## Task 6：验收

- [x] `python scripts/retrieval_eval.py` Recall ≥ 0.95 —— **实测 0.98**（45 例，与扩充前持平）
- [ ] `python scripts/retrieval_eval.py` MRR ≥ 0.85 —— **实测 0.824，未达标**（见下方说明）
- [x] 新主题抽样 5 例人工核对：5/5 top1 命中（同性伴侣 / 不孕不育 / 自杀风险 / 网恋骗局 / 婚前协议）
- [x] `README.md` 中「86 篇」全部更新为「131 篇」（grep 无残留）
- [ ] git commit + push 完成

---

## 未通过项说明与后续

### [ ] MRR 0.85 → 实测 0.824（−0.056）

**现象**：Recall 未退化（0.98），但排序质量下降。主要来自「语义近邻拥挤」——新增 45 篇与既有文档主题高度相邻（如「伴侣与原生家庭过度纠缠」vs「公婆岳父母越界」，「精神出轨」vs「出轨之后」），正确文档常在 top2–top3 而非 top1。

**唯一硬失败**：`gt_41`（多跳，expect_mode=all）——"他一开始疯狂示好要确认关系，现在又忽冷忽热吊着我，这算不算操控？" 期望「爱情轰炸」+「间歇性强化」同时进 top5，实际只命中「间歇性强化」（单查「爱情轰炸」时 gt_44 稳定 top1）。

**结论**：这是 **库规模扩大的结构性代价**，不是文档质量问题。恰恰触发 ADR-15 记录的复验点（"45 例 MRR 跌破 0.85 → 回到重排方案对照"）。

**后续（独立任务，需 ADR）**：
1. 启用/调优 rerank（`docs/phase5-rerank/`）——对 topN 候选做二阶段重排，直接改善 MRR；
2. 或对 gt_41 类多跳 query 引入查询分解；
3. 评估是否对高重叠文档加「场景识别」别名段以拉开区分度。

> 本次不擅自改动检索链路（AGENTS.md §1：与文档冲突的实现需先补 ADR）。已如实写入 README 与验收记录。
