#!/usr/bin/env bash
# WU-RP-022 — Performance baseline harness (RP-2).
# Reproducible measurement against the installed distribution.
# Usage: ./rp022_perf_baseline.sh <output.json>
set -euo pipefail

DIST=/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/build/install/pipelinek
BIN=$DIST/bin/pipelinek
OUT=${1:?usage: rp022_perf_baseline.sh <output.json>}
WORK=$(mktemp -d /tmp/rp022-baseline.XXXXXX)
trap 'rm -rf "$WORK"' EXIT

SHA=$(git -C /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin rev-parse HEAD)
HOST=$(hostname)
KERNEL=$(uname -sr)
CORES=$(nproc)
MEM_KB=$(awk '/MemTotal/{print $2}' /proc/meminfo)

# helper: run a command N times, report wall ms stats via /usr/bin/time
run_timed() { # run_timed <label> <iterations> <cmd...>
  local label=$1 iters=$2; shift 2
  local times=() i ms
  for ((i=0;i<iters;i++)); do
    local t0=$(date +%s%N)
    "$@" > "$WORK/last.out" 2> "$WORK/last.err" || true
    local t1=$(date +%s%N)
    times+=( $(( (t1 - t0) / 1000000 )) )
  done
  printf '%s\n' "${times[@]}" | sort -n | awk -v lbl="$label" -v n="$iters" '
    {v[NR]=$1; sum+=$1}
    END {printf("  {\"label\":\"%s\",\"iters\":%d,\"min\":%d,\"median\":%d,\"max\":%d,\"mean\":%.1f},\n",
      lbl, n, v[1], v[int((NR+1)/2)], v[NR], sum/NR)}' | tee -a "$OUT.times"
}

echo "=== WU-RP-022 perf baseline ===" | tee "$OUT.times"
echo "\"meta\":{\"sha\":\"$SHA\",\"host\":\"$HOST\",\"kernel\":\"$KERNEL\",\"cores\":$CORES,\"memKB\":$MEM_KB,\"date\":\"$(date -u +%FT%TZ)\"}" | tee -a "$OUT.times"

# --- fixtures -------------------------------------------------------------
cat > "$WORK/startup.pipeline.kts" <<'KTS'
pipeline {
    stages {
        stage("noop") {
            echo("ok")
        }
    }
}
KTS

# 1. Startup + compile + run, cold-ish (echo only)
echo ',"M1_startup_compile_run":[' >> "$OUT.times"
run_timed "echo-run" 5 "$BIN" run --db "$WORK/m1.db" "$WORK/startup.pipeline.kts"
run_timed "echo-run-fresh-db" 5 bash -c "rm -rf $WORK/m1f.db && $BIN run --db $WORK/m1f.db $WORK/startup.pipeline.kts"
echo '] ' >> "$OUT.times"

# 2. Compile cache: same script, same db -> compile cache hit
echo ',"M2_compile_cache":[' >> "$OUT.times"
run_timed "warm-db-rerun" 5 "$BIN" run --db "$WORK/m1.db" "$WORK/startup.pipeline.kts"
echo ']' >> "$OUT.times"

# 3. 200 MiB stdout emission through sh (transcript + capture path)
cat > "$WORK/big.pipeline.kts" <<KTS
pipeline {
    stages {
        stage("big") {
            sh("dd if=/dev/zero bs=1M count=200 2>/dev/null | tr '\\\\0' 'x'")
        }
    }
}
KTS
echo ',"M3_200MiB_stdout":[' >> "$OUT.times"
local_t0=$(date +%s%N)
"$BIN" run --db "$WORK/m3.db" "$WORK/big.pipeline.kts" > "$WORK/m3.stdout" 2> "$WORK/m3.stderr" || true
local_t1=$(date +%s%N)
echo "  {\"label\":\"200MiB-once\",\"wallMs\":$(( (local_t1-local_t0)/1000000 )),\"stdoutBytes\":$(stat -c%s "$WORK/m3.stdout")}," >> "$OUT.times"
echo ']' >> "$OUT.times"

# 4. Slow consumer: stdout pipe read slowly (backpressure via head+sleep)
cat > "$WORK/slow.pipeline.kts" <<KTS
pipeline {
    stages {
        stage("slow") {
            sh("for i in \\$(seq 1 20); do echo line-\\$i; sleep 0.2; done")
        }
    }
}
KTS
echo ',"M4_slow_consumer":[' >> "$OUT.times"
local_t0=$(date +%s%N)
"$BIN" run --db "$WORK/m4.db" "$WORK/slow.pipeline.kts" > "$WORK/m4.stdout" 2>/dev/null || true
local_t1=$(date +%s%N)
echo "  {\"label\":\"20x200ms-lines\",\"wallMs\":$(( (local_t1-local_t0)/1000000 ))}" >> "$OUT.times"
echo ']' >> "$OUT.times"

# 5. Soak >= 1 GiB stdout (single pass, resource-sampled)
cat > "$WORK/soak.pipeline.kts" <<KTS
pipeline {
    stages {
        stage("soak") {
            sh("dd if=/dev/zero bs=1M count=1024 2>/dev/null | tr '\\\\0' 'y'")
        }
    }
}
KTS
echo ',"M5_soak_1GiB":[' >> "$OUT.times"
"$BIN" run --db "$WORK/m5.db" "$WORK/soak.pipeline.kts" > "$WORK/m5.stdout" 2> "$WORK/m5.stderr" &
SOAK_PID=$!
SOAK_MAX_RSS=0
while kill -0 $SOAK_PID 2>/dev/null; do
  # max RSS across the process tree's java proc
  RSS=$(ps -o rss= -p $SOAK_PID 2>/dev/null | head -n1)
  JRSS=$(pgrep -P $SOAK_PID -d' ' 2>/dev/null | while read p; do ps -o rss= -p $p 2>/dev/null; done | sort -n | tail -n1)
  for v in "$RSS" "$JRSS"; do
    [ -n "$v" ] && [ "$v" -gt "$SOAK_MAX_RSS" ] 2>/dev/null && SOAK_MAX_RSS=$v
  done
  sleep 0.3
done
wait $SOAK_PID || true
echo "  {\"label\":\"1GiB-once\",\"stdoutBytes\":$(stat -c%s "$WORK/m5.stdout" 2>/dev/null || echo 0),\"maxRssKB\":$SOAK_MAX_RSS}" >> "$OUT.times"
echo ']' >> "$OUT.times"

# 6. CPU time of a plain run (from /usr/bin/time)
echo ',"M6_cpu":[' >> "$OUT.times"
/usr/bin/time -f '  {"label":"cpu-echo-run","wallSec":%e,"userSec":%U,"sysSec":%S,"maxRssKB":%M}' \
  "$BIN" run --db "$WORK/m6.db" "$WORK/startup.pipeline.kts" >/dev/null 2>>"$OUT.times" || true
echo ']' >> "$OUT.times"

echo "=== done: $(cat "$OUT.times" | wc -l) lines ==="
echo "Raw results in $OUT.times"
