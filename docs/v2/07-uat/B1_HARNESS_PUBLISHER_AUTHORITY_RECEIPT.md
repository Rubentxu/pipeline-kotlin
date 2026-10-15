# B1 — publisher authority: the verdict consumer can no longer be told who may publish

- **Date:** 2026-10-15
- **HEAD at authorship:** `9696f789` (worktree `test-harness-brave-harbor-6920`)
- **Scope:** `scripts/consult-harness-verdict.py` + suite,
  `docs/v2/07-uat/HARNESS_PUBLISHER_ALLOWLIST.txt` (new),
  `docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md` §5.4.

---

## 1. The gap (F5, recorded as "publisher identity is self-attested")

The verifier/publicador separation is a contract invariant
(`HARNESS_INVENTORY_HANDOVER.md` §5.4, `AGENTS.md` §"Frontera de responsabilidad"), but the
*allowlist* of logins allowed to publish a verdict lived only in the caller:

```text
DEFAULT_ALLOWED_AUTHORS = ("pipelinek-harness[bot]",)   # in-script constant
--allow-author <login>                                  # repeatable CLI flag
PIPELINEK_ALLOWED_AUTHORS=<login,login>                 # env var
```

Two consequences a reviewer could not see:

- widening the authority left no artifact that distinguishes "established publisher" from
  "login this invocation decided to trust" — the receipt recorded only the merged set;
- with the allowlist emptied (`PIPELINEK_ALLOWED_AUTHORS=""` plus no file), every verdict
  would be reported as an *unknown publisher* (exit 3), blaming the harness for a local
  configuration failure.

## 2. The change

```text
docs/v2/07-uat/HARNESS_PUBLISHER_ALLOWLIST.txt   committed authority (one login per line)
PIPELINEK_PUBLISHER_ALLOWLIST                    override path (tests, harness copy)
exit 5 / status CONFIG_ERROR                     authority missing, unreadable or empty
allowlist_source / allowlist_digest              recorded in the receipt
allowlist_deviations                             CLI/env additions beyond the authority, WARNING
```

Refusal happens **before** any network call: the authority is loaded immediately after
argument parsing. The default path is resolved against the script location, not the ambient
cwd, so a run from any directory reads the same authority.

## 3. Evidence

Suite (`scripts/test_consult_harness_verdict.py`, 24 tests, network stubbed):

```text
python3 scripts/test_consult_harness_verdict.py
Ran 24 tests in 0.083s
OK
```

Five new rows, each naming its mutation in the docstring: missing authority refuses before
network (the stub raises if reached), empty authority refuses, the committed authority is
used and its digest recorded, a CLI addition is recorded as a deviation, and a login outside
the authority is INVALID.

Real CLI runs, not just tests:

```text
$ PIPELINEK_PUBLISHER_ALLOWLIST=/nonexistent/allowlist.txt python3 scripts/consult-harness-verdict.py --candidate v0.0.0-test
exit_code=5
[consult] candidate=v0.0.0-test status=CONFIG_ERROR zip_match=None publisher=(unknown) publisher_ok=None fingerprint=0000000000000000
[consult] allowlist_source=/nonexistent/allowlist.txt digest=(unread)
[consult] detail=publisher allowlist authority unavailable; refused before any network call: allowlist file not found: /nonexistent/allowlist.txt

$ python3 scripts/consult-harness-verdict.py --candidate v0.61.0-rc5-d     # authority present
exit_code=4   (MISSING: the harness has published no verdict for that tag)
```

The two receipts those smoke runs wrote inside the tracked tree were deleted; the tree is
clean (`git status --short` lists only the three intended paths).

## 4. What this receipt does not claim

- It does not decide *who* the publisher is. The committed file currently names
  `pipelinek-harness[bot]`, which is the identity the in-script default already carried;
  widening it remains a contract decision, now reviewable in a diff.
- It does not make the harness publish anything, and it does not change promotion: this
  consumer still never promotes a candidate.
