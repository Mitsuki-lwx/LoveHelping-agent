# -*- coding: utf-8 -*-
"""生成修改后的简历 PDF"""
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm, mm
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, HRFlowable, KeepTogether
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.pdfmetrics import registerFontFamily

# ── Font Registration ──
pdfmetrics.registerFont(TTFont('MSYH', 'C:/Windows/Fonts/msyh.ttc', subfontIndex=0))
pdfmetrics.registerFont(TTFont('MSYHBD', 'C:/Windows/Fonts/msyhbd.ttc', subfontIndex=0))
registerFontFamily('MSYH', normal='MSYH', bold='MSYHBD')

pdfmetrics.registerFont(TTFont('SimHei', 'C:/Windows/Fonts/simhei.ttf'))
registerFontFamily('SimHei', normal='SimHei', bold='SimHei')

# ━━ Color Palette ━━
ACCENT       = colors.HexColor('#c7354e')
TEXT_PRIMARY  = colors.HexColor('#1c1b19')
TEXT_MUTED    = colors.HexColor('#8a867e')
BG_SURFACE   = colors.HexColor('#e0ddd8')
BG_PAGE      = colors.HexColor('#f2f0ed')

# ── Styles ──
name_style = ParagraphStyle(
    'ResumeName', fontName='MSYHBD', fontSize=22,
    leading=28, alignment=TA_CENTER, spaceAfter=2,
    textColor=TEXT_PRIMARY
)
contact_style = ParagraphStyle(
    'ResumeContact', fontName='MSYH', fontSize=10,
    leading=14, alignment=TA_CENTER, textColor=TEXT_MUTED,
    spaceAfter=10
)
section_title_style = ParagraphStyle(
    'ResumeSectionTitle', fontName='MSYHBD', fontSize=13,
    leading=18, spaceBefore=10, spaceAfter=4,
    textColor=ACCENT
)
sub_section_style = ParagraphStyle(
    'ResumeSubSection', fontName='MSYHBD', fontSize=10.5,
    leading=15, spaceBefore=6, spaceAfter=2,
    textColor=TEXT_PRIMARY
)
body_style = ParagraphStyle(
    'ResumeBody', fontName='MSYH', fontSize=9.5,
    leading=14, spaceAfter=1.5, wordWrap='CJK',
    textColor=TEXT_PRIMARY
)
bullet_style = ParagraphStyle(
    'ResumeBullet', fontName='MSYH', fontSize=9.5,
    leading=14, leftIndent=14, bulletIndent=0,
    spaceBefore=1, spaceAfter=1, wordWrap='CJK',
    textColor=TEXT_PRIMARY
)
project_title_style = ParagraphStyle(
    'ProjectTitle', fontName='MSYHBD', fontSize=11,
    leading=15, spaceBefore=6, spaceAfter=1,
    textColor=TEXT_PRIMARY
)
project_meta_style = ParagraphStyle(
    'ProjectMeta', fontName='MSYH', fontSize=9,
    leading=13, textColor=TEXT_MUTED, spaceAfter=3,
    wordWrap='CJK'
)
highlight_style = ParagraphStyle(
    'Highlight', fontName='MSYHBD', fontSize=9.5,
    leading=14, spaceBefore=1, spaceAfter=1, wordWrap='CJK',
    textColor=TEXT_PRIMARY
)
highlight_body_style = ParagraphStyle(
    'HighlightBody', fontName='MSYH', fontSize=9.5,
    leading=14, leftIndent=14, spaceBefore=0.5, spaceAfter=1, wordWrap='CJK',
    textColor=TEXT_PRIMARY
)
edu_style = ParagraphStyle(
    'Education', fontName='MSYHBD', fontSize=10.5,
    leading=14, spaceAfter=1, textColor=TEXT_PRIMARY
)
edu_meta_style = ParagraphStyle(
    'EducationMeta', fontName='MSYH', fontSize=9.5,
    leading=13, textColor=TEXT_MUTED, spaceAfter=4, wordWrap='CJK'
)

# ── Helpers ──
def section_header(title):
    return KeepTogether([
        Paragraph(f'<b>{title}</b>', section_title_style),
        HRFlowable(width='100%', thickness=0.8, color=ACCENT,
                    spaceBefore=0, spaceAfter=4),
    ])

def bullet(text):
    return Paragraph(f'\u2022 {text}', bullet_style)

def project_entry(title, tags, description, highlights):
    """项目经验条目，格式优化"""
    elements = [
        Paragraph(f'<b>{title}</b>', project_title_style),
        Paragraph(tags, project_meta_style),
    ]
    elements.append(Paragraph(description, body_style))
    elements.append(Spacer(1, 2))
    for h in highlights:
        elements.append(Paragraph(h, highlight_style))
    elements.append(Spacer(1, 4))
    return elements

# ── Output ──
output_path = os.path.join('D:', os.sep, 'Downloads', 'Java_AI_Agent_简历_修改版.pdf')

doc = SimpleDocTemplate(
    output_path, pagesize=A4,
    leftMargin=1.5*cm, rightMargin=1.5*cm,
    topMargin=1.5*cm, bottomMargin=1.5*cm,
    title='Resume - 林文熙',
    author='Z.ai', creator='Z.ai'
)

story = []

# ── Header ──
story.append(Paragraph('<b>林文熙</b>', name_style))
story.append(Paragraph(
    '13427004362  |  2961053856@qq.com',
    contact_style
))

# ── 教育背景 ──
story.extend(section_header('教育背景'))
story.append(Paragraph('<b>华南师范大学</b>', edu_style))
story.append(Paragraph('软件工程  |  本科  |  2024.09 - 至今', edu_meta_style))

# ── 专业技能 ──
story.extend(section_header('专业技能'))

# 编程语言
story.append(Paragraph('<b>编程语言</b>', sub_section_style))
story.append(bullet(
    '熟练掌握 Java SE，深入理解集合框架、多线程并发编程、JVM 内存模型与调优、反射机制、动态代理等高级特性，具备扎实的面向对象设计与工程实践能力'
))
story.append(bullet(
    '熟练掌握 Python，具备 AI 应用开发与脚本自动化实战经验，能够独立运用 LangChain、Spring AI 等框架进行 Agent 应用开发'
))
story.append(bullet(
    '熟悉 C/C++ 基础语法，用于底层逻辑理解与算法实现'
))

# 后端与框架
story.append(Paragraph('<b>后端与框架</b>', sub_section_style))
story.append(bullet(
    '熟练运用 Spring Boot 进行企业级应用开发，具备 RESTful API 设计、统一异常处理、参数校验、配置管理等工程化能力'
))
story.append(bullet(
    '熟练使用 MyBatis &amp; MyBatis-Plus 进行数据持久层开发，掌握动态 SQL、分页插件、代码生成器等高级用法'
))
story.append(bullet(
    '掌握 Spring Cloud 核心组件（服务注册发现、配置中心、网关、负载均衡），具备微服务架构基础认知'
))

# AI / Agent 开发
story.append(Paragraph('<b>AI / Agent 开发</b>', sub_section_style))
story.append(bullet(
    '具备 Spring AI 框架实战经验，熟练运用 Advisor 链式编排、DocumentLoader、TokenTextSplitter 等组件构建 RAG 知识库流水线'
))
story.append(bullet(
    '深入理解 Agent 架构设计，掌握 ReAct 推理-行动模式、工具调用（Tool Calling）、多智能体编排，能够独立设计和实现多 Agent 协作系统'
))
story.append(bullet(
    '熟悉 LangChain 框架，掌握 Prompt Engineering、SSE 流式推流、RRF 融合排序等 AI 应用工程化技术'
))
story.append(bullet(
    '掌握 RAG 混合检索架构设计，能够整合向量检索与关键词检索实现高精度知识召回'
))
story.append(bullet(
    '了解 MCP（Model Context Protocol）协议，具备多服务集成与模型上下文管理经验'
))

# 数据存储与检索 — 删除最后两条（ES 和 pgvector/Milvus）
story.append(Paragraph('<b>数据存储与检索</b>', sub_section_style))
story.append(bullet(
    '熟练使用 MySQL 进行关系型数据建模与查询优化，具备复杂业务数据设计能力'
))
story.append(bullet(
    '熟练运用 Redis 进行缓存设计、分布式锁、会话管理等高并发场景开发'
))

# 工程化与运维 — 全部改为"了解"
story.append(Paragraph('<b>工程化与运维</b>', sub_section_style))
story.append(bullet(
    '了解 OpenTelemetry 全链路可观测性方案，能够运用 Langfuse 实现 LLM 应用的链路追踪与性能分析'
))
story.append(bullet(
    '了解 Docker 容器化基础，具备应用容器化部署经验'
))
story.append(bullet(
    '了解 Linux 基础操作，能够进行服务部署与环境配置'
))
story.append(bullet(
    '了解企业级安全方案，包括 JWT 多租户隔离、DB 限流、Guardrail 安全治理（提示注入检测）等'
))

# ── 项目经验 ──
story.extend(section_header('项目经验'))

story.extend(project_entry(
    'CodeForge — 终端 AI 编程助手',
    '技术栈：Python / ReAct / MCP / Multi-Agent / Prompt Cache / Skill / OpenTelemetry / Langfuse  |  个人项目',
    '终端 AI 编程助手，基于可插拔 ReAct 循环与 Plan Mode 双模式驱动 LLM 自主完成编程任务。采用「交互/引擎/工具/记忆/安全/可观测」分层架构，支持 Anthropic、OpenAI 双协议、MCP 工具扩展、Skill 技能包、跨会话记忆、两层上下文压缩、模型路由与运行时切模，以及多 Agent 并行协作。',
    [
        '<b>可插拔 Agent 循环：</b>将 AgentLoop 抽象为协议接口，默认 ReAct 循环可通过配置或启动参数替换为自定义循环；引入阶段状态机与结束原因，max-tokens 触发后粘性续跑，长任务无缝衔接。',
        '<b>多协议 LLM 层 + Prompt Cache：</b>统一 Anthropic、OpenAI 两套流式响应协议为同一事件接口，新增供应商只需适配一个接口；断点在两套协议下均生效，显著降低重复上下文 token 成本。',
        '<b>两层上下文压缩：</b>设计「工具结果落盘 + LLM 摘要」两层渐进式压缩策略，自动对齐 Function Calling 的调用配对约束，支持数小时连续编程会话不丢失上下文。',
        '<b>确定性记忆去重：</b>以标题归一化 + 相似度匹配做 upsert 覆盖式去重，不依赖 LLM 判断，避免 LLM 去重的非确定性；覆盖时保留原始 created、更新时间戳与索引一致性，记忆积累稳定可复现。',
        '<b>多 Agent 协作 + Worktree 隔离：</b>Lead/Coordinator 将复杂任务拆分给多个子 Agent 并行处理，基于 Git Worktree 实现文件级隔离避免编辑冲突；子 Agent 前台同步收敛调度，消除 Lead 轮询空转，单次任务墙钟时间显著下降。',
        '<b>子代理级可观测：</b>基于 OpenTelemetry 三支柱（Logs/Metrics/Traces）+ Langfuse 上报，为每个子 Agent 独立打点 span 归属，多 Agent 场景下可精确定位单 Agent 的耗时与行为。',
    ]
))

story.extend(project_entry(
    'LoveHelping — AI 情感咨询服务平台',
    '技术栈：Spring Boot 3.4 / Spring AI / DeepSeek V4 / DashScope / Vue 3 / MySQL / PostgreSQL+pgvector / Milvus / Elasticsearch / Redis / MCP / OpenTelemetry  |  个人项目',
    '基于 Manus 智能体架构思想与 Spring AI 框架，构建覆盖 Harness 框架核心层次（工具接口、上下文管理、智能体编排、可观测性、安全治理）的多智能体协作系统。集成 ReAct 模式工具调用 Agent、混合检索 RAG、自我进化机制，提供具备记忆能力、反思能力与持续进化能力的 AI 情感咨询服务。',
    [
        '<b>智能体核心架构设计：</b>参考 Manus 智能体思想，设计并实现 LoveManus 工具调用 Agent，基于 ReAct 模式编排任务流程，支持网页搜索、图片搜索下载、PDF 生成、知识库检索、终端命令等自定义工具灵活扩展。实现 Agent 状态机（IDLE→RUNNING→FINISHED/ERROR），覆盖从初始化、工具选择、执行到结果回写的完整生命周期管理。',
        '<b>流式执行引擎改造：</b>将智能体执行循环从 .call() 阻塞调用重构为 .stream() + SSE 实时推流架构，对话响应体验从整块等待（平均 8-12s）优化为逐 token 流式渲染（首 token 延迟降至 200ms 以内），用户感知响应速度提升约 95%。',
        '<b>混合检索系统构建：</b>整合 Milvus 向量语义检索与 Elasticsearch BM25 关键词检索，通过 RRF 融合排序算法实现双路召回加权。实现查询重写与关键词增强策略，自定义分片策略与上下文增强器，检索 Top-5 命中率提升至 85%+，相比单一向量检索提升约 30%。',
        '<b>自我进化闭环设计：</b>采用 DB 轮询（@Scheduled 5min）替代内存定时器，构建企业级空闲/超时对话反思调度方案。对空闲或超时对话触发 LLM 反思萃取，形成结构化 skill 经验（名称 + 触发条件 + 具体做法），持久化至 MySQL + 向量库。下次对话时自动检索注入 system prompt，实现 Agent 能力的持续迭代进化。',
        '<b>全链路工程化落地：</b>统一响应格式与全局异常处理，通过 MCP 协议实现多服务标准化集成（高德地图、百度搜索）。基于 Spring AI Advisor 链式编排构建请求处理管线，自定义 DocumentLoader + TokenTextSplitter 实现知识库文档流水线加载。集成 OpenTelemetry 全链路追踪（Langfuse），实现 LLM 调用链路可视化与性能瓶颈定位。落地 JWT 多租户隔离、DB 限流、Guardrail 安全治理（提示注入检测），保障系统安全与稳定性。',
        '<b>前端全栈开发：</b>基于 Vue 3 + Vite 构建前端项目，通过 EventSource 实现 SSE 实时消息接收。配置 SPA 路由统一 fallback，实现单页应用在 Spring Boot 容器下的无缝部署。前后端分离架构，Vite 构建自动输出至 Spring Boot 静态资源目录，集成 Knife4j 接口文档自动生成。',
    ]
))

# ── Build ──
doc.build(story)
print(f'Resume generated: {output_path}')
