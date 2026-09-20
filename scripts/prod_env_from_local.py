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
"""

import argparse
import io
import os
import sys

WANTED = {
    ("spring", "datasource", "password"): "MYSQL_PASSWORD",
    ("spring", "ai", "dashscope", "api-key"): "DASHSCOPE_API_KEY",
    ("spring", "ai", "openai", "base-url"): "OPENAI_BASE_URL",
    ("spring", "ai", "openai", "api-key"): "OPENAI_API_KEY",
    ("spring", "ai", "openai", "chat", "options", "model"): "OPENAI_MODEL",
    ("search-api", "api-key"): "SEARCH_API_KEY",
    ("app", "pgvector", "datasource", "password"): "PGVECTOR_PASSWORD",
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
    for path, env_name in WANTED.items():
        value = values.get(path)
        if value:
            emitted.append((env_name, value))

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
    return 0


if __name__ == "__main__":
    sys.exit(main())
