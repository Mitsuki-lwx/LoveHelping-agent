# LoveHelping-agent

Enterprise-grade AI emotional support & dating planning service platform built on Spring AI + multi-agent collaboration.

## 架构

```
┌─────────────────────────────────────────────────────┐
│                    Vue 3 前端 (front/)                │
│   Login / LoveChat / ManusChat / History / Admin     │
└──────────────────────┬──────────────────────────────┘
                       │ SSE / REST
┌──────────────────────▼──────────────────────────────┐
│                Spring Boot 3.4 (api/)                │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ LoveApp  │  │ LoveManus│  │ ReflectionSched  │  │
│  │ (聊天)   │  │ (智能体) │  │ (自我进化调度)   │  │
│  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
│       │              │                 │             │
│  ┌────▼──────────────▼─────────────────▼─────────┐  │
│  │              Service Layer                     │  │
│  │  ChatService / MemoryService / EvolutionService│  │
│  └──────────────────────┬────────────────────────┘  │
│                         │                            │
│  ┌──────────┬───────────┼───────────┬──────────┐   │
│  │  MySQL   │  Milvus   │     ES    │  PgVector│   │
│  │ (记忆)   │ (向量)    │  (关键词) │ (PG向量) │   │
│  └──────────┘───────────┴───────────┴──────────┘   │
└─────────────────────────────────────────────────────┘
```

## 功能

- **AI 恋爱咨询** — 流式/同步聊天，DeepSeek 驱动，RAG 知识库增强
- **自我进化** — 对话空闲后 LLM 反思萃取 skill → 向量库 → 下次检索注入 prompt
- **LoveManus 智能体** — 工具调用 + MCP 多服务集成
- **混合检索** — Milvus 向量 + ES BM25 + RRF 融合排序
- **聊天历史** — CRUD + 用户归属 + 管理后台
- **用户反馈** — 赞踩投票，反向指导反思
- **多租户** — JWT 认证 + TenantContext 隔离
- **限流** — 按租户日配额

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.4, Spring AI, MyBatis-Plus |
| AI 模型 | DeepSeek (chat), DashScope (embedding) |
| 数据库 | MySQL (主存储), PostgreSQL + pgvector (向量) |
| 向量检索 | Milvus, Elasticsearch (IK 分词), RRF 融合 |
| 前端 | Vue 3, Vite, Axios |
| 集成 | MCP (Amap Maps 等), OpenTelemetry |

## 项目结构

```
src/main/java/cn/lwx/lwxaiagent/
├── agent/            # LoveManus 智能体
├── config/           # Cors, ChatModel, SPA fallback
├── controller/       # REST API (Auth/Ai/Memory/Vote)
├── entity/           # MyBatis-Plus 实体
├── evolution/        # 自我进化 (ReflectionScheduler, SkillReflector, SkillRetriever)
├── infrastructure/   # LoveApp 聊天客户端
├── mapper/           # MyBatis Mapper
├── memory/           # 对话记忆服务
├── rag/              # RAG 检索增强
├── retrieval/        # Milvus/ES/混合检索
├── service/          # ChatService, EvolutionService, RateLimiter
├── tenant/           # JWT 认证, 租户上下文
└── tools/            # AI 工具回调
front/                # Vue 3 前端源码
```

## 快速开始

### 前置依赖

- JDK 21
- MySQL 8
- PostgreSQL 15+ (pgvector)
- Milvus + Elasticsearch (可选，hybrid 检索用)
- Redis (可选)

### 1. 配置

**⚠️ API Key 安全说明：** 所有敏感配置（API Key、数据库密码）通过 `application-local.yml` 注入，该文件已在 `.gitignore` 中排除，不会被提交到 Git。

```bash
# 复制配置模板
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml

# 编辑填入真实值
vim src/main/resources/application-local.yml
```

或在启动时设置环境变量：

```bash
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx
export MYSQL_PASSWORD=your_password
export PGVECTOR_PASSWORD=your_pg_password
export JWT_SECRET=your_random_secret_at_least_32_chars
export SEARCH_API_KEY=your_search_key
```

编辑 `src/main/resources/application.yml` 按需调整数据库连接、检索模式等。

### 2. 启动后端

```bash
mvn spring-boot:run
# 启动在 http://localhost:8088/api
```

### 3. 启动前端（开发）

```bash
cd front
npm install
npm run dev
# 启动在 http://localhost:3003，自动代理 API 到后端
```

### 4. 生产构建

```bash
cd front && npm run build
# 构建产物输出到 src/main/resources/static/
# 直接访问 http://localhost:8088/api/ 即可
```

## API 概览

| 路径 | 说明 |
|------|------|
| `POST /auth/login` | 登录获取 JWT |
| `POST /auth/register` | 注册 |
| `GET /Love_app/chat/sse` | SSE 流式聊天 |
| `GET /Love_app/chat/sse/rag` | RAG 增强流式聊天 |
| `GET /Love_app/chat/LoveManus` | Agent 对话 |
| `GET /memory/conversations` | 对话列表 |
| `GET /memory/{id}` | 对话历史 |
| `DELETE /memory/{id}` | 删除对话 |
| `POST /evolution/vote` | 赞踩投票 |

## 配置要点

```yaml
evolution:
  enabled: true
  extract-delay-seconds: 1800    # 最后消息后 30 分钟无新消息 → 反思
  idle-timeout-seconds: 7200     # 会话跨 2 小时 → 强制反思（兜底）
  quality-threshold: 5           # skill 最低质量分
  skill-top-k: 3                 # 每次检索注入 top-3 skill
```
