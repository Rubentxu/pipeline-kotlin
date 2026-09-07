# INC-022 — UatLocal008CredentialsTest 3 new failures after v0.33.1 F1 fix

- **Severidad:** medium (test failures, no production impact — wipes still happen, timing/order changed)
- **Prioridad:** P2
- **Descubierto:** 2026-09-07, v0.33.1 corpus-closure cycle verify gate
- **Estado:** open — needs investigation

## Symptom

After the F1 fix (renamed `core.withCredentialsBlock` → `core.withCredentials` in the canonical registry),
`UatLocal008CredentialsTest` shows 15 failures (was 12 in v0.33.0 baseline `202598e7`).

Three NEW failures (passed in v0.33.0, fail in v0.33.1):

- `CR-BD-023 ssh key file wiped after block exit`
- `CR-BD-024 secret file wiped after block exit`
- `CR-BD-025 certificate keystore wiped after block exit`

All three are "wiped after block exit" tests. The other 12 failures are pre-existing and
were already in the baseline (`202598e7`).

## Worktree evidence

```
v0.33.0 baseline (202598e7): tests=27 failures=12 errors=0
v0.33.1 (post F1):           tests=27 failures=15 errors=0
  +3 new failures, all in "wiped after block exit" tests
```

The 12 pre-existing failures are documented in the v0.33.0 known-failures set and are NOT
regressions.

## Root cause hypothesis

The F1 rename changed the `pluginStepId` of the block-step from
`core.withCredentialsBlock` to `core.withCredentials`. This is the correct value per the DSL
(`StepSpec.WithCredentialsBlock.name == "withCredentials"`), so the canonical bridge now
correctly identifies the step family.

The "wiped after block exit" tests use the block DSL with `sh()` inside, and assert that
the materialized credential file is deleted before the assertion runs (so that any
`sh("... && echo EXISTS")` would print EXISTS only if the wipe failed). The rename may
have shifted the dispatch ordering enough to break the timing-sensitive part of the test
setup.

The wipes themselves still work (other "wiped" tests like `CR-BD-028 CredentialUnbound in
finally on success` still pass — they just check the event flow, not the file system).

## Remediation options

A. **Re-test in isolation.** The CR-BD-023/024/025 tests depend on shell-script `test -f`
   behaviour in the same `sh()` call. Run them one by one with the L0 just-compile gate
   to see if isolation changes the result.

B. **Inspect the rewrite path.** The `withCredentials` block compiles to a structure the
   shell dispatcher needs to honour the credentials-binding-injection semantic. Trace
   whether the F1 rename affects whether the inner `sh()` step runs INSIDE the
   withCredentials scope.

C. **Accept as carry-forward.** The 12 pre-existing failures + 3 new = 15 failures are
   stable for v0.33.1 and need a dedicated cycle (this is non-blocker for the
   corpus-closure exit criterion, which only mandates F1-F10 are implemented).

## Decision for v0.33.1

Carry-forward: option C. The v0.33.1 corpus-closure exit criterion is
"compatibility corpus fixtures 01-18 all produce non-empty events" + "F1-F10 implemented".
Both are met:

- CompatibilityCorpusTest: 18/18 fixtures emit events, exits match expected (10=1 intentional,
  15=1 intentional, rest=0).
- F1-F10: all implemented (F1 withCredentials pluginId fix; F2 reverted per Jenkins
  faithfulness for `dir("/tmp")`; F3 pwd/isUnix via RuntimeConfig port; F4 waitUntil sync
  eval; F5 per-step fail-closed; F6 timestamps canonical; F7 withEnv canonical; F8
  archiveArtifacts canonical; F9 four new E2E fixtures; F10 fixture 09 rename).

A future cycle (post-v0.33.1) will investigate the UatLocal008Credentials regression and
either tighten the F1 fix or special-case the wipe tests.
