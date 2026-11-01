# API 契约 Specification

## Purpose

定义 `lwx-ai-agent` 对客户端暴露的 HTTP 契约：路径、方法、鉴权、响应信封与关键错误语义。
路径前缀恒为 `/api`（`server.servlet.context-path`）。字段级细节以控制器注解为准，
本规格约束的是**端点存在性与可观察行为**，供 `scripts/check_api_contract.py` 做漂移检查。

来源：`docs/05-API契约设计.md`（2026-10-31 迁入，历史层）。本文件自此为该契约的事实源。

## Requirements

### Requirement: 统一响应信封

系统 SHALL 对所有 HTTP 响应返回 HTTP 200，业务结果由响应体 `code` 字段表达；
响应体 MUST 形如 `{"code":"A0000","msg":"success","data":{},"traceId":"..."}`，
且 MUST NOT 暴露堆栈。

#### Scenario: 成功响应

- **WHEN** 任意端点成功处理请求
- **THEN** 响应体 `code` 为 `A0000`，`data` 携带业务载荷

#### Scenario: 业务失败

- **WHEN** 请求违反业务规则（如缺少必填参数）
- **THEN** 响应体 `code` 为对应错误码，HTTP 状态仍为 200

### Requirement: 错误码分段

系统 SHALL 按以下分段使用错误码：`B1xxx` 输入错误、`B2xxx` 认证与权限、`B3xxx` 资源不存在、
`B4xxx` 业务规则（含护栏分级与未成年人模式）、`B5xxx` 配额与限流、`B6xxx` 幂等冲突、`Exxxx` 系统错误。

#### Scenario: 跨用户越权

- **WHEN** 用户 A 用用户 B 的会话 ID 请求资源
- **THEN** 返回 `code=B2002`

#### Scenario: 上游全链路失败

- **WHEN** 主链与所有降级级都失败
- **THEN** 返回 `code=E5000`，不伪造答案

### Requirement: 流式聊天端点

系统 SHALL 通过 SSE（`text/event-stream`）提供聊天流，并在正文之前发送且仅发送一个 `status`
占位事件；客户端 MUST NOT 把 `status` 的 `data` 当正文渲染。

#### Scenario: 普通流式聊天

- **WHEN** 已登录用户请求 `GET /Love_app/chat/sse`
- **THEN** 依次收到 `event: status`、若干正文 chunk，最后结束；缺 `chatId` 时返回 `code=400`

#### Scenario: 带图聊天

- **WHEN** 请求带上 `mediaIds`
- **THEN** 走视觉链路（ADR-11），并校验每个 mediaId 的归属

#### Scenario: RAG 增强聊天

- **WHEN** 用户请求 `GET /Love_app/chat/sse/rag`
- **THEN** 先检索知识库再作答，检索失败时降级为无上下文作答而非报错（ADR-30）

#### Scenario: 同步聊天

- **WHEN** 内部调用 `GET /Love_app/chat/sync`
- **THEN** 阻塞返回完整回答，不作为用户侧主路径

### Requirement: 话术三级建议事件

系统 SHALL 在识别为话术请求时于流末尾追加一个命名事件 `advice`，载荷含
`安全牌 / 进击牌 / 后撤牌` 三档，每档含 `content` 与 `reaction`；解析失败时降级为纯文本。

#### Scenario: 命中话术请求

- **WHEN** 请求被 `CapabilityRouter.isAdviceRequest` 判定为话术请求
- **THEN** 流末尾出现 `event: advice`，且 `name ∈ {安全牌, 进击牌, 后撤牌}`

#### Scenario: 非话术请求

- **WHEN** 普通聊天请求
- **THEN** 流中不出现 `advice` 事件，协议与旧版一致

### Requirement: Agent 任务端点

系统 SHALL 将 Agent 任务落库并暴露提交、查询、停止三个端点；任务崩溃后由心跳补偿标记为失败。

#### Scenario: 提交任务

- **WHEN** 用户请求 `GET /Love_app/chat/LoveManus`
- **THEN** 返回 SSE 推流，响应头含 `X-Session-Id`，任务行落库（ADR-3）

#### Scenario: 轮询任务状态

- **WHEN** 客户端请求 `GET /Love_app/chat/LoveManus/task/{taskId}`
- **THEN** 返回状态 RUNNING、SUCCESS 或 FAILED；崩溃后为 FAILED（可重提）

#### Scenario: 停止任务

- **WHEN** 客户端请求 `GET /Love_app/chat/LoveManus/stop/{sessionId}`
- **THEN** 幂等停止；无活动会话时返回 `no_active_session`

### Requirement: 记忆与会话端点

系统 SHALL 对每个会话类端点校验归属，跨用户访问一律拒绝；删除为软删，物理清除由
`MessagePurgeScheduler` 按保留期执行（ADR-5）。

#### Scenario: 会话列表与详情

- **WHEN** 用户请求 `GET /memory/conversations`
- **THEN** 返回本人会话，按时间倒序

- **WHEN** 用户请求 `GET /memory/{conversationId}` 或 `GET /memory/{conversationId}/messages`
- **THEN** 返回本人完整历史；`/messages` 每条带 `messageId`（ADR-74）

#### Scenario: 会话写入与删除

- **WHEN** 用户请求 `POST /memory/register`
- **THEN** 创建会话归属；抢注他人会话返回 `code=403`

- **WHEN** 用户请求 `DELETE /memory/{conversationId}`
- **THEN** 该会话消息软删，列表中不再出现（详情不再返回正文）

#### Scenario: 消息赞踩

- **WHEN** 用户请求 `POST /memory/message/{messageId}/feedback`
- **THEN** 写入 `LIKE/DISLIKE`，非法 `value` 被拒

#### Scenario: 事实记忆纠错

- **WHEN** 用户请求 `GET/PUT/DELETE /memory/facts[/{id}]`
- **THEN** 仅操作本人事实记忆，跨用户返回 403（ADR-14）

#### Scenario: 管理员查看全量会话

- **WHEN** 管理员请求 `GET /memory/admin/conversations`
- **THEN** 通过 `AdminGuard` 校验；无管理凭据返回 403

### Requirement: 认证与个人资料

系统 SHALL 以 JWT 标识身份，`/auth/me` 与 `/auth/profile` 共用同一份 camelCase 资料字段；
非法值必须显式报错且不落库。

#### Scenario: 注册与登录

- **WHEN** 用户请求 `POST /auth/register`
- **THEN** 必填 `birthYear`（1900..当年），`<18` 时置 `minor_mode=1`

- **WHEN** 用户请求 `POST /auth/login`
- **THEN** 返回 JWT；密码错误返回认证错误码

#### Scenario: 读取与更新资料

- **WHEN** 用户请求 `GET /auth/me`
- **THEN** 返回 `nickname / avatarEmoji / bio / anniversaryDate / minorMode / birthYear / evolutionConsent`

- **WHEN** 用户请求 `PUT /auth/profile` 且 `anniversaryDate` 非法
- **THEN** 返回 `success:false` 且不写库

#### Scenario: 进化模块单独同意

- **WHEN** 用户请求 `PUT /auth/evolution-consent`
- **THEN** 以 `{consent:boolean}` 记录，默认关闭

### Requirement: 媒体上传

系统 SHALL 只接受 JPG/PNG/WebP、原始 ≤10MB 的单文件，服务端压缩至长边 ≤2048px。

#### Scenario: 上传截图

- **WHEN** 客户端请求 `POST /media/upload`
- **THEN** 返回 `{mediaId,width,height}`；格式或大小越界返回 `code=B1002`

### Requirement: 投票

系统 SHALL 以幂等 upsert 记录赞踩，写入 `knowledge_vote`。

#### Scenario: 提交投票

- **WHEN** 用户请求 `POST /evolution/vote`
- **THEN** 按 `sessionId+messageIndex` upsert；非法 `voteType` 被拒

### Requirement: 举报与合规

系统 SHALL 提供用户举报与管理员处理，并对举报限流（24h 内 ≤5 条）。

#### Scenario: 提交举报

- **WHEN** 用户请求 `POST /complaint`
- **THEN** `category ∈ CONTENT/PRIVACY/ABUSE/OTHER`；第 6 次返回 `code=429`

#### Scenario: 查看举报

- **WHEN** 用户请求 `GET /complaint/mine`
- **THEN** 返回本人举报及 `status`

- **WHEN** 管理员请求 `GET /complaint/admin/list` 或 `POST /complaint/admin/{id}/status`
- **THEN** 通过 AdminGuard 校验后返回/流转

### Requirement: 进化技能审核

系统 SHALL 只统计真实技能行，排除 `__reflected_no_skill__` 占位行。

#### Scenario: 列出待审技能

- **WHEN** 管理员请求 `GET /evolution/skills`
- **THEN** 返回指定状态技能，`limit` 上限 200

- **WHEN** 管理员请求 `GET /evolution/skills/count`
- **THEN** 返回与列表同口径的真实条数

- **WHEN** 管理员请求 `POST /evolution/skills/bulk-reject`
- **THEN** 先取 id 再分批更新，`limit` 上限 5000

### Requirement: 未成年人模式

系统 SHALL 在服务端强制未成年人保护：宵禁命中返回 `code=4004`，每日超限返回 `code=4005`。

#### Scenario: 宵禁时段请求

- **WHEN** 未成年人账号在宵禁时段发起对话
- **THEN** 以默认 `data` 事件返回 `code=4004` 的人话提示

#### Scenario: 每日额度用尽

- **WHEN** 未成年人账号当日超限
- **THEN** 返回 `code=4005`；与刹车片 `code=4002`（独立 `event:brake`）区分

### Requirement: 管理端分析

系统 SHALL 仅对管理员暴露分析概览，并如实上报数据质量。

#### Scenario: 读取概览

- **WHEN** 管理员请求 `GET /admin/analytics/overview`
- **THEN** 返回 DAU/留存/漏斗/功能使用计数，并带 `dataQuality`；无凭据返回 403
