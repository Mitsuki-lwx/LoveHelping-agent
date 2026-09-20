#!/bin/bash
# 影子观测语料跑批 —— 一键复现（docs/phase7-jev-shadow §S5）
#
#   1) 从 agentdb 导出 USER 消息（密文，TSV）
#   2) 编译并运行 scripts/JevShadowCorpus.java
#      —— 它复用产线类：真实 EncryptionService 解密、真实 GuardrailRuleService 判词典、
#         真实 JevSelfHarmSignal.judge 调第二信号（问句与阈值都取自产线配置）
#   3) 生成统计报告（scripts/jev_shadow_observe.py）
#
# 用法：
#   JEV_KEY=apikey_xxx bash scripts/run_jev_shadow_batch.sh [每层随机条数] [并发] [尾部最小词典等级]
#   例：JEV_KEY=... bash scripts/run_jev_shadow_batch.sh 400 4 2
#
# 注意：明文语料只落在 outputs/（已 gitignore），不提交。
set -u
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
MYSQL=/d/mysql/bin/mysql
PER_STRATUM="${1:-400}"
THREADS="${2:-4}"
MIN_LEVEL="${3:-2}"
STAMP=$(date +%H%M%S)

if [ -z "${JEV_KEY:-}" ]; then echo "JEV_KEY 未设置（只从环境变量读，不写配置文件）"; exit 1; fi
if [ ! -f target/e2e-classpath.txt ]; then
  echo "缺 target/e2e-classpath.txt（构建依赖清单）——先跑一次构建或参见 logs/ 下的历史脚本"; exit 1
fi

TSV="outputs/shadow-corpus-raw-$STAMP.tsv"
RAW="outputs/shadow-batch-$STAMP.jsonl"
REPORT="outputs/shadow-report-$STAMP.json"

echo "=== 1. 导出密文语料 ==="
$MYSQL -uroot -p123456 --batch --raw --default-character-set=utf8mb4 agentdb -e "
select id, conversation_id, user_id, content from message
where role='USER' and deleted=0 and content is not null and content<>''
" > "$TSV" 2>/dev/null
echo "  $TSV  $(wc -l < "$TSV") 行"

echo "=== 2. 编译并跑批（复用产线类）==="
CP="target/classes;$(cat target/e2e-classpath.txt)"
mkdir -p logs/classes
$JAVA -version >/dev/null 2>&1
/d/jdk/bin/javac -encoding UTF-8 -proc:none -cp "$CP" -d logs/classes scripts/JevShadowCorpus.java
JEV_KEY="$JEV_KEY" $JAVA -Dfile.encoding=UTF-8 -cp "logs/classes;$CP" \
  JevShadowCorpus "$TSV" "$RAW" "$PER_STRATUM" "$THREADS" "$MIN_LEVEL" 600 2>&1 | grep -av "SLF4J"

echo "=== 3. 统计报告 ==="
$PY scripts/jev_shadow_observe.py --input "$RAW" --output "$REPORT" --top 40

echo "DONE_SHADOW_BATCH RAW=$RAW REPORT=$REPORT"
