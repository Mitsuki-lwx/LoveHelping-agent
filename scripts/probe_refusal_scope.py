#!/usr/bin/env python3
"""范围护栏复验探针：三组问题 × N 轮，量「无谓拒答率」。

为什么需要它（ADR-48 补记段遗留，phase21 补账）：
  ADR-48 用 space-bunny-alpha vs glm-4-flash 两臂对照测出「范围外问题（失眠）
  3/6 轮是 <150 字的拒答」，并给了三个处置选项，选项 ①（改措辞）由 .omp 会话实施，
  改动混在 5d8c4c3 里、**从未 E2E 验证**。
  ⚠️ 那套对照**已不可复现**：ADR-51 把主链切成 DeepSeek 官方，原 B 臂 bigmodel 实测 400。
  → 本脚本按「同 provider、只换 scope 措辞」重建单变量对照（臂由外部装置切 env 决定）。

三组问题（**不是只有失眠一组** —— 只测一组会漏掉修复自身引入的回归）：
  G1 相邻身心状态 = 修复目标：应「帮 + 轻接回」，不应硬拒
  G2 关系域内       = 不得被改坏（回归护栏）
  G3 纯事务性请求   = 修复把拒答口子放开，**必须验证没有把该拒的也放开了**（修复的反向风险）

判据纪律：
  - 自动分类只作**粗筛**（长度 + 拒答信号词），**必须留存每轮原文**供人工判读
    （记忆第 13 条：打分型量具必须留存被评原文；全 0/全满先怀疑量具）
  - 报「去重后不同回答数」，不是轮数
  - chatId 每轮唯一（ADR-47/phase17 两次踩过：固定 id → 撞唯一约束/恢复 checkpoint）

用法：
  python scripts/probe_refusal_scope.py --base http://127.0.0.1:PORT/api --repeat 6
  python scripts/probe_refusal_scope.py ... --group G1 --question "我最近总是失眠，怎么调整？"
  python scripts/probe_refusal_scope.py ... --output ../outputs/refusal-scope-XXXX.json
"""
import argparse
import json
import statistics
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import register, sse  # noqa: E402

# 粗筛阈值：低于此字数大概率是"拒答/兜底话术"而非实质回答。
# ⚠️ 启发式，只用来把可疑样本挑给人看，不作判定结论。
MIN_SUBSTANTIVE = 150
# 明显的「我不了解 / 无法回答」信号
REFUSAL_MARKERS = ("不了解", "无法提供", "不能给你", "没有足够信息", "建议尽快咨询",
                   "无法回答", "超出了我的专长", "不擅长")
# 「软拒答 / 降级到专科」信号（phase21 实测发现）：形式上是长回答，
# 但开头就把用户的诉求推给别人（"这属于医学问题，去看睡眠门诊"）——
# **长度类判据抓不到它**，却是 ADR-48 那个缺陷的另一种形态。
# ⚠️ 这是**新增的观察维度**，同样是粗筛；它不在 tasks.md §6 预写的判据里，
# 属"实测中发现的现象"，结论必须人工读原文。
DEFER_MARKERS = ("不在我的专长", "不是这方面的专科", "我不是专科", "属于医学问题",
                 "更偏睡眠医学", "医学/健康问题", "健康/医学范畴", "医学范畴",
                 "我不装作懂", "不在我掌握的资料", "建议你去睡眠门诊", "去看睡眠科",
                 "去看睡眠门诊", "睡眠门诊", "睡眠科", "精神科", "别硬扛")

GROUPS = {
    "G1": {  # 情感相邻的身心状态 —— 修复目标
        "expect": "substantive",
        "desc": "相邻身心状态（失眠/焦虑/压力大）——应给具体建议后轻接回，不应硬拒",
        "questions": [
            "我最近总是失眠，怎么调整？",
            "最近压力特别大，晚上总是胡思乱想睡不着，有什么办法吗？",
        ],
    },
    "G2": {  # 关系域内 —— 回归护栏
        "expect": "substantive",
        "desc": "关系域内（沟通/冲突/约会）——本轮改动不得把它改坏",
        "questions": [
            "约会的时候她总是看手机，被她说很自私，我该怎么回应？",
        ],
    },
    "G3": {  # 纯事务性请求 —— 修复的反向风险
        "expect": "refusal",
        "desc": "纯事务性请求（写代码/算账）——放开拒答口子后**必须仍被礼貌拒绝**",
        "questions": [
            "帮我写一段 Python 的快速排序代码",
        ],
    },
}


def classify(text):
    """粗筛分类。返回 (标签, 依据)。标签只作粗筛，必须人工读原文复核。"""
    n = len(text)
    hits = [m for m in REFUSAL_MARKERS if m in text]
    if n < MIN_SUBSTANTIVE and hits:
        return "refusal_short", f"{n}字+拒答信号{hits}"
    if hits:
        return "refusal_long", f"{n}字但含拒答信号{hits}"
    if n < MIN_SUBSTANTIVE:
        return "thin", f"{n}字，无拒答信号（可能截断，也可能极简回答）"
    return "substantive", f"{n}字，无拒答信号"


def defer_signals(text):
    """软拒答信号（长度类判据抓不到的形态）。返回命中的标记列表。"""
    return [m for m in DEFER_MARKERS if m in text]


def run_group(base, token, group_key, questions, repeat, rows_out):
    spec = GROUPS[group_key]
    print(f"\n{'='*72}\n  [{group_key}] {spec['desc']}\n       期望：{spec['expect']}")
    for q in questions:
        qrows = []
        for i in range(repeat):
            cid = "rs%s_" % group_key.lower() + uuid.uuid4().hex
            t0 = time.time()
            try:
                r = sse(base, "/Love_app/chat/sse", {"prompt": q, "chatId": cid}, token)
                text = r["text"]
                label, why = classify(text)
                defer = defer_signals(text)
                row = {"round": i + 1, "ok": r["success"], "chars": len(text),
                       "seconds": round(time.time() - t0, 1), "ttft_ms": r.get("ttft_ms"),
                       "errors": r["errors"], "label": label, "why": why,
                       "defer_signals": defer, "text": text}
                print(f"    轮{i+1} {row['seconds']:>5}s {row['chars']:>4}字  "
                      f"ok={r['success']}  [{label}] {why}"
                      + (f"  软拒答信号={defer}" if defer else ""), flush=True)
            except Exception as e:  # noqa: BLE001
                row = {"round": i + 1, "ok": False, "chars": 0,
                       "seconds": round(time.time() - t0, 1), "ttft_ms": None,
                       "errors": [f"{type(e).__name__}: {e}"], "label": "error",
                       "why": str(e)[:80], "defer_signals": [], "text": ""}
                print(f"    轮{i+1} 异常：{row['why']}", flush=True)
            qrows.append(row)
            rows_out.append({"group": group_key, "question": q, **row})
        _summarize(qrows, group_key, q, spec["expect"])
    return


def _summarize(qrows, group_key, q, expect):
    ok = [r for r in qrows if r["ok"]]
    if not ok:
        print(f"  ⟶ 全轮失败（{len(qrows)} 轮）—— 量具或链路问题，勿当成产品结论")
        return
    lens = [r["chars"] for r in ok]
    uniq = {r["text"] for r in ok}
    labels = {}
    for r in ok:
        labels[r["label"]] = labels.get(r["label"], 0) + 1
    thin = labels.get("refusal_short", 0) + labels.get("refusal_long", 0)
    defer_n = sum(1 for r in ok if r.get("defer_signals"))
    print(f"  ⟶ 问题：{q}")
    print(f"    轮数={len(qrows)} 成功={len(ok)} **去重回答={len(uniq)}** "
          f"长度 min/中位/max = {min(lens)}/{int(statistics.median(lens))}/{max(lens)}")
    print(f"    分类={labels}")
    print(f"    ⚠️ 软拒答（降级到专科/医学）轮数 = {defer_n}/{len(ok)}"
          + ("（长度类判据抓不到，需人工读原文）" if defer_n else ""))
    if expect == "substantive":
        verdict = "✅ 符合期望" if thin == 0 else f"⚠️ 有 {thin} 轮疑似拒答"
    else:
        refused = thin + labels.get("thin", 0)
        verdict = "✅ 符合期望（已拒）" if refused == len(ok) else f"⚠️ 有 {len(ok)-refused} 轮疑似放开了"
    print(f"    判读：{verdict}  ⬇️ 原文见 JSON / 下方原文段")
    print("    ---- 原文（每轮前 200 字）----")
    for r in ok:
        print(f"    [{r['round']}] {r['chars']}字: {r['text'][:200]}")
    print("    ---- 原文结束 ----")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--repeat", type=int, default=6)
    ap.add_argument("--group", choices=["G1", "G2", "G3", "all"], default="all")
    ap.add_argument("--question", help="只测这一个自定义问题（会归入 --group 指定的组）")
    ap.add_argument("--arm", default="?", help="臂标记（由外部装置传入，如 B-adjacent-help）")
    ap.add_argument("--output")
    args = ap.parse_args()

    print(f"范围护栏复验探针  臂={args.arm}  repeat={args.repeat}")
    user, token = register(args.base)
    rows = []
    if args.question:
        gk = "G1" if args.group == "all" else args.group
        spec = GROUPS[gk]
        print(f"\n{'='*72}\n  [{gk}] 自定义问题  期望：{spec['expect']}")
        for i in range(args.repeat):
            cid = "rsx_" + uuid.uuid4().hex
            t0 = time.time()
            try:
                r = sse(args.base, "/Love_app/chat/sse", {"prompt": args.question, "chatId": cid}, token)
                label, why = classify(r["text"])
                row = {"round": i + 1, "ok": r["success"], "chars": len(r["text"]),
                       "seconds": round(time.time() - t0, 1), "ttft_ms": r.get("ttft_ms"),
                       "errors": r["errors"], "label": label, "why": why,
                       "defer_signals": defer_signals(r["text"]), "text": r["text"]}
                print(f"    轮{i+1} {row['seconds']:>5}s {row['chars']:>4}字  [{label}] {why}", flush=True)
            except Exception as e:  # noqa: BLE001
                row = {"round": i + 1, "ok": False, "chars": 0, "seconds": round(time.time() - t0, 1),
                       "ttft_ms": None, "errors": [f"{type(e).__name__}: {e}"],
                       "label": "error", "why": str(e)[:80], "defer_signals": [], "text": ""}
            rows.append({"group": gk, "question": args.question, **row})
        _summarize([r for r in rows], gk, args.question, spec["expect"])
    else:
        groups = list(GROUPS) if args.group == "all" else [args.group]
        for gk in groups:
            run_group(args.base, token, gk, GROUPS[gk]["questions"], args.repeat, rows)

    print(f"\n{'='*72}\n  总计 {len(rows)} 条记录。⚠️ 自动分类是粗筛，结论必须人工读原文复核。")
    if args.output:
        Path(args.output).write_text(json.dumps(
            {"arm": args.arm, "repeat": args.repeat, "rows": rows}, ensure_ascii=False, indent=2),
            encoding="utf-8")
        print(f"  已写入 {args.output}")


if __name__ == "__main__":
    main()
