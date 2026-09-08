# LFC-2 — Honest Jenkins-like DSL closure

Change: `openspec/changes/lfc-2-honest-dsl-closure`. Tracks "Honest Jenkins-like DSL: familiar DSL
with no fake runtime values" as a closed milestone with an itemized list and an explicit exit gate.
Scope: the DSL surface + observability fold. Durable-runtime spine work is out of scope (EM track);
parallel/retry/timeout canonical parity is tracked as `E-EM-11`.

## Status
🟡 IN PROGRESS (DSL-surface items green; full gate formalized below). Parts T0-T3 + T1 closure landed;
T4 audit + gate recorded here. Representative Jenkins fixtures compile to expected IR on the canonical
linear subset; legacy-surface fixtures quarantined as BLOCKED-ON-EM E-EM-11.

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
| Parallel surface (G2) | ✅ as spine debt | T1 quarantine + `E-EM-11` backlog (a7a16c2c) |
| `parallel` canonical execution (composable ADR pending) | ⏭ deferred | `E-EM-11` (spine, EM track) |
| retry/timeout canonical per-step event projection | ⏭ deferred | `E-EM-11` (only legacy PipelineRun emits) |
| `@DslMarker` narrow receivers | 🔲 absent | no `@DslMarker` in `pipeline-scripting-api` main |
| Closed `StageBody` (no arbitrary receiver escaping) | 🟡 | `StageScope` concrete, nested inner scopes, no marker |
| Formal `.pipeline.kts` `@KotlinScript` | 🟡 | `Kotlin24ScriptingHost` compiles `.kts`; formal marker not evidenced |
| `pwd()`/`isUnix()` real return (no fake `StubRuntimeConfig`) | 🔲 | DSL helpers default to `StubRuntimeConfig` (fake) |
| `waitUntil` honest semantics (vs throw RuntimeException) | 🔲 | placeholder poll + throw |
| `post`/`whenCondition` execution on canonical path | 🟡 | parsed into `PostConditionSpec`/nested; execution not proven |
| `node` no-op with only AgentResolved | 🟡 | documented no-op; fake-return risk on label/workspace |
| `git`/`scmGit` duplicate constructors | 🔲 | both `fun scmGit` (1044) and `fun git` (1072) |
| shell dollar (`$VAR`) handling / source rewriting | 🔲 | `buildShellScript` single-quote+escape path; Kotlin `$` needs care |
| durable `script {}` boundary | 🟡 | canonical body steps exist; boundary/durable proof pending |

## Closure disposition
LFC-2's honest-DSL gate closes on the canonical **linear** subset that is genuinely real and observable:
stage bookends, G3 naming, canonical core steps, error-handling semantics (ERR-S), replay/UAT timeline.
Everything else above is either quarantined-to-EM (`E-EM-11`) or recorded as DSL debt with fail-closed
compilation as the safety rule — the DSL never silently fakes a runtime value on the canonical path.
Each `🔲`/`🟡` row is a follow-up candidate for a dedicated item; none blocks the LFC-2 milestone because
the exit gate is the honest linear subset + no-fake-return rejection rule.

## Verify
- UatDsl001 (mutating), UatEvt001, ErrorHandlingTest (ERR-S-*) green. UatDsl003 + UatDsl001-full-grammar
  quarantined to E-EM-11 with precise `@Disabled` reason.
