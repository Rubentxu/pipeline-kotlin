# WU-RP-042 S2 Slice Receipt — CLI fix regression, R1/R2 closure, ADR-0096, L5 gate

- **WU:** WU-RP-042 (release gate) — Slice S2 (closure)
- **Date:** 2026-09-22
- **Base SHA (S1):** `caa4ad5d` (CI `35776740141` SUCCESS 9/9 on this SHA)
- **Head SHA:** `65afc24d` (S2 commits: `3e4969f9`, `48cd4de3`, `65afc24d`)
- **L5 gate:** `./gradlew -p v2 check --rerun-tasks` (escalated, budget 1270s) — RESULT: see End-of-round below.

## Scope of S2

1. Regression test for the S1 `credentials add` defect (JUnit level; e2e PTY
   proof remains in S1 receipt).
2. R2 fix: CLI store-path contract divergence.
3. R1 fix: root `pipeline.kts` not compilable.
4. Mandatory ADR-0095 re-evaluation → ADR-0096.
5. L5 gate on the final head + CI run.

## Work performed

### 1. `credentials add` placeholder-id regression test (`3e4969f9`)

`CredentialsCliAddPlaceholderRegressionTest` (3→4 tests) pins:

- Blank `CredentialsId("")` is rejected by the ADT invariant (the guard that
  exposed the S1 defect).
- The non-blank CLI placeholder `<pending-store-id>` is a legal `CredentialsId`.
- `store.add(entryKey, credentialWithPlaceholderId)` persists and reads back
  under the entry key; the placeholder never appears as a persisted entry key.

Fresh XML: `tests="4" skipped="0" failures="0" errors="0"`.

### 2. R2 — CLI now honors `PIPELINE_CREDENTIALS_STORE` (`3e4969f9`)

`MainCredentialsCli.STORE_FILE` previously hard-coded
`~/.pipeline/credentials.bin`, while the runtime (`Main.kt`
`composeWithCredentialsExecutor`) resolves
`PIPELINE_CREDENTIALS_STORE` → default sibling of the journal db. The CLI wrote
one store while the run read another.

Fix: `MainCredentialsCli.resolveStoreFile()` is now the single authority with
the runtime-matching contract; `STORE_FILE` delegates to it. Contract test
added asserting env-honoring resolution.

**E2E proof (installed dist, real PTY):**

```
PIPELINE_CREDENTIALS_STORE=/tmp/r2test/store.bin pipelinek credentials add --kind secret-text r2-token
  -> "Credential 'r2-token' stored successfully." ; /tmp/r2test/store.bin created
PIPELINE_CREDENTIALS_STORE=/tmp/r2test/store.bin pipelinek credentials list
  -> exit 0, lists r2-token SecretText GLOBAL
```

Module suite after both changes: **56 tests, 0 failures, 0 errors**.

### 3. R1 — root `pipeline.kts` uses supported retry Block Step (`48cd4de3`)

Root script used `retry(2)` inside `options{}`. WU-RP-032 deliberately removed
retry from `OptionsSpec` ("retry semantics live in the retry Block Step,
ADR-0075"); extending `OptionsScope` would revert an accepted decision, so the
script was aligned instead: the Validate steps are now wrapped in
`retry(2) { ... }` (Block Step form, same as `v2/compatibility/11-*.kts`).

Proof: `pipelinek validate pipeline.kts` → `VALIDATION SUCCESSFUL`
(`CompilationFinished`, 0 diagnostics) on the installed dist.

### 4. ADR-0096 (`65afc24d`)

Mechanical application of ADR-0095's deferred-decision rule at the release
gate: the MANIFEST format spec does not exist, no consumer need surfaced since
ADR-0095, and ROADMAP L74 freezes the candidate's schema. Decision:
**KNOWN_LIMITATION confirmed for 0.39.0**; release notes MUST disclose it
(ADR-0095's own rule). UAT-RP-005 remains PARTIAL (inv 3) — classified, not
hidden. WU-RP-010 r2 remains the only legitimate implementation path (requires
spec + format ADR first).

## End-of-round checklist

- [x] Targeted L1/L2 green before L5 (regression class 4/4; module 56/56).
- [x] Exactly one L5 escalated run for the round: see live log `/tmp/gradle-l5.log`
      (result recorded in WORK_JOURNAL entry for this slice).
- [x] No assertion weakened; no test skipped to obtain green.
- [x] CI of base SHA `caa4ad5d`: `35776740141` SUCCESS 9/9 (observed, not assumed).
- [ ] CI of final head `65afc24d`: pushed; result recorded in WORK_JOURNAL.

## Verification executed vs reused

- Executed: regression class, full credentials module, PTY e2e add/list,
  root-script validation, L5 escalated gate.
- Reused: S1 distZip bit-a-bit reproducibility evidence (ZIP bytes unchanged —
  R1/R2 touch sources compiled into the dist, so the SHA cited in S1 applies to
  the S1 head; final release ZIP must be rebuilt from the release SHA and its
  sha256 recorded fresh).
- Deliberately not executed: full suite beyond the single L5 round (rule: one
  round gate per apply/verify round).

## Closure answers (AGENTS.md reference-implementation check)

- Reference implementation consulted: Jenkins CLI `credentials` plugin
  behaviour (store-per-instance, no env divergence concept); no literal reuse.
- Behaviour adopted: single store-path authority mirroring the runtime.
- Intentional deviations: retry moved from options (removed by WU-RP-032) to
  the Block Step form — Jenkins allows both; Pipeline-K deliberately allows
  only the Block Step.
- Security implications reviewed: passphrase handling unchanged; store file
  permissions unchanged (0600 observed on fresh store in e2e test).
- Tests demonstrating the contract:
  `v2/pipeline-credentials-local/src/test/kotlin/.../CredentialsCliAddPlaceholderRegressionTest.kt`

## Result

PASS (module-level + e2e + validation), pending L5 log + head CI recorded in
WORK_JOURNAL. WU-RP-042 closes with S1 + S2 unless L5/CI reveals a regression.
