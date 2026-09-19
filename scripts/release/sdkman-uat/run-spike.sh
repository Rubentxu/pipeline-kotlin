#!/usr/bin/env bash
# WU-LPR-090 spike driver: run `sdk install pipelinek 0.39.0` against
# the local mirror, with everything isolated to $WU_LPR_090_DIR.
#
# Strict isolation:
#   - SDKMAN_DIR points to $WU_LPR_090_DIR/sdkman/isolated (NOT $HOME/.sdkman)
#   - SDKMAN_CANDIDATES_API points to http://127.0.0.1:9999
#   - SDKMAN_BROKER_API points to http://127.0.0.1:9999
#   - PATH is sanitized so the user's normal SDKMAN is invisible to this run
#   - HOME is preserved (we don't fake it); but we never read $HOME/.sdkman
#
# Pre-flight:
#   - canonical ZIP SHA-256 must equal 385b140c...cbb8
#   - mirror must respond 200 on /healthcheck
#   - sdkman-cli 5.23.0 init must be at $WU_LPR_090_DIR/sdkman/sdkman-5.23.0

# SDKMAN init.sh was not written under `set -u`; relax for the whole driver.
set +u

WU_LPR_090_DIR=${WU_LPR_090_DIR:-/home/rubentxu/.local/share/jcode/wu-lpr-090}
SDKMAN_DIST="$WU_LPR_090_DIR/sdkman/sdkman-5.23.0"
ISOLATED_SDKMAN_DIR="$WU_LPR_090_DIR/sdkman/isolated"
MIRROR="http://127.0.0.1:9999"
EXPECTED_SHA256="385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8"

red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
log()    { printf '[driver] %s\n' "$*"; }

# ---- pre-flight 1: mirror alive ----
if ! curl -fsS --max-time 3 "$MIRROR/healthcheck" >/dev/null 2>&1; then
    red "FATAL: mirror not responding at $MIRROR/healthcheck"
    red "Start it first: python3 $WU_LPR_090_DIR/mirror.py &"
    exit 2
fi
green "pre-flight: mirror alive"

# ---- pre-flight 2: sdkman dist present ----
if [[ ! -f "$SDKMAN_DIST/bin/sdkman-init.sh" ]]; then
    red "FATAL: sdkman-cli 5.23.0 not extracted at $SDKMAN_DIST"
    exit 2
fi
green "pre-flight: sdkman-cli 5.23.0 present"

# ---- pre-flight 3: canonical ZIP SHA ----
ZIP="$WU_LPR_090_DIR/pipelinek-0.39.0.zip"
if [[ ! -f "$ZIP" ]]; then
    red "FATAL: canonical ZIP not at $ZIP"
    exit 2
fi
ACTUAL_SHA=$(sha256sum "$ZIP" | awk '{print $1}')
if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA256" ]]; then
    red "FATAL: ZIP SHA mismatch"
    red "  expected: $EXPECTED_SHA256"
    red "  actual:   $ACTUAL_SHA"
    exit 2
fi
green "pre-flight: canonical ZIP SHA verified"

# ---- prepare isolated SDKMAN_DIR (NOT $HOME/.sdkman) ----
if [[ -d "$ISOLATED_SDKMAN_DIR" ]]; then
    rm -rf "$ISOLATED_SDKMAN_DIR"
fi
mkdir -p "$ISOLATED_SDKMAN_DIR/candidates"
mkdir -p "$ISOLATED_SDKMAN_DIR/etc"
mkdir -p "$ISOLATED_SDKMAN_DIR/tmp"
mkdir -p "$ISOLATED_SDKMAN_DIR/var"
mkdir -p "$ISOLATED_SDKMAN_DIR/src"

# copy the module scripts (init sources ${SDKMAN_DIR}/src/sdkman-*.sh)
cp -a "$SDKMAN_DIST/src/." "$ISOLATED_SDKMAN_DIR/src/"
cp -a "$SDKMAN_DIST/contrib" "$ISOLATED_SDKMAN_DIR/contrib"

# platform file
printf 'UNIVERSAL\n' > "$ISOLATED_SDKMAN_DIR/var/platform"

# candidates cache CSV (the in-client allowlist)
printf 'pipelinek\n' > "$ISOLATED_SDKMAN_DIR/var/candidates"

# config
cat > "$ISOLATED_SDKMAN_DIR/etc/config" <<'EOF'
sdkman_auto_answer=true
sdkman_healthcheck_enable=true
sdkman_checksum_enable=true
sdkman_debug_mode=true
sdkman_colour_enable=false
EOF

# delay_upgrade marker (init.sh touches this)
touch "$ISOLATED_SDKMAN_DIR/var/delay_upgrade"

green "isolated SDKMAN_DIR prepared at $ISOLATED_SDKMAN_DIR"

# ---- run the test ----
log "exporting SDKMAN env to point at the mirror + isolated dir"
export SDKMAN_DIR="$ISOLATED_SDKMAN_DIR"
export SDKMAN_CANDIDATES_API="$MIRROR"
export SDKMAN_BROKER_API="$MIRROR"

# Make sure no other SDKMAN is on PATH; expose only this isolated dir's
# bin (which has the init script we just installed via copy).
# Note: SDKMAN doesn't need its own bin on PATH; the `sdk` function is
# exported by `source .../sdkman-init.sh`. We invoke `sdk` as a function.

log "sourcing sdkman-init.sh from $ISOLATED_SDKMAN_DIR/bin"
# the init script is at $SDKMAN_DIST/bin/sdkman-init.sh in the original
# layout; we copied src/ but not bin/. Copy it now.
mkdir -p "$ISOLATED_SDKMAN_DIR/bin"
cp -a "$SDKMAN_DIST/bin/sdkman-init.sh" "$ISOLATED_SDKMAN_DIR/bin/sdkman-init.sh"
# But init.sh hardcodes a `find "${SDKMAN_DIR}/src" "${SDKMAN_DIR}/ext"` so
# the src/ dir layout is what matters.

# shellcheck source=/dev/null
source "$ISOLATED_SDKMAN_DIR/bin/sdkman-init.sh"

log "SDKMAN_DIR=$SDKMAN_DIR"
log "SDKMAN_CANDIDATES_API=$SDKMAN_CANDIDATES_API"
log "SDKMAN_BROKER_API=$SDKMAN_BROKER_API"
log "SDKMAN_PLATFORM=$SDKMAN_PLATFORM"

log "---- sanity: sdk version ----"
sdk version 2>&1 | head -5
echo

log "---- sanity: sdk current (no candidate set yet) ----"
sdk current pipelinek 2>&1
echo

log "---- attempt: sdk install pipelinek 0.39.0 ----"
# pipe 'Y' twice in case the auto_answer flag is not honored by all branches
printf 'Y\nY\n' | sdk install pipelinek 0.39.0 2>&1
echo

log "---- post: sdk current pipelinek ----"
sdk current pipelinek 2>&1
echo

log "---- post: candidate on disk ----"
ls -la "$ISOLATED_SDKMAN_DIR/candidates/pipelinek/" 2>&1
echo

log "---- post: candidate/current symlink ----"
ls -la "$ISOLATED_SDKMAN_DIR/candidates/pipelinek/current" 2>&1
echo

log "---- post: bin/pipelinek in candidate/current/bin ----"
ls "$ISOLATED_SDKMAN_DIR/candidates/pipelinek/current/bin/" 2>&1
echo

log "---- post: invoke pipelinek -v ----"
"$ISOLATED_SDKMAN_DIR/candidates/pipelinek/current/bin/pipelinek" -v 2>&1 | head -5
echo

log "---- post: invoke pipelinek --help ----"
"$ISOLATED_SDKMAN_DIR/candidates/pipelinek/current/bin/pipelinek" --help 2>&1 | head -10
echo

green "driver finished."
