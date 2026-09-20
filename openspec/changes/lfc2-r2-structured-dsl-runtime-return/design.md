# Design: lfc2-r2-structured-dsl-runtime-return

## 1. Decision

Bring the LFC-2R2 design spike from branch
`cycle/lfc2-e1-r2-runtime-return@2227fa87+b7685b97` to `main` as
**ADR-0093**, with status `ACCEPTED` (it was `PROPOSED` on the branch; we
upgrade the status to reflect that the design is now the binding architectural
decision for the LFC-2R2 family on `main`).

## 2. Renumber path

```text
branch:        ADR-0081-structured-dsl-runtime-return.md    (2227fa87)
branch:        ADR-0082-structured-dsl-runtime-return.md    (b7685b97, renumber)
main (merge):  ADR-0093-structured-dsl-runtime-return.md    (this slice)

Rationale: main owns ADR-0081 (BodyInvoker/Continuation) and ADR-0082 (LPR
priority). ADR-0093 is the next free slot; the spike is renumbered on copy
without preserving the original 0081/0082 markers (the renumber history is
recorded in the commit message and this design).
```

## 3. Spike evaluation summary (mirrors ADR-0093 §1-§7)

| Dimension | A. CPS | B. Suspend DSL | C. Ref/val binding |
|---|---|---|---|
| Host compiles w/o plugin | NO (full-fidelity CPS = plugin) | YES (plain suspend, SPIKE-016) | YES |
| Spine semantic change | call-site identity degraded | NONE (scripted-consumer discipline) | Fingerprint over resolved values (new identity layer) |
| Typed control flow on `O` | yes, unreadable | YES (`if (out.trim() == dir)`) | NO (interpolation only) |
| No-hack law | fragile-generic, contradicts ADR-0006 | One generic seam over codec + callSite | New cross-cutting codec contract |
| Jenkins-familiar authoring | preserved shape, broken diagnostics | PRESERVED | Split-brain (branching → `script{}`) |
| In-tree precedent | ADR-0006 already rejected | SPIKE-016 PASSED, LFC-2R R2 shipped | None |
| Verdict | REJECTED | **ACCEPTED** | REJECTED (primary); reference insight reused in B |

## 4. Spike acceptance

The branch R2-R4B + S2-A5 (isUnix) work already in `main` is the empirical
basis for B's assumptions. SPIKE-016 passed (loops + nested blocks + replay
from same compiled artifact without serialising continuations); LFC-2R R2
shipped the same seam at generator level for `isUnix` and is the model for B.
Accepting B on `main` is therefore not a forward commitment; it is a
formalisation of a decision that is already operational for `core.isUnix`.

## 5. Eager/suspend duality in `steps { }` (the spike's hardest risk)

Today `steps { }` is pure eager data construction (AGENTS.md: construction
must not perform I/O). Design B makes runtime-returning calls `suspend`
*inside the same scope*. The implementation must pin the mixed-ordering rule
so call-site ordinals do not diverge between fresh and edited scripts in ways
the source-digest gate cannot catch. **Candidate rule (ADR-0093 §9, to be
locked by WU-LPR-087 implementation): strict lexical order — eager adds and
suspend calls share one ordered sequence.**

## 6. Hardest implementation risk (deferred to WU-LPR-087)

The Main.kt form selector predicate must extend to route sources with
runtime-returning calls inside `pipeline { }` to the suspend frontend,
**without** changing the backend. This is the R4B-style extension:

```text
Main may choose the frontend, never the backend.
```

The predicate is `sourceContainsRuntimeReturningCall(source)`, derived from a
PSI pre-pass; the predicate is the only new authority in the form selector.
Spike ADR-0093 §4.2 migration step #4 codifies this.

## 7. What WU-LPR-086 produces vs what WU-LPR-087 produces

| Item | WU-LPR-086 (this) | WU-LPR-087 (follow-up) |
|---|---|---|
| ADR-0093 file | ✅ | — |
| ADR README index entry | ✅ | — |
| STEP_INVENTORY_LFC2E0.md spike cross-ref | ✅ | — |
| INITIATIVE_LPR_001.md Tier A.1 cross-ref | ✅ | — |
| `stepValue` generic seam | — | ✅ |
| `RuntimeScriptedStepFacade.invokeTyped` | — | ✅ |
| `pwd()` / `pwd(tmp)` / `readFile()` / `fileExists()` consumer façades converted to `suspend` | — | ✅ |
| `sh(returnStdout = true)` consumer façade converted to `suspend` | — | ✅ |
| `Main.kt` form selector predicate extension | — | ✅ |
| `runtimeConfig.userDir()` placeholder killed | — | ✅ |
| `core.pwd` G7 canary (fresh + replay) PASS | — | ✅ |
| `core.pwd` G8 final certification | — | ✅ |
| `core.pwdTmp` G6+G8 | — | ✅ |
| `readFile` / `fileExists` G6+G8 | — | ✅ (separate consumers) |

## 8. Architectural fitness (acceptance criteria for this slice)

- ADR-0093 file present in `main`, content matches the spike (modulo renumber).
- ADR README indexes ADR-0093 between ADR-0092 and the next free slot.
- `STEP_INVENTORY_LFC2E0.md` `core.pwd` row references ADR-0093 in the
  blocker evidence.
- `INITIATIVE_LPR_001.md` §Tier A.1 references ADR-0093.
- `git log` shows one commit: "WU-LPR-086: bring ADR-0093 (LFC-2R2 spike) to
  main — docs only".
- No production code change.
- `LEGACY_PLUGIN_IDS = {}` (unchanged).
- L0 (compile) UP-TO-DATE; L1 (StepContractSuiteTest affected scope)
  UP-TO-DATE (no source change).
- L5 round gate not run for this docs-only slice (per AGENTS.md §V2 testing
  rules: docs-only changes skip Gradle validation).

## 9. End-of-slice closure block

```text
Reference implementation consulted: SPIKE-016, LFC-2R R2 (in-tree, binding)
Behaviour adopted:                 suspend structured DSL; one generic stepValue seam
Intentional deviations:            renumber 0082 → 0093 (slot conflict on main)
Security implications reviewed:    n/a (docs only)
Tests demonstrating the contract:   docs/v2/04-adrs/ADR-0093-structured-dsl-runtime-return.md
                                    docs/v2/04-adrs/README.md (index entry)
                                    docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md (cross-ref)
                                    docs/v2/05-roadmap/INITIATIVE_LPR_001.md (cross-ref)
```
