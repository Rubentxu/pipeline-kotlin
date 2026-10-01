#!/usr/bin/env bash
# WU-RP-5 / PR-015: repeatable 1 GiB soak with an RSS SLO (RP-2 M5 successor).
#
# RP-2 certified the 1 GiB soak for integrity and exit code but explicitly
# carried "RSS sin SLO". This script turns that scenario into a repeatable
# gate: same soak, byte integrity check, and a peak-RSS assertion whose value
# comes from MEASURED runs (never invented).
#
# Usage:
#   scripts/perf-soak-rss.sh <pipelinek-binary> [slo-mb]
#
#   <pipelinek-binary>  the installed pipelinek launcher
#   [slo-mb]            peak-RSS SLO in MiB. When omitted the script measures,
#                       prints the observed peak and skips the assertion
#                       (calibration mode). Set it once calibrated.
#
# Methodology notes:
#   - Run on an otherwise idle machine; this is a measurement, not a race
#     (the RP035 probe lesson: contention oracles do not belong in gates).
#   - The soak streams 1 GiB through `sh` so the child stdout crosses the
#     console/redaction path, matching the RP-2 M5 shape.
set -euo pipefail

BIN="${1:?usage: perf-soak-rss.sh <pipelinek-binary> [slo-mb]}"
SLO_MB="${2:-}"
EXPECTED_BYTES=1073741824  # 1 GiB

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT

# Fixture: the soak pipeline cats a 1 GiB file through sh.
head -c "$EXPECTED_BYTES" /dev/zero | tr '\0' 'a' > "$T/big.txt"
cat > "$T/soak.pipeline.kts" <<EOF
pipeline {
    stages {
        stage("Soak") {
            sh("cat $T/big.txt")
        }
    }
}
EOF

echo "[soak] streaming $EXPECTED_BYTES bytes through the console path..."
/usr/bin/time -v "$BIN" run "$T/soak.pipeline.kts" > "$T/out.bin" 2> "$T/time.txt"
EXIT=$?

ACTUAL_BYTES=$(wc -c < "$T/out.bin")
RSS_KB=$(grep -oE "Maximum resident set size \(kbytes\): [0-9]+" "$T/time.txt" | grep -oE "[0-9]+$")
RSS_MB=$(( RSS_KB / 1024 ))

echo "[soak] exit=$EXIT bytes=$ACTUAL_BYTES (expected $EXPECTED_BYTES) peakRSS=${RSS_MB}MiB"

if [[ "$EXIT" != "0" ]]; then
    echo "[soak] FAIL: pipeline exited $EXIT" >&2
    exit 1
fi
# The console path carries the child bytes PLUS pipelinek's own transcript lines
# (discovery, stage/run events, final summary). RP-2's M5 had the same shape
# (1 GiB + 292 bytes). Integrity = every soak byte crossed + bounded overhead,
# not exact equality with the raw payload.
OVERHEAD=$(( ACTUAL_BYTES - EXPECTED_BYTES ))
if (( OVERHEAD < 0 || OVERHEAD > 65536 )); then
    echo "[soak] FAIL: stdout integrity broken ($ACTUAL_BYTES != $EXPECTED_BYTES + transcript overhead)" >&2
    exit 1
fi

if [[ -z "$SLO_MB" ]]; then
    echo "[soak] CALIBRATION: observed peak RSS ${RSS_MB}MiB — set the SLO above this with headroom."
    exit 0
fi

if (( RSS_MB > SLO_MB )); then
    echo "[soak] FAIL: peak RSS ${RSS_MB}MiB exceeds the SLO of ${SLO_MB}MiB" >&2
    exit 1
fi
echo "[soak] PASS: peak RSS ${RSS_MB}MiB within the ${SLO_MB}MiB SLO"
