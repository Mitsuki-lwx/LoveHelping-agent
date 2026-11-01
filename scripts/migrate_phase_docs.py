#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 docs/phase*/ 的历史变更迁入 openspec/changes/archive/。

只复制、不删除、不改写正文；仅在文件头加一段迁移出处。
删除原目录是独立步骤（tasks 2.5），本次不碰。

用法：
    python scripts/migrate_phase_docs.py --phase phase4-advice      # 试跑单个
    python scripts/migrate_phase_docs.py --all                     # 全量
    python scripts/migrate_phase_docs.py --all --dry-run           # 只看计划
    python scripts/migrate_phase_docs.py --all --force             # 覆盖已存在的归档
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
ARCHIVE = ROOT / "openspec" / "changes" / "archive"

# 文件名映射：OpenSpec 归档里 proposal/tasks 是标准名，checklist 改名避免与 artifact 冲突
RENAME = {
    "spec.md": "proposal.md",
    "tasks.md": "tasks.md",
    "checklist.md": "verification.md",
}

PROVENANCE = (
    "> **迁移记录（{date}）**：本文件由 `docs/{phase}/{src}` 原样迁入，"
    "正文未改写，仅加本段出处。\n"
    "> 这是 OpenSpec 之前的历史变更记录，仅供追溯；系统当前必须满足什么，"
    "以 `openspec/specs/` 为准。\n\n"
)


def phase_dirs(names: list[str] | None) -> list[Path]:
    all_dirs = sorted(p for p in DOCS.glob("phase*") if p.is_dir())
    if names:
        wanted = {n.strip() for n in names}
        return [p for p in all_dirs if p.name in wanted]
    return all_dirs


def migrate(phase: Path, *, dry_run: bool, force: bool, date: str) -> tuple[int, list[str]]:
    dest = ARCHIVE / phase.name
    written, skipped = 0, []
    for src in sorted(phase.glob("*.md")):
        target_name = RENAME.get(src.name, src.name)
        target = dest / target_name
        if target.exists() and not force:
            skipped.append(target_name)
            continue
        body = src.read_text(encoding="utf-8", errors="replace")
        header = PROVENANCE.format(date=date, phase=phase.name, src=src.name)
        if dry_run:
            written += 1
            continue
        dest.mkdir(parents=True, exist_ok=True)
        target.write_text(header + body, encoding="utf-8", newline="\n")
        written += 1
    return written, skipped


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", action="append", help="指定 phase 目录名（可重复）")
    ap.add_argument("--all", action="store_true", help="迁移全部 phase")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true", help="覆盖已存在的归档文件")
    ap.add_argument("--date", default="2026-11-01")
    args = ap.parse_args()

    if not args.all and not args.phase:
        ap.error("需要 --all 或 --phase <name>")

    phases = phase_dirs(args.phase)
    if not phases:
        print("没有匹配的 phase 目录")
        return 1

    total_written = total_skipped = 0
    for phase in phases:
        written, skipped = migrate(
            phase, dry_run=args.dry_run, force=args.force, date=args.date
        )
        total_written += written
        total_skipped += len(skipped)
        flag = "DRY" if args.dry_run else "OK "
        print(f"  {flag} {phase.name}: 写入 {written}" + (f"，跳过 {len(skipped)}" if skipped else ""))

    print(f"\n合计：phase {len(phases)} 个，写入 {total_written} 个文件"
          + (f"，跳过 {total_skipped}" if total_skipped else ""))
    if args.dry_run:
        print("（dry-run，未落盘）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
