# phase21 · 验收清单（实测结果）

> 判据在**实现前**写定于 `tasks.md` §6（J1~J7），本清单逐条对应。
> 状态：**已按实测回填**（2026-09-28）。每条带证据；未通过项**不勾**并写明原因。
> 原始产物：`outputs/refusal-{strict,adjacent-help}-{141739,143849,145242}.json`（含每轮全文）。

## A. 范围护栏可切换（G1）

- [x] A1 `app.chat.scope-wording` 配置项存在，取值 `adjacent-help`（默认）/ `strict`
      —— `src/main/resources/application.yml:205`（`${APP_CHAT_SCOPE_WRITING:adjacent-help}`）
- [x] A2 **默认值 = `adjacent-help`** —— 单测 `defaultIsAdjacentHelp` +
      `fallbackDefaultInAnnotationIsAdjacentHelp`（解析 `@Value` 的兜底值断言）
- [x] A3 非法取值**启动失败**，不静默回退 —— 单测 `illegalWordingFailsFast`
      （`strict-ish` / `ADJACENT-HELP` / `""` 三种均抛 `IllegalStateException`）
- [x] A4 scope 段抽成两个常量，主体提示词其余部分**逐字未改** —— 单测
      `bothWordingsKeepHeadAndTail` 断言两版都以 `HEAD` 开头、`TAIL` 结尾，
      且六小节标题（Answer-Type Routing / Counter-Question / Three-Tier Advice /
      Tool Use / Scope / Confidentiality）全在
- [x] A5 三处 yml 用 `${APP_CHAT_SCOPE_WRITING:adjacent-help}` 形态，无具体值
      —— `grep -rn "scope-wording" src/main/resources/` 仅 1 处，无 profile 覆盖
- [x] A6 启动日志自报 `[scope-wording] 生效范围护栏措辞 = <版本>`
      —— E2E 日志实测：`[scope-wording] 生效范围护栏措辞 = adjacent-help（生产默认）`
- [x] A7 单测：两版文案存在 + **内容真的不同** + 默认值 + 非法值抛异常
      —— `ScopeWordingTest` 8 用例全绿

## B. 复验：单变量对照（J1~J5）

> 装置：`scripts/probe_refusal_scope.py` + `scripts/run_phase21_refusal.sh`
> 两臂只差 `APP_CHAT_SCOPE_WRITING`，`OPENAI_BASE_URL/MODEL/API_KEY` 全同；
> `APP_SCHEDULER_MASTER_ENABLED=false` 冻结库

- [x] B0 ⛔ **两臂都跑成**，且**各跑了 2 次、顺序反转**
      —— `143849`（strict 先）与 `145242`（adjacent-help 先）；另有 `141739` 单臂现状测量
- [x] B1 两臂 `[scope-wording]` 自报值与 `ARM_W` **一致**（J7）—— 4 次运行全部一致
- [x] B2 两臂生效端点都是 `https://api.deepseek.com | deepseek-flash`（只差一个变量）
      —— 两轮 4 个臂的启动横幅均一致
- [x] B3 **J1**：G1 组实验臂 `refusal_short + refusal_long` = **0**（0/11、0/12）
      ⚠️ **但对照臂也是 0/12 → 该判据在本 provider 上无区分力，不能作为"修复有效"的证据**
- [ ] B4 **J2**：G1 组实验臂中位长度 > 对照臂中位长度 —— ❌ **不成立（方向相反）**
      - `143849`：实验臂 628 **<** 对照臂 677
      - `145242`：实验臂 629 **<** 对照臂 737
      - **这恰恰是"缺陷换形态"的信号**：`strict` 措辞「先推走再补关系视角」把篇幅**撑长**了。
        按纪律**如实报告不成立**，不事后改判据圆过去。
- [x] B5 **J3**：G2 组两臂均 0 拒答；实验臂中位 ≥ 对照臂 ×0.7
      —— `143849`：948/1090 = 0.87 ✅；`145242`：1007/922 = 1.09 ✅
- [x] B6 **J4**：G3 组两臂均全轮拒答 —— 两轮均 12/12、去重=1、68 字一字不差
- [ ] B7 **J5**：两臂 `llm.fallback`=0、加上下文 402=0、应用层 ERROR=0 —— ⚠️ **部分不成立**：
      - ✅ `llm.fallback` 条数 = 0（两轮全程未发生降级）
      - ✅ 带上下文 402 = 0
      - ❌ **应用层 ERROR ≠ 0**：`143849` 轮 `strict` 臂 **15 行 PKIX TLS 失败**
        （`PKIX path building failed`，5 次实际失败，含 **2 轮用户可见失败**「AI 服务暂时不可用」），
        同轮 `adjacent-help` 臂 **0 次** → **两臂条件不等价**
      - 追查：`145242` 轮**反转顺序后 PKIX 出现在先跑的 `adjacent-help` 臂**（各 6 行）
        → **PKIX 跟时间跑、不跟臂跑**（环境抖动，非本轮代码）
      - 但当天 `api.deepseek.com` / `api.siliconflow.cn` 系统信任库直连实测均 `OK TLSv1.3`
        → 抖动在 **JVM 侧信任链**（代理/Clash fake-ip 相关），**未归因**
- [x] B8 每臂 `去重回答数` 与轮数一并记录 —— 见 ADR-53 表（G1 全 6/6、G3 去重=1）
- [x] B9 ⚠️ **人工读原文复核完成，且发现分类计数与原文不一致**：
      关键词判据把「全文出现"睡眠门诊"」也算成软拒答 → 首轮误判 `adjacent-help` 为 2/6。
      **改判据为「开头 60 字内是否把诉求推给专科/医学」后**，三次运行稳定为 **0/6 · 0/6 · 0/6**。
      → 印证"数字与原文冲突时以原文为准"，也印证"分类器不是判据"。
- [x] B10 ✅ **G3 走的是规则层，已用实测证实**：耗时 **0.0~0.1s**、6 轮**去重=1**、68 字固定话术
      → 毫秒级返回 ⇒ 根本没调 LLM ⇒ 命中 `CapabilityRouter.isOffTopic` → `OffTopicNode`。
      **G3 通过不能证明提示词的 (B) 类有效**（见 I1）。

## C. 编译 / 单测 / E2E（J6）

- [x] C1 `BUILD SUCCESS`
      ⚠️ 第一次编译**失败**：Java 静态字段**前向引用**（`SYSTEM_PROMPT` 声明在三段常量之前）
      → 把常量挪到三段之后修复。**这不算"测试通过"，是编译排错。**
- [x] C2 单测 **339/339 全绿**，**339 > 331**（+8，全部是新增 `ScopeWordingTest`）
      ⚠️ 第一轮 1 个失败：断言 `endsWith("adjacent-help")` 而 `@Value` 字面值以 `}` 结尾
      → **测试写错，不是代码错**，改为解析兜底默认值后通过
- [x] C3 真实 E2E **22/22**（`logs/run_phase21_e2e.sh`，端口 2645）
      附加断言：A3 ✅（scope 措辞自报 == 配置值）、A3-1 ✅（默认是 adjacent-help）、
      A/A2/B ✅（沿用 phase20 的端点与零 tier 断言）、D1/D2/D4/D5 ✅
      ⚠️ E2E 轮亦有 **4 行 PKIX**（约 1 次失败），被重试兜底 → 22/22 仍全过
- [x] C4 前端未触及，既有 5 个失败（`Home.test.js` / `Login.test.js` 期望 `LoveHelping`
      但实际是中文文案）仍为**既有失败、未新增** —— `git status --short front/` 无本轮改动

## D. 文档

- [x] D1 `docs/03-技术决策记录.md` 追加 **ADR-53**（编号接 ADR-52）
- [x] D2 ADR-53 写明改动来源（混在 `5d8c4c3`、commit message 未提、此前无 ADR）、
      验证口径、结论（**缺陷换形态**）、以及全部未验证项
- [x] D3 ADR-48「已知限制（补记）」段加**更正块**：**原文保留不改写**，
      注明适用范围变更、被 ADR-53 取代、且**跨 provider 数字不可直接比较**
- [x] D4 `docs/11-环境与装置.md` §6 登记新装置（`probe_refusal_scope.py` / `run_phase21_refusal.sh`）
- [x] D5 三件套 `docs/phase21-refusal-scope/{tasks,spec,checklist}.md` 齐备
- [x] D6 `.workbuddy/memory/2026-09-28.md` 追加结论

## E. 密钥与提交纪律

- [x] E1 diff / 未跟踪文件 / 索引三路扫 `sk-` 形态 → 0 命中
- [x] E2 `git diff --cached --name-only` 无 `application-local.yml` / `.env.local`
- [x] E3 `logs/` 与 `outputs/` 的新产物不误入仓库（gitignore 生效）
- [ ] E4 推送后 `git ls-remote` 复核远端 HEAD == 本地 HEAD —— **未推送**（等用户确认）

## I. 显式未验证（不许含糊）

- [ ] I1 **提示词的 (B) 类处置未被独立验证** —— G3 走规则层（B10）。
      要验需构造**不命中 `isOffTopic` 但明显事务性**的问题（如"帮我算一下这个月的账"）
- [ ] I2 **答案质量未测**（P2）—— **不得声称**答案质量变好或变差
- [ ] I3 未控变量：两臂 TTFT 含 RAG 检索，**检索内容可能不同**；
      未做「固定检索结果、只换措辞」的对照
- [ ] I4 样本量：3 组 4 题 × 6 轮 × 2~3 次运行，**仍非统计显著**。
      但"开头推走率"方向在**两次独立运行中一致**（strict 5/12 vs adjacent-help 0/18），
      **不是单次观察**
- [ ] I5 未做 judge 自一致性检验
- [ ] I6 **ADR-48 的 3/6 短拒答在本轮一次都没复现**（两臂 0/30 与 0/36）。
      它是"当时 space-bunny-alpha 的真实观测"还是样本偶然，**本轮无法回答**
- [ ] I7 `strict` 臂"开场推走"的**根本机制未归因** ——
      只观测到相关性（措辞 vs 开场推走），**未做证伪实验**排除
      "RAG 检索到不同内容导致的差异"（该变量未固定）
- [ ] I8 ⛔ **本轮顺带发现但未修**：`CheckNode` 的 L3 安全护栏替换
      在流式路径下对用户无效（`ChatEntry:158` 的 `if (!stream.streamed())` 跳过替换）。
      实测证据：3 次 `L3 blocked (self_harm)`，被拦原文（如 1334 字那条）**完整送达用户**。
      **这是安全缺陷，优先级高于本轮的体验修复，已单独记账。**
