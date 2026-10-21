# Release history

This file collects the per-train commentary that used to live inside the
root `v2/build.gradle.kts`, immediately above the `version = "..."` line.
Each block below is the rationale that justified that train's version
bump, taken verbatim from the build file at the moment the train opened.
Read it for *why* a number was chosen; read `git log` for *what* the
number contains.

The version itself still lives in `v2/build.gradle.kts` as a single
declaration, and the 0.x convention still applies: any change to the
published surface bumps MINOR, any other change bumps PATCH. Candidate
suffixes are rejected by `ProductVersion`; candidates are identified by
sha256 of the distribution ZIP, not by the version string.

## 0.45.0 — the 0.44.1..HEAD carries seven breaking commits

- `feat(cli)!` `3cd0d0d2` — local-first workspace default
- `fix(steps)!` `19577730` — core.pwd reports the cwd
- `refactor(workspace)!` `b797ca27` — the capability value IS the ExecutionLocation ADT
- `refactor(steps)!` `4d615fff` — scm checkout and junit results anchored to the location
- `fix(workspace)!` `9ef19694` — ambient-state escape hatch removed
- `fix(workspace)!` `9d2e999a` — ownership crosses the boundary; a user root is not destructible
- `fix(corpus)!` `459f431c` — each fixture states the workspace mode it requires

Breaking under 0.x convention bumps the MINOR component: 0.44.1 → 0.45.0.

This also repairs pre-existing drift: the declared version said 0.44.0
while the previous tag was v0.44.1, so the single-version provider had
been stale.

## 0.46.0 — WU-RP-035 train, no breaking commits, pipeline-domain.api purely additive (+21/-0)

- `feat(domain)` `83d5b2b0` — HANDLER_CONTINUATION + body-bound continuation
- `feat(engine)` `5a2c8a7d` — handler-driven route over the durable spine
- `feat(examples)` `03e77df2` — example.repeat external body plugin
- `fix(engine)` `342897f0` — canonical eligibility admits handler-driven blocks
- `docs(uat)` `25c670f7` — RP3_EXIT_REVIEW corrective addendum
- `docs(uat)` `d2f40691` — RP035-E receipt

The 0.x convention keeps the MINOR bump: 0.45.0 → 0.46.0.

## 0.47.0 — S4 train: a new `pipeline-output` module, `StageBody.Scripted`, and the durable/process transcript separation

- `docs(adr)` `de1c812c` — ADR-S4-F2 ACCEPTED — StageBody.Scripted de primera clase
- `feat(domain)` `0c04b543` — StageBody.Scripted y ScriptedStageRef (base de F2)
- `refactor(durable)` `fa72c901` — una sola regla de finalización de stage (B0)
- `Merge:` `9deaa9f9` — absorbe el M1 Output Plane (8 commits, SHAs preservados)
- `fix(s4-m1)` `294aea19` — una sola autoridad de salida de proceso (B1)
- `test(output)` `63dda75f` — el contrato medido del plano para un paso sin salida (B2a)
- `feat(output)` `051fb610` — autoridad de retención propia del Output Plane (B2b)
- `fix(output)` `4f82fd3a` — soltar una reserva nunca falla ni inventa bytes (B2c)
- `fix(test)` `(este)` — el corpus release-scale vuelve a mirar dónde están los bytes

`v0.46.0` remains a certified, immutable tag over the bytes at `63ef3220`
and is not reused. New public surface arrives rather than being removed:
a new `pipeline-output` module (`OutputStore`, the four ports,
`OutputCursor`), `StageBody.Scripted`, and the durable/process transcript
separation. Nothing pre-existing was taken away, so the 0.x convention
takes the MINOR component: 0.46.0 → 0.47.0. MINOR rather than PATCH
because the added module and the added `StageBody` case both change what
a consumer can build against, and because `EchoOutputCaptured` stops
carrying process output — a semantic change even though the type still
exists.

## 0.48.0 — TRAIN P3: product identity law

The product version is final from the moment the candidate is built, and
the tag is applied afterwards to exactly the certified bytes. So this
line opens the 0.48.0 train without creating a tag and without claiming
certification.

`:pipeline-sdk-bom` is a new published module, which changes what a
consumer can build against; `:pipeline-step-sdk:processor` is retired
in the same window. Nothing pre-existing was removed, so the 0.x
convention takes the MINOR component: 0.47.0 → 0.48.0.

This line carries no candidate suffix and must not grow one. The
v0.43.0 incident: an artifact internally consistent at `0.43.0-rc1`
was later presented under a GA identity. Candidate state lives in
`CandidateHandoff` (`candidateSequence`, `candidateId` = sha256 of
the distribution ZIP), not by the version string.

## 0.51.0 — CRIC-M3 publishes three EXPERIMENTAL consumer-negotiable capabilities

`output.read.digested.v1`, `output.pin.v1`, and `output.refusal.retention.v1`
are now consumer-negotiable as EXPERIMENTAL. M1 capabilities are PUBLIC,
M2 capabilities move from EXPERIMENTAL to PUBLIC on cert. The 0.50.0
train is closed (`v0.50.0-rc1` was the only candidate); the 0.51.0
train opens with `v0.51.0-rc1`.

## 0.61.0 — the 0.60.0 oversight in the rc1 block

The `v0.61.0-rc1` candidate was published with `ProductVersion` set to
`0.60.0` — an oversight in the rc1 block: the train version was 0.61.0
but `ProductVersion` was the 0.60.0 string. Bumped here to 0.61.0 so
the rc2 candidate has `ProductVersion = 0.61.0`, satisfying the product
identity law across all five surfaces.

## 0.62.0 — 2026-10-16: the 0.61.0 train closes at v0.61.0-rc6

The 0.62.0 train opens here (`ProductVersion 0.62.0`) so the next
candidate can be tagged `v0.62.0-rc1` and satisfy the identity law
across all five surfaces.

## 0.63.0 — 2026-10-19: the 0.62.0 train closes at v0.62.0

The 0.63.0 train opens here (`ProductVersion 0.63.0`) so the next
candidate can be tagged `v0.63.0-rc1` and satisfy the identity law
across all five surfaces.

## 0.64.0 — 2026-10-19: the Saga SLOT seam enters the published SDK

The public SDK contracts grew in this train: the Saga SLOT seam
(`BodySlotInvoker`, `BodySlots`, `SlotResolution`) is new published API,
so the version that carries it cannot be the version that shipped
without it.

## 0.65.0 — 2026-10-19: the Saga PLAN port and obligation lifecycle states enter the published SDK

The Saga PLAN port (`SagaPlanOperations`, `SAGA_PLAN_CAPABILITY`,
`CompensationDirective`, `SagaSnapshot`, `CompensationLifecycle`,
`CompensationObservation`) and the Saga obligation lifecycle states are
new published API, so the version that carries them cannot be the
version that shipped without them.

## 0.66.0 — 2026-10-19: the Saga RUNTIME seam enters the published SDK

The Saga RUNTIME seam (`SagaRuntime`, `SagaPlan`, `SagaActionPlan`,
`SagaCompensationReport`, `SAGA_RUNTIME_CAPABILITY`) is new published
API, so the version that carries it cannot be the version that shipped
without it.

## 0.67.0 — 2026-10-19: `COMPENSATION_NOT_DISPATCHED` enters the published vocabulary

`InterventionReason` gained `COMPENSATION_NOT_DISPATCHED`, the word for
a compensation that was not dispatched because the durable state refused
the write-ahead. The published vocabulary of a Saga is closed on
purpose, so a fact the old one could not name needs a new name in it.

## 0.68.0 — 2026-10-19: Saga facts become EVENTS on the event plane

The Saga's own facts are EVENTS now (`CompensationStarted`,
`CompensationFinished`, `SagaTerminal`), so a consumer of the event
plane can see what the compensation loop did instead of having to read
the durable rows to find out. New event kinds are new published API,
and the version that carries them cannot be the version that shipped
without them.

## 0.69.0 — 2026-10-19: the external Saga consumer travels with the shipped product

The external Saga consumer now travels with the shipped product, because
the E2E scenarios have to reach the Saga contract the way a real
plugin does — from outside, through published capabilities, with the
engine owning the durable state.

## 0.70.0 — 2026-10-19: the run's verdict describes its Saga; provider contracts become a closed set

The distribution grew in this train: the run's verdict now says what
happened to its Saga, and the example plugin's provider contract is a
closed set instead of a `String` whose typo would have meant the
opposite in silence.

The published ABI does not change — the five published artifacts are
identical — but the shipped product is not, and the version that carries
a content cannot be the one that already went out without it.

## 0.71.0 — 2026-10-20: change in the RELEASE PROTOCOL, not in the shipped runtime

The publication gate no longer loses a plugin whose manifest does not
declare a range. The gate is part of the product's contract — `AGENTS.md`
§1 counts identity, digests, and reproducible release assets among the
non-negotiable product constraints — and the 0.70.0 train shipped with
the blind spot, so the train that carries the fixed gate cannot be 0.70.0.

It is MINOR and not PATCH because the history derives it that way: every
train in this repository's history has been a MINOR, and the one train
that was a behavioural fix (0.66.0 → 0.67.0) was MINOR too. The
published ABI does not change either way — the five published artifacts
are identical.
