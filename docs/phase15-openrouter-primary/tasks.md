# Phase 15 · OpenRouter 转主链 + bigmodel 降为最低等级 + 修 attempt-timeout

> 立项：2026-09-27　性质：**修正**（不是新功能）　对应 ADR-48
> 前置：`docs/phase14-model-routing/`（已写但暂缓，本 phase 不动模型路由逻辑）

## 0. 为什么现在做

三件事撞在一起，且都是**已实测**的事实，不是推测：

| # | 事实 | 来源 |
|---|---|---|
| 1 | `glm-4-flash` 生成速率仅 **11~14 tok/s**，单次调用 21~41s，而 `attempt-timeout-ms=25000` | `scripts/probe_llm_timing.py` 两轮独立复现 |
| 2 | bigmodel 端点**根本没有 prompt cache**（usage 里无任何 cache 字段） | `scripts/probe_prompt_cache.py` |
| 3 | OpenRouter `qwen/qwen-plus` **51 tok/s**、cache 命中 **7/8**、并发 96 零 429 | `scripts/probe_openrouter*.py`（本轮） |

第 1 条的后果链是硬的：正常回答 300~500 tokens → 25s 装不下 → 超时 → 重试 3 次 → 降级 → **用户拿到降级文案**。
这不是"可能慢"，是**已经在发生**。

## 1. 目标

- [ ] T1　主链端点切到 OpenRouter（`qwen/qwen-plus`）
- [ ] T2　bigmodel 从 primary 降为**最低一级**降级目标
- [ ] T3　`attempt-timeout-ms` 按**实测 tok/s** 定值，不拍数
- [ ] T4　降级链从两级扩到三级（ADR-23 不变式不许动）
- [ ] T5　凭据全部走环境变量，仓库零明文

## 2. 不做什么（边界）

- ❌ **不做模型路由**（phase14 的智能选型）——本轮是"换供应商 + 修 timeout"，不是"按需选型"
- ❌ 不改 RAG / 检索 / 切块任何参数
- ❌ 不改 `VisionChatClient`（它绕过网关，是独立缺陷，另立 phase）
- ❌ 不动 Jev（用户说"直接开"，但**先修 timeout**——见 §4 排序理由）
- ❌ 不删 `deepSeekChatModel` bean（降级链仍需要它作中间级）

## 3. 关键约束

1. **ADR-23 不变式**：`LlmGateway` 是唯一重试所有者。降级链扩展**只在网关内部**，
   路由层/业务层不感知、不自己重试。
2. **闸门值要重估**：`max-concurrent-calls=24` 是按 bigmodel 上限 24 定的。
   OpenRouter 实测 96 零 429 → 这个数现在**过保守**。但改它属于容量决策，
   本 phase **只做实测记录，不改值**（改要另立 ADR，见 §5）。
3. **prompt cache 命中靠前缀稳定**：本轮不动 prompt 内容（动就毁掉已命中的前缀）。

## 4. 排序：为什么先修 timeout 再开 Jev

Mitsuki 要求"先修"。理由不是优先级偏好，是**依赖关系**：

- timeout 坏了 → 正常长回答的用户**已经在拿降级文案**。这是**当前正在损害用户**的缺陷。
- Jev 是增强，开它会**增加**一次外部调用 → 在 timeout 还没修好时开 Jev，
  等于往一个已经超时的链路里再加延迟。

所以顺序是：换端点 → 修 timeout → 验证 → 再开 Jev。

## 5. 遗留（不在本 phase，但必须记）

- **闸门 24 是否要上调**：OpenRouter 实测 96 零 429，24 明显保守。但上调涉及
  "多实例时如何折算容量"，与 ADR-23"单机不冒充跨实例容量"相关，需另立 ADR。
- **VisionChatClient 绕过网关**：无闸门/无熔断/无 token 计量，独立缺陷。
- **模型路由（phase14）**：本 phase 完成后，"换更强模型"的收益需要重新评估
  （原假设"省 token"已部分成立——OpenRouter 有 cache）。
