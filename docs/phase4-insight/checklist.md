# checklist.md — 对话洞察 验收清单

> 每一项必须可执行、可勾选。以 curl / SQL / grep 为准。

---

## 端点

- [ ] `POST /insight/analyze` 存在，返回 200
- [ ] 请求体无 token 时返回 401
- [ ] 路径 `/insight/**` 已注册到 SecurityConfig 拦截器

## 分析输出

- [ ] 输出包含 `statistics` 字段（轮次、字数）
- [ ] 输出包含 `patterns` 字段（至少 1 条模式描述）
- [ ] 输出包含 `suggestions` 字段（至少 1 条建议）
- [ ] 输出包含 `disclaimer` 字段（免责声明）
- [ ] 输出中不含以下关键词：`障碍`、`人格`、`障碍型`、`诊断`、`依恋`

**验证命令**：
```bash
curl -s -X POST "$B/insight/analyze" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"conversation":"用户: 你从来不回我消息\n对方: 我在忙\n用户: 你总是这样","sourceType":"PASTE"}'
```

## 截图分析

- [ ] 上传截图后调用 `/insight/analyze?mediaIds=xxx` 可以工作
- [ ] 结果标注"文字由AI识别，可能有误差"

## 历史记录

- [ ] `V16__insight_records.sql` 已执行（`SELECT COUNT(*) FROM insight_record`）
- [ ] `GET /insight/history` 返回列表
- [ ] `GET /insight/{id}` 返回详情
- [ ] `DELETE /insight/{id}` 返回 200
- [ ] 删除后 `GET /insight/{id}` 返回 404

## 隐私红线

- [ ] 分析结果仅用户本人可见（他人 token 访问 403）
- [ ] 注销时 insight_record 级联删除（DeleteService 对接）
- [ ] 免责声明在每个分析结果中附带

## 文档同步

- [ ] `docs/03-技术决策记录.md` 无变化（ADR-10 仍待定，未涉及诊断）
- [ ] `docs/11-软件需求规格说明书.md` 无需变更（对话洞察不在 SRS 核心 14 项中）
- [ ] `docs/06-核心流程设计.md` §8 可附加对话洞察流程说明