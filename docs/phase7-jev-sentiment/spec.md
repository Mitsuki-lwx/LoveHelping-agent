# 用 Jev 替换情绪打分 —— 方案（spec）

## S1 接口与配置

- 端点 `POST {app.jev.base-url}/v1/systemone`，`Authorization: Bearer <api-key>`。
- 配置（`app.jev.*`）：`enabled`（默认 **false**）、`api-key`（默认读 `${JEV_API_KEY:}`）、
  `base-url`（默认 `https://api.typesafe.ai`）、`model`（默认 `jev-latest`）、`timeout-ms`（默认 **3000**）。
- **默认关闭**的理由：这是一条外部依赖，未拿到成本报价、未做灰度；关闭时行为与现在**完全一致**。

## S2 问题设计（Score，5 档）

```json
{
  "questions": {
    "mood": {
      "type": "score",
      "instructions": "根据 `user_text`，判断写这段话的人此刻的情绪状态。只评他本人的情绪，不评他人。",
      "criteria": [
        "非常糟糕或处于危机：绝望、想不开、崩溃",
        "低落、难过、沮丧",
        "平静、中立、没有明显起伏",
        "有起色、稍微好一些",
        "明显变好、积极、有希望"
      ]
    }
  }
}
```

- 档位 → 既有分值的映射：`score = round(answer.score) - 2`，落在 **-2..2**，与 DB 语义一致。
- `reason` 取 `answer.legend[round(score)]`（官方返回的档位描述），**不再由模型写句子**。
- 会话打分（`scoreConversation`）把 `state` 传成对象 `{"conversation": "用户：…\nAI：…"}`，
  `instructions` 里用反引号引用字段名（官方推荐的用法）。

## S3 回退与失败语义

| 情况 | 行为 |
| --- | --- |
| `app.jev.enabled=false` | 走**原有** LLM 路径（一字未改） |
| Jev 超时 / 非 200 / 响应缺字段 | 记 WARN **单行**（遵守 ADR-33 降噪），**回退到原 LLM 路径** |
| 两条路径都失败 | 与现状一致：`catch` 住、静默跳过，不写记录 |

**为什么不"失败就写 0 分"**：现状的正则失败会得 0 分（= "平静"），这是一个**伪装成正常数据的错误**。
新实现明确"拿不到就回退"，回退也失败就不写。

## S4 验证

1. **单测**（用本地 `HttpServer` 桩，模式同 `LangfuseTracingTest`）：
   请求体形状（问题 id / 类型 / 档位数）、200 解析、档位→分值的映射边界（0→-2、4→+2）、
   `legend` 取 reason、非 200 / 超时 → 返回空（触发回退）、不发请求当 `enabled=false`。
2. **真实对照**：同一批**人工标注**消息（覆盖 5 档 + 2 条边界），分别用
   `app.jev.enabled=true` 与 `false` 各跑一次，比对与人工标签的一致率、延迟，
   并经 `GET /sentiment/timeline` 确认落库的 `score` 与 `reason`。
3. **E2E**：`scripts/e2e_live.py` 22 项不回归。
