#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""YAML **重复键**检查（ADR-67 的教训做成装置）。

为什么需要它：2026-09-29 我给 `application-prod.yml` 加配置时**新建了第二个顶层 `app:`**
→ Spring 启动直接 `DuplicateKeyException`。
⛔ 而 `yaml.safe_load()` **默认不报重复键**（后者静默覆盖前者）→ 我当时的"结构校验通过"是**假绿**。
本脚本用**严格构造器**把重复键挖出来（退出码非 0）。

用法：python scripts/check_yml_duplicates.py [paths...]   默认 src/main/resources/*.yml
"""
import io
import sys
from pathlib import Path

import yaml


class _Strict(yaml.SafeLoader):
    pass


def _no_duplicates(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError("重复键: %r（第 %s 行）" % (key, key_node.start_mark.line + 1))
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_Strict.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _no_duplicates)


def check(path: Path) -> bool:
    try:
        yaml.load(io.open(path, encoding="utf-8"), Loader=_Strict)
        print("  OK   %s" % path)
        return True
    except Exception as e:  # noqa: BLE001
        print("  FAIL %s -> %s" % (path, e))
        return False


def main(argv):
    paths = [Path(p) for p in argv[1:]] or sorted(Path("src/main/resources").glob("*.yml"))
    print("YAML 重复键检查（%d 个文件）" % len(paths))
    ok = all(check(p) for p in paths)
    print("全部通过" if ok else "⛔ 有重复键 —— Spring 启动会直接失败")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
