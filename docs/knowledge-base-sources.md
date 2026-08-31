# 知识库来源与许可清单（knowledge-base-sources）

> 目标：每篇知识库文档可溯源——来源、许可、链接（AGENTS.md 信任红线 / 版权可追溯）。
> 本清单随入库批次更新。

## 第一批（2026-08-29 入库）

| 文档 | 来源 | 许可 | 链接 |
| --- | --- | --- | --- |
| 彩礼与婚约的法律边界 | 中华人民共和国民法典·婚姻家庭编 | 立法文件,不适用著作权（可自由使用） | 国家法律法规数据库 |
| 离婚冷静期：30天的制度与心态 | 同上（民法典 §1077） | 立法文件 | 同上 |
| 夫妻共同财产与个人财产 | 同上（民法典 §1062/1063） | 立法文件 | 同上 |
| 家庭暴力的识别与求助 | 《反家庭暴力法》+ 民法典婚姻家庭编 | 立法文件 | 同上 |
| 危险信号：煤气灯效应、爱情轰炸与间歇性强化 | 开源仓库 she-love-me（概念提炼转写） | MIT | https://github.com/863401402/she-love-me |
| 末日四骑士：关系里最伤人的四种沟通模式 | partner-skill 仓库概念 + Gottman 公开理论 | MIT（转写） | https://github.com/NatalieCao323/partner-skill |
| 关系健康度自检：六个维度 | partner-skill 仓库概念（转写） | MIT | https://github.com/NatalieCao323/partner-skill |

## 第二批（2026-08-29 追加）

| 文档 | 来源 | 许可 | 链接 |
| --- | --- | --- | --- |
| 人际吸引的三因素 | OpenStax Psychology 2e（理论自撰，未照搬原文） | CC BY 4.0 | https://openstax.org/details/books/psychology-2e |
| 爱情三角论：激情亲密与承诺 | 同上（Sternberg 公开理论） | CC BY 4.0 | 同上 |
| 亲密关系的维系 | 同上 | CC BY 4.0 | 同上 |

> 说明：OpenStax 正文在环境内为 SPA 无法直接抓取，采用「公开理论 + 自撰改写 + 署名（CC BY 4.0）」落地；维基条目待网络可达后由脚本抓取。

## 记录说明

- **立法文件**：依《著作权法》第五条不适用著作权；文档头标注"法律常识整理，不构成法律意见"。
- **MIT 仓库**：内容经知识化转写（剥离技能参数、去操控化），概念署名于文档头。
- **每篇文档头部**均带「来源 + 许可」注释，与索引 metadata（filename）对应，可溯源。

## 待引入（第二批 / 条件触发）

- **OpenStax Psychology 2e**（CC BY 4.0，可商用）——关系心理学章节抽取翻译改写，源自 https://openstax.org/details/books/psychology-2e
- **中文维基百科关系类条目**（CC BY-SA 4.0）——抓取脚本已备 `scripts/kb-fetch/fetch_wikipedia.py`，**网络环境不可达，待可达后执行**
- CPED / PsyQA（申请制，商用未证）、SoulChat（NC）、知乎类（第三方版权存疑）、Gottman 博客（版权所有）——**明确不纳入**