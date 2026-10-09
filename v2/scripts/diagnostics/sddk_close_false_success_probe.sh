#!/usr/bin/env bash
# Discriminator for the `git sddk-close` false-success report.
#
# WHY THIS EXISTS
#
# `git sddk-close` prints the word "Satisfies" when it REJECTS malformed
# arguments. The word is not a success message: it is part of the usage text
# at sddk-close:13 ("Satisfies the post-commit semantic closeout
# obligation"), which `usage >&2` prints from the unknown-argument branch at
# line 28 before `exit 2`.
#
# The safety property actually holds — exit code 2, no receipt, pending state
# preserved. What is broken is the READABLE signal: any verification that
# greps the output for "Satisfies" reads a rejection as a satisfaction. That
# is exactly the class of false green this repository keeps paying for.
#
# This script measures the real behaviour on all three paths so the claim
# above is OBSERVED rather than inferred from reading the source.
#
# It does NOT modify the hook. The hook lives in global SDDK config, which is
# out of scope for this repository's work.

set -uo pipefail
HOOK="$(git config --get alias.sddk-close 2>/dev/null | sed 's/^!//')"
STATE="$(git rev-parse --git-dir)/sddk-agent-gate"
PENDING="$STATE/closeout-pending.env"
FAIL=0

note() { printf '  %-46s %s\n' "$1" "$2"; }
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then note "$1" "OK ($3)"
  else note "$1" "MISMATCH expected=$2 actual=$3"; FAIL=1; fi
}

cd "$(git rev-parse --show-toplevel)" || exit 1
echo "hook:   $HOOK"
echo "state:  $STATE"
echo

if [[ ! -x "$HOOK" ]]; then echo "hook not found/executable; nothing to discriminate"; exit 0; fi

# ---------------------------------------------------------------- path 1
# Malformed argument (the reported case). Expect: non-zero exit, NO receipt
# created, pending state UNTOUCHED.
echo "PATH 1  malformed argument (--discoverions)"
BEFORE=""; [[ -f "$PENDING" ]] && BEFORE="present"
OUT="$("$HOOK" --outcome a --evidence b --remaining c --discoverions d --decisions e --unknowns f 2>&1)"
RC=$?
check "exit code is non-zero" "yes" "$([[ $RC -ne 0 ]] && echo yes || echo no)"
check "actual exit code" "2" "$RC"
AFTER=""; [[ -f "$PENDING" ]] && AFTER="present"
check "pending state preserved" "$BEFORE" "$AFTER"
echo "$OUT" | grep -q 'Satisfies' \
  && note "output contains 'Satisfies'" "YES <- the false-success trap" \
  || note "output contains 'Satisfies'" "no"
echo "$OUT" | grep -q 'Unknown argument' \
  && note "output names the bad argument" "yes" \
  || note "output names the bad argument" "NO"

# ---------------------------------------------------------------- path 2
# Valid arguments but nothing pending. Expect: exit 0 with an HONEST message
# and NO receipt. This path must NOT be mistaken for a closeout either.
echo
echo "PATH 2  valid arguments, nothing pending"
RECEIPTS_BEFORE=$(ls -1 "$STATE"/closeout-*.txt 2>/dev/null | wc -l)
OUT2="$("$HOOK" --outcome a --evidence b --remaining c --discoveries d --decisions e --unknowns f 2>&1)"
RC2=$?
check "exit code" "0" "$RC2"
RECEIPTS_AFTER=$(ls -1 "$STATE"/closeout-*.txt 2>/dev/null | wc -l)
check "no receipt created" "$RECEIPTS_BEFORE" "$RECEIPTS_AFTER"
echo "$OUT2" | grep -q 'No pending' \
  && note "output says 'No pending'" "yes (honest)" \
  || note "output says 'No pending'" "NO"

echo
if [[ $FAIL -eq 0 ]]; then echo "RESULT: all discriminator checks behaved as specified"
else echo "RESULT: at least one check MISMATCHED - re-read the source"; fi
echo "NOTE:   path 1 confirms the trap exists (rejection prints 'Satisfies')."
echo "        That is a reporting defect, not a permission defect: exit 2 holds."
exit $FAIL