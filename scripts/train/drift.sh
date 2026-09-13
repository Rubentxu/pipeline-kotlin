#!/usr/bin/env bash
# merge-train drift oracle — makes the READY_TO_MERGE law executable.
#
# Law (operator-defined, see docs/v2/07-uat/MERGE_TRAIN_LAW.md):
#
#   READY_TO_MERGE iff
#       PR.base            == main
#       fork-point(branch) == current origin/main      (behind == 0)
#       fresh canaries     == GREEN                    (MANUAL — declared, not inferred)
#       new regressions    == 0                        (MANUAL — declared, not inferred)
#       receipt/provenance == current HEAD             (MANUAL — receipt must name this HEAD)
#       global writer collision == false               (<= 1 [WRITER] PR)
#
# This script mechanizes everything that can be read from git + GitHub, and
# prints the residual MANUAL clauses as an explicit checklist. It never
# mutates the repository.
#
# Usage:
#   scripts/train/drift.sh              # full report
#   scripts/train/drift.sh --json       # machine-readable
#   scripts/train/drift.sh <pr-number>  # single PR verdict, exit 0 iff READY

set -Eeuo pipefail

JSON=0
ONLY=""
for arg in "$@"; do
    case "$arg" in
        --json) JSON=1 ;;
        ''|*[!0-9]*) echo "unknown arg: $arg" >&2; exit 2 ;;
        *) ONLY="$arg" ;;
    esac
done

command -v gh >/dev/null || { echo "gh not found" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 2; }

git fetch origin --prune --quiet

MAIN_SHA=$(git rev-parse origin/main)

# ── collect PRs ──────────────────────────────────────────────────────────────
PRS=$(gh pr list --state open --limit 100 \
    --json number,title,headRefName,baseRefName,isDraft,mergeable,headRefOid,labels)

if [[ -n "$ONLY" ]]; then
    PRS=$(jq --argjson n "$ONLY" '[.[] | select(.number == $n)]' <<<"$PRS")
fi

# ── classify ─────────────────────────────────────────────────────────────────
# STACKED  base != main            -> has no merge authority yet
# CONFLICT mergeable == CONFLICTING
# PREP     draft                   -> no merge authority
# BEHIND   behind > 0              -> must rebase + regenerate evidence
# READY    everything mechanical green
classify() {
    local base="$1" draft="$2" mergeable="$3" behind="$4"
    if [[ "$base" != "main" ]];          then echo STACKED;  return; fi
    if [[ "$mergeable" == "CONFLICTING" ]]; then echo CONFLICT; return; fi
    if [[ "$draft" == "true" ]];            then echo PREP;     return; fi
    # unknown drift is NOT ready: fail closed rather than claim a proven fork-point
    if [[ ! "$behind" =~ ^[0-9]+$ ]];       then echo BEHIND;   return; fi
    if [[ "$behind" -gt 0 ]];               then echo BEHIND;   return; fi
    echo READY
}

# Prefer the remote-tracking ref (that is what the PR actually merges);
# fall back to a local branch, then the PR's recorded head SHA.
resolve_head() {
    local head="$1" sha="$2"
    for cand in "refs/remotes/origin/$head" "refs/heads/$head" "$sha"; do
        if git rev-parse --verify --quiet "$cand^{commit}" >/dev/null 2>&1; then
            echo "$cand"; return 0
        fi
    done
    return 1
}

writer_count=0
rows=""
verdict_state=""
while IFS=$'\t' read -r num title head base draft mergeable shafull labels; do
    fork=""; ahead="?"; behind="?"
    if ref=$(resolve_head "$head" "$shafull"); then
        fork=$(git merge-base origin/main "$ref" 2>/dev/null || echo "")
        if [[ -n "$fork" ]]; then
            ahead=$(git rev-list --count "$fork..$ref" 2>/dev/null || echo "?")
            behind=$(git rev-list --count "$fork..$MAIN_SHA" 2>/dev/null || echo "?")
            fork_match=$([[ "$fork" == "$MAIN_SHA" ]] && echo yes || echo no)
        else
            fork_match="?"
        fi
    else
        fork_match="?"
    fi
    state=$(classify "$base" "$draft" "$mergeable" "$behind")
    if printf '%s' "$labels" | grep -q '\[WRITER\]'; then
        writer_count=$((writer_count + 1))
        state="${state}+WRITER"
    fi
    [[ -n "$ONLY" ]] && verdict_state="$state"
    rows+="${num}\t${state}\t${base}\t${behind}\t${ahead}\t${fork_match}\t${head}\t${title}\n"
done < <(jq -r '.[] | [.number, (.title|gsub("\t";" ")), .headRefName, .baseRefName, (.isDraft|tostring), .mergeable, .headRefOid, ([.labels[].name]|join(" "))] | @tsv' <<<"$PRS")

if [[ "$JSON" == "1" ]]; then
    printf '{"origin_main":"%s","writer_count":%s,"lanes":[' "$MAIN_SHA" "$writer_count"
    first=1
    while IFS=$'\t' read -r num state base behind ahead forkmatch head title; do
        [[ -z "$num" ]] && continue
        [[ "$first" == "1" ]] || printf ','
        first=0
        printf '{"pr":%s,"state":"%s","base":"%s","behind":"%s","ahead":"%s","fork_matches_main":"%s","head":"%s"}' \
            "$num" "$state" "$base" "$behind" "$ahead" "$forkmatch" "${head:0:8}"
    done < <(printf "$rows")
    printf ']}\n'
    exit 0
fi

# ── report ───────────────────────────────────────────────────────────────────
echo "merge-train drift"
echo "  origin/main = ${MAIN_SHA:0:12}"
echo

if [[ -z "$(printf "$rows")" ]]; then
    echo "  (no open PRs)"
else
    printf "  %-5s %-14s %-6s %-7s %-6s %-9s %s\n" PR STATE BASE BEHIND AHEAD FORK=MAIN BRANCH
    printf "  %s\n" "-------------------------------------------------------------------------------"
    while IFS=$'\t' read -r num state base behind ahead forkmatch head title; do
        [[ -z "$num" ]] && continue
        printf "  %-5s %-14s %-6s %-7s %-6s %-9s %s\n" "$num" "$state" "$base" "$behind" "$ahead" "$forkmatch" "$head"
    done < <(printf "$rows")
fi

echo
if [[ "$writer_count" -gt 1 ]]; then
    echo "  LAW VIOLATION: ${writer_count} [WRITER] PRs open; at most one may hold global authority."
elif [[ "$writer_count" -eq 1 ]]; then
    echo "  writer: exactly 1 [WRITER] PR — ok"
else
    echo "  writer: none — no lane currently authorized to mutate global authority"
fi

cat <<'EOF'

  READY_TO_MERGE additionally requires (MANUAL — declare in the PR body, do not infer):
    [ ] fresh canaries GREEN        (XML + sha256, canary file deleted before the run)
    [ ] new regressions == 0        (base-vs-head, same argv)
    [ ] receipt/provenance == HEAD  (receipt names this HEAD, or is precedent-'pending'
                                     and resolved by the next gate's receipt)

  After EVERY merge:  git fetch origin && scripts/train/drift.sh
    READY    -> stays ready
    BEHIND   -> rebase + regenerate evidence
    STACKED  -> remains on its parent, retarget when parent lands
    CONFLICT -> STOP
EOF

# Single-PR mode is a gate, not a report: exit non-zero unless READY.
if [[ -n "$ONLY" ]]; then
    case "$verdict_state" in
        READY*)   echo; echo "PR #$ONLY: mechanical clauses of READY_TO_MERGE hold"; exit 0 ;;
        "")       echo; echo "PR #$ONLY: not found among open PRs"; exit 2 ;;
        *)        echo; echo "PR #$ONLY: NOT ready ($verdict_state)"; exit 1 ;;
    esac
fi
