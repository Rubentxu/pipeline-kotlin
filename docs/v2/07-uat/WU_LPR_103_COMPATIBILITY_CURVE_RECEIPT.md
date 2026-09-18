# WU-LPR-103 — Compatibility Curve Burn-down (Corpus Classification)

Trunk at start: `c29e3c1f` (WU-LPR-061). Date: 2026-09-18.
Contractual classification — NO historical rewrite, NO expected-output tampering.

## Scope

The 21-fixture compatibility corpus (`v2/compatibility/*.pipeline.kts` +
`CompatibilityCorpusTest`) is the LPR compatibility curve. At cycle start:
19/21 green; `fixture05ScriptedIf` and `fixture14CredentialsBindings` red.

## Characterization (evidence, installed binary)

### fixture05 — scripted-if

`./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application run 05-scripted-if.pipeline.kts`
(OBSERVED): exit 1, compile diagnostic:
`'fun echo(text: String): Unit' cannot be called in this context with an implicit receiver` (line 7).

Decision authority: `WU_LPR_032_RECEIPT.md` admission table row
`script { line(...) } = SUPPORTED (ScriptScope -> StepSpec.Shell(isScriptBlock=true))`
(`PipelineDsl.kt:2125 ScriptScope`, only `line(command: String)`).

**Decision: HISTORICAL.** The fixture uses raw Kotlin control flow with
`echo()` inside `script {}` — the legacy in-process implicit-receiver
compilation, not the admitted surface. The installed binary rejects it with a
typed compile error (fail-closed, correct). The fixture is preserved as-is and
pinned by `runFixtureCompileFail` asserting: exit 1 + `CompilationFinished`
event + ERROR diagnostic present. NOT in the LIVE gate as a pass-fixture; the
compile rejection IS the contract.

### fixture14 — credentials-bindings

Two distinct findings:

1. **The DSL surface IS LIVE_SUPPORTED.** With a seeded
   `LocalSecretStore` (7 credential kinds: SecretText, UsernamePassword,
   SshPrivateKey, SecretFile, Certificate, Zip, UsernameColonPassword) the
   fixture passes E2E through the installed binary: compile OK, 7x
   `CredentialBound`, sh echoes all 7 injected env vars (typed materialized
   paths for file/ssh/cert/zip kinds), `echo` OK, 7x `CredentialUnbound`
   (balanced), `RunFinished success`, exit 0.

2. **Production defect (fixed): the default `pipeline run <script>` path
   (in-memory, no `--db`) never composed the credential injection stack.**
   `Main.kt` wired `WithCredentialsExecutor` only in the durable branch; the
   in-memory branch called `runCanonicalPipeline` with the default
   `withCredentialsExecutor = null`, so every withCredentials block failed
   closed with typed `StoreUnavailable` (INFRASTRUCTURE) — AND the failure was
   SILENT: no `StepFailed` event, empty `RunFinished.diagnostics`. Observed:
   `StageStarted -> RunFinished(failure)` with nothing in between.

## Changes

### Fix 1 — credential composition parity (production, `Main.kt`)

Extracted `composeWithCredentialsExecutor(controlDirRoot)` (single composition
of LocalSecretStore -> LocalCredentialProvider -> CredentialMaterializer ->
LocalFileMaterialization -> WithCredentialsExecutor, env
`PIPELINE_CREDENTIALS_STORE` / `PIPELINE_STORE_PASSPHRASE`, identical fail-fast
exit 3/4 semantics) and wired it into the in-memory `pipeline run` branch.
Durable path semantics unchanged (same composition, now via the shared
helper). OBSERVED: `pipeline run 14-...pipeline.kts` with seeded store env now
succeeds (exit 0, CredentialBound x7) WITHOUT `--db`.

### Fix 2 — credential-lease admission observability (production,
`CanonicalDurableRunCoordinator.executeCredentialLeasedBody`)

An `Unavailable`/`Invalid` acquisition now emits a typed `StepFailed`
(failureKind INFRASTRUCTURE/SCHEMA respectively, message from
`CredentialScopeFailure.describe()`) BEFORE returning the `StepOutcome.Failure`.
The fail-closed behavior itself is unchanged — only the observability gap is
closed. OBSERVED: storeless run of fixture14 now shows
`StepFailed{failureKind=INFRASTRUCTURE, message="No WithCredentialsExecutor
configured; credential scope cannot be acquired"}`.

### Harness (tests, `CompatibilityCorpusTest`)

- `fixture05ScriptedIf` -> `runFixtureCompileFail` (HISTORICAL pin, documented
  in `historicalCompileFailureFixtures`).
- `fixture14CredentialsBindings` -> `runFixturePassWithCredentialsStore`:
  seeds a temp `LocalSecretStore` with all 7 kinds, passes the env vars to the
  installed binary (same mechanism as UatLocal008), asserts exit 0 + events.
- NEW `fixture14WithoutStoreFailsTyped`: forever-fitness for Fix 2 — storeless
  run must exit 1 AND emit a typed `StepFailed` with the credential-scope
  message (fail-closed is observable, never silent).

## Verification (fresh XML, canary-checked)

| Level | Scope | Result |
|---|---|---|
| L1 | fixture05 + fixture14 | 2/2 PASS |
| L2 | `CompatibilityCorpusTest` full class | **22/22 PASS** (`tests="22" failures="0" errors="0"`, includes new `fixture14WithoutStoreFailsTyped`) |
| L3 | `dev.rubentxu.pipeline.v2.application.durable.*` | **612/612 PASS** |
| L3 | `UatLocal008*` | 27 tests, 1 failure: `CR-BD-027 CredentialUsed per use(Path)` |
| L3 | base-SHA reproduction (`c29e3c1f` worktree, fresh build) | `CR-BD-027` fails IDENTICALLY (`Got: 0`) → **PRE_EXISTING**, not caused by this change |

## Classification ledger (corpus)

| Fixture | Status |
|---|---|
| 05-scripted-if | HISTORICAL (compile-reject pin; supported form is `script { line(...) }`) |
| 14-credentials-bindings | LIVE_SUPPORTED (with seeded store; harness support added) |
| all others | LIVE_SUPPORTED (unchanged, 19 green) |

## Deliberately NOT done

- No `script {}` raw-Kotlin implicit-receiver compilation resurrection
  (POST_LPR at best; admission table is the contract).
- No `load` step work (POST_LPR per prior decision, INC-024).
- UatLocal008 CR-BD-027 (CredentialUsed event emission) remains open as a
  pre-existing defect on the base SHA — separate WU, unchanged by this cycle.
