# Handoff to certifier — v0.60.0-rc4 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-14
- **Train:** 0.60.0-rc4 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** PR #117 (branch `r4/wu-r4-3-console-jsonl`) → merge commit `9d4bd44a`
  (squash, AGENTS ADR-0099: main keeps the full commit history)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

- **R4 / WU-R4.3 — console `--format text|jsonl` (JSONL envelope)**
  ([receipt](../../docs/v2/07-uat/R4_WU_R4_3_CONSOLE_JSONL_ENVELOPE_RECEIPT.md))
  — closes **CON-2.1** from `R4_CLI_GAP_ANALYSIS §2`. The default is `text` (raw bytes to stdout,
  unchanged from M1-P3); `--format jsonl` switches the emitter to
  [ObservationJsonLines] and emits one canonical record per line.
- Version unchanged at `0.60.0` (rc4 advances the candidate sequence on the same train).

## Source of truth

| Item | Value |
|---|---|
| Train | 0.60.0-rc4 |
| Branch | `r4/wu-r4-3-console-jsonl` (squash-merged to main) |
| Merge commit | `9d4bd44abb33afab687ced164d91a66e95a86c2c` |
| Tree state at merge | clean (verified by `git ls-tree` at merge) |
| Audit | R4 / WU-R4.3 closed in this candidate |

## Artefacts (SHA-256, from `SHA256SUMS` and `git ls-tree`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.60.0.zip` | 88.6 MB | `47d4bb4cf6f5d6e6bc6c250898bbb7849a0daf9de78b1dc20196ec3c02c3edf9` |
| `pipelinek-0.60.0.sbom.json` | 1.3 MB | `61c39f695a2426fde8a5f01eee91fc9fd284a83ecd62c1889fd69565b88b7b11` |
| `git-head.txt` | 41 B | `9d4bd44abb33afab687ced164d91a66e95a86c2c` |

The SBOM digest is unchanged from rc1/rc2/rc3 (no dependency change in this slice);
the ZIP digest is new (the new `ConsoleFormat` ADT + 2 dispatch cases + the JSONL
synthetic-frame emit path + 9 new tests).

## Verification (producer-measured)

| Check | Result |
|---|---|
| `:pipeline-application:test --tests 'MainConsoleFormatTest'` | 9 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainConsoleCliArgumentContractTest'` | 5 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainConsoleCliArgumentStrictnessTest'` | 4 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'MainConsoleCliRequestStrictnessTest'` | 10 tests / 0 failures / 0 errors |
| `:pipeline-application:test --tests 'ConsoleReadServiceTest'` | 12 tests / 0 failures / 0 errors |
| New detekt issues introduced | 0 |
| `git diff --quiet HEAD --` | clean |
| `git status` after merge | working tree clean |

Canary XMLs:
- `v2/pipeline-application/build/test-results/test/TEST-...MainConsoleFormatTest.xml` — `tests=9 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainConsoleCliArgumentContractTest.xml` — `tests=5 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainConsoleCliArgumentStrictnessTest.xml` — `tests=4 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...MainConsoleCliRequestStrictnessTest.xml` — `tests=10 skipped=0 failures=0 errors=0`
- `v2/pipeline-application/build/test-results/test/TEST-...ConsoleReadServiceTest.xml` — `tests=12 skipped=0 failures=0 errors=0`

## What the certifier must do

1. **Re-compute SHAs** against the two core artefacts. Every line must match.
2. **Re-run the targeted tests** from a clean checkout at the merge commit `9d4bd44a`:
   ```bash
   git checkout 9d4bd44a
   cd v2
   ./gradlew :pipeline-application:test --tests 'MainConsoleFormatTest' \
             :pipeline-application:test --tests 'MainConsoleCliArgumentContractTest' \
             :pipeline-application:test --tests 'MainConsoleCliArgumentStrictnessTest' \
             :pipeline-application:test --tests 'MainConsoleCliRequestStrictnessTest' \
             :pipeline-application:test --tests 'ConsoleReadServiceTest'
   ```
   All five classes must report 0 failures / 0 errors. The HF0 / HF1 contract is the
   production entry point the harness crosses; a re-implementation is not a substitute
   (HARNESS FIDELITY LAW §1).
3. **Re-measure the closed ADT** of `ConsoleFormat` (2 cases: `TEXT`, `JSONL`). Adding
   a third case is a compile-time break for every caller of `ConsoleFormat.parse` and
   for the `when (request.format)` dispatch in `MainConsoleCli.emit`.
4. **Re-measure the refusal contract parity**: `--format text` and `--format jsonl`
   both produce the same `console-refused:` prefix on a missing run.

## Known issues (carried over, not introduced by this candidate)

- 4 pre-existing detekt errors in `:pipeline-application` (`OutputProtocol.kt:98`,
  `LaunchReceiptModesTest.kt:276`, `OutputProtocolNegotiationTest.kt:150`,
  `ObsPc2ProducerSurvivalSpikeTest.kt:107`) — out of scope at base `347470d4`.
- Pre-existing UAT failures in `CompatibilityCorpusTest`, `DirScopeEndToEndTest`,
  `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`,
  `Lpr011r2SecretRedactionAtRestUatTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`,
  `ObsAOutputStreamingCharacterisationTest`, `ObsPc2LevelAUatTest` etc. — not regressions
  from WU-R4.3; documented elsewhere as part of the LFC-2 / R3 / OBS carry-over.

## Product-gate status

`PRODUCT-GATE = BLOCKED_EXTERNAL` per ADR-0105: no CI surface in this repo. The harness
authority decides `CERTIFIED` and `STABLE_PROMOTED`; this handoff is the producer
delivery, not the certification verdict.
