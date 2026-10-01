#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""API 契约漂移检查：docs/05-API契约设计.md ↔ 控制器实现（ADR-72）。

AGENTS.md §1 明令"不允许代码与文档静默偏离"，但这条此前**靠人肉** ——
2026-10-01 phase33 审计就抓到同族的事（SRS §7 把早已实现的功能标成"待立项"）。

判定：
- MISSING（文档写了、代码没有）→ ⛔ 退出码 1：文档在撒谎。
- EXTRA（代码有、文档没写）→ 只报告不失败（未文档化 ≠ 错，但该被看见）。
- UNPARSED（`[/{id}]` 这类可选段）→ **显式列出**，不许静默跳过：
  "没检查"和"检查通过"必须分得开。

用法：python scripts/check_api_contract.py [--doc docs/05-API契约设计.md]
"""
import argparse
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTROLLERS = os.path.join(ROOT, "src", "main", "java")

# 文档表格里形如：`POST /memory/register` / `GET/PUT/DELETE /memory/facts[/{id}]` / `GET /x?y`
DOC_EP = re.compile(r"`([A-Z]{3,7}(?:/[A-Z]{3,7})*)\s+(/[^`?\s]*)")
# ⛔ 不能要求引号后紧跟 `)`：实测有 `@GetMapping(value = "Love_app/chat/sse", produces = "...")`
#    这种带额外参数的形态（AiController 全是）—— 旧正则因此把它们**整条漏掉**，
#    报表上表现为"文档写了、代码没有"的假漂移。**解析器的漏报会伪装成被测对象的缺陷。**
CLASS_MAP = re.compile(r'@RequestMapping\s*\(([^)]*)\)')
METHOD_MAP = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(([^)]*)\))?')
FIRST_STR = re.compile(r'"([^"]*)"')


def code_endpoints():
    """@return (set of "METHOD /path", set of controller files scanned)"""
    out = set()
    files = []
    for dirpath, _dirs, names in os.walk(CONTROLLERS):
        for n in names:
            if not n.endswith(".java"):
                continue
            path = os.path.join(dirpath, n)
            src = io.open(path, encoding="utf-8", errors="replace").read()
            if "@RestController" not in src and "@Controller" not in src:
                continue
            files.append(n)
            base = ""
            m = CLASS_MAP.search(src)
            if m:
                first = FIRST_STR.search(m.group(1))
                if first:
                    base = first.group(1).rstrip("/")
            for verb, args in METHOD_MAP.findall(src):
                first = FIRST_STR.search(args or "")
                sub = (first.group(1) if first else "").strip().rstrip("/")
                full = (base + sub) if sub.startswith("/") else (base + "/" + sub if sub else base)
                out.add("%s %s" % (verb.upper(), full or "/"))
    return out, files


def doc_endpoints(doc):
    """@return (declared set, unparsed list)"""
    raw = io.open(doc, encoding="utf-8", errors="replace").read()
    # ⛔ 忽略 blockquote（`> ...`）——那里是**说明**（含"更正：原有一行 `GET /x` 从未实现"这类引用），
    #    不是声明。否则我解释某个漂移端点的那句话，会被解析器当成一条新的声明（实测踩到）。
    txt = "\n".join(ln for ln in raw.splitlines() if not ln.lstrip().startswith(">"))
    declared, unparsed = [], []   # declared 现在是 (verb,[variants]) 组
    for verbs, path in DOC_EP.findall(txt):
        path = path.rstrip("/").split("?")[0]
        # 展开可选段：`/memory/facts[/{id}]` → 同时核对 `/memory/facts` 与 `/memory/facts/{id}`。
        # ⛔ 两种写法任一命中即算"文档说的能力存在"；**都不在**才算 MISSING（不再整条跳过）。
        variants = [path]
        while "[" in variants[-1]:
            cur = variants.pop()
            variants += [cur[:cur.index("[")].rstrip("/"), cur.replace("[", "").replace("]", "")]
        variants = [v for v in dict.fromkeys(variants) if v]
        for v in verbs.split("/"):
            declared.append((v, variants))   # 成组：这一"动词"下，任一写法命中即满足
    return declared, unparsed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--doc", default=os.path.join(ROOT, "docs", "05-API契约设计.md"))
    ap.add_argument("--show-extra", action="store_true")
    args = ap.parse_args()

    declared_groups, unparsed = doc_endpoints(args.doc)
    declared = {"%s %s" % (v, var) for v, variants in declared_groups for var in variants}
    implemented, files = code_endpoints()
    print("扫描控制器 %d 个；文档声明端点 %d 条；代码实现端点 %d 条" % (len(files), len(declared), len(implemented)))

    def shape(ep):
        verb, _, path = ep.partition(" ")
        return verb + " " + re.sub(r"\{[^}]+\}", "{}", path)

    implemented_shapes = {shape(e) for e in implemented}
    # 可选段展开后允许"任一命中"：这里逐条判定，命中任一变体即不算缺
    # ⛔ 可选段（`X[/{id}]`）是"要么要么"，不是"两者都要"：这一组里**任一**写法命中即算满足。
    #    我第一版要求全部命中 → 立刻造出 `DELETE /memory/facts` 这种假 MISSING。
    missing = []
    for verb, variants in declared_groups:
        if any(("%s %s" % (verb, v)) in implemented or shape("%s %s" % (verb, v)) in implemented_shapes
               for v in variants):
            continue
        missing.append("%s %s" % (verb, " | ".join(variants)))
    extra = sorted(implemented - declared)
    if missing:
        print("\n⛔ MISSING（文档写了、代码里没有）—— 文档与实现已经分道扬镳：")
        for e in missing:
            print("   " + e)
    else:
        print("\n✅ 文档声明的端点**全部**在代码里找得到")
    if unparsed:
        print("\n⚠️ UNPARSED（未能自动核对，请人工确认；**不算通过**）：")
        for e in unparsed:
            print("   " + e)
    print("\nℹ️ EXTRA（代码有、文档未写）= %d 条%s" % (len(extra), "" if args.show_extra else "（--show-extra 可列出）"))
    if args.show_extra:
        for e in extra:
            print("   " + e)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
