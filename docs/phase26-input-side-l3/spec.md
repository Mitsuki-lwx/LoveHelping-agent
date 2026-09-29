# phase26 · spec：`context_exclude` 豁免维度

## 数据模型

```sql
ALTER TABLE guardrail_rule ADD COLUMN context_exclude VARCHAR(500) DEFAULT NULL;  -- V28
```

| 列 | 语义 | 引入 |
|---|---|---|
| `scope` | **哪一侧**（INPUT / OUTPUT / BOTH） | V25 / ADR-55 |
| `context_exclude` | **什么前提下不算命中**（REGEX，可空） | V28 / ADR-59 |

### 判定顺序（`GuardrailRuleService.check`）

```
for each rule:
  if scope 不适用 → skip
  if 规则命中:
      if context_exclude 存在 且 也 find() 命中 → continue   # 本规则不算命中
      else → 参与 maxLevel 计算
```

⛔ **豁免不做全局短路**：只 `continue` 本规则，其余规则照常判。
否则「他抛弃了我，我想死」这类**同时含第三人称主语与第一人称风险**的输入会被整个放过。

⛔ **失败方向必须偏严**：`context_exclude` 正则编译失败 → 降级为"不豁免"（仍拦）+ WARN。
写坏的豁免绝不能让规则静默失效。已由 `GuardrailContextExcludeTest.invalidExcludeFailsSafe` 钉住。

## 为什么独立一列（而不是把豁免写进 `pattern`）

15 条 `self_harm` 是 **KEYWORD 行**（`Pattern` 为 null，走 `text.contains`）。
要表达"除第三人称框架外"就得把每条都改成带 lookaround 的正则：

- Java 的变长 lookbehind 有界但难审，15 条各写一遍 = 15 个出错点；
- 改完读代码的人**看不出**"这条例外是为了修哪个用户场景"。

独立一列让"什么情况下**不**拦"**可单独审计、可单独单测**，且规则表本身就说明了前提。

## 两条豁免式（逐字，与 V28 一致）

### 1) `self_harm`（第三人称转述框架）

```
(?:朋友|对象|男朋友|女朋友|男友|女友|老公|老婆|前男友|前女友|老公公|他|她|家人|家里人|父母|妈|爸|
   同事|同学|室友|哥们|闺蜜|兄弟|姐妹|姐姐|妹妹|哥哥|弟弟|孩子|女儿|儿子)们?的?
(?:说|讲|告诉|提到|提过|觉得|认为|想|要|打算|准备|好像|似乎|总是|总|老是|经常|天天|成天|动不动|一直|自己|最近|也|还){0,3}
(?:自己)?
(?:自杀|自残|想死|轻生|割腕|跳楼|不想活|活不下去|撑不下去|不想再醒|离开这个世界|伤害自己|结束自己|了结自己|结束生命)
```

**为什么是「紧邻框架」而不是「全句含第三人称就豁免」**：`{0,3}` 限定主语与风险词之间
只容得下少数言说/程度词 —— 「他抛弃了我，我想死」里"他"到"我想死"隔着动词与逗号，
**不满足**该框架 → 仍拦（判据 J3）。

### 2) `illegal` 的 `诈骗`（受害 / 防范框架）

```
(?:被|遭|遭到|遭遇|遇到|碰到|碰上|差点被|差点儿被|反|防|识别|警惕|预防)[^。！？\n]{0,4}诈骗
```

覆盖「我被诈骗了」「家人被诈骗了」「怎么防诈骗」；「教我诈骗别人」无豁免框架 → 仍拦。

## 实测踩到的坑（值得记住）

**"总"这个单字漏在语义词表里** → 「我对象最近**总**说活不下去」排除失效 → 离线标定
立刻显示 `6/7`，补 `总|老是|经常|天天|成天|动不动` 后 `7/7`。

📌 语义词表**永远会漏**，所以判据装置必须能立刻把漏的**那一条**指出来（脚本逐条打 ✅/❌ +
"旧式拦 → 新式放行"的漂移标注），而不是只给一个总数。总数 6/7 与 7/7 的差别在于
**是哪一条**没修好 —— 这正是"分类器不是判据、必须读原文"的同一族教训。

## 回滚

```sql
ALTER TABLE guardrail_rule DROP COLUMN context_exclude;   -- 回到 V27 语义
```

Java 侧：`CompiledRule.exclude` 为 null 时行为与加列前**逐字一致**
（`GuardrailContextExcludeTest.noExcludeBehavesAsBefore` 钉住），故先回滚 DB 即安全。

## 范围评估影响

`GuardrailRuleService.load()` 的自报行 `Guardrail rules loaded: N enabled (INPUT-only=..)` 不变 ——
V28 **只加列不增删规则**，实测仍是 `46 enabled (INPUT-only=21, OUTPUT-only=3, BOTH=22)`
（与 ADR-56 记录逐字一致）。
