# phase7-jev-shadow · 验收清单（已逐条自检）

> 交付：2026-09-20。每条的实测证据见 `spec.md` §S6。

## A. 模式与语义

- [x] A1 `app.jev.guardrail.mode` 支持 `off` / `shadow` / `enforce` 三态，**默认 `off`**
      —— `JevProperties.Guardrail.mode`，`application.yml` 默认 `${JEV_GUARDRAIL_MODE:off}`
- [x] A2 `off` 模式下**一次 Jev 请求都不发**
      —— `JevSelfHarmSignalTest.offModeNeverCallsHttp` 用本地 HttpServer 计数断言 `calls==0`
- [x] A3 `shadow` 模式下越阈消息**不改变响应**
      —— E2E：Jev 判 **0.66**（>0.6）的句子，用户拿到的是正常 LLM 回复而非转介文案
- [x] A4 `enforce` 模式下越阈消息抛 `BizException(4001)` 且为转介文案
      —— `ChatEntryTest.enforceModeBlocksAndRecordsBlocked` 断言 code=4001 且含 `400-161-9995`
- [x] A5 词典已判 L3 的消息仍走词典分支，且**不调 Jev**
      —— `ChatEntryTest.dictionaryHitShortCircuitsBeforeJev` 断言 stub `calls==0`；
      E2E 中该句仍拿到词典转介文案（一字未变）
- [x] A6 旧 `enabled: boolean` 已移除，全仓无残留引用
      —— `grep -rn "getGuardrail().isEnabled\|setEnabled" src/main src/test` 无命中（JevProps 顶层 `enabled` 是总开关，保留）

## B. 判定与记录

- [x] B1 `judge()` 在**低于阈值**时也返回概率 —— `shadowModeStillReturnsProbabilityBelowThreshold`
- [x] B2 `judge()` 失败（未启用/不可达/非 200/缺字段/空文本）返回 `empty` 且不抛
      —— `failuresReturnEmptyAndNeverThrow`、`unreachableEndpointReturnsEmpty`、`blankInputIsNotSent`
- [x] B3 Flyway `V24` 可应用，两列存在 —— 启动日志 `Migrating schema to version "24"`；
      `show columns from guardrail_event like 'signal%'` 返回两行
- [x] B4 既有插入路径行为不变 —— `GuardrailAdvisor` 改用统一记录器后，其 4 个调用点语义未变；全量单测 245/245
- [x] B5 落库仍只有 content_hmac，无原文 —— E2E DB 查询 `has_hmac=1`，
      `ChatEntryTest.shadowEventStoresHashNotPlaintext` 断言不等于原文且长度 64
- [x] B6 事件语义正确 —— `shadow` → `action=SHADOW`；`enforce` 拦截 → `action=BLOCKED`；均 `rule_id=jev:self_harm`

## C. 真实语料影子观测

- [x] C1 语料解密复用项目 `EncryptionService` —— `JevShadowCorpus.java` 直接 `new EncryptionService(...)`
- [x] C2 解密成功率已报告 —— 53,028 条中失败 **20（0.04%）**
- [x] C3 分母口径正确：先剔除词典 L3 —— 全量词典分布 `{0:8996, 1:44011, 2:14, 3:7}`；`dict_l3` 单列不进分母
- [x] C4 概率直方图（含低于阈值的部分）—— 随机样本 800 条全部落在 `[0.01,0.05)`
      ⚠️ **后续修正**：这 800 条**去重后只有 20 条不同文本**（483 条是"你好"），
      有效样本量远小于名义值 → 该直方图只描述"这 20 条"，不代表真实流量分布
- [x] C5 阈值→拦截表（0.3/0.5/0.6/0.7/0.8/0.9）—— **各档拦截数均为 0（0.000%）**
- [x] C6 HTTP 失败单独列出 —— `失败 = 0`（821 次调用）；探针不把失败并入"低风险"，
      失败会写 `error` 字段并有独立计数
- [x] C7 高分区逐条人工复核 —— 概率 ≥0.30 的去重条数 **0**；最高 0.16「去死吧」（骂对方，非自伤）
- [x] C8 局限已写明 —— `spec.md` §S8：单一部署、90.7% 脚本账号、**无真实自伤阳性样本**
- [x] C9 原文未提交 —— 明文只落 `outputs/`（`.gitignore` 第 22 行 `outputs/`）；
      报告 JSON 只含统计量与 40 条样本摘录，且该报告也在 `outputs/` 下

## D. 回归

- [x] D1 编译通过
- [x] D2 全量单测通过 —— **245/245**（基线 236，+9）
- [x] D3 真实 E2E —— `e2e_live.py` **22/22**
- [x] D4 `shadow` 下真实 SSE 实测：用户收到正常回复 **且** DB 有 `SHADOW` 事件 —— 两条都验到（4/4 通过）
- [x] D5 无真实密钥入库 —— `JEV_API_KEY` 只走环境变量；
      grep 到的 `apikey_test_only` 是单测桩值（本地 HttpServer 用，不指向真实服务），真实 key 未出现在任何文件
- [x] D6 临时探针/对照实验代码已撤除 —— 两处注入撤销后与备份逐字节一致，复跑全绿

## E. 交付

- [x] E1 文档数字与真实产出一致（全部取自 `outputs/` 原始 JSON，无"应该"）
- [x] E2 决策记录 / 测试策略已同步（`docs/03` ADR-36、`docs/09` §8.15）
- [x] E3 `MEMORY.md` 待办已更新（开 enforce 的前提条件）
- [x] E4 提交并推送，`git ls-remote` 复核

## 未通过项（显式声明）

**无阻塞项未通过。** 但有三项**能力边界**必须连同结论一起读：

1. **召回侧未在真实数据上验证**：语料里自伤基率 <0.013%，一条都没抽到；
   正例仍来自我自制的 12 例 + 本轮 E2E 里 2 句我自己写的句子。
2. **误报侧的 0/800 已不足以支撑任何结论**（2026-09-20 修正）：去重后只有 20 条不同文本，
   且本库整体词汇量只有几十条（含「他」的不同消息仅 2 条）。真实误报率**未知**。
3. **未做**：灰度发布、成本核算、限流阈值探测。

## 顺带发现（本轮不修）

- `ChatEntry` 的词典 L3 分支**不写 `guardrail_event`**（只记 meters）→ 审计表看不出"词典兜底拦了多少"。
  E2E 里 `BLOCKED 事件数 = 0` 就是这个原因（拦是拦住了，只是没记账）。已记入待办。
