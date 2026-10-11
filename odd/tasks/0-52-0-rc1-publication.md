# 0.52.0-rc1 — runtime ports publication (PK-R1 / CRIC-M2 train)

## Goal

Publish `:pipeline-runtime-api` (the new module carved out of
`:pipeline-runtime` in the previous M.0 commit) and the three M2
runtime ports it contains (RuntimeRecoverPort, RuntimeControlPort,
RuntimeIntrospectionPort) plus the pure decider (RuntimeRecoverDecision)
in the SDK for 0.52.0-rc1. The 3 ports become available to external
consumers via Maven coordinates; the SDK's contract-maturity JSON grows
its 5th entry; the FABRIC capability surface grows three new capability
checks; the external consumer project gains `RuntimePortsContractTest`.

## Why

The rc2 train (0.51.0-rc2) shipped without these ports; they were
documented deuda CRIC-M2. The certifier's response will determine
whether they are required for 0.51.0 finalisation, but the publication
itself is the user's chosen path (MINOR bump per the release-evolution
contract, see `docs/v2/07-uat/evidence/cric-m2-publication-plan.md`
section 5.2): adding a published module surface is MINOR, not PATCH,
so the train name moves to 0.52.0.

## Scope — four tracks

### Track W (sequential head, must land first)

Carries the SDK publication machinery wiring that the three port
tracks depend on. One work-unit commit on `0.52/rc1/wiring`.

- [ ] **W.1** Read `docs/v2/07-uat/evidence/cric-m2-publication-plan.md`
  sections 4 (publish procedure) and 5 (train recommendation) in full.
- [ ] **W.2** Update `v2/build.gradle.kts`:
  - `version = "0.51.0"` → `version = "0.52.0"` (train bump).
  - Append `:pipeline-runtime-api` to `publishedContractModules`.
  - Append `:pipeline-runtime-api` to `sdkBomModules`.
  - Append `:pipeline-runtime-api` to `bcvModules` (the
    binary-compatibility-validator module list).
- [ ] **W.3** Run `:pipeline-runtime-api:apiDump` to generate
  `v2/pipeline-runtime-api/api/pipeline-runtime-api.api`. Commit the
  generated file. This is the binary-compatibility contract the
  downstream bcv checks against.
- [ ] **W.4** Update the two existing fitness tests in
  `v2/pipeline-runtime-api/src/test/kotlin/.../` that check the
  module's structural / decider properties to reflect the new
  module path. (The tests are still the same code; the module
  path they reference is now `:pipeline-runtime-api`.)
- [ ] **W.5** Add the 5th entry to the contract-maturity JSON
  (find it under `v2/pipeline-domain/` or wherever the rc2 train
  added the prior 4 entries; the JSON lists each published
  contract surface with its maturity level and the train that
  promoted it). The 5th entry records `:pipeline-runtime-api` at
  maturity `BETA` (interfaces stable, behaviour covered by the
  in-monorepo adapter tests; FABRIC capability checks pending the
  port tracks below).
- [ ] **W.6** Run full gate on the post-W commit:
  `./gradlew :pipeline-runtime-api:compileKotlin :pipeline-runtime-api:compileTestKotlin :pipeline-runtime-api:test :pipeline-runtime-api:detekt :pipeline-runtime-api:apiDump :pipeline-runtime:test :pipeline-application:test :pipeline-application:installDist :buildExternalDirectivePlugin :pipeline-release:assemble` (or whatever the local release-equivalent task is). Report all totals.
- [ ] **W.7** Update the publication plan's "M.0 module split — done"
  section to add an "M.1 SDK publication wiring — done" section
  with the commit SHA, the version bump, the 3-list changes, and
  the 5th contract-maturity JSON entry.

### Track I — Introspection port (parallel tail, after W)

- [ ] **I.1** Add `RuntimePortsContractTest > RuntimeIntrospectionPort`
  to `examples/example-external-consumer/src/test/kotlin/.../`. The
  test distinguishes DECLARATIVE CONTRACT (the interface and option
  types exist with the documented shape) from EXECUTABLE CAPABILITY
  (the interface can be implemented and a sample snapshot built).
  The consumer implements a tiny in-memory `RuntimeIntrospectionPort`
  using only the public types from `:pipeline-runtime-api`, then
  exercises it. This is the contract surface external consumers
  depend on.
- [ ] **I.2** Add the FABRIC capability check entry
  (`RuntimeIntrospectionPort` is invocable through the host's
  introspection plane) to whatever capability registration the
  host uses (likely under `v2/pipeline-application/...`).
- [ ] **I.3** Run the consumer's full `./gradlew -PsdkVersion=0.52.0
  -PsdkRepo=file://... test --no-daemon` and verify the new test
  is green; report totals.
- [ ] **I.4** One work-unit commit on `0.52/rc1/port-introspection`.

### Track C — Control port (parallel tail, after W)

- [ ] **C.1** Same as I.1 but for `RuntimeControlPort` —
  `RuntimePortsContractTest > RuntimeControlPort`. Consumer
  implements an in-memory `RuntimeControlPort`, exercises
  cancel / idempotency / refusal, asserts outcome ADTs.
- [ ] **C.2** FABRIC capability check entry for control.
- [ ] **C.3** Run the consumer full test, verify green, report totals.
- [ ] **C.4** One work-unit commit on `0.52/rc1/port-control`.

### Track R — Recover port (parallel tail, after W)

- [ ] **R.1** Same as I.1 but for `RuntimeRecoverPort` —
  `RuntimePortsContractTest > RuntimeRecoverPort`. Consumer
  implements an in-memory `RuntimeRecoverPort`, exercises
  recover / idempotency / refusal / `RuntimeRecoverDecision`,
  asserts outcome ADTs and decision table.
- [ ] **R.2** FABRIC capability check entry for recover.
- [ ] **R.3** Run the consumer full test, verify green, report totals.
- [ ] **R.4** One work-unit commit on `0.52/rc1/port-recover`.

## Constraints (apply to all four tracks)

- No push to origin.
- No `--no-verify` for the SDDK commit-msg gate.
- Each track on its own branch in its own worktree.
- Train version is `0.52.0` from Track W onward; do not bump
  mid-track.
- "no falsear PASS, no ocultar skipped, no rebajar leyes de
  fitness, no tests vacuos con dobles que deciden lo que
  debería decidir producción" — every gate command reports
  actual observed output, not intended behaviour.
- Worktree-isolated only; do not touch the main checkout's
  working tree from inside an agent.

## Acceptance criteria — 0.52.0-rc1 closeout

- Track W merged to main: version is `0.52.0`, the three
  module lists carry `:pipeline-runtime-api`, the apiDump
  file is committed, the contract-maturity JSON has 5 entries,
  the publication plan's M.1 section records the wiring.
- Three port tracks merged to main: each with its consumer
  contract test green, FABRIC capability check in place,
  per-track commit. 50/50 → 56/56 (or whatever the new total
  is) on `examples/example-external-consumer`.
- `v2/build/sdk-repo` carries `:pipeline-runtime-api` under
  `dev/rubentxu/pipeline/v2/`.
- T9-equivalent: produce a 0.52.0-rc1 candidate locally
  (tag, ZIP, SDK tarball, SBOM, manifest, SHA256SUMS,
  handoff) and verify SHA reproducibility. Push to origin
  remains the user's decision under ordinary repository
  policy.

## Branch summary

- `0.52/rc1/wiring` — Track W (opus, sequential head).
- `0.52/rc1/port-introspection` — Track I (sonnet, parallel tail).
- `0.52/rc1/port-control` — Track C (sonnet, parallel tail).
- `0.52/rc1/port-recover` — Track R (sonnet, parallel tail).

## Status

- 2026-10-11 — feature document created; CRIC-M2 module split
  prerequisite (M.0) landed on main at `527ab61f`. Track W is
  next.
