# checklist.md — 沙盘模拟 Phase 4 验收清单

> 每一项必须可执行、可勾选，不得写「功能完整」「代码质量好」等不可观测描述。
> 以 `grep -r`、`curl -s`、SQL 查询、浏览器操作为准。

---

## 数据库

- [ ] `sandbox_session` 表已创建（V11），含 `user_id / channel / persona_id / history_version / created_at / last_active_at / needs_user_confirm / drift_count`
- [ ] `sandbox_persona` 表已创建（V12），含 `id / name / archetype / traits_json / avatar_url / is_custom`
- [ ] `sandbox_session.user_id + channel` 复合索引已创建
- [ ] `sandbox_persona.archetype` 字段值分布在 8 个以上唯一值（傲娇/天然呆/三无/元气/温柔/霸道/知性/阳光）

**验证命令**：
```bash
mysql -e "SHOW CREATE TABLE sandbox_session\G"
mysql -e "SELECT archetype, COUNT(*) FROM sandbox_persona GROUP BY archetype"
```

---

## 实体与 Mapper

- [ ] `SandboxSession.java` 存在，`@TableName("sandbox_session")`，`@Data` 注解完整
- [ ] `SandboxPersona.java` 存在，`@TableName("sandbox_persona")`，`@Data` 注解完整
- [ ] `SandboxSessionMapper.java` 继承 `BaseMapper<SandboxSession>`
- [ ] `SandboxPersonaMapper.java` 继承 `BaseMapper<SandboxPersona>`
- [ ] 两个 Mapper 均有 `@Mapper` 注解，被 Spring 扫描

**验证命令**：
```bash
find src -name "SandboxSession.java" -o -name "SandboxPersona.java" | wc -l
grep -n "@TableName" src/main/java/cn/lwx/lwxaiagent/entity/SandboxSession.java
```

---

## 沙盘会话创建（POST /sandbox/create）

- [ ] `/sandbox/create` 接口存在，`@PostMapping` 注解
- [ ] 路径 `/sandbox/create` 已注册到 SecurityConfig 拦截器（需 JWT 认证）
- [ ] `personaId` 和 `customTraits` 二选一（有 personaId 时忽略 customTraits）
- [ ] `customTraits` 经 LLM 归一化（长度 ≤ 200 字，特殊字符被过滤）
- [ ] 成功返回 `{"sandboxId": ..., "channel": "REALISTIC", "personaName": ...}`
- [ ] 归属校验：userId 为空时返回 401，他人沙盘不可修改

**验证命令**：
```bash
# 创建真人沙盘（有 personaId）
curl -s -X POST "$B/sandbox/create" -H "Authorization: Bearer $TOKEN" \
  -d '{"personaId":1,"channel":"REALISTIC","relationshipStage":"dating"}'
# 创建自定义沙盘（无 personaId）
curl -s -X POST "$B/sandbox/create" -H "Authorization: Bearer $TOKEN" \
  -d '{"channel":"ANIME","customTraits":"傲娇，会说「哼」"}'
```

---

## 沙盘对话执行（POST /sandbox/chat）

- [ ] `/sandbox/chat` 接口存在，返回 SSE 流
- [ ] 请求需 `sandboxId` 参数（已有沙盘会话）
- [ ] 每轮对话注入 persona 参数（system prompt 含 persona name + traits）
- [ ] 工具调用仅限 `searchWeb`、`searchKnowledge`、`planDate`（排除 `doTerminate`、`PDFGenerationTool` 等）
- [ ] 多轮对话历史保留（history_version 正确递增）
- [ ] 归属校验：用户 A 的沙盘用户 B 不可访问（403）
- [ ] RAG 知识检索在沙盘中可被触发（searchKnowledge 调用有日志）
- [ ] 工具调用记录有审计日志（AgentGuardrailInterceptor 已实现）

**验证命令**：
```bash
# 正常沙盘对话
curl -s -N "$B/sandbox/chat?sandboxId=1&message=你好" -H "Authorization: Bearer $TOKEN" | head -5
# 越权测试
curl -s "$B/sandbox/chat?sandboxId=1&message=hi" -H "Authorization: Bearer $OTHER_TOKEN"
```

---

## 会话管理

- [ ] `POST /sandbox/{id}/reset` 存在，清空 history_version（保留 persona）
- [ ] `DELETE /sandbox/{id}` 存在，返回 200（归属校验）
- [ ] `GET /sandbox/list` 存在，按 `created_at DESC` 排序

**验证命令**：
```bash
# 重置会话
curl -s -X POST "$B/sandbox/1/reset" -H "Authorization: Bearer $TOKEN"
# 删除会话
curl -s -X DELETE "$B/sandbox/1" -H "Authorization: Bearer $TOKEN"
# 列表
curl -s "$B/sandbox/list" -H "Authorization: Bearer $TOKEN"
```

---

## 人设漂移检测（ADR-9）

- [ ] `SandboxDriftDetector.java` 存在，实现 drift 检测逻辑
- [ ] `sandbox_session` 表含 `drift_count` 字段（默认 0）
- [ ] 每轮对话后 `drift_count` 递增（漂移时）或重置（不漂移）
- [ ] `drift_count >= 3` 时 `needs_user_confirm = 1`
- [ ] 下次对话注入漂移修正指令（或返回 409 Conflict 提示用户确认）

**验证命令**：
```bash
# 连续3轮后 drift_count 检查
mysql -e "SELECT drift_count, needs_user_confirm FROM sandbox_session WHERE id=1"
```

---

## 合规护栏（ADR-12）

- [ ] 请求"扮演原神/忍者"被 L3 拦截（返回提示"请使用平台原创角色"）
- [ ] 请求"扮演 miHoYo 角色"被 L2 记录但不阻断（可选：引导到相近原型）
- [ ] 注册年龄 < 18 的用户，创建沙盘返回 403（未成年受限）

**验证命令**：
```bash
curl -s "$B/sandbox/chat?sandboxId=1&message=请你扮演原神里的纳西妲" -H "Authorization: Bearer $TOKEN" | grep -i "原创\|拦截"
```

---

## 注销级联删除（ADR-5 对接）

- [ ] 注销用户后，`sandbox_session WHERE user_id = ?` 记录已删除
- [ ] 沙盘关联的 `message` 记录已软删（`deleted=1`）
- [ ] `sandbox_persona` 自定义头像文件已从磁盘删除

**验证命令**：
```bash
# 注销后检查
mysql -e "SELECT COUNT(*) FROM sandbox_session WHERE user_id='deleted_user'"
mysql -e "SELECT COUNT(*) FROM message WHERE conversation_id IN (SELECT id FROM sandbox_session WHERE user_id='deleted_user') AND deleted=0"
```

---

## 端到端冒烟

- [ ] `e2e-smoke.sh` 新增 7.8 沙盘段（4 项）
- [ ] 7.8.1 创建沙盘会话（REALISTIC）→ 200 + 有 sandboxId
- [ ] 7.8.2 3 轮沙盘对话 → 输出含 persona 特征词汇（如"哼"至少出现 1 次，假设 persona 是傲娇原型）
- [ ] 7.8.3 重置会话 → history_version 归零
- [ ] 7.8.4 删除会话 → 200

**验证命令**：
```bash
cd /path/to/project && BASE_URL=http://localhost:8088/api sh scripts/e2e-smoke.sh 2>&1 | grep -A 1 "7.8"
```

---

## 文档同步

- [ ] `docs/01-产品定位与需求.md` 场景表新增沙盘模拟行（✅ 已上线 或 ⏸ 待验证）
- [ ] `docs/10-重构路线图.md` Phase 4 区域新增沙盘模拟 checkbox（✅）
- [ ] `docs/03-技术决策记录.md` ADR-9 状态从"待定"改为"已落地"（附定稿日期）
- [ ] `docs/06-核心流程设计.md` §7 沙盘模拟从"远期附录"改为"已实现"（附实现说明）
- [ ] `docs/00-文档导读.md` 无需变更（编号体系不变）