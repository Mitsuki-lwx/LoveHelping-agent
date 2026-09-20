#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""护栏影子观测报告（生产用）—— 读 `guardrail_event` 出统计，并支持人工标注后算真实误报率。

它解决的是 ADR-37 留下的那个前置条件："**先在生产 shadow 上量出真实误报率**"。
离线拿不到那个数（本库去重后只有约 20 条不同文本），只能靠真实流量。

为什么落地成脚本而不是让人手写 SQL：
  * 口径容易写错（分母该是谁、失败/低分怎么算、词典那部分算不算）；
  * 越阈样本的**人工复核**是一次性动作，需要一份"抽哪几条去看"的清单和回填格式。

用法：
    # 1) 看总体情况（不需要标注）
    MYSQL_PASSWORD=xxx python scripts/guardrail_shadow_report.py --window-hours 24

    # 2) 导出待复核清单（越阈的 + 按分数排序的高分区）
    ... --export-review outputs/review.jsonl --review-size 200

    # 3) 复核后回填标注（一行一条：{"event_id": 123, "label": "negative"}）
    ... --label-file outputs/labels.jsonl

label 取值约定：
    positive  确实表达自伤/自杀意愿（该拦）
    negative  不该拦（误报）
    ambiguous 说不清 —— **单列，不计入误报率**（不要拿它凑数）

⚠️ 统计口径的三条纪律（写死在输出里，避免事后被读错）：
  1. **失败要单列**：没有 signal_score 的事件不算"低风险"，算"没测到"。
  2. 词典已判 L3 的消息根本不会走到第二信号 → 它们不是这个分母的一部分。
  3. 只有**抽样方式明确**时才报误报率：如果只标了越阈的样本，算出来的叫
     "越阈样本里的误报占比"（≈不精确度），**不是**全量的误报率。脚本会把这两者分开写。
"""

import argparse
import io
import json
import os
import sys
from collections import Counter

BINS = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]


def connect(args):
    try:
        import pymysql
    except ImportError:
        print("需要 pymysql：pip install pymysql", file=sys.stderr)
        raise
    return pymysql.connect(
        host=args.host, port=args.port, user=args.user,
        password=os.environ.get("MYSQL_PASSWORD", args.password),
        database=args.database, charset="utf8mb4")


def fetch_events(cur, hours, only_jev):
    where = ["created_at >= NOW() - INTERVAL %s HOUR"]
    params = [hours]
    if only_jev:
        where.append("rule_id LIKE 'jev:%%'")
    sql = ("select id, user_id, level, rule_id, action, signal_score, signal_threshold, created_at "
           "from guardrail_event where " + " and ".join(where) + " order by id")
    cur.execute(sql, params)
    cols = ["id", "user_id", "level", "rule_id", "action", "score", "threshold", "created_at"]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def num(value):
    """DECIMAL 从 pymysql 回来是 decimal.Decimal —— 不转就没法 JSON 序列化。
    统一在这里转，别在每个用到的地方各转一次（漏一个就炸，且只在有数据时才炸）。"""
    return None if value is None else float(value)


def histogram(values):
    out = []
    for lo, hi in zip(BINS, BINS[1:]):
        n = sum(1 for v in values if (lo <= v < hi) or (hi == 1.0 and v == 1.0))
        out.append({"bin": "[%.1f,%.1f%s" % (lo, hi, "]" if hi == 1.0 else ")"), "count": n})
    return out


def wilson_interval(successes, n, z=1.96):
    """比例的 Wilson 95% 区间 (lo, hi)。n=0 返回 (None, None) —— 不编造。

    直接对"我们关心的那个比例"算区间，不要拿另一个比例的下界去凑 —— 那样界会错。
    """
    if n == 0:
        return (None, None)
    p = successes / n
    d = 1 + z * z / n
    centre = p + z * z / (2 * n)
    half = z * ((p * (1 - p) / n + z * z / (4 * n * n)) ** 0.5)
    return (max(0.0, (centre - half) / d), min(1.0, (centre + half) / d))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.environ.get("MYSQL_HOST", "localhost"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("MYSQL_PORT", "3306")))
    ap.add_argument("--user", default=os.environ.get("MYSQL_USER", "root"))
    ap.add_argument("--password", default="")
    ap.add_argument("--database", default=os.environ.get("MYSQL_DATABASE", "agentdb"))
    ap.add_argument("--window-hours", type=int, default=24)
    ap.add_argument("--only-jev", action="store_true", help="只看第二信号事件（默认看全部护栏事件）")
    ap.add_argument("--export-review", help="导出待人工复核清单（jsonl）")
    ap.add_argument("--review-size", type=int, default=200)
    ap.add_argument("--label-file", help="人工标注回填（jsonl: {\"event_id\":N,\"label\":\"...\"}）")
    ap.add_argument("--output", help="把统计结果写成 json")
    args = ap.parse_args()

    conn = connect(args)
    cur = conn.cursor()
    events = fetch_events(cur, args.window_hours, args.only_jev)
    conn.close()

    by_action = Counter(e["action"] for e in events)
    by_rule = Counter(e["rule_id"] or "<none>" for e in events)

    shadow = [e for e in events if e["action"] == "SHADOW"]
    shadow_scored = [e for e in shadow if e["score"] is not None]
    shadow_failed = [e for e in shadow if e["score"] is None]
    scores = [num(e["score"]) for e in shadow_scored]
    thresholds = Counter(str(e["threshold"]) for e in shadow_scored)
    exceeded = [e for e in shadow_scored if num(e["score"]) >= (num(e["threshold"]) or 0.6)]

    report = {
        "window_hours": args.window_hours,
        "events_total": len(events),
        "by_action": dict(by_action),
        "by_rule_id": dict(by_rule),
        "shadow": {
            "n": len(shadow),
            "scored": len(shadow_scored),
            "no_result": len(shadow_failed),
            "thresholds_seen": dict(thresholds),
            "histogram": histogram(scores),
            "exceeded": len(exceeded),
            "exceeded_share_of_scored": round(len(exceeded) / len(shadow_scored), 6) if shadow_scored else None,
        },
    }

    print("== 窗口 %sh 内的护栏事件（n=%d）==" % (args.window_hours, len(events)))
    print("  按 action: %s" % dict(by_action))
    print("  按 rule_id: %s" % dict(by_rule))
    print("\n== 影子观测（action=SHADOW）==")
    s = report["shadow"]
    print("  事件 %d 条；有概率 %d 条；**无结果（没测到）%d 条**" % (s["n"], s["scored"], s["no_result"]))
    print("  ⚠️ 无结果的必须单列，不能当低风险 —— 它们是失败/超时/未启用，不是判定结论。")
    if s["thresholds_seen"]:
        print("  记录到的阈值: %s（存了阈值，日后改过也能复算'当时为什么没拦'）" % s["thresholds_seen"])
    if scores:
        print("  分数 max=%.4f  " % max(scores)
              + "  ".join("%s:%d" % (b["bin"], b["count"]) for b in s["histogram"] if b["count"]))
        print("  **越阈 %d 条（占已判定的 %.4f%%）** —— 若开 enforce，这就是会被拦下的量"
              % (s["exceeded"], 100 * s["exceeded_share_of_scored"]))
    else:
        print("  （窗口内没有可用分数——检查 mode 是否真的是 shadow）")

    # ---------- 待复核清单 ----------
    if args.export_review:
        pool = sorted(shadow_scored, key=lambda e: -num(e["score"]))
        picked = pool[: args.review_size]
        with io.open(args.export_review, "w", encoding="utf-8") as f:
            for e in picked:
                f.write(json.dumps({
                    "event_id": e["id"], "score": num(e["score"]), "threshold": num(e["threshold"]),
                    "created_at": str(e["created_at"]), "user_id": e["user_id"], "label": "",
                }, ensure_ascii=False) + "\n")
        print("\n已导出待复核 %d 条 → %s" % (len(picked), args.export_review))
        print("  复核方式（不把原文写进审计表，所以需要关联取原文）：")
        print("    select m.content from message m join guardrail_event g on m.content_hmac = g.content_hmac")
        print("    where g.id = <event_id>;   -- content 是密文，用应用侧密钥解密后读")
        print("  回填格式（每行一条）：{\"event_id\": 123, \"label\": \"positive|negative|ambiguous\"}")

    # ---------- 标注 → 两个不同口径的数 ----------
    if args.label_file:
        labels = {}
        for line in io.open(args.label_file, encoding="utf-8"):
            line = line.strip()
            if line:
                d = json.loads(line)
                if d.get("label"):
                    labels[d["event_id"]] = d["label"]
        labeled = [e for e in shadow_scored if e["id"] in labels]
        cnt = Counter(labels[e["id"]] for e in labeled)
        pending = len(shadow_scored) - len(labeled)

        if exceeded:
            ex_labeled = [e for e in exceeded if e["id"] in labels]
            ex_cnt = Counter(labels[e["id"]] for e in ex_labeled)
            fp = ex_cnt.get("negative", 0)
            n_ex = ex_cnt.get("positive", 0) + fp
            pos = ex_cnt.get("positive", 0)
            prec_lo, prec_hi = wilson_interval(pos, n_ex)
            report["precision_on_exceeded"] = {
                "labeled": n_ex, "positive": pos, "negative": fp,
                "ambiguous_excluded": ex_cnt.get("ambiguous", 0),
                "false_positive_rate_on_exceeded": round(fp / n_ex, 6) if n_ex else None,
                "precision_95ci": [None if prec_lo is None else round(prec_lo, 4),
                                   None if prec_hi is None else round(prec_hi, 4)],
            }
            print("\n== 口径 A：越阈样本里的误报占比（≈不精确度）==")
            print("  已标注 %d 条（positive %d / negative %d / ambiguous %d，歧义不计入）"
                  % (n_ex + ex_cnt.get("ambiguous", 0), ex_cnt.get("positive", 0), fp,
                     ex_cnt.get("ambiguous", 0)))
            if n_ex:
                print("  **被拦下的人里 %.1f%% 其实不需要**（精确率 %.1f%%，95%% CI %.0f%%~%.0f%%；n=%d）"
                      % (100 * fp / n_ex, 100 * pos / n_ex,
                         100 * (prec_lo or 0), 100 * (prec_hi or 0), n_ex))
                print("  ⚠️ n=%d 的区间很宽；样本不够时别拿点估计下结论。" % n_ex)
            print("  ⚠️ 这**不是全量误报率**：分母只有越阈样本。要报全量误报率，"
                  "必须同时随机抽若干**未越阈**的消息标注（分母是全部标注过的消息）。")

        print("\n== 标注进度 ==")
        print("  已标注 %d / %d（未标注 %d）。标注构成：%s" % (len(labeled), len(shadow_scored), pending, dict(cnt)))

    if args.output:
        with io.open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print("\n统计已写出 → %s" % args.output)


if __name__ == "__main__":
    sys.exit(main())
