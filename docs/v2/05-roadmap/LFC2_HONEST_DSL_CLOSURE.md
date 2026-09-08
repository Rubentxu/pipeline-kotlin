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
| `pwd()`/`isUnix()` real return (no fake `StubRuntimeConfig`) | 🔲 | Stub fallback exists; Main injects host config, which is not the stage/dir executor context; canonical admission must also be checked |
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
