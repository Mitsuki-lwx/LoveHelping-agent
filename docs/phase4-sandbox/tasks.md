# tasks.md — 沙盘模拟 Phase 4 实现任务

> **前置条件**：ADR-9（人格一致性方案）+ ADR-12（版权政策）同时定稿后方可开始 Task 3+。

---

## Task 1：沙盘数据库设计

**目标**：创建沙盘会话表（sandbox_session）+ 沙盘角色原型表（sandbox_persona）  
**依赖**：无  
**影响文件**：
- `src/main/resources/db/migration/V11__sandbox_tables.sql`
- `docs/04-数据模型设计.md`（新增 §2.11）

**内容**：
- `sandbox_session` 表：`id / user_id / channel(REALISTIC/ANIME) / persona_id / history_version / created_at / last_active_at`
- `sandbox_persona` 表：`id / name / archetype / traits_json / avatar_url / is_custom / created_at`
- 两个表的索引设计（`user_id+channel` 复合索引；`persona_id` 外键）

---

## Task 2：角色原型库种子数据

**目标**：定义 8-12 个原创角色原型（傲娇/天然呆/三无/元气/温柔/霸道/知性/阳光）  
**依赖**：Task 1  
**影响文件**：
- `src/main/resources/db/migration/V12__sandbox_persona_seeds.sql`
- `src/main/java/cn/lwx/lwxaiagent/entity/SandboxPersona.java`
- `src/main/java/cn/lwx/lwxaiagent/mapper/SandboxPersonaMapper.java`

**内容**：
- 实体类 + Mapper（MyBatis-Plus BaseMapper）
- Seed SQL：每个原型含 name、archetype（枚举标签）、traits_json（说话风格+关系阶段范围）

---

## Task 3：沙盘会话创建接口

**目标**：`POST /sandbox/create` 创建沙盘会话  
**依赖**：Task 1, Task 2  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/entity/SandboxSession.java`
- `src/main/java/cn/lwx/lwxaiagent/mapper/SandboxSessionMapper.java`
- `src/main/java/cn/lwx/lwxaiagent/service/SandboxService.java`（新建）
- `src/main/java/cn/lwx/lwxaiagent/controller/SandboxController.java`（新建）
- `src/main/java/cn/lwx/lwxaiagent/tenant/config/SecurityConfig.java`（注册拦截路径）

**接口**：
```
POST /sandbox/create
{
  "personaId": 1,              // 预置原型 或 null（自定义）
  "channel": "REALISTIC",      // REALISTIC / ANIME
  "customTraits": "...",       // 自定义特征（≤200字，LLM归一化后存储）
  "relationshipStage": "dating"
}
Response: { sandboxId, channel, personaName }
```

**业务规则**：
- `personaId` 和 `customTraits` 二选一；有 personaId 时忽略 customTraits
- `customTraits` 经 LLM 归一化（防注入 + 字段长度约束）再存
- 禁用说明：如果 ADR-9/12 未定稿，返回 403 + 说明文案

---

## Task 4：沙盘对话执行（核心）

**目标**：`POST /sandbox/chat` 执行沙盘多轮对话  
**依赖**：Task 3, Task 1（history_version 控制并发）  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/service/SandboxService.java`（扩展）
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/ai/AgentLoopExecutor.java`（或新建 SandboxAgentExecutor）

**核心逻辑**：
```
用户消息 → 注入 system prompt（persona + traits + 关系阶段 + 漂移检测指令）
        → ReactAgent 执行（共享全部 10 个工具，含 RAG 检索）
        → 人格参数约束工具使用场景（目标对象不会生成 PDF，但会搜索天气/知识）
        → 响应
```

**关键约束**：
- system prompt 注入人格参数（结构化字段，非自由文本）
- 历史窗口仅沙盘内消息（不读普通聊天历史，避免角色混乱）
- 每轮漂移检测（可异步，不阻塞）：输出风格是否偏离设定
- 并发控制：乐观锁 `history_version`，避免消息重叠

---

## Task 5：会话管理接口

**目标**：重置/删除沙盘会话  
**依赖**：Task 3  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/service/SandboxService.java`
- `src/main/java/cn/lwx/lwxaiagent/controller/SandboxController.java`

**接口**：
- `POST /sandbox/{id}/reset` — 重置会话历史（保留 persona 设定，清空 history_version）
- `DELETE /sandbox/{id}` — 删除会话（软删，归属校验）
- `GET /sandbox/list` — 列出用户所有沙盘会话

---

## Task 6：人设漂移检测（ADR-9 层 2）

**目标**：每轮沙盘回复后异步检测是否漂移  
**依赖**：Task 4  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/service/SandboxDriftDetector.java`（新建）
- `src/main/java/cn/lwx/lwxaiagent/entity/SandboxSession.java`（加 drift_count 字段）

**逻辑**：
- 输入：当前 persona 设定 + 最近 3 轮回复
- LLM 输出：是否漂移（Boolean）+ 简要说明
- 连续 3 轮 drift_count >= 3 → 下次对话注入漂移修正指令 + 标记 `needs_user_confirm=1`
- 用户确认后：重置 drift_count（或重新创建会话）

**注意**：漂移检测是轻量 LLM 调用（可复用 judgeModel），异步执行不阻塞主回复。

---

## Task 7：注销时清理沙盘数据（ADR-5 对接）

**目标**：用户注销时级联删除沙盘会话  
**依赖**：Task 3  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/service/DeleteService.java`（增加 sandbox 清理）
- `src/main/java/cn/lwx/lwxaiagent/service/MessageChatMemory.java`（沙盘消息也落 message 表，ADR-4 已加密）

**逻辑**：`DeleteService.deleteUserData` 增加：`sandbox_session WHERE user_id = ?` → `message WHERE conversation_id IN (sandbox_ids)` 软删

---

## Task 8：合规护栏接入（ADR-12）

**目标**：沙盘场景特殊护栏  
**依赖**：Task 4, Task 7  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/harness/governance/GuardrailRuleService.java`（新增规则）
- `src/main/java/cn/lwx/lwxaiagent/service/SandboxService.java`（添加 agent 路径护栏）
- `src/main/resources/db/migration/V11__sandbox_tables.sql`（护栏规则种子）

**规则**：
- L3 阻断：请求"扮演 XX（知名角色名）" → 返回"建议使用我们平台的原创角色"
- L2 降温：detect 昵称含"忍者/米哈游/原神"等 → 引导回原创
- 未成年检测：注册时声明年龄 < 18 → 沙盘创建受限 + 使用时长提醒

---

## Task 9：接入主流程（ChatEntry 路由）

**目标**：沙盘对话走 ChatEntry 统一入口（可选：保留独立 Controller 或路由）  
**依赖**：Task 4, Task 8  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatEntry.java`（可选扩展）
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatExecutor.java`

**决策点**：沙盘对话是"Agent深层"还是"ChatExecutor浅层"？——沙盘是多轮对话，不需要调用外部工具（计划已限制工具集），用 ChatExecutor 浅层即可（节省 checkpoint 开销）。需要在 ChatExecutor 加 persona/systemPrompt override 支持。

---

## Task 10：端到端验证

**目标**：沙盘全流程冒烟  
**依赖**：Task 1-9  
**影响文件**：
- `scripts/e2e-smoke.sh`（新增沙盘段 7.8）
- `docs/09-测试策略.md`（新增沙盘冒烟项）

**验证内容**：
- 创建沙盘会话（REALISTIC）
- 3 轮对话（角色一致性：输出含设定特征）
- 重置会话 → 对话历史清空
- 注销用户 → 沙盘数据已删
- 连续漂移 → 用户确认提示
- 非知名角色请求正常；扮演知名角色被拦截（L3）