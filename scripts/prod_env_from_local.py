#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把本地（gitignored）配置里的凭据转成 `KEY=value` 行，供 `eval` 后以 prod profile 启动应用。

**为什么需要它**：`application-prod.yml` 的密钥全部 `${ENV}` 注入（这是对的），
而真实值只存在于 gitignored 的 `target/classes/application-local.yml`。
为了在"生产配置"下跑模拟、又不在仓库里出现任何密钥，就在**运行时**把它读出来。

极简缩进扫描器（够用即可，不引入 yaml 依赖），只取固定几个路径：

    spring.datasource.password          -> MYSQL_PASSWORD
    spring.ai.dashscope.api-key         -> DASHSCOPE_API_KEY
    spring.ai.openai.base-url           -> OPENAI_BASE_URL
    spring.ai.openai.api-key            -> OPENAI_API_KEY
    spring.ai.openai.chat.options.model -> OPENAI_MODEL
    search-api.api-key                  -> SEARCH_API_KEY
    app.pgvector.datasource.password    -> PGVECTOR_PASSWORD
    jwt.secret                          -> JWT_SECRET
    admin.api-key                       -> ADMIN_API_KEY（local 通常不配，缺就跳过）

用法：
    eval "$(python scripts/prod_env_from_local.py)"
    eval "$(python scripts/prod_env_from_local.py --file path/to/other.yml)"

缺失的路径**不报错、直接跳过**（例如 local 从不配 admin-api-key，那个本来就该由外部注入）。

## ⚠️ Spring 占位符的处理（2026-09-27 修，ADR-51）

`application-local.yml` 里的值可能是 **Spring 占位符**而非真值：

    base-url: ${OPENAI_BASE_URL:https://api.deepseek.com}
    api-key:  ${OPENAI_API_KEY}

旧版解析器不辨真伪，把整串 `${OPENAI_BASE_URL:https://api.deepseek.com}`
当值导出 → `eval` 后 `OPENAI_BASE_URL` 变成一个**非法 URL 字符串**，
而主 yml 的 `${OPENAI_BASE_URL:default}` 会**优先取走环境变量** →
应用拿到垃圾值。**这是静默的**：不报错，只是打不到正确的端点。

（同形错误本项目已累计 6 次：ADR-46 载荷契约 / 9f608ea 凭据路径 /
ADR-50 `answer_eval.py` 正则取占位符 / ADR-48 判据被启动横幅污染 /
ADR-51 `grep dashscope` 把"没日志"读成"没调用" / 本项。）

现行规则：
- `${VAR:default}` → 取 **default**（与 Spring 的解析结果一致，幂等）
- `${VAR}`（无默认值）→ **跳过**并在 stderr 说明（取了只会污染环境变量）
- 普通字面值 → 原样导出
"""

import argparse
import io
import os
import re
import sys

WANTED = {
    ("spring", "datasource", "password"): "MYSQL_PASSWORD",
    ("spring", "ai", "dashscope", "api-key"): "DASHSCOPE_API_KEY",
    ("spring", "ai", "openai", "base-url"): "OPENAI_BASE_URL",
    ("spring", "ai", "openai", "api-key"): "OPENAI_API_KEY",
    ("spring", "ai", "openai", "chat", "options", "model"): "OPENAI_MODEL",
    ("search-api", "api-key"): "SEARCH_API_KEY",
    ("app", "pgvector", "datasource", "password"): "PGVECTOR_PASSWORD",
    # Phase 8（2026-09-21）：硅基流动凭据，同时供 embedding 与远端 rerank 使用。
    ("app", "siliconflow", "api-key"): "SF_API_KEY",
    ("jwt", "secret"): "JWT_SECRET",
    ("admin", "api-key"): "ADMIN_API_KEY",
}


def parse_scalars(text):
    """返回 {(路径...): 值}。只处理 `key: value`，忽略列表与多行块。"""
    out = {}
    stack = []  # [(indent, key)]
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        line = raw.strip()
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip().strip('"').strip("'")
        value = value.strip()
        # 去掉"  # 注释"，但保留值里本身就带 # 的情况（本文件没有）
        if " #" in value:
            value = value.split(" #")[0].rstrip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        if not value:
            stack.append((indent, key))
            continue
        path = tuple(k for _, k in stack) + (key,)
        out[path] = value.strip('"').strip("'")
    return out


# Spring 占位符：${VAR} 或 ${VAR:default}
_PLACEHOLDER = re.compile(r"^\$\{([^:}]+)(?::(.*))?\}$", re.S)


def resolve_placeholder(value):
    """把 Spring 占位符解析成**可用值**，而不是把占位符本身当值带走。

    返回 ``(usable_value_or_None, kind)``：

    - ``kind == "literal"``             普通字面值，原样可用
    - ``kind == "placeholder-default"`` 形如 ``${VAR:default}`` → 取 default
      （与 Spring 的解析结果一致；导出后 Spring 再解析一次也是同一个值，幂等）
    - ``kind == "placeholder-empty"``   形如 ``${VAR}`` 无默认值 → **不可用**
      （导出后 Spring 会优先取走这个垃圾值，比不导出更糟）
    """
    m = _PLACEHOLDER.match(value)
    if not m:
        return value, "literal"
    default = m.group(2)
    if default is None or default == "":
        return None, "placeholder-empty"
    return default, "placeholder-default"


def main():
    default_path = os.path.join("target", "classes", "application-local.yml")
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", default=default_path)
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(args.file):
        print("# 找不到 %s —— 无法以 prod profile 启动（该文件在 .gitignore 里，"
              "需先构建或手动准备）" % args.file, file=sys.stderr)
        return 1

    values = parse_scalars(io.open(args.file, encoding="utf-8").read())
    emitted = []
    skipped = []
    for path, env_name in WANTED.items():
        raw = values.get(path)
        if not raw:
            continue
        usable, kind = resolve_placeholder(raw)
        if usable is None:
            skipped.append((env_name, "占位符无默认值（${...}），跳过以免污染环境变量"))
            continue
        emitted.append((env_name, usable))
        if kind == "placeholder-default" and not args.quiet:
            print("# %s 取自占位符默认值（yml 里写的是 ${...:default}，非明文）" % env_name,
                  file=sys.stderr)

    for name, value in emitted:
        # 值里出现单引号的可能性可忽略（都是 key/url/model 名）；仍做一次防呆
        if "'" in value:
            print("# 跳过 %s：值含单引号，拒绝拼接" % name, file=sys.stderr)
            continue
        # 必须带 export：不带的话 eval 后只是**本 shell 变量**，Maven 派生的子 JVM 看不到它
        # （2026-09-20 第一次跑 prod profile 就栽在这：起不来，报 OpenAI API key must be set）。
        print("export %s='%s'" % (name, value))
    if not args.quiet:
        print("# 已提取 %d/%d 项（值不回显）" % (len(emitted), len(WANTED)), file=sys.stderr)
        for name, why in skipped:
            print("# ⚠️ 跳过 %s：%s" % (name, why), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
