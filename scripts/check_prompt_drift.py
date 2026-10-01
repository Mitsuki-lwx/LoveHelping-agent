#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提示词副本漂移检测（phase33 · R1，ADR-73）。

## 它解决什么

SpotBugs 的 `HSC_HUGE_SHARED_STRING_CONSTANT` 报 `ChatExecutor.SYSTEM_PROMPT`：
5662 字符常量、**在另外 3 个 class 文件里重复**。⛔ 但那条报告只说"有副本"，
**不说是哪几份、也不说它们本该一样** —— 真要修（提示词集中化）属**行为风险改动**，
本仓此前就栽过"编译期常量 + 断言判旧文本"的坑。

⇒ 所以**先把防护做成便宜的装置**，再做集中化：
* 一旦有人把某档 scope 提示词**复制**到别处，两份会开始各自演化 ⇒ 检测要报红；
* 真正该做的"集中化"留到单独一轮（有对照实测兜底）。

## 判定口径（诚实说明它能做什么、不能做什么）

⛔ **不能**：证明四个 scope 常量"内容一致"（它们本来就**不该**一致 —— 是四个不同的对照臂）。
⛔ **不能**：发现"有人改了提示词但忘了改文档"这类语义漂移。
✅ 能：发现**同一段文本被复制成多份字面量**（漂移的物理前提）。
✅ 能：守住 prompt 的**结构契约** —— 五个片段（HEAD / 四档 SCOPE / TAIL）齐全、
     四档都存在、`SYSTEM_PROMPT` 仍由三段拼成（不是又变成一坨整体常量）。

⛔ **豁免的设计性重复**：`【角色与领域边界（Scope）】你是恋爱/关系顾问。` 这句抬头在四档里
各出现一次（归一空白后 26 字符）—— 那是**四档刻意共用的开场**，不是复制漂移。
本装置按"≥ 120 字符的完全相同文本块"判定，短于此不报。

用法：python scripts/check_prompt_drift.py [--min-len 120]
退出码 0 = 通过；1 = 发现重复副本（漂移风险）或结构契约破损。
"""
import argparse
import pathlib
import re
import sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

# 五段式结构契约：HEAD + 四档 SCOPE + TAIL
REQUIRED_CONSTANTS = [
    "SYSTEM_PROMPT_HEAD",
    "SCOPE_ADJACENT_HELP",
    "SCOPE_BOUNDED",
    "SCOPE_ANCHORED",
    "SCOPE_STRICT",
    "SYSTEM_PROMPT_TAIL",
]
# 四档措辞（ScopeWording 的取值域，ADR-53/61）
SCOPE_WORDS = ["anchored-help", "bounded-help", "adjacent-help", "strict"]


def norm(text: str) -> str:
    """归一化空白：Java 文本块里的换行/缩进不是语义，换行造成的"看起来不同"要抹平"""
    return re.sub(r"\s+", " ", text).strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-len", type=int, default=120,
                    help="判定为'副本'的最小字符数（低于此视为设计性重复）")
    args = ap.parse_args()

    java_files = sorted(SRC.rglob("*.java"))
    if not java_files:
        print("⛔ 没找到源码目录：", SRC)
        return 1

    literals = defaultdict(list)          # 归一后的文本 -> [(文件, 常量名, 原始长度)]
    const_text = {}                       # 常量名 -> 归一文本（仅本仓编排类）
    failures = []

    for f in java_files:
        src = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'String\s+([A-Z][A-Z0-9_]*)\s*=\s*"""(.*?)"""', src, re.S):
            name, raw = m.group(1), m.group(2)
            value = norm(raw)
            if not value:
                continue
            literals[value].append((f.name, name, len(value)))
            const_text.setdefault(name, value)

    # 1) 重复副本检测
    dups = {k: v for k, v in literals.items() if len(v) > 1 and len(k) >= args.min_len}
    if dups:
        failures.append("重复副本")
        print(f"⛔ 发现 {len(dups)} 处**完全相同的长文本被复制成多份**（漂移的物理前提）：")
        for value, where in dups.items():
            print(f"   {len(value)} 字符 出现于：")
            for fname, cname, _ in where:
                print(f"     · {fname} :: {cname}")
            print(f"     开头：{value[:90]}")
    else:
        print(f"✅ 没有长文本副本（阈值 {args.min_len} 字符）")

    # 2) 结构契约：五段齐全
    executor = SRC / "cn" / "lwx" / "lwxaiagent" / "infrastructure" / "orchestration" / "ChatExecutor.java"
    if executor.exists():
        src = executor.read_text(encoding="utf-8")
        missing = [c for c in REQUIRED_CONSTANTS if f"String {c} =" not in src]
        if missing:
            failures.append("结构契约")
            print(f"⛔ ChatExecutor 缺少片段常量：{missing}")
        else:
            print(f"✅ 六个片段常量齐全（HEAD + 四档 SCOPE + TAIL）")

        # SYSTEM_PROMPT 必须仍由三段拼成 —— 否则就退化成"一坨整体常量"，无法分档
        m = re.search(r'String\s+SYSTEM_PROMPT\s*=\s*(.+?);', src, re.S)
        if m:
            expr = norm(m.group(1))
            needed = ["SYSTEM_PROMPT_HEAD", "SCOPE_ANCHORED", "SYSTEM_PROMPT_TAIL"]
            gone = [n for n in needed if n not in expr]
            if gone:
                failures.append("SYSTEM_PROMPT 组成")
                print(f"⛔ SYSTEM_PROMPT 不再由 {needed} 拼成，缺 {gone}"
                      " —— 分档能力已经丢失（运行期虽不读它，但版本检测依赖其可比性）")
            else:
                print("✅ SYSTEM_PROMPT 仍由 HEAD + SCOPE_ANCHORED + TAIL 三段拼成（分档结构完好）")

        # 四档措辞齐全
        scope_file = SRC / "cn" / "lwx" / "lwxaiagent" / "infrastructure" / "orchestration" / "ScopeWording.java"
        if scope_file.exists():
            s2 = scope_file.read_text(encoding="utf-8")
            missing_words = [w for w in SCOPE_WORDS if f'"{w}"' not in s2]
            if missing_words:
                failures.append("scope 取值域")
                print(f"⛔ ScopeWording 缺少档位：{missing_words}")
            else:
                print(f"✅ 四档措辞齐全（{' / '.join(SCOPE_WORDS)}）")
    else:
        failures.append("定位")
        print("⛔ 找不到 ChatExecutor.java（重构后请同步更新本装置的路径）")

    # 3) 报告总长（给人看规模，但不作为判定）
    longest = max(((len(v), k) for v, lst in literals.items() for k in [lst[0][1]]), default=(0, ""))
    if longest[0]:
        print(f"\nℹ️ 最长的提示词片段：{longest[1]} = {longest[0]} 字符")

    if failures:
        print(f"\n结论：⛔ 未通过（{', '.join(failures)}）")
        return 1
    print("\n结论：✅ 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
