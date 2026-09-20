#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""阈值运营点：召回用 hold-out 实测，误报当**自由参数**做敏感性表（docs/phase7-prod-sim §S4）。

为什么误报不能只给一个数：真实语料**给不出可信的误报率**——
53,028 条去重后只有约 20~55 条不同文本（483/800 条随机样本是"你好"），
样本量名义上 800、实质上是几十条。把它换算成"95% 上界 0.375%"是虚假精度。
所以这里改成：**召回照实算，误报扫一遍取值**，让读的人看到"误报到多少这个开关就不再划算了"。

用法：
    python scripts/prod_operating_point.py \
        --holdout outputs/holdout-judge-*.jsonl \
        --base-rates 0.005,0.01,0.02 \
        --fp-rates 0,0.001,0.003,0.01,0.02 \
        --output outputs/prod-operating-point.json
"""

import argparse
import glob
import io
import json
import os
import sys

THRESHOLDS = [0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9]


def load_jsonl(path):
    return [json.loads(l) for l in io.open(path, encoding="utf-8") if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--holdout", required=True, help="--judge 的输出（支持通配）")
    ap.add_argument("--base-rates", default="0.005,0.01,0.02")
    ap.add_argument("--fp-rates", default="0,0.001,0.003,0.01,0.02")
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    path = max(glob.glob(args.holdout), key=os.path.getmtime)
    rows = [r for r in load_jsonl(path) if r.get("probability") is not None]
    by = {}
    for r in rows:
        by.setdefault(r["group"], []).append(r)

    crisis = by.get("crisis_holdout", [])
    dict_covered = [r for r in crisis if r["dict_level"] >= 3]
    dict_missed = [r for r in crisis if r["dict_level"] < 3]
    near_miss = by.get("near_miss_negative", [])
    ambiguous = by.get("ambiguous", [])
    dict_group = by.get("dictionary_covered", [])

    curve = []
    for t in THRESHOLDS:
        jev_hit = sum(1 for r in dict_missed if r["probability"] >= t)
        # 口径：召回 = 词典已覆盖的（无论如何都会被拦）+ 词典漏判但 Jev 越阈的
        recall = (len(dict_covered) + jev_hit) / len(crisis) if crisis else None
        curve.append({
            "threshold": t,
            "recall": round(recall, 4) if recall is not None else None,
            "recall_detail": "%d(词典) + %d/%d(Jev)" % (len(dict_covered), jev_hit, len(dict_missed)),
            "selfauthored_negative_blocked": sum(1 for r in near_miss if r["probability"] >= t),
            "selfauthored_negative_n": len(near_miss),
            "ambiguous_blocked": sum(1 for r in ambiguous if r["probability"] >= t),
            "ambiguous_n": len(ambiguous),
        })

    # 运营点：拦截占比 = 基率×召回 + (1−基率)×误报
    grid = []
    for br in [float(x) for x in args.base_rates.split(",") if x.strip()]:
        for fp in [float(x) for x in args.fp_rates.split(",") if x.strip()]:
            for row in curve:
                if row["recall"] is None:
                    continue
                blocked = br * row["recall"] + (1 - br) * fp
                grid.append({
                    "base_rate": br, "fp_rate": fp, "threshold": row["threshold"],
                    "recall": row["recall"], "blocked_share": round(blocked, 5),
                })

    # 决策用的关键量：在给定基率下，"被拦的人里有多少其实不需要"（1−精确率）
    precision = []
    for br in [float(x) for x in args.base_rates.split(",") if x.strip()]:
        for fp in [float(x) for x in args.fp_rates.split(",") if x.strip()]:
            for row in curve:
                if row["recall"] is None:
                    continue
                tp = br * row["recall"]
                fpc = (1 - br) * fp
                if tp + fpc > 0:
                    precision.append({
                        "base_rate": br, "fp_rate": fp, "threshold": row["threshold"],
                        "precision": round(tp / (tp + fpc), 4),
                    })

    report = {
        "input": path,
        "holdout_composition": {g: len(v) for g, v in by.items()},
        "threshold_curve": curve,
        "operating_point_grid": grid,
        "precision_grid": precision,
        "caveats": [
            "召回来自**本人编写**的 hold-out（20 条危机 + 12 条近邻负向 + 5 条歧义），未参与阈值标定，但仍是自造样本。",
            "误报是**自由参数**：真实语料去重后仅约 20~55 条不同文本，给不出可信误报率。",
            "基率是**假设值**：关系咨询场景的真实自伤表达率未知。",
        ],
    }
    with io.open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("== hold-out 构成 ==")
    for g, n in report["holdout_composition"].items():
        print("  %-20s %d" % (g, n))
    print("  危机句里：词典已覆盖 %d / 词典漏判 %d（后者才是第二信号要负责的）"
          % (len(dict_covered), len(dict_missed)))

    print("\n== 阈值 → 召回（hold-out，未见过的句子）==")
    print("  %-7s %-30s %s" % ("阈值", "召回", "自造负向被拦 / 歧义被拦"))
    for row in curve:
        print("  ≥%-5s %-6s %-22s %d/%d   %d/%d"
              % (row["threshold"], row["recall"], row["recall_detail"],
                 row["selfauthored_negative_blocked"], row["selfauthored_negative_n"],
                 row["ambiguous_blocked"], row["ambiguous_n"]))

    print("\n== 运营点：拦截占比（基率 × 召回 + 其余 × 误报）==")
    fps = [float(x) for x in args.fp_rates.split(",") if x.strip()]
    print("  基率\\误报  " + "".join("%-9s" % ("%.1f%%" % (100 * f)) for f in fps))
    for br in [float(x) for x in args.base_rates.split(",") if x.strip()]:
        for t in (0.4, 0.6):
            cells = []
            for f in fps:
                v = next((g["blocked_share"] for g in grid
                          if g["base_rate"] == br and g["fp_rate"] == f and g["threshold"] == t), None)
                cells.append("%-9s" % ("%.2f%%" % (100 * v) if v is not None else "-"))
            print("  %-8s ≥%.1f  %s" % ("%.1f%%" % (100 * br), t, "".join(cells)))

    print("\n== 决策量：被拦消息里'其实不需要'的比例 = 1 − 精确率 ==")
    for br in [0.01]:
        for t in (0.4, 0.6):
            row = [g for g in precision if g["base_rate"] == br and g["threshold"] == t]
            print("  基率 %.1f%%、阈值 %.1f：%s" % (100 * br, t,
                  "  ".join("误报%.1f%%→精确率%.0f%%" % (100 * g["fp_rate"], 100 * g["precision"]) for g in row)))
    print("\n完整结果: %s" % args.output)


if __name__ == "__main__":
    sys.exit(main())
