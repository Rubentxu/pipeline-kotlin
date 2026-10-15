# B1 — UAT status: REFERENCED can no longer hide a superseded status, and R8 keeps it fresh

- **Date:** 2026-10-15
- **HEAD at authorship:** `b2adcff3` (worktree `test-harness-brave-harbor-6920`)
- **Scope:** `scripts/gen-current-uat-status.py` + suite, `scripts/admission-check.py` + suite,
  `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` (regenerated).

---

## 1. The hazard the backlog recorded as "ADR-0095 supersede rule"

Regenerating the committed status document on this tree moved UATs from an explicit status to
`REFERENCED` — including because **this session's own receipts** mention their ids in prose.

```text
committed (2026-09-27):  COVERED 19 · PARTIAL 1 · KNOWN_LIMITATION 2 · REFERENCED 2 · NOT_APPLICABLE 3
regenerated (2026-10-15): COVERED 13 · PARTIAL 1 · KNOWN_LIMITATION 1 · REFERENCED 9 · NOT_APPLICABLE 3
```

The derivation is declared behaviour, not a bug: `select_evidence` takes the DAG-maximal
evidence, and a narrative-only maximal yields `REFERENCED`; its own docstring says callers that
need to distinguish "still certified" from "superseded by narrative" must inspect the maximal
set explicitly. No caller did. Printed bare, `REFERENCED` reads as "never certified", and the
document silently lost seven certifications.

**The derivation was not changed.** Changing it would reinterpret existing history, which is an
ADR decision (DR-10/DR-12), and this receipt does not take it.

## 2. What changed

```text
gen-current-uat-status.py
  narrative_supersession(triples, candidate, git, derived_status)
        reports the causally-latest hidden explicit status (DAG-maximal among the
        hidden triples; most-blocking only as a tie-break when the latest disagree)
  row annotation     "... → narrative-superseded: <STATUS> (older evidence: `<path>`)"
  new section        "## Narrative supersessions" table: UAT, hidden status,
                     older evidence, superseding narrative
  --check            prints "narrative_supersessions=present" when the section exists
  scratch hygiene    the check's scratch copy now lives in a TemporaryDirectory
                     (was a fixed /tmp/_uat_status_check.md: a concurrent check
                     collided with it and every run left a stray file)

admission-check.py
  R8   runs the generator's --check and reads its verdict; a stale document or a
       missing generator both FAIL (freshness not adjudicable). Diagnostics are
       path-relative so two clones print identical output (the suite's
       reproducibility invariant has teeth: the first version of R8 failed it
       because the message embedded the clone path).
```

## 3. Evidence

Suites (hermetic temp repos, real `git`, no network):

```text
python3 scripts/test_gen_current_uat_status.py     Ran 44 tests in 115.622s   OK
python3 scripts/test_admission_check.py            Ran 51 tests in 56.233s    OK
```

Four new rows pin the annotation, one per mutation named in its docstring: the bare
`REFERENCED` row (killed by mutating the renderer to skip the note — measured RED: the rendered
row was `| UAT-RP-005 | **REFERENCED** | b.md | | UAT-RP-005 | narrative mention | |`), the
silent case (no older explicit status), the causally-latest-over-most-blocking choice, and the
section/row pair in the rendered document. Three new rows pin R8: generator missing,
stale document, fresh document.

Real tools, real tree:

```text
$ python3 scripts/gen-current-uat-status.py --check
OK-IDENTICAL (# Current UAT Status (Production-Readiness))
narrative_supersessions=present (...)

$ python3 scripts/admission-check.py --head b2adcff3
R4 no blocking UAT states ....................... PASS
R5 receipt provenance ........................... PASS
R8 CURRENT_UAT_STATUS.md fresh .................. PASS (in sync; narrative supersessions disclosed)
R7 origin/main not ahead ........................ FAIL (candidate behind origin/main by 62 commits, pre-existing)
```

The disclosed supersessions in the regenerated document:

| UAT | hidden status | superseded by (narrative) |
| --- | --- | --- |
| `UAT-RP-005` | KNOWN_LIMITATION | `B1_HARNESS_UAT_STATUS_BINDING_RECEIPT.md` |
| `UAT-RP-011` | COVERED | `WIP9_OBSERVABILITY_RECEIPT.md` |
| `UAT-RP-015` | COVERED | `UAT_GATE_CLASSIFICATION.md` |
| `UAT-RP-017` | COVERED | `WIP9_OBSERVABILITY_RECEIPT.md` |
| `UAT-RP-019` | COVERED | `UAT_GATE_CLASSIFICATION.md` |
| `UAT-RP-020` | COVERED | `UAT_GATE_CLASSIFICATION.md` |
| `UAT-RP-021` | COVERED | `UAT_GATE_CLASSIFICATION.md` |

## 4. What the operator can now do, and what this receipt does not claim

- To restore a status, publish evidence with a real marker
  (`UAT-EVIDENCE | <UAT> | <STATUS> | candidate=<sha>`) — the document no longer leaves the
  reader guessing which rows need it, because it lists them.
- Amending the derivation itself (e.g. "a narrative mention must not supersede an explicit
  status") remains an ADR decision; the annotations make that decision cheap to write and
  impossible to skip.
- No UAT status was invented, and no blocking status (`FAIL_PROVEN`/`BLOCKED`/`REJECTED`/
  `NOT_RUN`) was introduced by the regeneration: R4 still passes.
