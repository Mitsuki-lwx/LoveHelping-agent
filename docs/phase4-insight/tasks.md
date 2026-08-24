# tasks.md — 对话洞察 实现任务

> **前置条件**：无（ADR-10 无关，非诊断类功能）

---

## Task 1：对话洞察端点

**目标**：`POST /insight/analyze` 接收聊天记录，返回结构化洞察  
**依赖**：无  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/controller/InsightController.java`（新建）
- `src/main/java/cn/lwx/lwxaiagent/service/InsightService.java`（新建）
- `src/main/java/cn/lwx/lwxaiagent/tenant/config/SecurityConfig.java`（注册拦截路径）

**接口**：
```
POST /insight/analyze
Content-Type: application/json
Authorization: Bearer <token>

{
  "conversation": "用户: 你从来不回我消息\n对方: 我在忙\n用户: 你总是这样\n对方: 又要吵吗",
  "sourceType": "PASTE"  // PASTE / SCREENSHOT
}

Response:
{
  "statistics": { "轮次": 4, "用户平均字数": 12, "对方平均字数": 5 },
  "patterns": ["反问句式：2次", "指责句式：1次"],
  "suggestions": ["尝试用'我感到...'代替'你总是...'"],
  "disclaimer": "以上分析基于您提供的对话片段，仅为观察性反馈，不做任何心理或关系诊断。"
}
```

---

## Task 2：LLM 分析 Prompt 设计

**目标**：设计洞察分析用的 system prompt，约束 LLM 只输出观察性反馈，不做诊断  
**依赖**：Task 1  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/service/InsightService.java`

**Prompt 架构**：
```
你是一个专业的沟通模式分析师。请分析用户提供的聊天记录，输出：
1. 基本统计（轮次、字数、冷场次数）
2. 沟通模式（反问句式、指责句式、'你总是'句式、表达感受的次数）
3. 改善建议（可操作、具体、不空泛）

约束：
- 只描述观察到的事实，不做心理诊断
- 不说"你有XX障碍""你是XX型人格"
- 不说"你情商低""你性格有问题"
- 不预测关系走向
- 使用"观察到"而不是"诊断出"
- 给出具体可执行的建议
```

---

## Task 3：截图解析支持

**目标**：用户上传聊天截图，VisionPort 提取文字后同 Task 1 分析  
**依赖**：Task 1, Task 2  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/controller/InsightController.java`
- `src/main/java/cn/lwx/lwxaiagent/service/InsightService.java`

**内容**：
- 复用 `/media/upload` 上传图片，获取 mediaId
- InsightController 接收 `mediaIds` 参数 → VisionPort 提取文字 → 调用 Task 1 分析
- 注意：VisionPort 解析后的文字可能不准确（OCR 识别率），在结果中标注"文字由AI识别，可能有误差"

---

## Task 4：历史记录与趋势对比

**目标**：用户多次分析后，可查看历史记录和趋势对比  
**依赖**：Task 1  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/entity/InsightRecord.java`（新建）
- `src/main/java/cn/lwx/lwxaiagent/mapper/InsightRecordMapper.java`（新建）
- `src/main/resources/db/migration/V16__insight_records.sql`

**接口**：
- `GET /insight/history` — 列出用户所有分析记录
- `GET /insight/{id}` — 查看单条分析详情
- `DELETE /insight/{id}` — 删除分析记录（隐私红线，CAP-6）

**趋势对比**：在 `InsightService` 中对比最近两次分析的沟通模式统计（如：反问句频率从 50% 降到 30%）

---

## Task 5：E2E 验证

**目标**：对话洞察全流程冒烟  
**依赖**：Task 1-4  
**影响文件**：
- `scripts/e2e-smoke.sh`（新增 7.10 洞察段）

**验证内容**：
- 粘贴聊天记录 → 返回结构化洞察（含统计、模式、建议、免责声明）
- 真人对话分析 → 不含诊断性语言（"障碍""障碍型""人格"等词）
- 截图分析 → 返回标注"AI识别可能有误差"
- 历史记录 → 列表可查、详情可看、可删除