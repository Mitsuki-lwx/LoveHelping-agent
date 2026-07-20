# Phase 0 Evaluation Report

> Generated: 2026-07-17 19:42
> Total conversations: 12
> Success: 12 | Failed: 0
> Success rate: **100.0%**

## 1. Response Latency Distribution

| Metric | Value |
|--------|-------|
| Count (successful) | 12 |
| Min | 5881 ms |
| Max | 53559 ms |
| Mean | 16194 ms |
| Median (p50) | 11281 ms |
| p90 | 18809 ms |
| p95 | 34153 ms |
| p99 | 34153 ms |
| Total wall time | 194.3s |

** Interpretation:**
- p50 < 2s → good user experience
- p90 < 5s → acceptable
- p99 > 10s → needs optimization (check LLM + retrieval bottlenecks)

## 2. Retrieval Hit Rate

| Metric | Value |
|--------|-------|
| Total RAG queries | 3 |
| Successful | 3 |
| Hit rate | **100.0%** |
| Avg retrieval latency | 7870 ms |
| Max retrieval latency | 9784 ms |


## 3. Tool Call Success Rate

| Metric | Value |
|--------|-------|
| Total tool queries | 12 |
| Successful | 12 |
| Success rate | **100.0%** |
| Avg tool latency | 16194 ms |


## 4. Category Breakdown

| Category | Count | Success Rate | Avg Latency |
|----------|-------|-------------|-------------|
| eval | 12 | 100.0% | 16194ms |

## 5. Identified Bottlenecks (Top 3)

1. **High p90 latency (18809ms)** — response too slow for users
