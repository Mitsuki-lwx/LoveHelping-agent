<p align="center">
  <a href="./README.en.md">English</a> · 简体中文
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen?style=flat-square&logo=springboot" alt="Spring Boot 3.4"/>
  <img src="https://img.shields.io/badge/Spring%20AI-1.1-green?style=flat-square" alt="Spring AI"/>
  <img src="https://img.shields.io/badge/Vue-3-42b883?style=flat-square&logo=vuedotjs" alt="Vue 3"/>
  <img src="https://img.shields.io/badge/MySQL-8-4479a1?style=flat-square&logo=mysql" alt="MySQL 8"/>
  <img src="https://img.shields.io/badge/PostgreSQL-16%20%2B%20pgvector-336791?style=flat-square&logo=postgresql" alt="PG + pgvector"/>
  <img src="https://img.shields.io/badge/Docker-compose-blue?style=flat-square&logo=docker" alt="Docker Compose"/>
  <img src="https://img.shields.io/badge/状态-稳定_%E2%9C%93-1f8f4c?style=flat-square" alt="stable"/>
  <img src="https://img.shields.io/github/actions/workflow/status/Mitsuki-lwx/LoveHelping-agent/ci.yml?branch=main&label=CI&style=flat-square&logo=githubactions" alt="CI"/>
  <img src="https://img.shields.io/github/actions/workflow/status/Mitsuki-lwx/LoveHelping-agent/security.yml?branch=main&label=gitleaks&style=flat-square" alt="gitleaks"/>
</p>

<h1 align="center">💌 LoveHelping · 恋爱解忧</h1>

<p align="center">
  <b>基于 Spring AI 的 AI 情感陪伴服务——情感知识问答 · 沟通演练 · 跨会话记忆个性化。</b><br/>
  深夜的纠结 · 说不出口的话 · 放不下的关系 —— 都值得一封信的位置。
</p>

<p align="center">
  <a href="#-功能特性">功能特性</a> · <a href="#-快速开始">快速开始</a> ·
  <a href="#-环境变量">环境变量</a> · <a href="#-文档">文档</a> ·
  <a href="#-系统架构">系统架构</a> · <a href="#-安全与护栏">安全与护栏</a> ·
  <a href="#-质量与评测">质量与评测</a>
</p>

> LoveHelping 基于 **Spring AI + 有状态图编排**构建：意图在服务端自动路由（RAG 知识问答 / Agent 工具任务 / 纯对话），内容安全由三层护栏兜底，LLM 故障走降级链自动切换。部署与评测资产随仓库维护——`docker compose up -d` 即可启动完整栈。

---

## ✨ 功能特性

| | | |
|---|---|---|
| 💌 **恋爱解忧** | 🧭 **智能路由，不分难度** | 🎭 **角色模拟屋** |
| 71 篇精选情感内容构建知识库，**混合检索**（jieba 分词 RRF + pgvector 语义召回），回答附来源引用与打分 | 一张图自动分发：闲聊 / 知识问答 / 工具任务全自动（`OrchestrationGraph`），**用户永远只需要"写一封信"** | 预置 8 种人设 + 自定义性格/关系阶段创建沙盘会话，模拟对话用于沟通排练；每个 TA 有独立记忆上下文 |
| 🧠 **长期记忆档案** | 🛡️ **三层安全护栏** | 🔧 **Agent 工具面** |
| 对话自动萃取事实沉淀为记忆条目（分类/置信度），支持人工**核对 / 修正 / 删除**，跨会话个性化随使用累积 | 操控（PUA）识别 · 危机关键词全天转介 · 深夜情绪刹车 —— 是陪伴，更是边界 | 搜索 / 天气 / 网页抓取 / PDF 生成 / 约会方案，独立 MCP 进程承载，**故障不放大** |
| 🎮 **SSE 流式输出与排队体验** | 🔁 **自我进化** | 🐳 **一键部署** |
| 基于 SSE 的 token 级流式响应；并发超限返回 4003 排队告知（含预估等待 `retryAfterSec`），错误路径同构呈现 | 反思调度定期复盘对话，自动沉淀 Prompt 与技能版本（evolution_skill） | `Dockerfile × 2 + docker-compose`：MySQL / pgvector / Redis / MCP / App 单命令拉起 |

## 🖼 界面预览

> 项目运行后访问 `http://localhost:8088/api/`。以下为功能入口总览（截图欢迎以 PR 形式补充至 `docs/screenshots/`）：

| 入口 | 定位 | 对应路由 |
|---|---|---|
| 📮 解忧信箱 | 统一对话入口（自动路由；SSE 流式 + 超限排队告知） | `/love-chat` |
| 🎭 角色模拟屋 | 沙盘会话：自定义人设对话排练 + 会话独立记忆管理 | `/sandbox` |
| 🧠 记忆档案 | AI 记住的关于你的事：分类 / 置信度 / 修正 | `/memory` |
| 🏠 我的小窝 | 头像（emoji）· 昵称 · 签名 · 改密码 · 账号注销 | `/profile` |
| 📚 旧信存档 | 历史会话回顾与续写 | `/history` |

## 🚀 快速开始

### A. Docker 一键部署（推荐）

```bash
# 1. 准备密钥
cp .env.example .env        # 填入 6 个必填项（见下方环境变量表）

# 2. 构建并启动（首次自动拉取镜像 + 编译）
docker compose up -d --build

# 3. 等就绪（首次 PG 全量向量化约 3-10 分钟）
docker compose logs -f app

# 4. 打开
open http://localhost:8088/api/
```

> ✅ 首次启动自动完成：MySQL Flyway 迁移（V1-V19）、PostgreSQL 建表 + **71 篇文档自动向量化**（增量按 doc_hash，重启只同步变更）。

### B. 本地开发

```bash
# 后端（默认 profile=local；依赖本地 MySQL/PG(pgvector)/Redis + mcp-server:8125）
./mvnw spring-boot:run

# 前端热更新
cd front && npm install && npm run dev   # → http://localhost:5173（proxy 到后端 /api）

# 冒烟回归（需后端在跑）
BASE_URL=http://localhost:8088/api ADMIN_API_KEY=xxx bash scripts/e2e-smoke.sh
```

## ⚙️ 环境变量

| 变量 | 必填 | 说明 | 示例 |
|---|---|---|---|
| `OPENAI_API_KEY` | ✅ | 主对话模型 key（OpenAI 兼容接口，模型由环境变量指定） | — |
| `DASHSCOPE_API_KEY` | ✅ | 向量 embedding 与降级备模型共用 key | — |
| `JWT_SECRET` | ✅ | JWT 签名密钥，**≥32 字符随机串**（缺失拒绝启动） | `openssl rand -hex 32` |
| `MYSQL_PASSWORD` | ✅ | MySQL root 密码（首启创建） | `change-me` |
| `PGVECTOR_PASSWORD` | ✅ | PostgreSQL 密码（首启创建） | `change-me` |
| `ADMIN_API_KEY` | ✅ | 管理端点请求头 `X-Admin-Key` | `change-me` |
| `OPENAI_MODEL` / `OPENAI_BASE_URL` | ⭕ | 指定主模型 id / 服务端点 | `<model-id>` |
| `APP_PORT` | ⭕ | 对外端口 | `8088` |

## 📚 文档

从需求到部署的完整决策链沉淀在 `docs/`（含 ADR 与技术选型裁决）：

| 文档 | 内容 |
|---|---|
| [00 · 文档导读](docs/00-文档导读.md) | 阅读路线图 |
| [01 · 产品定位](docs/01-产品定位与需求.md) · [02 · 架构](docs/02-架构设计.md) | 为什么做 / 怎么搭 |
| [03 · ADR 决策记录](docs/03-技术决策记录.md) | 20+ 条技术裁决（父子索引为何放弃、降级链怎么落地…）|
| [07 · 安全与合规](docs/07-安全与合规.md) | 内容安全 / 数据合规 / 域外规则 |
| [08 · 可观测](docs/08-可观测性与发布.md) · [09 · 测试策略](docs/09-测试策略.md) | 指标 trace / 压测 NFR |
| [10 · 部署发布](docs/10-部署发布.md) | Docker 编排 / **CD 流水线** / **灾备运维手册**（备份恢复·Sentry·密钥轮换）/ FAQ |

## 🏗 系统架构

```mermaid
flowchart LR
  U[浏览器 /api SPA+SSE] --> A[Spring Boot 3.4 主应用]
  A --> C{OrchestrationGraph<br/>自动路由}
  C -->|闲聊/情感| N[Normal 节点<br/>RAG 混合检索]
  C -->|工具/长任务| T[Agent 节点]
  C -->|危机信号| G[三层护栏<br/>阻断/转介/安抚]
  N --> KB[(MySQL agentdb<br/>Flyway V1-V19)]
  N --> V[(PG 16 + pgvector<br/>71 篇情感知识库)]
  A --> R[(Redis)]
  A -->|MCP / Streamable HTTP| M[mcp-server<br/>搜索/天气/网页/PDF]
  A -->|降级链| F[主模型故障<br/>自动切换备模型]
```

- **路由**：前端不做"简单/困难"之分——`classify` 在服务端按意图分发，RAG / 工具 / 纯聊天对用户透明。
- **记忆**：对话自动萃取事实 → 记忆档案；沙盘 TA 拥有独立记忆上下文。
- **工具面**：`mcp-server` 独立进程（端口 8125），主应用经 HTTP 调用——工具故障不放大为聊天故障。
- **前端**：Vue 3 + Vite，产物编译进后端静态目录，单 jar 交付。

## 🛡 安全与护栏

区别于一般"AI 聊天壳"，本项目把**边界**做成了产品能力：

| 层 | 机制 |
|---|---|
| 🚨 危机干预（L3） | 自伤 / 轻生类关键词 **全天 24h 硬阻断** + 专业转介话术（含口语变体库，持续补充）|
| 🧊 情绪刹车 | 深夜脆弱时段对"想死 / 撑不下去"等信号自动降级安抚，不机械背台词 |
| 🚷 操控识别 | PUA / 煤气灯话术识别阻断 + 亲密关系暴力内容合规（V17 意图护栏）|
| 🔌 域外拦截 | 非情感领域请求规则化拒绝（零 LLM 成本，防 prompt 越权）|
| 🔐 工程安全 | JWT fail-fast（无兜底密钥）· SSRF / 路径穿越防护 · 降级链故障注入实测 · 并发闸门（满则快速拒绝并告知等待）|

## 📊 质量与评测

自动化资产全部随仓库维护（`scripts/`）：

- 🎯 **检索评测**：45 例 ground-truth，生产基线 **Recall 1.00 / MRR 0.911**
- 🧪 **答案评测**：16 golden × 3 轮（期望内容含多跳）
- 🤖 **Agent 评测**：6 类三层边界（工具调用 / 域外 / 内容安全）全过
- 🚀 **E2E 冒烟**：18 项（注册 → SSE 流式对话 → RAG → Agent → 护栏 → 降级）一键回归
- ⚡ **容量实测**：并发 40 零 429（厂商无硬限流）；闸门默认 24 路（P95 ≈3.5s 平衡点）；SSE 50 路长连接 0 断连

### 工程与运维（2026-09-07 企业级四课）

- ✅ **单测 105**：接口层秒级回归网——Auth(10)/Sandbox(7)/MemoryFacts(7) 契约 + RBAC 拦截器四态，越权 403/404/未登录 401 全部钉死
- 🔄 **CI 三层流水线**（GitHub Actions）：`gitleaks` 密钥扫描（全历史）→ `mvn test` → E2E 18 项（mysql/pgvector/redis service 容器，配置 Secrets 后自动全量回归）
- 📦 **CD**：`git tag v*` → 构建双镜像推送 GHCR → （可选）ssh 自动部署；`TAG=旧版本` 一键回滚
- 📜 **审计日志**：敏感操作（登录/改密/注销/删除）append-only 落表，`/admin/audit` 可查——**谁在几点做了什么都有据可查**
- 🧯 **灾备**：3-2-1 备份脚本（`ops/backup.sh`，7 日轮转）+ 恢复演练手册 + Sentry 错误追踪（填 `SENTRY_DSN` 即启用）
- 🔐 **RBAC**：方法级 `@RequireRole`（拦截器真校验，非前端藏菜单）——后续 ADMIN 能力的权限底座

## 🗂 目录结构

```
├── src/main/java/cn/lwx/lwxaiagent
│   ├── agent/               # Agent 工具面（搜索/天气/网页/PDF…）
│   ├── infrastructure/      # LLM 网关（降级链）、图编排、调度器
│   ├── rag/                 # 混合检索（jieba RRF + pgvector）、知识库同步
│   ├── sandbox/  memory/    # 角色模拟屋 / 记忆萃取
│   ├── harness/             # 护栏（governance）、可观测、评测
│   └── controller/          # REST + SSE API（/api 前缀）
├── front/src               # Vue 3 前端（纸感主题 UI）
├── mcp-server/             # 独立工具面进程（Docker 单独构建）
├── scripts/                # 评测 / 冒烟 / 压测资产
├── docs/                   # 产品 → 架构 → ADR → 部署 全决策链
└── docker-compose.yml      # 一键部署
```

## 🤝 贡献

项目处于 V1 发布期。欢迎：

- 🐛 报 bug（危机词口语变体、RAG 漏召回的 ground-truth 样例尤佳）
- 💡 新人格模板 / 知识库内容
- 📸 界面截图与视觉打磨

提交前请跑 `scripts/e2e-smoke.sh`（18 项需全绿）。

---

<p align="center">
  <sub>每个深夜的纠结，都值得一封信的位置。</sub><br/>
  <a href="#">↑ 回到顶部</a>
</p>
