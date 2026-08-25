#!/usr/bin/env bash
# 从 Redis checkpoint 导出 chunks / context / result 供离线评测
# 用法: dump_checkpoint.sh <mediaId> [outdir]
set -euo pipefail
ID="$1"; OUT="${2:-.}"
R="${REDIS_CLI:-redis-cli} -a ${REDIS_PASSWORD:?请先 export REDIS_PASSWORD} --no-auth-warning"

# chunks（媒体级 hash）
$R HGET "agent:checkpoint:$ID" chunks > "$OUT/chunks_$ID.json" 2>/dev/null
# context（媒体级 hash，含 segments——证据校验的 ground truth）
$R HGET "agent:checkpoint:$ID" context > "$OUT/context_$ID.json" 2>/dev/null
# result（目标级 hash：agent:checkpoint:{id}:goal:{digest}）——goal digest 用 HKEYS 找
$R --scan --pattern "agent:checkpoint:$ID:goal:*" | head -1 | xargs -I{} $R HGET "{}" result > "$OUT/result_$ID.json" 2>/dev/null
for f in chunks context result; do
  sz=$(wc -c < "$OUT/${f}_${ID}.json")
  echo "${f}_${ID}.json: $sz bytes"
done
