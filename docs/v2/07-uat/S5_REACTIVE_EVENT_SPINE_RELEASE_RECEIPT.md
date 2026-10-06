# S5 Reactive Event Spine — RELEASE RECEIPT

**Cycle:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine`
**Path:** A-full (explore → specify → design → plan → build → verify → release)
**Branch:** `s5-observation-vertical`
**Candidate SHA (gated):** `880a5287c9a45d8654fabc3b81ec615152765ccb`
**Base main:** `569a088cc76f1a826c619577eefa1475404c3fb4`
**Date:** 2026-10-06
**Status:** INTEGRATION RELEASE (not a version publication — see §1)

---

## 1. What this release is, and what it is not

This is an **integration release**: it lands the S5 Reactive Event Spine on `main`.
It is **not** a version publication. There is no tag, no `distZip`, no SBOM, no
GitHub release, and none is claimed. The versioned release that carries this work
is `0.48`, and per the roadmap it lands **after** S6, not here.

That distinction is recorded rather than blurred because the previous real
release receipt in this repository (`rp-053r-c1-d-dsl-residual-partition`) *is* a
publication. Reusing its shape here without its artifacts would have produced a
receipt describing bytes and a tag that do not exist.

**Gate class:** `STEP-CERT`.
**PRODUCT-GATE:** `BLOCKED_EXTERNAL`, unchanged by this receipt. Remote CI has not
existed since `754ddda0`; no `STEP-CERT` can turn that gate green.

## 2. Release artifact scope

15 commits over `main@569a088c`, 21 files:

| Category | Count | Files |
|---|---:|---|
| Production source | 4 | `pipeline-events`: `EventHistoryPorts.kt`, `EventHistoryReader.kt` · `pipeline-application`: `EventPageDrain.kt`, `MainEventsCli.kt` |
| Tests | 7 | drain, CLI visibility, external UAT, refusal contract, refusal propagation, S5.4 architectural law, contract-maturity fitness |
| Published API dump | 1 | `v2/pipeline-events/api/pipeline-events.api` (real binary break, registered with a real SHA in `ab7b6f6d`) |
| Governance ledgers | 2 | `published-contract-maturity.json`, `published-contract-exceptions.json` |
| Documentation | 7 | exploration, specification, design, plan, S5.4 receipt, ADR-0104, S5 verification report |

**Firewall files untouched:** `CanonicalDurableRunCoordinator.kt` and
`DslCompiledPipelineCompiler.kt` appear zero times in the diff.

**Hexagonal direction verified, not assumed.** `pipeline-events/src/main` contains
**zero** imports toward `events.durable`, `events.store`, `.application` or
`SqliteEventStore` — the port module does not know its adapters.
`EventHistoryPorts.kt` has exactly 2 imports, both inward (`domain`, `events`).
`SqliteEventStore` is reached only from `MainEventsCli`, which is the adapter and
the correct place for a composition.

## 3. Verification evidence

Full gate on the exact candidate:

| Field | Value |
|---|---|
| argv | `cd v2 && ./gradlew --no-daemon check --rerun-tasks` |
| SHA | `880a5287c9a45d8654fabc3b81ec615152765ccb` |
| started | `2026-10-06T18:15:27Z` |
| result | `BUILD SUCCESSFUL in 33m 43s` · **329 tasks executed, 0 up-to-date** · exit 0 |
| tests (bounded by that instant, over `v2/**`) | **5078** · 0 failures · 0 errors · 140 skipped · 766 classes |
| `apiCheck` / `detekt` | 6 / 27 modules green |
| log digest | `5fef62fa91d88e137c6574e2020974ed8bf27778e2dc13e714242fccd0bd77c5` |
| tree fingerprint (21 files) | `9f6962fb8a4863877de8fb795646018bba42f11546e48e042c2d489486269c0d` |

**Why this gate existed at all.** The S5.4 receipt was gated on `527ca770`, which
is three commits behind this candidate. A receipt is evidence for its own SHA and
inherits nothing, so re-gating the tip was mandatory before merging to `main`.

**The count was taken only after the gate finished.** Gradle wipes and regenerates
`test-results` when a test task starts; a mid-run count reads a partially rewritten
directory. An earlier mid-gate snapshot reported 346 classes / 2172 tests and was
discarded as smoke, not evidence.

**The `+1` against `527ca770` is explained, not hand-waved.** `65b643b6` added the
contract-maturity exhaustiveness law, moving that fitness from 15 to 16 tests
(`P3EPublishedContractMaturityFitnessTest` reports 16 in this gate, green). Skips
(140) and classes (766) are unchanged. No unexplained drift remains.

**The fingerprint definition changed, on purpose.** The previous `3d35eaea34…`
fingerprint cannot be reproduced because the file list behind it was never
recorded. Rather than carry an opaque number forward, this receipt defines its own:
sha256 of `xargs sha256sum` over the sorted 21-file diff against `main`.

## 4. UAT basis for `release-uat-approved`

The external vertical forked the **installed binary**, not the build tree:

| Suite | Tests | Failures | In this gate |
|---|---:|---:|---|
| `S54ExternalVerticalRestartUatTest` | 2 | 0 | yes |
| `S54DurableRefusalContractTest` | 5 | 0 | yes |
| `EventPageDrainTest` | 6 | 0 | yes |
| `MainEventsCliRefusalVisibilityTest` | 5 | 0 | yes |
| `EventHistoryReaderRefusalPropagationTest` | 6 | 0 | yes |
| `FArchS54PageRefusalIsTheStoreRefusalTest` | 4 | 0 | yes |

`AppBinSupport.discover()` resolves `build/install/pipelinek/bin/pipelinek`. It does
**not** read `PIPELINEK_SPIKE_HOME`. The bytes claim therefore rests on
`installDist` having run in this gate: the launcher script's mtime is
`2026-10-06T18:16:36Z`, after the `18:15:27Z` start. Verified, not inferred.

## 5. Boundaries this release does not cross

- It does not certify a plugin-contributed reactor. ADR-0104 records S5.5 as
  `DEFERRED`; that question belongs to S6, where plugin identity and admission exist.
- It does not certify the agent/MCP projection. There is no MCP surface and no
  agent consumer in this repository; §3 of the verification report records why and
  what such a projection would owe when it starts.
- It does not promote any published module out of `EXPERIMENTAL`. That is S8.
- It does not resolve any of the six debts below. They are assigned, not closed.

## 6. Debts carried forward, unchanged in status

| ID | Debt | Severity | Priority | Owner |
|---|---|---|---|---|
| D1 | `CompiledScriptedEntryPointHostTest` `@Timeout(30)` against a 12.59 s body; two more `@Timeout` in the same module | MEDIUM | P1 | S7 |
| D2 | `CoreWaitUntilStep.kt:69` rebuilds `StepOutcome` by comparing a `String` token; allowlisted | HIGH | P1 | **S7, not S8** |
| D3 | `UatDurableDefaultReuseCliTest` `waitFor(45, SECONDS)` | MEDIUM | P2 | S7 |
| D4 | `AGENTS.md` prose still names three `UNSUPPORTED_FAIL_CLOSED` incl. `agent`, stable since S3.1 | LOW | P3 | holder of that file |
| D5 | `PRODUCT-GATE = BLOCKED_EXTERNAL` while policy forbids remote CI | MEDIUM | P2 | before S7, needs an ADR |
| D6 | Cycle `rp7-sem-s4-scripted-runtime-v2` still `OPEN` with work integrated | LOW | P3 | operator decision |

D2 is deliberately scheduled **before** S8: resolving it during the compatibility
freeze would entrench the allowlist instead of removing it.

---

Reference implementation consulted: `S54ExternalVerticalRestartUatTest` is the reference external consumer; `CoreEchoStep` and `CoreShellStep` remain the reference certified Step shapes, untouched here.

Behaviour adopted: an unreadable durable row travels to the caller inside `EventPage.refusals`, carrying the store's own `EventRecordRead.Undecodable`, and `MainEventsCli` reports it on stderr.

Intentional deviations: no `@JvmOverloads` on `EventPage` (it would publish a constructor meaning "a page with no refusals"); `EventQuery.matches` is additive and carries no ABI seat.

Security implications reviewed: no credential, network, persistence or process boundary was widened. The refusal payload carries the store's decode reason and contains no secret material.

Tests demonstrating the contract: the seven suites in §4, all green on `880a5287`, plus the mutation record D-M1..D-M6, M-A and M-B recorded in the S5.4 receipt, each attributed 1:1 and restored with a verified sha256.
