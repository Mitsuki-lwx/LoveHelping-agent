"""Jev（TypeSafe AI /systemone）路由可行性探针。

对同一批用例，分别取「Jev 判定」与「产线 CapabilityRouter 判定」，各自与人工标签比对。
Key 只从环境变量 JEV_KEY 读，不落盘、不入库。

用法：
    JEV_KEY=... python scripts/jev_routing_probe.py --output outputs/jev-routing.json
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ENDPOINT = "https://api.typesafe.ai/v1/systemone"
MODEL = "jev-latest"

ROUTE_OPTIONS = {
    "off_topic": "与恋爱/亲密关系无关的请求：写代码、写作业论文、办公文档表格、翻译资料、数理化题目等",
    "simple": "纯寒暄或短句，没有实质问题：打招呼、道谢、道晚安、附和",
    "advice": "要的是「具体怎么说/怎么做」的沟通话术或行动建议：怎么回复、怎么道歉、怎么表白、怎么开口",
    "agent": "需要查实时或外部信息：查天气、联网搜索、查最新政策、找附近地点",
    "normal": "情感/亲密关系咨询本身，陈述困惑或求分析（不需要话术模板，也不需要查外部信息）",
}

QUESTIONS = {
    "route": {
        "type": "choice",
        "instructions": "把这条用户消息归到最合适的一类。以用户真正想要的东西为准，不要被个别关键词带偏。",
        "criteria": ROUTE_OPTIONS,
    },
    "self_harm_risk": {
        "type": "noul",
        "instructions": "这条消息是否表达了自伤、自杀或「不想活下去」的意愿（含口语、隐晦、玩笑式的表达）？",
        "criteria": {
            "true": "有自伤/自杀意愿或绝望到不想活的表达",
            "false": "没有这类表达，或只是抱怨劳累、夸张修辞（如「累死了」「游戏里死了」）",
        },
    },
}


def call(message, key, retries=3):
    body = json.dumps({"state": {"user_message": message}, "model": MODEL, "questions": QUESTIONS},
                      ensure_ascii=False).encode()
    for attempt in range(retries + 1):
        request = urllib.request.Request(
            ENDPOINT, data=body,
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        started = time.time()
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.status, json.loads(response.read()), time.time() - started, ""
        except urllib.error.HTTPError as error:
            detail = ""
            try:
                detail = error.read().decode("utf-8", "replace")[:300]
            except Exception:
                pass
            if error.code in (429, 529) and attempt < retries:
                time.sleep(2 ** attempt)
                continue
            return error.code, None, time.time() - started, detail
        except Exception as error:  # 网络层
            if attempt < retries:
                time.sleep(2 ** attempt)
                continue
            return 0, None, time.time() - started, repr(error)[:300]
    return 0, None, 0.0, "unreachable"


def main(args):
    key = os.environ.get("JEV_KEY", "")
    if not key:
        raise SystemExit("JEV_KEY 环境变量未设置")
    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))["cases"]

    rows = []
    for case in cases:
        status, payload, elapsed, detail = call(case["q"], key)
        row = {"id": case["id"], "q": case["q"], "label_route": case["route"], "label_risk": case["risk"],
               "source": case.get("source", ""), "status": status, "latency_s": round(elapsed, 2),
               "error": detail}
        if status == 200 and payload:
            answers = payload.get("answers", {})
            route = answers.get("route", {})
            risk = answers.get("self_harm_risk", {})
            row.update({
                "model": payload.get("model"),
                "usage": payload.get("usage"),
                "jev_route": route.get("choice"),
                "jev_route_confidence": route.get("confidence"),
                "jev_route_probs": route.get("probabilities"),
                "jev_risk": risk.get("noul"),
            })
        rows.append(row)
        print("%-22s %-10s %-10s conf=%-5s risk=%-5s %5.2fs http=%s" % (
            row["id"], row["label_route"], row.get("jev_route"), row.get("jev_route_confidence"),
            row.get("jev_risk"), row["latency_s"], status), flush=True)

    ok = [r for r in rows if r["status"] == 200]
    route_hit = [r for r in ok if r.get("jev_route") == r["label_route"]]
    risk_expected = [r for r in ok if r["label_risk"] == 1]
    risk_hit = [r for r in risk_expected if (r.get("jev_risk") or 0) >= 0.5]
    risk_neg = [r for r in ok if r["label_risk"] == 0]
    risk_false_pos = [r for r in risk_neg if (r.get("jev_risk") or 0) >= 0.5]
    latencies = sorted(r["latency_s"] for r in ok)

    report = {
        "endpoint": ENDPOINT, "model": MODEL, "cases": len(rows), "ok": len(ok),
        "route_accuracy": round(len(route_hit) / len(ok), 4) if ok else None,
        "route_wrong": [{"id": r["id"], "label": r["label_route"], "jev": r.get("jev_route"),
                         "conf": r.get("jev_route_confidence")} for r in ok if r.get("jev_route") != r["label_route"]],
        "risk_recall": round(len(risk_hit) / len(risk_expected), 4) if risk_expected else None,
        "risk_false_positive": [r["id"] for r in risk_false_pos],
        "risk_missed": [r["id"] for r in risk_expected if (r.get("jev_risk") or 0) < 0.5],
        "latency_p50_s": latencies[len(latencies) // 2] if latencies else None,
        "latency_max_s": latencies[-1] if latencies else None,
        "input_tokens_total": sum((r.get("usage") or {}).get("input_tokens", 0) for r in ok),
        "output_tokens_total": sum((r.get("usage") or {}).get("output_tokens", 0) for r in ok),
        "rows": rows,
    }
    if args.rule_dump and Path(args.rule_dump).exists():
        rules = {r["id"]: r["rule_route"] for r in json.loads(Path(args.rule_dump).read_text(encoding="utf-8"))["rows"]}
        rule_hit = [r for r in ok if rules.get(r["id"]) == r["label_route"]]
        report["rule_accuracy"] = round(len(rule_hit) / len(ok), 4) if ok else None
        report["rule_wrong"] = [{"id": r["id"], "label": r["label_route"], "rule": rules.get(r["id"])}
                                for r in ok if rules.get(r["id"]) != r["label_route"]]
    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=== 汇总 ===")
    print("成功 %d/%d  route_accuracy(Jev)=%s  rule_accuracy=%s" % (
        len(ok), len(rows), report["route_accuracy"], report.get("rule_accuracy")))
    print("risk_recall(Jev)=%s  false_positive=%s  missed=%s" % (
        report["risk_recall"], report["risk_false_positive"], report["risk_missed"]))
    print("latency p50=%ss max=%ss  tokens in/out=%s/%s" % (
        report["latency_p50_s"], report["latency_max_s"],
        report["input_tokens_total"], report["output_tokens_total"]))
    if report.get("route_wrong"):
        print("Jev 错例：", json.dumps(report["route_wrong"], ensure_ascii=False))
    if report.get("rule_wrong"):
        print("规则错例：", json.dumps(report["rule_wrong"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="scripts/jev_routing_cases.json")
    parser.add_argument("--rule-dump", default="")
    parser.add_argument("--output")
    sys.exit(main(parser.parse_args()))
