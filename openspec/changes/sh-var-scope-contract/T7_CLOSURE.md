# SH-VAR-SCOPE-CONTRACT — T7 closure

**Author:** SDDK orchestrator
**Date:** 2026-09-20
**Cycle:** `cycle/sh-var-scope-contract-s1`
**Reference:** `openspec/changes/sh-var-scope-contract/tasks.md §T7`.

## Result

No new fixtures were added under `v2/compatibility/` and no rows
were added to `CompatibilityCorpusTest`.

## Why

T7 in `tasks.md` reserved nine fixtures and one registration row.
This closure exits F1 with zero fixtures because every contract
the cycle committed to (Gap #2, #3, #4) is now verified by
in-process contract tests that pin byte-level behaviour directly
against the production `EnvVarNameExtractor + ScriptTextEscaper`
pipeline and an in-process `bash -c` invocation for layer-4 (Gap #4):

- Gap #2: `ShVarScopeGap02Test` (3 sub-cases, 3/0/0 GREEN).
- Gap #3: `ShVarScopeGap03Test` (3 sub-cases, 3/0/0 GREEN).
- Gap #4 (Form F): `ShVarScopeGap04FormFProbeTest` (2 sub-cases,
  2/0/0 GREEN) plus the byte-level probe extension in
  `S2ThreePhaseProbeTest` (1 sub-case, 2/0/0 GREEN).

A `v2/compatibility/*.pipeline.kts` fixture introduces a third
party to the contract: the Kotlin scripting compiler and the
installed binary. Per Guard G2 ("bytes, not aspect"), the byte
sequence is the contract, not how a third-party compile passes
or fails. Adding a corpus fixture would also re-derive the
already-pinned bytes via a third-party path that is not the
contract authority.

Gap #1 (same-name collisions) and Gap #6 (`withEnv` not
auto-protected) are `DOC only` per the spec.md decision matrix
and the operator's G1 ("DOC only is not abandonment"). No
test, no fixture.

Gap #5 (F2_TRIGGER) is GATED on a UX judgement that does not
yet exist. The test captures the byte-level delta (F2_TRIGGER_DATA
in `gap05-diagnostics.txt`); a future cycle can author a
single fixture for it if/when the operator opens F2.

## Decision authority

`openspec/changes/sh-var-scope-contract/spec.md` decision matrix
explicitly says:

- Gap #1 — DOC only (link to CHARACTERISATION.md §5.2).
- Gap #2 — CONTRACT TEST (covered by `ShVarScopeGap02Test`).
- Gap #3 — CONTRACT TEST (covered by `ShVarScopeGap03Test`).
- Gap #4 — CONTRACT TEST (covered by `ShVarScopeGap04FormFProbeTest`).
- Gap #5 — GATED (covered by `ShVarScopeGap05Test`).
- Gap #6 — DOC only (link to CHARACTERISATION.md §5.5).

None of these require a `v2/compatibility/` corpus entry. T7
therefore exits with no corpus rows added.

## If a future cycle wants corpus coverage

A future proposal can author F2 or an Offset Map cycle and
introduce a corpus fixture then — under its own GO, without
re-opening this change.

## Companion evidence

The four contract tests' JUnit XMLs and SHA-256 are in:

- `docs/v2/07-uat/evidence/sh-var-scope-contract/S2-three-phase-probe-extended.txt`
- `docs/v2/07-uat/evidence/sh-var-scope-contract/gap02-no-creds-block.txt`
- `docs/v2/07-uat/evidence/sh-var-scope-contract/gap03-shell-expansions.txt`
- `docs/v2/07-uat/evidence/sh-var-scope-contract/gap04-formF-raw-triples.txt`
- `docs/v2/07-uat/evidence/sh-var-scope-contract/gap05-diagnostics.txt`

## Untouched

- `v2/compatibility/` — no new files.
- `CompatibilityCorpusTest.kt` — no new methods; existing rows
  remain unchanged.
- All production sources remain untouched per Guard G3.
