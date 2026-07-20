#!/usr/bin/env python3
"""
Phase 0 Analysis: 读取 phase0-report.json，分析三个核心指标。

使用方式:
    pip install -r requirements.txt
    python analyze.py --input ../../target/phase0-report.json --output phase0-report.md
"""
import json
import argparse
import statistics
from pathlib import Path
from datetime import datetime


def load_data(path: str) -> list:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def compute_latency_distribution(records: list) -> dict:
    durations = sorted([r["durationMs"] for r in records if r["success"]])
    if not durations:
        return {}
    n = len(durations)

    def pct(p):
        idx = max(0, min(int(p / 100 * n) - 1, n - 1))
        return durations[idx]

    return {
        "count": n,
        "min_ms": durations[0],
        "max_ms": durations[-1],
        "mean_ms": round(statistics.mean(durations)),
        "median_ms": round(statistics.median(durations)),
        "p50_ms": pct(50),
        "p90_ms": pct(90),
        "p95_ms": pct(95),
        "p99_ms": pct(99),
        "total_seconds": round(sum(durations) / 1000, 1),
    }


def compute_retrieval_analysis(records: list) -> dict:
    retrieval_records = [r for r in records if r.get("retrievalCalled")]
    total = len(retrieval_records)
    if total == 0:
        return {"total": 0, "note": "No retrieval calls recorded"}
    success = sum(1 for r in retrieval_records if r["success"])
    durations = [r["durationMs"] for r in retrieval_records if r["success"]]
    return {
        "total": total,
        "success": success,
        "hit_rate_pct": round(success / total * 100, 1) if total > 0 else 0,
        "avg_latency_ms": round(statistics.mean(durations)) if durations else 0,
        "max_latency_ms": max(durations) if durations else 0,
    }


def compute_tool_analysis(records: list) -> dict:
    tool_records = [r for r in records if r.get("toolsCalled")]
    total = len(tool_records)
    if total == 0:
        return {"total": 0, "note": "No tool calls recorded"}
    success = sum(1 for r in tool_records if r["success"])
    durations = [r["durationMs"] for r in tool_records if r["success"]]
    return {
        "total": total,
        "success": success,
        "success_rate_pct": round(success / total * 100, 1) if total > 0 else 0,
        "avg_latency_ms": round(statistics.mean(durations)) if durations else 0,
    }


def compute_category_breakdown(records: list) -> dict:
    categories = {}
    for r in records:
        cat = r.get("category", "unknown")
        if cat not in categories:
            categories[cat] = {"count": 0, "success": 0, "durations": []}
        categories[cat]["count"] += 1
        if r["success"]:
            categories[cat]["success"] += 1
            categories[cat]["durations"].append(r["durationMs"])

    breakdown = {}
    for cat, data in categories.items():
        breakdown[cat] = {
            "count": data["count"],
            "success": data["success"],
            "success_rate_pct": round(data["success"] / data["count"] * 100, 1),
            "avg_latency_ms": round(statistics.mean(data["durations"])) if data["durations"] else 0,
        }
    return breakdown


def generate_report(records: list, output_path: str = None) -> str:
    total = len(records)
    success = sum(1 for r in records if r["success"])
    failed = total - success

    latency = compute_latency_distribution(records)
    retrieval = compute_retrieval_analysis(records)
    tools = compute_tool_analysis(records)
    categories = compute_category_breakdown(records)

    lines = []
    lines.append("# Phase 0 Evaluation Report")
    lines.append(f"\n> Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    lines.append(f"> Total conversations: {total}")
    lines.append(f"> Success: {success} | Failed: {failed}")
    lines.append(f"> Success rate: **{round(success / total * 100, 1) if total > 0 else 0}%**")
    lines.append("")

    # 1. Latency Distribution
    lines.append("## 1. Response Latency Distribution")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|--------|-------|")
    if latency:
        lines.append(f"| Count (successful) | {latency['count']} |")
        lines.append(f"| Min | {latency['min_ms']} ms |")
        lines.append(f"| Max | {latency['max_ms']} ms |")
        lines.append(f"| Mean | {latency['mean_ms']} ms |")
        lines.append(f"| Median (p50) | {latency['median_ms']} ms |")
        lines.append(f"| p90 | {latency['p90_ms']} ms |")
        lines.append(f"| p95 | {latency['p95_ms']} ms |")
        lines.append(f"| p99 | {latency['p99_ms']} ms |")
        lines.append(f"| Total wall time | {latency['total_seconds']}s |")

    lines.append("")
    lines.append("** Interpretation:**")
    lines.append("- p50 < 2s → good user experience")
    lines.append("- p90 < 5s → acceptable")
    lines.append("- p99 > 10s → needs optimization (check LLM + retrieval bottlenecks)")
    lines.append("")

    # 2. Retrieval Analysis
    lines.append("## 2. Retrieval Hit Rate")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|--------|-------|")
    lines.append(f"| Total RAG queries | {retrieval.get('total', 0)} |")
    lines.append(f"| Successful | {retrieval.get('success', 0)} |")
    lines.append(f"| Hit rate | **{retrieval.get('hit_rate_pct', 'N/A')}%** |")
    lines.append(f"| Avg retrieval latency | {retrieval.get('avg_latency_ms', 'N/A')} ms |")
    lines.append(f"| Max retrieval latency | {retrieval.get('max_latency_ms', 'N/A')} ms |")
    lines.append("")
    if retrieval.get("total", 0) > 0 and retrieval["hit_rate_pct"] < 80:
        lines.append("⚠️ **Hit rate below 80%** — consider hybrid retrieval (ES BM25 + RRF)")
    lines.append("")

    # 3. Tool Call Analysis
    lines.append("## 3. Tool Call Success Rate")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|--------|-------|")
    lines.append(f"| Total tool queries | {tools.get('total', 0)} |")
    lines.append(f"| Successful | {tools.get('success', 0)} |")
    lines.append(f"| Success rate | **{tools.get('success_rate_pct', 'N/A')}%** |")
    lines.append(f"| Avg tool latency | {tools.get('avg_latency_ms', 'N/A')} ms |")
    lines.append("")
    if tools.get("total", 0) > 0 and tools["success_rate_pct"] < 80:
        lines.append("⚠️ **Tool success rate below 80%** — check tool execution flow")
    lines.append("")

    # 4. Category Breakdown
    lines.append("## 4. Category Breakdown")
    lines.append("")
    lines.append("| Category | Count | Success Rate | Avg Latency |")
    lines.append("|----------|-------|-------------|-------------|")
    for cat, data in sorted(categories.items()):
        lines.append(f"| {cat} | {data['count']} | {data['success_rate_pct']}% | {data['avg_latency_ms']}ms |")
    lines.append("")

    # 5. Top 3 Bottlenecks
    lines.append("## 5. Identified Bottlenecks (Top 3)")
    lines.append("")
    bottlenecks = []
    if latency:
        if latency["p90_ms"] > 5000:
            bottlenecks.append(f"1. **High p90 latency ({latency['p90_ms']}ms)** — response too slow for users")
        if retrieval.get("total", 0) > 0 and retrieval["hit_rate_pct"] < 80:
            bottlenecks.append(f"2. **Low retrieval hit rate ({retrieval['hit_rate_pct']}%)** — RAG needs hybrid upgrade")
        if tools.get("total", 0) > 0 and tools["success_rate_pct"] < 80:
            bottlenecks.append(f"3. **Low tool success rate ({tools['success_rate_pct']}%)** — tool execution needs review")
        if not bottlenecks:
            bottlenecks.append("1. No critical bottlenecks detected — current performance is acceptable.")
        for b in bottlenecks:
            lines.append(b)
    lines.append("")

    # 6. Failed queries detail
    if failed > 0:
        lines.append("## 6. Failed Queries")
        lines.append("")
        lines.append("| # | Category | Query | Error |")
        lines.append("|---|----------|-------|-------|")
        for i, r in enumerate(records):
            if not r["success"]:
                q = r["query"][:60]
                lines.append(f"| {r['id']} | {r['category']} | {q}... | {r.get('errorMessage', 'N/A')[:100]} |")
        lines.append("")

    report = "\n".join(lines)

    if output_path:
        Path(output_path).write_text(report, encoding="utf-8")
        print(f"Report saved to {output_path}")

    return report


def main():
    parser = argparse.ArgumentParser(description="Phase 0 Evaluation Analysis")
    parser.add_argument("--input", default="../../target/phase0-report.json",
                        help="Path to phase0-report.json (default: ../../target/phase0-report.json)")
    parser.add_argument("--output", default="phase0-report.md",
                        help="Output report path (default: phase0-report.md)")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Input file not found: {input_path}")
        print("Run Phase0EvaluationTest first to generate the data.")
        return

    records = load_data(args.input)
    print(f"Loaded {len(records)} conversation records")

    report = generate_report(records, args.output)
    print(report)


if __name__ == "__main__":
    main()
