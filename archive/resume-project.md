<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>个人简历 - LoveHelping-AI 智能体项目</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, "Microsoft YaHei", sans-serif; background: #f5f5f5; padding: 40px; color: #333; line-height: 1.6; }
  .page { max-width: 900px; margin: 0 auto; background: #fff; padding: 48px 56px; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.08); }
  h1 { font-size: 28px; font-weight: 700; margin-bottom: 4px; }
  .subtitle { font-size: 14px; color: #666; margin-bottom: 24px; }
  hr { border: none; border-top: 1px solid #e5e5e5; margin: 0 0 24px; }
  h2 { font-size: 18px; font-weight: 600; color: #1a1a1a; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
  h2::before { content: ''; display: inline-block; width: 4px; height: 18px; background: #2563eb; border-radius: 2px; }
  .section { margin-bottom: 28px; }
  .project-name { font-size: 20px; font-weight: 700; color: #1a1a1a; margin-bottom: 4px; }
  .tech-stack { font-size: 13px; color: #2563eb; background: #eff6ff; display: inline-block; padding: 4px 12px; border-radius: 6px; margin-bottom: 12px; }
  .project-desc { font-size: 14px; color: #444; margin-bottom: 16px; padding-left: 16px; border-left: 3px solid #2563eb; }
  ul { list-style: none; padding: 0; }
  li { position: relative; padding-left: 20px; margin-bottom: 12px; font-size: 14px; color: #444; }
  li::before { content: '•'; position: absolute; left: 4px; color: #2563eb; font-weight: 700; }
  .highlight { color: #1a1a1a; font-weight: 600; }
  .badge { display: inline-block; background: #e5e7eb; color: #374151; font-size: 11px; padding: 1px 8px; border-radius: 10px; margin-right: 4px; }
  .badge-blue { background: #dbeafe; color: #1d4ed8; }
  .badge-green { background: #d1fae5; color: #059669; }
  .badge-amber { background: #fef3c7; color: #d97706; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
  .card { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px 16px; }
  .card h3 { font-size: 13px; color: #6b7280; margin-bottom: 4px; }
  .card p { font-size: 14px; color: #1a1a1a; font-weight: 500; }
  @media (max-width: 640px) { body { padding: 16px; } .page { padding: 24px; } .grid { grid-template-columns: 1fr; } }
</style>
</head>
<body>
<div class="page">

<h1>XXX</h1>![img.png](img.png)
<div class="subtitle">Java 后端开发 / 全栈工程师 · XXX 年经验</div>
<hr>

<div class="section">
  <h2>项目经历</h2>
  <div class="project-name">LoveHelping-AI · 企业级 AI 情感咨询服务平台</div>
  <div class="tech-stack">Spring Boot 3.4 / Spring AI / DeepSeek V4 / DashScope / Vue 3 / MySQL / PgVector / Milvus / ES / MCP</div>
  <div class="project-desc">
    基于 Manus 智能体架构 + Spring AI，基本覆盖 Harness 框架核心层次（工具接口、上下文管理、智能体编排、可观测性、安全治理），构建多智能体协作系统。集成 ReAct 模式 Agent、混合检索 RAG、自我进化机制，提供有记忆、能反思、可进化的 AI 咨询服务。
  </div>
  <ul>
    <li>
      <span class="highlight">智能体架构设计与流式改造</span>：参考 Manus 智能体思想实现 LoveManus 工具调用 Agent，基于 ReAct 模式编排任务流程，支持网页搜索、图片搜索下载、PDF 生成、知识库检索等自定义工具。实现 Agent 状态机（IDLE→RUNNING→FINISHED/ERROR），覆盖完整生命周期管理。<span class="badge badge-blue">Streaming</span>
      重写智能体流式执行循环，从 <code>.call()</code> 阻塞调用改造为 <code>.stream()</code> + SSE 实时推流，对话体验从整块等待降至逐 token 流式渲染。
    </li>
    <li>
      <span class="highlight">混合检索增强生成</span>：整合 Milvus 向量检索 + Elasticsearch BM25 关键词检索 + PostgreSQL pgvector，通过 RRF 融合排序。实现查询重写与关键词增强，自定义分片策略与上下文增强器，提升检索准确率。
      <span class="badge badge-green">Hybrid RAG</span>
    </li>
    <li>
      <span class="highlight">自我进化闭环</span>：设计 DB 轮询（@Scheduled 5min）替代内存定时器的企业级方案，对空闲/超时对话进行 LLM 反思萃取，形成结构化 skill 经验（名称 + 触发条件 + 具体做法），存入 MySQL + 向量库，下次对话检索注入 system prompt，实现持续进化。
      <span class="badge badge-amber">Self-Evolution</span>
    </li>
    <li>
      <span class="highlight">全链路工程落地</span>：统一响应格式与全局异常处理，MCP 多服务集成（高德地图、百度搜索），Spring AI Advisor 链式编排（日志 / 安全 / 记忆），自定义 DocumentLoader + TokenTextSplitter 实现知识库文档流水线加载，OpenTelemetry 全链路追踪（Langfuse），JWT 多租户隔离，DB 限流，Guardrail 安全治理（提示注入检测）。前后端分离，Vite 构建自动输出至 Spring Boot 静态资源目录，SPA 路由统一 fallback。
    </li>
  </ul>
  <div class="grid">
    <div class="card"><h3>后端</h3><p>Spring Boot / Spring AI / MyBatis-Plus / MySQL / Redis</p></div>
    <div class="card"><h3>AI / 模型</h3><p>DeepSeek V4 / DashScope / 流式 SSE / 多 Agent 协作</p></div>
    <div class="card"><h3>检索</h3><p>Milvus / ES / PgVector / RRF 融合 / 查询重写</p></div>
    <div class="card"><h3>前端 &amp; DevOps</h3><p>Vue 3 / Vite / EventSource / MCP / OpenTelemetry</p></div>
  </div>
</div>

</div>
</body>
</html>
