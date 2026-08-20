# 05 · API 契约设计

> 基线：context-path `/api`，端口 8088。OpenAPI 由 Springdoc/Knife4j 自动生成，本文定义**规范**，逐端点字段以代码注解为准。

## 1. 通信协议

| 场景 | 协议 | 说明 |
| --- | --- | --- |
| 普通同步（登录/会话管理/投票） | REST JSON | |
| 流式聊天 | SSE（GET） | 现状 `/Love_app/chat/sse*` 系列即流式端点（§3.1）；**`/v1/chat/stream` 统一重构是否推进：待讨论** |
| Agent 任务 | GET 提交（SSE）+ GET 轮询 | 现状 `Love_app/chat/LoveManus`（GET，SSE 返回）+ `/task/{taskId}` 轮询（ADR-3） |

SSE 基础设施要求：Nginx `proxy_buffering off; proxy_cache off; proxy_read_timeout 300s`。

## 2. 统一响应结构

```json
{ "code": "A0000", "msg": "success", "data": {}, "traceId": "..." }
```

错误响应**永不暴露堆栈**；`traceId` 全链路透传（见 08 §2.3）。

### 错误码分段

| 码段 | 含义 | 示例 |
| --- | --- | --- |
| A0000 | 成功 | |
| B1xxx | 输入错误 | B1001 含违禁内容（L3 阻断） |
| B2xxx | 认证/权限 | B2001 JWT 过期；B2002 非本人资源（跨用户越权） |
| B3xxx | 资源不存在 | B3001 会话不存在 |
| B4xxx | 业务规则 | B4002 护栏 L2 降温提示附带 |
| B5xxx | 配额/限流 | B5001 日配额耗尽；B5002 系统繁忙（Agent 池满） |
| B6xxx | 幂等冲突 | B6001 重复提交 |
| Exxxx | 系统错误 | E5000 LLM 全降级链失败 |

## 3. 端点规范（现状，与代码一一对应）

> 以下路径均为现状实现（2026-08-19 核对）。历史文档中的 `/v1/...` 统一路径重构为**待讨论项**，未实现不承诺。

### 3.1 流式聊天（现状 SSE 家族）

| 端点 | 用途 | 说明 |
| --- | --- | --- |
| `GET /Love_app/chat/sse?prompt&chatId&mediaIds?` | 流式聊天（打字机） | `mediaIds` 非空时走视觉（ADR-11）；缺 chatId 返回 400 |
| `GET /Love_app/chat/sse/rag?prompt&chatId` | 聊天 + 知识检索（RAG） | 能力开关形态，非独立模式（SRS COMM-04） |
| `GET /Love_app/chat/sse/tools?prompt&chatId` | 聊天 + 工具调用 | 单轮 function calling |
| `GET /Love_app/chat/sync?prompt&chatId` | 同步聊天（阻塞，测试/内部调用） | 非用户侧主路径 |

SSE 事件序列（现状为文本 chunk 流；结构化事件（start/chunk/end/usage）为**待讨论**重构项）：
```
data: <文本片段>            （多个，打字机效果）
```

### 3.2 Agent 任务（现状）

| 端点 | 用途 | 说明 |
| --- | --- | --- |
| `GET /Love_app/chat/LoveManus?message&sessionId&idempotencyKey?` | 提交 Agent 任务（SSE 实时推流） | 任务落库（ADR-3），响应头 `X-Session-Id` |
| `GET /Love_app/chat/LoveManus/task/{taskId}` | 查询任务状态（RUNNING/SUCCESS/FAILED） | 崩溃后前端轮询确认 FAILED 可重提 |
| `GET /Love_app/chat/LoveManus/stop/{sessionId}` | 停止运行中的 Agent 会话 | 幂等：无活动会话返回 no_active_session |

**待讨论**：`/v1/agent/tasks`（POST 提交/SSE 进度/取消）统一路径与"工具调用可视化"契约的推进方式（SRS AGENT-03 能力已实现，前端展示层随实现完善）。

### 3.3 记忆与会话（现状 `/memory/**`）

| 端点 | 用途 | 说明 |
| --- | --- | --- |
| `POST /memory/register` | 创建/注册对话归属 + 标题 | 抢注他人会话 403 |
| `GET /memory/conversations?chatType` | 本人对话列表（含消息数） | 按时间倒序 |
| `GET /memory/{conversationId}` | 对话详情（完整历史） | IDOR 归属校验 |
| `GET /memory/{conversationId}/count` | 消息数 | |
| `DELETE /memory/{conversationId}` | 清空该会话历史（现状语义 = clearHistory，软删） | 与 SRS"删除对话/清空历史"口径对齐方式**待讨论** |
| `POST /memory/message/{messageId}/feedback?value=LIKE/DISLIKE` | 单条消息赞踩反馈 | 写 message.feedback（SRS FB-01） |
| `GET/PUT/DELETE /memory/facts[/{id}]` | 用户事实记忆（纠错闭环） | ADR-14 |
| `GET /memory/admin/conversations` | 管理员全量会话 | AdminGuard 校验 |

所有响应确认不含跨用户数据（07 §3）。

### 3.4 认证

现有 `/auth/register|login|me` 保留。`/tenant/token`：**已于 Phase 1 整体删除**（ADR-13，代码中已不存在）——调试与测试统一走 `/auth/login` 获取真实身份 Token。

### 3.5 媒体上传 `POST /media/upload`

multipart/form-data，单文件。限制：JPG/PNG/WebP、原始 ≤10MB、服务端压缩至长边 2048px（超出格式/大小返回 B1002）。响应：`{ "mediaId": "...", "width": 2048, "height": 1536 }`。上传后经 `/Love_app/chat/sse` 的 `mediaIds` 引用（≤4 张，归属校验）。

### 3.6 投票 `POST /evolution/vote`

赞踩反馈（SRS FB-01）：`{ sessionId, messageIndex, voteType: LIKE/DISLIKE/NEUTRAL, feedbackText? }`，幂等 upsert 至 knowledge_vote（`@Valid`）。

## 4. 契约纪律

- 字段命名 camelCase；时间 ISO-8601 带时区。
- 破坏性变更：新版本路径（`/v2/...`），旧版本保留一个迭代后下线并公告。
- 每个端点的错误码在本文表格中有归属段；新增错误码先改文档再改代码。
