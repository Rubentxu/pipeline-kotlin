# LFC-2 — Honest Jenkins-like DSL closure

Change: `openspec/changes/lfc-2-honest-dsl-closure`. Tracks "Honest Jenkins-like DSL: familiar DSL
with no fake runtime values" as an OPEN milestone with an itemized list and an explicit exit gate.
Scope: the DSL surface + observability fold. Durable-runtime spine work is out of scope (EM track);
parallel/retry/timeout canonical parity is tracked as `E-EM-11`.

## Status
🟡 OPEN / CLOSURE NOT PROVEN (reconciled at `7c9ce5c7`, 2026-09-08).
T2/T3 have historical focused evidence. T1 is quarantine, NOT implementation completion:
four `UatDsl003ParallelTest` methods and three full-grammar `UatDsl001JenkinsFamiliarityTest`
methods are disabled. Those seven obligations remain open. T4 is an inventory, not a passed
no-fake-return gate. See the native change's `design.md` for first bounded slice LFC2-H01 and
`evidence-2026-09-08.md` for fresh CLI evidence, source causes and remaining decisions.

## Exit gate
- [ ] Representative Jenkins fixtures compile to expected IR on the canonical path (no fake-return).
- [ ] No-fake-return DSL fitness: every named DSL step either has a real value/effect or is rejected at
      compile before execution (fail-closed) — never a silent placeholder return.
- [ ] Stage observability: canonical coordinator emits `StageStarted`/`StageFinished` (T3, DONE).
- [ ] Confirmed DSL gaps green: ERR-S-004 bookends (DONE), UatEvt001 G3 naming (DONE), DSL UATs green
      (mutating + UatEvt001 + ERR-S).
- [ ] Coordinator/EM suites stay green (no unjustified regression).
- [ ] Legacy-event-surface failures (M2-R1 parallel/retry/timeout) are NOT conflated with DSL-fake;
      quarantined and traceable to E-EM-11 (DONE).

## Reconstitution (2026-09-08) — extensibility is part of the LFC-2 gate

Change: `openspec/changes/lfc2-step-constitution-plugin-seam` (Phase B, APPROVED). Authority:
ADR-0070..0074 + specs (STEP_CONSTITUTION, EXECUTABLE_SCENARIO_CORPUS, PIPELINE_TEST_HARNESS,
STEP_PLUGIN_CERTIFICATION, TEST_SANDBOX_PROFILES). LFC-2 does not close only because the DSL surface
is honest; it must also be **demonstrably extensible**: a Step is not done because a façade returns
values, it is done when it is CERTIFIED.

Reconstitution itemized list:
| Item | Status | Evidence |
|------|--------|----------|
| Open StepRegistry + closed ExecutionNode structure (ADR-0070) | 🔲 pending (B1) | — |
| generic Step seam: Invoke → Registry → typed adapter → handler | 🔲 pending (B4) | — |
| migrate `echo` + `sh` onto the seam | 🔲 pending (B5) | — |
| RealPipelineExtension (HF2) | 🔲 pending (B6) | — |
| external reference plugin proof (zero core change) | 🔲 pending (B7) — extensibility gate | — |
| StepContractSuite / certification (ADR-0074) | 🔲 pending (B8) | — |

Reconstitution gate (added to Exit gate): `lfc-2-honest-dsl-closure` green AND the extensibility
proof CERTIFIED (external plugin runs through the same path as `echo`/`sh` with no core edit, on a
real distribution, via the common StepContractSuite). Until then LFC-2 stays OPEN.

## Itemized list (audit 2026-09-08, status legend: ✅ closed · 🟡 partial/debt · 🔲 pending · ⏭ deferred)
| Item | Status | Evidence |
|------|--------|----------|
| Stage bookends restore (ERR-S-004) | ✅ | T3 (coordinator run(), commit 800f1006) |
| G3 step-naming `<stage>/<type>-<index>` reconcile | ✅ | T2 (UatEvt001, commit 800f1006) |
| Parallel surface (G2) | 🔲 BLOCKED, not closed | T1 quarantine + `E-EM-11` backlog (a7a16c2c), seven disabled obligations |
| `parallel` canonical execution (composable ADR pending) | ⏭ deferred | `E-EM-11` (spine, EM track) |
| retry/timeout canonical per-step event projection | ⏭ deferred | `E-EM-11` (only legacy PipelineRun emits) |
| `@DslMarker` narrow receivers | 🔲 absent | no `@DslMarker` in `pipeline-scripting-api` main |
| Closed `StageBody` (no arbitrary receiver escaping) | 🟡 | `StageScope` concrete, nested inner scopes, no marker |
| Formal `.pipeline.kts` `@KotlinScript` | 🟡 | `Kotlin24ScriptingHost` compiles `.kts`; formal marker not evidenced |
| `isUnix()` real return (no fake `StubRuntimeConfig`) | ✅ CLOSED | `LFC-2R` R1..R4B (commits `7ade760a`..`3e194ee0`); runtime-returning seam through `ScriptedFrontendRunner`; `D3 DSL_RUNTIME_RETURN_GAP = CLOSED` for the `isUnix` seam. S2-A5/G3..G8 still pending for full certification. See `docs/v2/07-uat/LFC2R_R4B_INSTALLED_PRODUCTION_WIRING.md`. |
| `pwd()` real return (no fake `StubRuntimeConfig`) | 🔲 OPEN | Stub fallback still exists at `PipelineDsl.pwd()` (`pipeline-scripting-api/.../PipelineDsl.kt:1438`, per `EM_DEAD_CODE_AUDIT.md` A3). Canonical admission + runtime-returning seam NOT wired for `pwd` (distinct slice from `isUnix`); see `IMPLEMENTATION_BACKLOG.md` B14. Out of scope this cycle. |
| `waitUntil` honest semantics (vs throw RuntimeException) | 🔲 | placeholder poll + throw |
| `post`/`whenCondition` execution on canonical path | 🔲 | `toStageBuilder` omits post; `whenCondition` discards expression and appends body unconditionally |
| `node` no-op with only AgentResolved | 🟡 | documented no-op; fake-return risk on label/workspace |
| `git`/`scmGit` duplicate constructors | 🔲 | both `fun scmGit` (1044) and `fun git` (1072) |
| shell dollar (`$VAR`) handling / source rewriting | 🔲 | `buildShellScript` single-quote+escape path; Kotlin `$` needs care |
| durable `script {}` boundary | 🟡 | canonical body steps exist; boundary/durable proof pending |

## Closure disposition
NOT CLOSED. The prior claim that partial/debt rows could not block the milestone is withdrawn.
Admission is not yet fail-closed for all unsupported shapes: retry/timeout blocks are admitted as
single-pass bodies, whole-stage parallel is skipped by admission, post is discarded and when is
unconditional. A green linear subset does not discharge these obligations. E-EM-11 owns runtime
promotion, but LFC-2 retains its acceptance dependency. H01 is containment only, never closure.

## Verify
- Historical focused evidence: UatDsl001 (mutating), UatEvt001, ErrorHandlingTest (ERR-S-*).
  UatDsl003 + UatDsl001-full-grammar are disabled, NOT PASS. No fresh full gate in this diagnostic round.

## Local-first ecosystem continuation — LFC-2E

Once the foundational LFC-2 gate and EVT-3 Event Harness are green, the next product priority is **not**
EVT-4/controller/remote execution. It is a broad local-first Step ecosystem expansion using the proven
external plugin seam.

Canonical planning documents:

- `LFC2_STEP_ECOSYSTEM_EXPANSION.md` — ordered implementation roadmap;
- `../01-product/STEP_ECOSYSTEM_MATRIX.md` — family/delivery/certification coverage;
- `../03-specifications/STEP_ECOSYSTEM_POLICY.md` — core vs official/external plugin law.

### Sequencing law

```text
LFC-2 foundation + EVT-3
        ↓
LFC-2E0..E10 local-first Step/plugin expansion
        ↓
local-first feature freeze
        ↓
EVT-4 live relay / M4 remote-controller continuation
```

EVT-4+ remains tracked but is intentionally deferred until the local-first product surface has broad,
certified value.

### Core/plugin law

- universal pipeline semantics may remain `CORE`;
- non-universal high-value capabilities default to `OFFICIAL_PLUGIN`;
- vendor/domain surfaces default to `EXTERNAL_REFERENCE`;
- remote/controller-only semantics are `DEFERRED_REMOTE`;
- Jenkins internal Java-extension bridges may be `REJECTED_JENKINS_INTERNAL`.

A popular Jenkins Step is **not** automatically core.

### Extensibility pressure-test

Every non-universal family is attempted through the public plugin seam first. If a family such as
`junit`, utilities, HTTP, Git, containers or Artifactory needs a Step-specific edit in
`CanonicalDurableRunCoordinator` or a central per-Step dispatcher, the family slice fails its architecture
gate. The correct response is to improve the generic capability/plugin seam and prove the change with
multiple consumers, never to add a privileged plugin-name-specific core case.

### Certification after EVT-3

Every Step/family claimed complete must have:

- typed Step contract/codecs;
- declared capabilities/effects;
- canonical durable execution/replay behavior;
- real `.pipeline.kts` scenario;
- Step/Plugin Contract Suite evidence;
- reusable Event Harness acceptance contract where observable;
- no legacy executable alternative.

The existing `example.uppercase` remains the minimal external proof. LFC-2E progressively raises the
plugin difficulty from pure/string operations to filesystem/typed data, testing, network+credentials,
SCM, containers and finally a complex vendor reference plugin.
