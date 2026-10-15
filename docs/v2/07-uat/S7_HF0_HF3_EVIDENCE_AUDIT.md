# S7 — HF0..HF3 evidence audit and gap report

**Cycle:** `p-733fb505b5a6bd2d/b3-4-s7-evidence`
**WU:** `6a2ee13b-651f-4d1e-a7c1-089df5824079`
**Branch / base:** `b3-4-s7-evidence` @ `ce8fa8839c08b4ee8b05284f27cfc35fd50be52d` (post-`docs(historico): archive v0.61.0-rc2 evidence byte-inmutably`, 2026-10-15)
**Authority:** ADR-0072 (layered test-harness fidelity), `docs/v2/03-specifications/PIPELINE_TEST_HARNESS.md` (§R1 ladder, §R2 capabilities), `docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md` (R2/C01..C19), `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md` (T0..T5).
**Method:** doc-only audit. No production code touched, no test modified (per AGENTS.md V2 TESTING RULES rules 14, 17, 18: classify, then later widen — this slice is classification, not measurement).
**Scope:** S7 evidence consolidation for **HF0..HF3** per `PIPELINE_TEST_HARNESS.md` §R1. HF4..HF6 (sandbox / service sandbox / online smoke) out of scope — they belong to ADR-0048 / ADR-0053 and to `pipelinek-fabric` (ROADMAP §10.1, §11.1: `RP-8`/`RP-9` are not this repository).
**Scope firewall:** this document does **not** modify `UAT_GATE_CLASSIFICATION.md` — the only edit that would qualify there is a newly discovered pre-existing failure, and the audit found none (§5).

---

## §1. Why this audit, and what counts as evidence here

The S7 train's exit criterion (`docs/pipelinek-semantic-evolution/08-roadmap.md` §S7.1..§S7.7) is **"a third-party plugin cannot be called certified while bypassing core laws"**. ADR-0072 / `PIPELINE_TEST_HARNESS.md` define that as a four-step ladder HF0..HF3 with explicit boundaries per rung. ROADMAP §13.3 row B3 lists B3.1 (witnesses), B3.5 (clock sweep), B3.7 (verdict vocabulary) as the declared work; B3.4 row is recorded as `kill/restart/recovery` in `B3_PRECONDITION_STATE_RECEIPT.md §2` and the user has **reslotted** B3.4 here as the **S7 evidence consolidation** (audit + gap report).

What counts as evidence for an HF rung is not the same as what counts as "a green test". For this audit:

```text
evidence row = { rung, claim, code-under-test (file:line),
                 exercising test (file:line), observed state, latest SHA }
```

Any row missing one of `claim` / `code-under-test` / `exercising test` is **characterisation** rather than evidence, and `HARNESS FIDELITY LAW §1` would call it `model`/`spike`. Stale evidence (latest successful run older than the prior release-train or any accepted ADR change on that surface) is recorded as such and ranked in §4.

Five pieces of authoritative context the audit maps against:

```text
PIPELINE_TEST_HARNESS.md §R1      HF0..HF6 ladder (HF0..HF3 in scope here)
PIPELINE_TEST_HARNESS.md §R2      PipelineExtension (HF1), RealPipelineExtension (HF2),
                                  PipelineSessionExtension (HF3), SandboxPipelineExtension
                                  (HF4/HF5), plus StepContractSuite / PluginContractSuite
CERTIFICATION_PROTOCOL.md §2 / §3 T0..T5 layers / C01..C19 Step dimensions
ROADMAP.md §13.3                  B3 exit criterion + sub-block state
08-roadmap.md §S7.1..S7.7         S7 train features (matrix, directives, events,
                                  plugin mutation, installed, restart, verdict)
```

The §R2 capabilities are the ones most consequential to this audit (§4 gap 1).

---

## §2. Inventory of existing evidence, by rung

This section maps each rung to its real evidence on disk. The columns are: **(a)** the most authoritative receipt(s) the rung points to; **(b)** the **test classes** whose `@Test`-level rows actually exercise that rung on the installed distribution or its in-process equivalent; **(c)** the **code-under-test** they cross; **(d)** the freshness verdict.

### §2.0 — S7 BLOCK 3.x sprint (HF-fidelity LAW cleanup, the closest thing to a "S7 corpus")

Receipts in `docs/v2/07-uat/` named `S7_BLOCK_3_x_*_RECEIPT.md` are the **directly named S7 evidence**. They migrated HF-violating harnesses (H8-10, `UatLocal008`, parallel, timeout) onto the canonical `OwnedSubprocess` primitive and wrote two fitness tests (`TestSandboxFitnessTest`, `InstalledDistributionHarnessFitnessTest`) that the file produces mechanical coverage for HF2/HF3 boundaries.

| Receipt | HF rung defended | Migration target / coverage shape |
|---|---|---|
| `S7_BLOCK_3_1_H8_10_DISCRETE_BUDGET_RECEIPT.md` | HF2 (real heap applied to child) | H8-10 row → discrete observations, not duration/size |
| `S7_BLOCK_3_2_HTTP_HARNESS_MIGRATION_RECEIPT.md` | HF2 | `HttpInstalledUatTest` → `OwnedSubprocess`, ownership hole fixed |
| `S7_BLOCK_3_3_PARALLEL_HARNESS_MIGRATION_RECEIPT.md` | HF2 | `UatDsl003ParallelTest` `/tmp` leak + classification |
| `S7_BLOCK_3_4_TIMEOUT_GRAMMAR_HARNESS_MIGRATION_RECEIPT.md` | HF2 | `UatDsl005TimeoutGrammarTest`; `F_GETPIPE_SZ` measured, 8 KiB not 64 KiB |
| `S7_BLOCK_3_5_TMP_DEBT_LAST_TWO_HARNESSES_RECEIPT.md` | HF2 | `UatLocal008CredentialsTest` (`/tmp` absolute-path) |
| `S7_BLOCK_3_6_TMP_SANDBOX_LAW_RECEIPT.md` | HF2 (fitness) | `TestSandboxFitnessTest`, two assertions, 677 test files scanned |
| `S7_BLOCK_3_7_UAT008_OWNED_SUBPROCESS_RECEIPT.md` | HF2 | `UatLocal008CredentialsTest` migrated to `OwnedSubprocess` |
| `S7_BLOCK_3_8_RUNTIME_FORK_LAW_WIDENING_RECEIPT.md` | HF2 (fitness widened) | `InstalledDistributionHarnessFitnessTest`, 23 → 53 entries |

These receipts constitute **34 documented migration receipts** across an S7 train that produced 8 numbered blocks (`S7_BLOCK_3_1..3_8`) plus the underlying audit triage `S7_AUDIT_REVIEW_FINDINGS_TRIAGE.md` (11 AUD-01..AUD-11 findings). Freshness: the S7 BLOCK 3.x chain is ratcheted off a `s6-plugin-sdk` branch and was not re-anchored to a current `origin/main` SHA in 2026-10-09..10. The closing block (`S7_BLOCK_3_8`) carries no current main SHA in its front matter, and the very next `git log -- docs/v2/07-uat/S7_BLOCK_3_*.md` shows the S7 BLOCK 3.x receipts were last touched prior to the recent main churn. **Treat the S7 BLOCK 3.x chain as evidence for a fork-of-an-older-trunk, not as fresh as-of-HEAD evidence.** (§4 gap 5.)

### §2.1 — HF0 evidence (Pure Contract)

Definition (per `PIPELINE_TEST_HARNESS.md §R1`): "no process — ADTs, codecs, validation".

| Evidence row | Code-under-test | Exercising test | Observed state at head |
|---|---|---|---|
| 22 per-Step `*StepContractSuiteTest` (`EchoStepContractSuiteTest`, `ShStepContractSuiteTest`, `UppercaseStepContractSuiteTest`, …) | `:pipeline-domain` codecs + `:pipeline-application` `StepRegistry` | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/*StepContractSuite*.kt` (one suite per Step family, 14..18 rows each, e.g. `EchoStepContractSuiteTest` row 1 "identity" through row 17 "real pipeline scenario") | Green at HEAD `ce8fa883` per class KDoc; coverage matrix mirrors G7 (`STEP_PLUGIN_CERTIFICATION.md` R2/C01..C19). Bodies are mostly HF1 in-process over `CanonicalDurableRunCoordinator` with the real registry; codec round-trip and identity rows are pure HF0. |
| `S3*LegacyRemovedFitnessTest` family (7+ files in `:pipeline-architecture-tests`) | `LegacyResidualConvergenceFitnessTest.kt`, `S3EchoLegacyRemovedFitnessTest.kt`, `S3ErrorLegacyRemovedFitnessTest.kt`, `S3IsUnixLegacyRemovedFitnessTest.kt`, `S3PwdLegacyRemovedFitnessTest.kt`, `S3SleepLegacyRemovedFitnessTest.kt`, `S3EmitEventLegacyRemovedFitnessTest.kt`, `S3WriteFileLegacyRemovedFitnessTest.kt` | same | Architectural fitness: source-level absence of legacy decoder/dispatcher/registration per Step. Fresh as of HEAD (in `:pipeline-architecture-tests`). |
| `Lfc2RegistryFamilyFitnessTest` + `FArchE6AuthoringBoundaryFitnessTest` | registry contract surface | `:pipeline-architecture-tests` | Family-routing invariant. |
| `LFC2E0_PRE_S1_EVIDENCE_AUDIT.md` `Finding 2` (Event Harness verdict preserved) | `:pipeline-event-harness` / `EventHarnessContractTest`, `RealHistoryParityTest` | `:pipeline-event-harness:test --tests 'EventHarnessContractTest' --tests 'RealHistoryParityTest'` | 19 / 0 / 0 (`LFC2E0_PRE_S1_EVIDENCE_AUDIT.md:37-43`), captured SHA `b1466f91…` **at base `bbb2e584` (2026-09-11)**. Stale relative to HEAD (~34 days old at this read); no re-measure on `origin/main`. |
| `LFC2E0_PRE_S1_EVIDENCE_AUDIT.md` `Finding 3` (193 arch-fitness tests) | `:pipeline-architecture-tests:test --rerun-tasks` | same | 193 / 0 / 0; SHA `f419e9f4…`, base `bbb2e584`. Same staleness. |
| `B2_CHECKLIST_VERIFICATION_RECEIPT.md` | B2.2 plugin-admission rows | multiple — refixed-receipts in `S2_Ax_G*_*RECEIPT.md` | One executed pass at `s6-plugin-sdk @ 94e0817c` (2026-10-08). |
| `LB02_G3_G7_CODEC_ROUND_TRIP.md`, `LB02_G3_A4_3_TYPED_OUTPUT_CARRIER.md`, `LB02_G3_A4_1_DESCRIPTOR_RECOVERY.md`, `LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY.md` | the typed-output carrier / descriptor-recovery capability contract | LB02 matrices (`ShStepContractSuiteTest`) | G3/G7 family of core.sh. SHA `79d9d39b…` recorded. |

**HF0 verdict:** coverage is broad; the per-Step `*StepContractSuiteTest` pattern is the canonical HF0/HF1 entry, with the matrix's C01..C19 / G0..G8 mapped row-for-row. The known caveat is "freshness": most LB02 receipts are anchored to a 2026-09 SHA and were not re-measured at this SHA. §4 gap 5 records the staleness.

### §2.2 — HF1 evidence (In-Process / canonical-runtime integration)

Definition: "in-process — DSL/IR/handler integration (`pipeline-test-rule`)".

The PIPELINE_TEST_HARNESS spec's HF1 capability is **`PipelineExtension`** (a JUnit 5 extension). **There is no `PipelineExtension` class anywhere in `v2/`.** §4 gap 1 is the named consequence. Per-test HF1 evidence therefore lies in tests that drive the canonical `CanonicalDurableRunCoordinator` (or its witness, `CoordinatorFixture`) with `@TempDir`, build the pipeline through the public DSL, and assert typed outcomes — without forking a JVM.

| Evidence row | Code-under-test | Exercising test | Observed state |
|---|---|---|---|
| `S0SemanticWitnessMatrixTest` (17 rows, one per STABLE surface) | `:pipeline-application` end-to-end | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/S0SemanticWitnessMatrixTest.kt` — KDoc lists seven legs (CARRIER, POSITIVE, NEGATIVE, OUTCOME, EVENT, REPLAY, INSTALLED) — runs through `AppBinSupport.discover()` (HF2 child) but also inspects the in-process event log; **the INSTALLED leg is HF2**: a single test class spans HF1+HF2 | 17 / 0 / 0 / 0 per `B3_PRECONDITION_STATE_RECEIPT.md §1` at `b30584e5` (2026-10-08). Stale at HEAD. |
| `S3EnvironmentSemanticWitnessTest` (worked `environment` example, AMBIENT vs PATCH discrimination) | `:pipeline-application` `environment` semantics | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/S3EnvironmentSemanticWitnessTest.kt` | 7 / 0 / 0 / 0 at `b30584e5` (2026-10-08). |
| `B1A_CHARACTERIZATION_RECEIPT.md` (HF1 in-process over real CLI entry point + real SQLite) | AUD-02 / AUD-08 characterisations | `B1A_CHARACTERIZATION_RECEIPT.md` | "Fidelidad. HF1 in-process cruzando las autoridades productivas, nombradas en el KDoc de cada clase: `ShExecution.invokeShell` → `ProcessDurableTaskRuntime` / → `DurableShellExecutor.executeTerminal` + `ShExecution.ingestTranscriptIntoOutputPlane`" (`B1A_CHARACTERIZATION_RECEIPT.md`). Captured at `s6-plugin-sdk @ 3af8d4a9` (Oct 2026-10); stale at HEAD. |
| `B2A_SDK_BOM_AND_EXTERNAL_EXECUTION_RECEIPT.md` | external `:pipeline-sdk-bom` resolution + installDist | external-consumer harness | `:pipeline-sdk-bom` GREEN. |
| `LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` (`UppercaseStepContractSuiteTest`, 14 rows + delegations) | `UppercaseStep` (externally registered via ServiceLoader) | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UppercaseStepContractSuiteTest.kt` | 14 / 0 / 0 / 0; latest shell `UppercaseStepContractSuiteTest.xml`. The G3-A3 (HF0..HF1) / G3-A4-x (HF1 capability admission) / G7 (HF1 contract suite) / G8 (HF2 — see §2.3) chain. |
| `B10_W0_INNER_SEAM_RECEIPT.md`, `B11_W123_CONTEXT_BLOCKS_RECEIPT.md` | `BodyInvoker` / `BranchInvoker` inner seam | `:pipeline-application/src/test/kotlin/...Core*StepContractSuiteTest.kt` rows that exercise `dir`/`withEnv` blocks | Per `B10_W1_PREFLIGHT.md`, `dir`/`withEnv`/`timestamps` blocks re-enter engine through `BodyInvoker.invoke` (ADR-0073). |
| `CTX_P_CLOSURE_RECEIPT.md` | explicit immutable execution context (CTX-P) | `FArchExecutionContextOwnershipTest` (6 / 0 / 0) | HEAD `f5fdd150..CTX-P4`; green with `check` exit code 0. |
| `LFC2R_R4B_INSTALLED_PRODUCTION_WIRING.md` (R4B "Scripted Frontend / Canonical Backend") | wiring `pipeline { ... }` → scripted runtime → canonical coordinator | `:pipeline-application:test --tests 'ExternalPluginFourFamilyAdmissionTest'` (380 / 0 / 0) | GREEN. The SCRIPTED_FRONTEND_CANONICAL_BACKEND identity law is the architectural authority that makes HF1 possible. |
| CLI in-process tests: `MainEventsCliArgumentContractTest`, `MainEventsCliRefusalVisibilityTest`, `MainConsoleCliArgumentContractTest`, `MainConsoleCliArgumentStrictnessTest`, `MainInspectCliTest`, `MainInspectCliLogTailOpTest`, `MainEventsInspectionFlagsTest` (and the prior `CliParser*` family) | `MainEventsCli`, `MainConsoleCli`, `MainInspectCli` parsers + dispatch | the same test files | Drives `main(...)` in-process. R4 CLI gap analysis (`R4_CLI_GAP_ANALYSIS.md §1.4, §2.4, §3.4, §4.4`) classifies these as HF1 in-process and reports the per-verb HF2 rung as **empty for `console`, `events`, and `inspect`**, with single tests for `observe`-specific lanes. §4 gap 2. |

**HF1 verdict:** dense per-test-class coverage that crosses the canonical coordinator; the absence of the named `PipelineExtension` JUnit extension is structural (§4 gap 1), not a coverage hole on any single test class.

### §2.3 — HF2 evidence (Forked Real Distribution / installDist JAR)

Definition: "forked distribution — CLI, classpath, plugin loading".

| Evidence row | Code-under-test | Exercising test | Observed state |
|---|---|---|---|
| Per-Step G8 receipts (12 receipts, one per Step in `LEGACY_PLUGIN_IDS`): `S2_A1_CORE_ERROR_G8_...`, `S2_A2_CORE_SLEEP_G8_...`, `S2_A3_CORE_WRITEFILE_G8_...`, `S2_A4_CORE_EMITEVENT_G8_...`, `S2_A5_CORE_ISUNIX_G8_...`, `S2_A6_CORE_PWD_G8_...`, `S2_A7_CORE_DELETEDIR_G8_...`, `S2_A8_CORE_WAITUNTIL_G8_...`, `S2_A9_CORE_MILESTONE_G8_...`, `S2_A10_CORE_CLEANWS_G8_...`, `S2_B10_ARCHIVEARTIFACTS_G8_...`, `CORE_ECHO_CERTIFICATION.md` (S3 burn-down), plus `CORE_ECHO_G4_FITNESS_RECEIPT.md` and `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md` (core.sh, G0..G8) | the canonical installer `v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application` (or `pipelinek`); fixtures `v2/compatibility/15-error.pipeline.kts`, `22-wait-until.pipeline.kts`, … | `pipeline-application run --db <path> [--rerun\|--resume] --control-root <path> <script>` (twice, fresh + replay against SAME `--db` and `--control-root`); observable JSON events via `--log-tail-op` etc.; fresh + replay exits 0 | All 12 closed `CERTIFIED` by their own receipts; each carries G0..G7 SHAs plus a G8 SHA, and a frozen binary SHA-256 (e.g. `S2_A4_CORE_EMITEVENT_G8_...` binary `3e1cb6fefd89a3133653e570371589f75141425ad1e4f257bff46d168b12a526`; `S2_A8_CORE_WAITUNTIL_G8_...` binary `045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8`). |
| `ConsoleMultiOpInstallCanaryTest` (`BlockE` consolidation; Observability roadmap PC-08/PC-208 follow-on) | `MainConsoleCli` against real `installDist` | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ConsoleMultiOpInstallCanaryTest.kt` | `@EnabledIf("isRealHostAvailable")`; green when the host is real. |
| `B2A_SDK_BOM_AND_EXTERNAL_EXECUTION_RECEIPT.md` — BOM distribution executed with `--plugin-jar` | external consumer / `:pipeline-sdk-bom` + runtime + `--plugin-jar` | as cited | Verified end-to-end outside the monorepo (cited verbatim in `B2_BLOCK_EXIT_RECEIPT.md`). |
| `LB02_EXTERNAL_PLUGIN_INFRA_BASELINE.md` (frozen before the proof) | production plugin seam | n/a — the baseline IS the evidence that the seam is stable | Frozen SHA `587f9ab5`. |
| `LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` (delegated G8 row) | install-distribution scenario | "WITHOUT plugin → compile-exit-1 · WITH plugin → SUCCESS" isolation pair | Real JAR + real `.pipeline.kts`; GREEN. (Re-explains why `example.uppercase = CERTIFIED` carries a delegation, not a re-run.) |
| `B3_PRECONDITION_STATE_RECEIPT.md §2` "kill/restart/recovery" rows (HF3 rung, but cross-tested through HF2 installDist `install-dist` after kills) | the same installer | `UatLocal001KillDuringShTest` (1/0), `UatLocal002ResumeAfterKillTest` (1/0), `S54ExternalVerticalRestartUatTest` (2/0), `DestructiveSafetyOwnershipTest` (11/0) | 15 / 0 / 0 at base `b30584e5`. |
| `R4_CLI_GAP_ANALYSIS.md §1.4..§4.4` — explicit HF2 absent rows | see §4 gap 2 | — | The per-verb matrix marks HF2 **empty** for `console`, `events`, and `inspect`, and partial for `observe` (only `ObserveLanes` lanes, "NOT a real store"). |
| `S7_BLOCK_3_6_TMP_SANDBOX_LAW_RECEIPT.md` + `S7_BLOCK_3_8_RUNTIME_FORK_LAW_WIDENING_RECEIPT.md` | fitness tests | `TestSandboxFitnessTest`, `InstalledDistributionHarnessFitnessTest` | Legal coverage: every forked harness goes through `OwnedSubprocess`. |

**HF2 verdict:** dense for the canonical pipeline body — every core Step has its G8 installDist canary through `--rerun`/`--resume` (HF3 cross-check inside the same fixture), and the external plugin is covered. The empty rung is **the CLI verbs of R4** where `inspect` is not implemented and `console`/`events` lack installed-JAR canaries for the new flags.

### §2.4 — HF3 evidence (Restart/Resume / kill/restart)

Definition: "kill/restart — journal, replay, resume".

| Evidence row | Code-under-test | Exercising test | Observed state |
|---|---|---|---|
| `UatLocal001KillDuringShTest` (kill during a long-running `sh`) | shell-execution control / journal | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal001KillDuringShTest.kt` | 1 / 0 / 0 at `b30584e5`. |
| `UatLocal002ResumeAfterKillTest` (resume after kill via `--resume` against the SAME `--db` / `--control-root`) | durable control row + replay | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal002ResumeAfterKillTest.kt` | 1 / 0 / 0 at `b30584e5`. |
| `S54ExternalVerticalRestartUatTest` (vertical restart: SIGTERM during the installDist run) | external restart of installed binary | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/S54ExternalVerticalRestartUatTest.kt` | 2 / 0 / 0 at `b30584e5`. |
| `DestructiveSafetyOwnershipTest` (11 rows: ownership, disk, user project, allowed subdir) | destructive-safety invariants | `:pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/DestructiveSafetyOwnershipTest.kt` | 11 / 0 / 0 at `b30584e5`. |
| `E-EM-11 R1..R6` retry control-row closure (ADR-0075) | `RetryReconciler`, control-row ledger | `pipeline-domain` unit tests (R1/R3/R4/R6), `caa0b497` | GREEN. R2 installDist closure: counter 1→2, retry-ok=1, journal `{FAILED, SUCCEEDED}`, replay reuses cached success — `aae1acb1`. |
| Per-Step G8 receipts' `--rerun` vs `--resume` (e.g. `S2_A4_CORE_EMITEVENT_G8_...` "Re-run" leg) | the canonical installed binary | the `pipeline-application run` invocations described in each receipt | Both legs exit 0; `ReplayPolicy.MEMOIZED` confirmed (`S2_A8_CORE_WAITUNTIL_G8_FINAL_CERTIFICATION_RECEIPT.md §2 G7`). |
| `S4_R1E_RECERTIFICATION_RECEIPT.md` (re-cert on `c32fa1e1`) | `BodyStructureDigest`, `InMemoryOperationJournal` NUL-byte audibility + `check` rerun | full `cd v2 && ./gradlew check --rerun-tasks` | 27 módulos · 732 suites · 4873 tests · 0 fallos; XML fresh. Latest recert over a 79-commit window; **earlier receipts unchanged unless re-anchored here**. |
| `EffectReplayPolicyTableFitnessTest` (DR-2 teeth) | replay-policy table | `:pipeline-architecture-tests` | Active green; falsifies the pre-ADR-0103 table. |

**HF3 verdict:** the canonical kill/restart is GREEN; the only structural exposure is that **the HF3 rung does not exercise the four CLI verbs** — none of `UatLocal001`, `UatLocal002`, `S54ExternalVerticalRestart`, `DestructiveSafetyOwnership` is per-verb. The verb-level restart story lives in `R4_CLI_GAP_ANALYSIS.md §5` and is outside S7's lettered scope.

---

## §3. Per-HF deep dive

### §3.1 HF0 — Pure Contract

**Strengths**

```text
- 22 per-Step *StepContractSuiteTest rows serve as the canonical HF0 entry;
  every row is on the C01..C19 axis from STEP_PLUGIN_CERTIFICATION.md §R2.
- 8 S3*LegacyRemovedFitnessTest in :pipeline-architecture-tests cover HF0
  legacy-source absence (ADR-0070 invariant).
- The FArch*FitnessTest family adds property-level fitness (registry/
  body-policy/payload/sequence authority), all HF0 because they assert
  source/source-of-truth, not behaviour.
- LB02 G3 receipts (codec round-trip, typed-output carrier, descriptor
  recovery, shell-operations capability) all live on the G3 axis
  (pre-flip readiness) which is HF0 with contracts settled.
```

**Weaknesses**

```text
- Most LB02 receipts are anchored to 2026-09 SHAs and were not re-measured
  at origin/main @ ce8fa883. The matrix is shaped, not freshly green.
- The "Pure Contract" rung's discriminated negative path (PROHIBITED
  inputs are refused at compile time or at the contract seam, never at
  the handler call) is most clearly visible in Echo/Sh/Uppercase
  contracts; the rest of the per-Step matrix reaches that level only
  piecewise, with no single SHIP-time claim.
```

### §3.2 HF1 — In-Process canonical runtime

**Strengths**

```text
- S0SemanticWitnessMatrixTest is the cross-cutting HF1+HF2 witness with
  17 rows for the 17 STABLE surfaces; HF1 leg is satisfied by feeding
  the canonical coordinator with a synthetic pipeline (the REPLAY leg
  proves freshness; the INSTALLED leg proves HF2).
- Per-Step contract suites build the run through `CoordinatorFixture`
  (HF1) using the real `StepRegistry` — no mocks of the coordinator.
- B11 context-blocks receipts prove BodyInvoker / BranchInvoker are
  reached in-process (no privileged core path) and that capability
  admission happens before the handler (HF1 capability admission).
- AUD-04 (`AUD04_CLI_ARGUMENT_STRICTNESS_RECEIPT.md`) declares its
  harness level as "HF1 in-process over the real CLI entry point and
  a real SQLite store" — explicit honesty.
- AUDIT-04 applied 7 discriminating mutations on top of 8 rows; the
  receipt is the only HF1 evidence in the audit surface that names the
  rung in a header row.
```

**Weaknesses**

```text
- No `PipelineExtension` JUnit 5 extension exists; HF1 lacks a
  reusable harness primitive, so every test re-instantiates the harness
  ad-hoc. §4 gap 1.
- CLI verb tests are HF1 in-process (drive `main(args)`), but the
  multi-verb end-to-end discrimination (HF2 installed JAR) is missing
  for `console`, `events`, `inspect` — `R4_CLI_GAP_ANALYSIS.md §1.4..4.4`.
```

### §3.3 HF2 — Forked Real Distribution / installDist

**Strengths**

```text
- 12 G8 receipts close the per-Step "real installed CLI" rung: every
  LEGACY_PLUGIN_IDS Step has at least one fresh + replay `--db` /
  `--control-root` invocation against a built-and-installed JAR.
- External plugin: example.uppercase reaches CERTIFIED with the
  isolation pair (WITHOUT plugin compile-exit-1 / WITH plugin SUCCESS)
  through real JAR + real .pipeline.kts, delegation-only at G8.
- S7 BLOCK 3.x fitness tests give HF2 a closed contract: every forked
  child goes through `OwnedSubprocess`, every leak to /tmp is rejected
  by an architecture fitness scan (TestSandboxFitnessTest, 2
  assertions; InstalledDistributionHarnessFitnessTest, 3 assertions,
  ledger 23 → 53).
- AUDIT-08 evidence (B1A) measures the canonical authority
  (`ShExecution.invokeShell` → `ProcessDurableTaskRuntime` /
  `DurableShellExecutor.executeTerminal`) and is wired at the right
  rung.
```

**Weaknesses**

```text
- R4 CLI verbs: `console` no installed-JAR UTF-8 / `--stage` / `--kind` /
  `--tail` test; `events` no installed-JAR `--follow` / `--fields` /
  `--context` / `--tail` test; `inspect` not implemented at all.
  R4_CLI_GAP_ANALYSIS.md:230-329, 408-432. §4 gap 2.
- NO EI profile: real `installDist` recipe + `--plugin-jar` is proved
  for ONE plugin (example.uppercase) and via BOM citation; no fresh
  matrix covers the next plugin. §4 gap 3.
- S7 BLOCK 3.x chain freshness (§2.0): receipts point at
  `s6-plugin-sdk @ ...` SHAs, not at HEAD. §4 gap 5.
```

### §3.4 HF3 — Restart/Resume (kill, journal, replay)

**Strengths**

```text
- 15 tests cited in B3_PRECONDITION_STATE_RECEIPT.md §2 give four
  independent kill/restart shapes (mid-execution kill, resume after
  kill, vertical restart, destructive-safety).
- ADR-0075 E-EM-11 R1..R6 covers retry control rows with caa0b497 +
  aae1acb1 R2 installDist: counter 1→2, retry-ok=1, journal
  `{FAILED, SUCCEEDED}`, replay reuses cached success.
- Per-Step G8 receipts' `--rerun`/`--resume` distinction is a runtime
  gate within HF3: the same DB + control-root produces deterministic
  outcome on both legs. The "neither coincidental equivalence nor
  silent acceptance" rule is asserted per receipt.
- DR-2 teeth: `EffectReplayPolicyTableFitnessTest` falsifies the
  pre-ADR-0103 table; `ScriptedRegistryInvoker` is scanned with
  comments stripped so the table cannot be re-acquired as a literal.
```

**Weaknesses**

```text
- The 4 CLI verbs are **not** represented at HF3: there is no
  "restart-with-the-binary-running-and-the-second-CLI-call-resumes"
  proof for any verb. §4 gap 4.
- HF3 evidence is owned by four UAT classes and not split into a
  `*StepResumeTest.kt` / `*StepKillTest.kt` directory; whatever the
  next reuse-shape cares about must itself be added on top, rather
  than composed.
- The `ManualCleanupTest.kt`-shape remnant is the kind of HF3 evidence
  that gets cheaper the more it is shared; the absence costs an
  extra `process.cleanup()` per UAT to keep the SLA on PRs.
```

---

## §4. Gap list, ranked by cost

The ranking is **what closes each gap, and what stays unclosed if it isn't closed**. Per `AGENTS.md` (V2 TESTING RULES rule 6, "prefer evidence over test quantity"), gaps are not runnable counts — they are certification risks.

### §4.1 GAP — `PipelineExtension` / `RealPipelineExtension` / `PipelineSessionExtension` (most consequential structural gap)

**Status.** `PIPELINE_TEST_HARNESS.md §R2` names these as the HF1/HF2/HF3 capability surfaces:

```text
PipelineExtension (HF1), RealPipelineExtension (HF2), PipelineSessionExtension (HF3),
SandboxPipelineExtension (HF4/HF5)
```

**What does not exist on disk:**

```text
$ cd v2 && grep -rln 'class PipelineExtension\|class RealPipelineExtension\|class PipelineSessionExtension\|class SandboxPipelineExtension' .
(no output)

$ cat v2/pipeline-testkit/src/main/kotlin/dev/rubentxu/pipeline/v2/testkit/PipelineFixture.kt
package dev.rubentxu.pipeline.v2.testkit
class PipelineFixture
```

The `pipeline-testkit` module — at the spec's central delivery slot — is a 3-line stub.

**Cost of NOT closing.** Every HF1/HF2/HF3 test class hand-rolls its own harness: `CoordinatorFixture`, `AppBinSupport.discover()`, `OwnedSubprocess.run(...)`, `Main*` CLI calls with `String[]` arguments. The cost shows up as
1. the same bug (e.g. the OwnedSubprocess observer-escape hole from `S7_BLOCK_3_2`) re-introduces across N UAT classes;
2. every new test author needs to know the harness protocol before writing the contract test (cf. `B4_4_PLUGIN_AUTHOR_GUIDE_RECEIPT.md` §D3: `StepContractSuite` was cited as a class that "no longer exists", a doc defect that arises precisely because the spec and the implementation disagree);
3. the S7 BLOCK 3.x sprint exists only because the unified primitive was missing — three BLOCKs (3.6, 3.8) write **fitness tests** that are themselves the cheapest substitute for a real extension class.

**Cheapest path.** Land `PipelineFixture` as the minimal primitive (HF1 in-process over `CanonicalDurableRunCoordinator` + the real registry + `@TempDir`), then `RealPipelineExtension` (HF2: a JUnit 5 extension that owns `AppBinSupport.discover()` + `OwnedSubprocess.run(...)` and tears down), then `PipelineSessionExtension` (HF3: same as HF2 + kill/resume hooks). The S7 BLOCK 3.x `OwnedSubprocess`-fitness suite should be ported onto `RealPipelineExtension` and made **inherited by default**, so the next BLOCK-style sprint has nothing to migrate.

### §4.2 GAP — `console`, `events`, `inspect` empty HF2 rung

`R4_CLI_GAP_ANALYSIS.md` records explicit "NO installed-distribution (HF2 / real `installDist` JAR) test" rows per verb:

```text
§2.4 — `console`: HF2 absent (no `WULpr*CliConsoleTest`; no installDist canary)
§3.4 — `events`: HF2 empty rung (none invoke a real CLI process)
§4.4 — `inspect`: NOT IMPLEMENTED (BLOCKER), no HF0/HF1/HF2 stack
§1.4 — `observe`: HF2 partial (`ObserveLanes` fixture NOT a real store)
```

**Cost of NOT closing.** R4 (CLI observability) was the originating requirement for a "real JAR" expectation. Without per-verb HF2 tests, the receiving harness (`pipelinek-release-harness`) cannot discharge "real installDist canary" for the four verbs without reimplementing the matrix. ROADMAP §13.3 R4 row closes only the **handoff** of the candidate, not the matrix itself.

**Cheapest path.** One JUnit 5 class per verb, one method per surface flag. The shared helper already lives in `S0SemanticWitnessMatrixTest` (`run(script)` returning `Triple<Int, List<DomainEvent>, Path>`). The `events`/`console`/`observe` HF2 rung should be 4–6 short tests per verb; `inspect` is BLOCKED until the verb is implemented.

### §4.3 GAP — second external plugin has no first installDist canary

`LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md` proves the seam works for **one** external plugin. The "plugin-zero-production-change" claim (`STEP_PLUGIN_CERTIFICATION.md R1 acceptance`) is NOT proved for a second, structurally different external plugin (e.g. one with rich capabilities, body semantics, or event contribution).

**Cost of NOT closing.** The seam may carry a hidden assumption that holds for `example.uppercase` but breaks for a Step with, say, `NETWORK` capability or a body invocation pattern. Until 2..3 plugins cross-installDist the seam, the **universal** clause of the EXT-1/EXT-2 ADRs is honour-by-name, not by-evidence.

**Cheapest path.** Identify one Tier B / LFC-2E3 candidate (e.g. `junit.results` or `stash`) and require it to pass the closed G0..G8 + `UppercaseStepContractSuiteTest`-shape isolation pair before its Tier B tier is published.

### §4.4 GAP — CLI HF3 restart/resume missing

`UatLocal001KillDuringShTest`, `UatLocal002ResumeAfterKillTest`, `S54ExternalVerticalRestartUatTest`, `DestructiveSafetyOwnershipTest` cover HF3 for **the core pipeline**. None covers what happens to a CLI verb (e.g. `events --follow` mid-run, or `console --tail` reconnect) when the CLI process restarts.

**Cost of NOT closing.** A live `events --follow` against a long pipeline, then a kill, then a relaunch, is precisely the workflow a downstream observability tool (or the harness's OWN stream consumer) will hit. The CLI is the **observation** surface for HF3 — by the S7 BLOCK 3.8 fork-law widening argument, the rungs have to be defended at every forked binary entry.

**Cheapest path.** Two tests, one per pull-model verb (`events` paginated, `console` paginated): run the installDist binary, fork the CLI child, send SIGTERM/SIGKILL to the child, re-launch with the SAME cursor / `--limit` / `--after-cursor` and assert the timeline is complete.

### §4.5 GAP — S7 BLOCK 3.x freshness versus HEAD

Every S7 BLOCK 3.x receipt front-matter cites a SHA on `s6-plugin-sdk`:

```text
S7_BLOCK_3_1       b01b8d37 (S6 BLOCK 2e)
S7_BLOCK_3_2       164b9a42 (S7 BLOCK 3.1)
S7_BLOCK_3_3       577b0586 (S7 BLOCK 3.2)
S7_BLOCK_3_4       7dc8af79 (S7 BLOCK 3.3)
S7_BLOCK_3_5       b6e1ab49 (S7 BLOCK 3.4)
S7_BLOCK_3_6       e87be13d (S7 BLOCK 3.5)
S7_BLOCK_3_7       ee355a15 (S7 BLOCK 3.6)
S7_BLOCK_3_8       3fd0c235 (S7 BLOCK 3.7)
```

vs the most recent `S4_R1E_RECERTIFICATION_RECEIPT.md` SHA `c32fa1e1` (2026-10-05) and HEAD `ce8fa883` (2026-10-15). The 8 BLOCK receipts are 1–2 weeks behind, and ro ROADMAP §13.3 row B3 calls B3 PARCIAL with the S7 BLOCK receipts as evidence, not as a fresh assertion at HEAD.

**Cost of NOT closing.** Per `CERTIFICATION_PROTOCOL.md §7`: "Certificación antigua: referencia histórica válida para SU artefacto, no resultado de HEAD." Anyone reading the S7 BLOCK 3.x receipts as "current S7 evidence" gets the WRONG certification — the receipts certify a fork that diverged and was rebranded `s6-plugin-sdk`. The corpus would benefit from a **single re-anchoring receipt** that re-runs the 8 BLOCK migrations on `origin/main @ HEAD` and (re)issues the XML/digests.

**Cheapest path.** Add a single SUITE of `--rerun-tasks` invocations at HEAD (e.g. `cd v2 && timeout 1500 ./gradlew :pipeline-application:test --tests 'OwnedSubprocessRun*' --tests 'TestSandboxFitness*' --tests 'InstalledDistributionHarnessFitness*' --rerun-tasks`) and re-issue one digest anchor per BLOCK, naming the re-anchored state.

### §4.6 GAP — `LFC2E0_PRE_S1_EVIDENCE_AUDIT.md` "Event Harness verdict preserved" still pinned at `bbb2e584`

Same shape as §4.5: a 2026-09-11 anchor with 19/0/0 + 193/0/0 fitness numbers, never re-run on a more recent `origin/main`. There is no fresh "Event Harness verdict preserved post-`s6-plugin-sdk`" claim, only the LFC-2E0 closure receipt. The R4 CLI gap analysis reuses this anchor implicitly through the R4 row but does not refresh it.

**Cost of NOT closing.** A reader who needs to confirm `EVT-3` (pipeline-event-harness verdict) still holds at HEAD has to re-run the gate themselves.

**Cheapest path.** One `cd v2 && timeout 600 ./gradlew :pipeline-event-harness:test --tests 'EventHarnessContractTest' --tests 'RealHistoryParityTest' --rerun-tasks` and record in `docs/historico/` archive the per-candidate SHA.

### §4.7 GAP — `R4_WU-R4.5 BLOCKED` cross-CLI installDist canary is `@Disabled`

`docs/v2/07-uat/R4_WU_R4_5_BLOCKED_2026_10_14.md` records that the cross-CLI installDist canary (PR #119 → `5c845ceb`) shipped `@Disabled` because a "pre-existing pipelinek run defect (C8 canary also red at HEAD)" blocked the green. This is exactly the §4.2 gap in single-test form.

**Cost of NOT closing.** Until the canary is un-@Disabled, every claim that "the four CLI verbs work as a set from a real JAR" is true by absence, not by evidence.

**Cheapest path.** Diagnose the C8 canary defect (`OBS2_LEVEL_A_COVERAGE_AUDIT.md` and downstream PC-208 wave), close it, re-enable the canary.

### §4.8 GAP — `PipelineTestHarness.md §R2` says `StepContractSuite` / `PluginContractSuite` exist

§R2 lists `StepContractSuite` and `PluginContractSuite` as extension surfaces, but the codebase ships per-Step `*StepContractSuiteTest` classes (one per Step) and one `DirectivePluginContractSuiteTest`, not these two named abstract suites. `B4_4_PLUGIN_AUTHOR_GUIDE_RECEIPT.md §D3` records "StepContractSuite NO es una clase del repositorio, la implementación son suites POR STEP". Same shape as §4.1, but documentation-level.

**Cost of NOT closing.** External plugin authors reading the spec will hit the same dead end. The doc defect was patched for `B4_4`; the spec text in `PIPELINE_TEST_HARNESS.md` was **not** patched, and other consumer docs (e.g. the docs/semantic-evolution `06-certification-harness-v2.md`) still name the same construct.

**Cheapest path.** Update §R2 to enumerate "per-Step `*StepContractSuiteTest` is the HF0/HF1 carrier; the spec name is `StepContractSuite` for SHIP-time test class discovery" or rename the file-level convention to `StepContractSuite.kt` under each per-Step directory.

### §4.9 GAP — pre-existing failures not re-classified against HF tiers

`UAT_GATE_CLASSIFICATION.md` table rows 1–10 classify against STEP-CERT / PRODUCT-GATE; **none** classifies against HF0/HF1/HF2/HF3. The rows that bear on HF evidence:

```text
Row 1 — ExternalPluginFourFamilyAdmissionTest   STEP-CERT C05/C08 (registry admission)
Row 4 — FArchS3PureBuilderPurityWitnessTest      @EnabledIf installDist — fails on installed binary
                                                  while in-process sibling is green
Row 5 — CompatibilityCorpusTest                  ~30 fixtures run against the real CLI produce an
                                                  EMPTY observed transcript
Row 6 — DirScopeEndToEndTest                     installed-CLI `dir` end-to-end, RED
Row 7 — ObsPc2LevelAUatTest                      installed-CLI output-plane semantics, RED
Row 9, 10 — SegmentOutputStoreTest               durability format defect, RED
```

Of these, rows 4 (HF2 installDist), 5 (HF2 corpus), 6 (HF2 `dir` end-to-end), 7 (HF2 output plane) belong to the HF tier axis. None cross-references `PIPELINE_TEST_HARNESS.md §R1`. Classifying them against HF rungs would let the next audit say "HF2 has these blockers" instead of reading the receipt text end-to-end.

**Cost of NOT closing.** A future RFC on a new HF rung will face the same unclassified evidence problem; the HF axis is the natural follow-on for `UAT_GATE_CLASSIFICATION.md`.

**Cheapest path.** Add a one-line HF rung column to the UAT_GATE_CLASSIFICATION table (or a footnote). Documented gap, not a code defect; recorded here for the operator's next UAT_GATE_CLASSIFICATION pass.

---

## §5. Findings on pre-existing failures (criterion for editing `UAT_GATE_CLASSIFICATION.md`)

This audit did NOT execute any new test class against HEAD. It only **read** receipts and test files. Per AGENTS.md rule 14 ("Zero-fabrication: every reported test result comes from a fresh run of an existing file"), a doc-only audit cannot surface a NEW red, and so cannot justify adding a row to `UAT_GATE_CLASSIFICATION.md`. The rules in that file (rule 6 in particular) require a base-vs-head diff, not a file read.

**No edit to `UAT_GATE_CLASSIFICATION.md` is recommended from this WU.**

What the audit CAN justify, and what the operator may want to consider in their next pass:

```text
- A future classification pass could add HF-tier cross-references to the
  rows 4, 5, 6, 7, 9, 10 (per §4.9).
- The §4.7 R4-WU-R4.5 defect (cross-CLI installDist canary @Disabled) is
  documented in its OWN BLOCKED receipt and need NOT be re-listed on the
  ten-row UAT_GATE_CLASSIFICATION table.
```

---

## §6. Recommendations for the next train

Ordered by **closure ratio × cost-recovery**:

```text
R1  Adopt the §4.1 PipelineExtension/RealPipelineExtension/PipelineSessionExtension
     trio at the minimal primitive line count. Port S7 BLOCK 3.6 + 3.8 fitness
     tests under them as inherited defaults. Expected: 200-400 lines of testkit,
     +1 small migration commit, kills the BLOCK-3.x-as-permanent-cleanup mode.

R2  Add the per-verb HF2 canary tests for `events`, `console`, `observe`,
     built on the S0SemanticWitnessMatrixTest helper, four methods per verb
     each. Implementing `inspect` removes the BLOCKER in §4.2 entirely.

R3  Build a second external-plugin installDist canary, ideally with rich
     capability requirements (`NETWORK` or a body Step), to prove the seam
     is one-plugin-deep, not upper-bounded by `example.uppercase`.

R4  Re-anchor the S7 BLOCK 3.x chain against HEAD in a single digest
     commit (per §4.5). One receipt, eight BLOCK-chained anchors, no
     per-BLOCK re-execution ceremony.

R5  Close the R4-WU-R4.5 BLOCKED canary defect, un-@Disabled, complete
     the §4.7 obligation. This is the smallest single-issue gain.

R6  Patch PIPELINE_TEST_HARNESS.md §R2 + 06-certification-harness-v2.md
     to refer to per-Step *StepContractSuiteTest / DirectivePluginContractSuiteTest
     instead of an absent StepContractSuite class. Doc-only.
```

R1 and R2 share the same answer: **the missing harness primitive**. R5 is the cheapest win. R3 and R4 are larger LFC-2 commitments.

---

## §7. Evidence used (and what was NOT read)

The audit read the following files end-to-end (limited by purpose, not by length):

```text
ADR/code/spec layer:
  docs/v2/03-specifications/PIPELINE_TEST_HARNESS.md       (HF0..HF6 ladder, R2 capabilities)
  docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md    (R1 states, R2 C01..C19, R3 suites, R4 honesty)
  docs/v2/07-uat/CERTIFICATION_PROTOCOL.md                 (§2 T0..T5 layers, §3 C01..C19, §4 two gates)
  docs/v2/05-roadmap/ROADMAP.md §13.3 rows B0..B7          (B3 PARCIAL state, S7 BLOCK 3.x recap)
  docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md B1..B17     (per-WU HF rung assignment)
  docs/v2/05-roadmap/R4_CLI_GAP_ANALYSIS.md §1..§5         (per-verb HF2 absence matrix)
  AGENTS.md (this repository's operational constitution)

Receipt layer (sample of 50+ for the rung assignments):
  docs/v2/07-uat/S7_AUDIT_REVIEW_FINDINGS_TRIAGE.md        (§1–§10 AUD-01..AUD-11)
  docs/v2/07-uat/S7_BLOCK_3_{1..8}_*RECEIPT.md             (8 receipts, HF2 migration corpus)
  docs/v2/07-uat/B3_PRECONDITION_STATE_RECEIPT.md          (B3 sub-row state, HF3 owner rows)
  docs/v2/07-uat/LFC2E0_PRE_S1_EVIDENCE_AUDIT.md           (Finding 1..5, HF0 anchor)
  docs/v2/07-uat/B1A_CHARACTERIZATION_RECEIPT.md           (HF1 "Fidelidad" cited verbatim)
  docs/v2/07-uat/B1D_CLOCK_SEPARATION_RECEIPT.md           (reloj AUD-06 B1d)
  docs/v2/07-uat/AUD04_CLI_ARGUMENT_STRICTNESS_RECEIPT.md  (HF1 header field, evidence shape)
  docs/v2/07-uat/S2_A{1..10}_G8_*RECEIPT.md, S2_B10_G8_*, CORE_ECHO_CERTIFICATION.md,
  CORE_ECHO_G4_FITNESS_RECEIPT.md, LB02_S6_BURN_DOWN_AND_CERTIFICATION.md
  docs/v2/07-uat/LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md (G7/G8 external plugin shape)
  docs/v2/07-uat/LB02_EP0_EXTERNAL_PLUGIN_PROOF_OPENING.md (EP-0 opening)
  docs/v2/07-uat/CTX_P_CLOSURE_RECEIPT.md                  (CTX-P, contextual ownership)
  docs/v2/07-uat/LFC2R_R4B_INSTALLED_PRODUCTION_WIRING.md  (R4B decision)
  docs/v2/07-uat/R4_WU_R4_5_BLOCKED_2026_10_14.md          (§4.7 gap)
  docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md                (10 rows × STEP-CERT/PRODUCT-GATE, no HF tier)
  docs/v2/07-uat/S4_R1E_RECERTIFICATION_RECEIPT.md          (recert closure over 79 commits)
  docs/pipelinek-semantic-evolution/06-certification-harness-v2.md (HF0..HF6 spec restatement)
  docs/pipelinek-semantic-evolution/08-roadmap.md §S7.1..S7.7 (S7 train features)

Code layer (file:line):
  v2/pipeline-testkit/                                    (stub: `PipelineFixture` only)
  v2/pipeline-application/src/test/kotlin/.../S0SemanticWitnessMatrixTest.kt
                                                            (HF1+HF2 cross-cutting witness)
  v2/pipeline-application/src/test/kotlin/.../EchoStepContractSuiteTest.kt
                                                            (HF0/HF1 per-Step suite template)
  v2/pipeline-application/src/test/kotlin/.../AgentCapabilitiesTargetWitnessTest.kt
                                                            (HF1 cited in KDoc)
  v2/pipeline-application/src/test/kotlin/.../B10/11 context blocks tests
  v2/pipeline-application/src/test/kotlin/.../UatLocal001KillDuringShTest.kt
  v2/pipeline-application/src/test/kotlin/.../UatLocal002ResumeAfterKillTest.kt
  v2/pipeline-application/src/test/kotlin/.../cli/S54ExternalVerticalRestartUatTest.kt
  v2/pipeline-application/src/test/kotlin/.../durable/DestructiveSafetyOwnershipTest.kt
  v2/pipeline-architecture-tests/.../FArchS3*LegacyRemoved*FitnessTest*.kt
  v2/pipeline-architecture-tests/.../EffectReplayPolicyTableFitnessTest.kt
  v2/pipeline-architecture-tests/.../Lfc2RegistryFamilyFitnessTest.kt
  v2/pipeline-architecture-tests/.../TestSandboxFitnessTest.kt
  v2/pipeline-architecture-tests/.../InstalledDistributionHarnessFitnessTest.kt
```

What the audit DID NOT do (and why):

```text
- Did NOT re-execute any test on the actual HEAD. Per AGENTS.md rule 14
  (zero-fabrication) and rule 17 (validation ladder), running the gate
  is a separate WU with its own budget.
- Did NOT open or modify any production file or test class.
  This was an evidence-classification WU.
- Did NOT re-measure the S7 BLOCK 3.x chain on HEAD.
  Recorded as §4.5 gap.
- Did NOT resurface a pre-existing failure that would justify an
  UAT_GATE_CLASSIFICATION.md edit.
```

---

## §8. Cycle closeout

```text
WU   6a2ee13b-651f-4d1e-a7c1-089df5824079 (B3.4 S7 evidence consolidation)
SHA  ce8fa8839c08b4ee8b05284f27cfc35fd50be52d (HEAD at start; receipt committed on top)

Evidence produced:
  docs/v2/07-uat/S7_HF0_HF3_EVIDENCE_AUDIT.md      (this file)

Evidence NOT produced:
  - no fresh -rerun-tasks JUnit XMLs
  - no SHA-anchored receipt
  - no edit to UAT_GATE_CLASSIFICATION.md
  - no code mutation anywhere in v2/
```

The audit is summary at the operator's discretion: closures of §4.1 (extension primitives) and §4.2 (per-verb HF2 tests) are the two investments that move the S7 train from "PARCIAL with named residues" (ROADMAP §13.3 B3 row) to a status where every rung has both the primitive and the proof.
