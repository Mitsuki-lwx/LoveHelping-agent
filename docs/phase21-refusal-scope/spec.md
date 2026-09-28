# phase21 · spec —— 范围护栏可切换 + 复验

> 任务拆分见 `tasks.md`；验收判据 J1~J7 见 `tasks.md` §6（实现前写死）。
> 本文件给**技术方案与数据约定**。

## 1. 配置项

```yaml
app:
  chat:
    # 范围护栏措辞版本：
    #   adjacent-help（默认）= 情感相邻议题先帮再接回；纯事务性请求才拒
    #   strict              = 修复前措辞：一律对"明显无关请求"礼貌拒绝（仅作对照臂）
    scope-wording: adjacent-help
```

- 默认值 **`adjacent-help`**（= 当前 `5d8c4c3` 的线上行为，**默认值不变即行为不变**）
- `strict` 是**对照臂**，启动日志标 `[对照臂]`，文档写明不建议生产启用
- 值非法 → **启动失败**（不静默回退默认）。理由：本仓已踩过"配置字面值优先于环境变量"，
  一个被静默忽略的开关会让对照臂"以为切了其实没切"，臂失效且无痕。

## 2. 代码改动

### 2.1 `ChatExecutor`

现状：scope 段是 `SYSTEM_PROMPT` 拼接常量里的一段（`:112-124`）。

改为：把 scope 段抽成两个 `private static final String`，按注入的 `scopeWording` 选其一。

```java
/** 修复前措辞（ADR-48 之前的原文）——仅作对照臂，会无谓拒答。 */
private static final String SCOPE_STRICT = """...""";

/** 修复后措辞（A/B 两类边界，5d8c4c3 引入）——生产默认。 */
private static final String SCOPE_ADJACENT_HELP = """...""";

private final String scopeWording;

public ChatExecutor(..., @Value("${app.chat.scope-wording:adjacent-help}") String scopeWording) {
    this.scopeWording = scopeWording;
    this.systemPrompt = SYSTEM_PROMPT_HEAD
            + switch (scopeWording) {
                case "strict"           -> SCOPE_STRICT;
                case "adjacent-help"    -> SCOPE_ADJACENT_HELP;
                default -> throw new IllegalStateException(
                    "app.chat.scope-wording 取值非法：" + scopeWording
                    + "，可选 adjacent-help | strict");
              }
            + SYSTEM_PROMPT_TAIL;
    log.info("[scope-wording] 生效范围护栏措栏 = {}（strict 为对照臂，线上不建议启用）", scopeWording);
    ...
}
```

⚠️ **构造器签名变更**：`ChatExecutor` 的构造器有 10 个参数且被 Spring 装配 +
可能有测试直接 new。改签名会让所有 `new ChatExecutor(...)` 编译失败 →
**这是有意的**（编译期暴露全部调用点，比漏一个运行期 NPE 强）。
单测里的直接构造要补上这个参数。

### 2.2 日志自报

```
[scope-wording] 生效范围护栏措辞 = adjacent-help
```

- 装置靠这行**证明臂真的切了**（J7）—— 不能靠"我 export 了 ARM_W"（本仓纪律：
  **profile yml 字面值优先于环境变量**，"我 export 了" ≠ "它生效了"）
- strict 时额外打 `[对照臂]` 前缀

### 2.3 三处 yml

`src/main/resources/application.yml` + profile yml 各加：

```yaml
  chat:
    scope-wording: ${APP_CHAT_SCOPE_WRITING:adjacent-help}
```

⚠️ 用 `${ENV:default}` 形态，与本仓其它配置一致（phase20 H1 纪律：yml 不写具体值）。

## 3. 复验装置

### 3.1 `scripts/probe_refusal_scope.py`

已在 `5d8c4c3` 之外的当前工作区新建（phase21）。要点：

| 要素 | 决定 | 理由 |
| --- | --- | --- |
| 三组问题 | G1 相邻身心 / G2 关系域内 / G3 纯事务 | 只测 G1 无法排除"把该拒的也放开" |
| chatId | **每轮 `uuid4`** | 固定 id 会撞唯一约束（ADR-47）/ 恢复 checkpoint（phase17） |
| 分类 | 长度阈值 150 + 拒答信号词 → 四标签 | 粗筛，**必须人工读原文**（第 13 条） |
| 原文 | 每轮全文落 `outputs/refusal-<arm>-<stamp>.json` | 同上；且**跨臂可比** |
| 报数 | 「去重回答数」+ 轮数 + 分类计数 | 轮数不能代表多样性（ADR-48 报的是去重数） |
| 期望 | 每组带 `expect`（substantive / refusal），脚本自动给「符合期望/疑似偏离」 | 但**不自动判通过** —— 结论由人读 |

⚠️ **分类器不是判据**。`REFUSAL_MARKERS` 是关键词匹配，会有假阳/假阴
（例如正文里引用「我不了解」这句来解释某个概念 → 误判拒答）。
**J1~J4 的最终判定必须读原文。**

### 3.2 `scripts/run_phase21_refusal.sh`（canonical，入仓）

- 两臂循环，`ARM_W` 注入 `app.chat.scope-wording`
- 启动后先 grep `[scope-wording]` 自报，再跑探针
- 汇总打印每组 `n / 去重 / 中位长度 / <150 字计数 / 分类直方图`
- ⛔ **只跑成 1 臂时汇总显式打印「不能得结论」**

## 4. 复现方式

```bash
# 两臂对照（生产环境之外的操作，勿在正式部署跑）
cd /d/java/lwx-ai-agent
REPEAT=6 ARMS="strict adjacent-help" bash scripts/run_phase21_refusal.sh

# 只测现状（不切臂）
REPEAT=6 ARMS="adjacent-help" bash scripts/run_phase21_refusal.sh
```

## 5. 文档改动

| 文件 | 改动 |
| --- | --- |
| `docs/03-技术决策记录.md` | 追加 **ADR-53**（编号接 ADR-52） |
| `docs/03-技术决策记录.md` | ADR-48「已知限制（补记）」段加**更正块**：原文保留 + 指向 ADR-53 |
| `docs/11-环境与装置.md` §6 | 登记新装置（`probe_refusal_scope.py` / `run_phase21_refusal.sh`） |
| `.workbuddy/memory/2026-09-28.md` | 追加本轮结论 |
| `README.md` | 若有配置项清单则同步 `scope-wording` |

⚠️ **不改写 ADR-48 的历史结论**（工作纪律）——只加更正块，注明适用范围与被谁取代。
