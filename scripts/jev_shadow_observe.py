#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""影子观测报告（docs/phase7-jev-shadow）。

探针在 `logs/JevShadowCorpus.java`：它 **复用产线类**（真实 EncryptionService 解密、
真实 GuardrailRuleService 判词典、真实 JevSelfHarmSignal.judge 调第二信号），
输出 JSONL。本脚本只做**统计**，不重算任何判定语义。

两种取样必须分开统计（这是本报告的核心纪律）：
  - `selected_by=random`（分层随机）→ 唯一能用来估**真实误报率**的样本；
  - `selected_by=tail`（刻意挑词典有信号的）→ **加权样本，绝不能当分母**，
    它只用来回答"词典认为有风险的那些，Jev 怎么看"。

统计量里不含内容信息，可安全提交；原文只留在 gitignore 的 outputs/ 里供人工复核。

用法：
    python scripts/jev_shadow_observe.py --input "outputs/shadow-observe2-*.jsonl" \
        --output outputs/shadow-report.json [--top 30]
"""

import argparse
import glob
import io
import json
import os
import statistics
import sys

THRESHOLDS = [0.3, 0.5, 0.6, 0.7, 0.8, 0.9]
BINS = [0.0, 0.01, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]


def load(path):
    summary, rows = None, []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if d.get("kind") == "corpus_summary":
                summary = d
            else:
                rows.append(d)
    return summary, rows


def histogram(values):
    out = []
    for lo, hi in zip(BINS, BINS[1:]):
        n = sum(1 for v in values if (lo <= v < hi) or (hi == 1.0 and v == 1.0))
        out.append({"bin": "[%.2f,%.2f%s" % (lo, hi, "]" if hi == 1.0 else ")"), "count": n})
    return out


def pct(n, d):
    return round(100.0 * n / d, 3) if d else 0.0


def counts(it):
    out = {}
    for v in it:
        key = v if v else "<none>"
        out[key] = out.get(key, 0) + 1
    return out


def summarise(rows, label):
    """rows 应已剔除"不进分母"的消息（词典 L3）。"""
    scored = [r for r in rows if r.get("probability") is not None]
    failed = [r for r in rows if r.get("probability") is None]
    probs = [r["probability"] for r in scored]
    lat = [r["ms"] for r in scored if r.get("ms")]
    return {
        "label": label,
        "denominator": len(rows),
        "scored": len(scored),
        "failed": len(failed),
        "failure_reasons": counts(r.get("error") for r in failed),
        "above_threshold_060": sum(1 for p in probs if p >= 0.6),
        "max_probability": max(probs) if probs else None,
        "p50_probability": round(statistics.median(probs), 4) if probs else None,
        "p95_probability": sorted(probs)[min(len(probs) - 1, int(len(probs) * 0.95))] if probs else None,
        "latency_ms_p50": int(statistics.median(lat)) if lat else None,
        "latency_ms_p95": sorted(lat)[min(len(lat) - 1, int(len(lat) * 0.95))] if lat else None,
        "histogram": histogram(probs),
        "threshold_table": [
            {"threshold": t, "blocked": sum(1 for p in probs if p >= t),
             "pct": pct(sum(1 for p in probs if p >= t), len(scored))}
            for t in THRESHOLDS
        ],
    }


def print_summary(s):
    print("\n== %s ==  分母 %d  有概率 %d  无结果 %d" % (s["label"], s["denominator"], s["scored"], s["failed"]))
    if s["failed"]:
        print("   失败构成: %s" % s["failure_reasons"])
    print("   概率 max=%s p50=%s p95=%s | 延迟 p50=%sms p95=%sms"
          % (s["max_probability"], s["p50_probability"], s["p95_probability"],
             s["latency_ms_p50"], s["latency_ms_p95"]))
    print("   直方图: " + ("  ".join("%s:%d" % (b["bin"], b["count"]) for b in s["histogram"] if b["count"]) or "(空)"))
    print("   阈值→拦截: " + "  ".join(">=%.1f %d(%.3f%%)" % (t["threshold"], t["blocked"], t["pct"])
                                       for t in s["threshold_table"]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, help="JevShadowCorpus 输出的 JSONL（支持通配）")
    ap.add_argument("--output", required=True)
    ap.add_argument("--top", type=int, default=30)
    args = ap.parse_args()

    path = max(glob.glob(args.input), key=os.path.getmtime)
    summary, rows = load(path)

    entered = [r for r in rows if r.get("decrypted") and r.get("dict_level", 0) < 3]
    random_rows = [r for r in entered if r.get("selected_by") == "random"]
    tail_rows = [r for r in entered if r.get("selected_by") == "tail"]
    l3_rows = [r for r in rows if r.get("decrypted") and r.get("dict_level", 0) >= 3]

    report = {
        "input": path,
        "corpus": {
            "total_user_messages": (summary or {}).get("rows"),
            "decrypt_failed": (summary or {}).get("decrypt_failed"),
            "dictionary_level_histogram": (summary or {}).get("dictionary_level_histogram"),
            "sent_to_jev": len(rows),
            "entered_denominator": len(entered),
            "dictionary_L3_excluded": len(rows) - len(entered),
        },
        "false_positive_margin": {
            "note": "分层随机样本 —— 唯一可用于估计真实误报率的一支",
            "all": summarise(random_rows, "random/all"),
            "synthetic": summarise([r for r in random_rows if r["stratum"] == "synthetic"], "random/synthetic"),
            "organic": summarise([r for r in random_rows if r["stratum"] == "organic"], "random/organic"),
        },
        "dictionary_tail": {
            "note": "刻意挑词典 level>=2 的消息 —— 加权样本，不得当分母",
            "all": summarise(tail_rows, "tail/all"),
        },
        "positive_control_dict_l3": {
            "note": "词典判 L3 的真实消息 —— 产线上不会走到 Jev，此处只作分级一致性对照，不进任何分母",
            "all": summarise(l3_rows, "dict_l3/all"),
        },
        "top_by_probability": sorted(
            ({"probability": r["probability"], "selected_by": r["selected_by"], "stratum": r["stratum"],
              "dict_level": r["dict_level"], "dict_rule": r["dict_rule"], "text": r["text"]}
             for r in entered if r.get("probability") is not None),
            key=lambda x: -x["probability"])[: args.top],
    }

    with io.open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    c = report["corpus"]
    print("== 语料 ==")
    print("  全量 USER 消息 %s / 送往 Jev %d / 解密失败 %s" % (c["total_user_messages"], c["sent_to_jev"], c["decrypt_failed"]))
    print("  全量词典等级分布 %s" % c["dictionary_level_histogram"])
    print("  词典 L3 剔除 %d / 进入分母 %d" % (c["dictionary_L3_excluded"], c["entered_denominator"]))

    fp = report["false_positive_margin"]
    print("\n【误报率口径】" + fp["note"])
    for key in ("all", "synthetic", "organic"):
        print_summary(fp[key])

    print("\n【词典尾部口径】" + report["dictionary_tail"]["note"])
    print_summary(report["dictionary_tail"]["all"])

    print("\n【阳性对照】" + report["positive_control_dict_l3"]["note"])
    print_summary(report["positive_control_dict_l3"]["all"])

    print("\n== 概率最高的 %d 条（供人工复核）==" % args.top)
    for r in report["top_by_probability"][:15]:
        print("  p=%-6s %-7s dict=%s/%s  %r"
              % (r["probability"], r["selected_by"], r["dict_level"], r["dict_rule"], r["text"][:64]))
    print("\n完整报告: %s" % args.output)


if __name__ == "__main__":
    sys.exit(main())
