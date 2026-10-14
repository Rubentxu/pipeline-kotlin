# CDH-H0-EVIDENCE-03 — A refusal is a typed value, not an exit code plus silence

| Field | Value |
| --- | --- |
| Work unit | `CDH-H0-EVIDENCE-03` |
| Base SHA | `16272584b5ca00e894384ff10ecc3e2bb4a0a699` |
| Production SHA at measurement | `0de546e1b23aca0744aaf6978cd7a7a2211fb539374d10e86b83d141246e064d` (`scripts/run-scoped-junit-report.py`) |
| Classification | STEP-CHARACTERISATION → non-regression rows (defect measured first, then closed) |
| Suite | `scripts/test_run_scoped_junit_report.py`, 36 → **42** rows, green |
| ADR | none adopted. The CDH package remains unratified and untracked. |

## The defect, measured before any code changed

A repeated Gradle invocation was `UP-TO-DATE` (17 tasks, `BUILD SUCCESSFUL in 1s`) and
rewrote no XML. The reporter correctly refused. It did so like this:

```console
$ python3 scripts/run-scoped-junit-report.py --head "$H" \
    --results-dir v2/pipeline-events --boundary-file B --json 2>/dev/null | wc -c
0
$ echo $?
2
```

**Zero bytes on stdout with `--json` requested.** Four early exits returned `2` and
wrote a sentence to stderr, and none of them consulted `args.json`. A gate consuming
this tool could not distinguish "this run produced no evidence" from "the tool
crashed": `json.loads("")` raises before any verdict is read.

The characterisation harness entered the real CLI on all three reachable refusal
paths and recorded `stdout_bytes: 0` for every one
(`evidence/cdh-h0-evidence-03/early-exit-characterization.json`):

| Path | exit | stdout bytes | parseable |
| --- | --- | --- | --- |
| `no_xml_at_all` | 2 | 0 | no |
| `xml_outside_boundary` | 2 | 0 | no |
| `head_mismatch` | 2 | 0 | no |

A second, smaller defect was in the same message. It named ONE cause — "the boundary
file was almost certainly created after the run" — for a condition with two. On the
measured `UP-TO-DATE` run the boundary had been created *before* Gradle was invoked,
so the tool pointed the reader at the wrong file.

## The change

`Refusal` is a frozen record with a closed discriminant, and `emit()` is the single
exit point for every refusal. A refusal now emits the report shape with `verdict`,
`exit_code` and `refused{case,reason}` always present, so the machine contract does
not depend on which path was taken.

```text
NO_EVIDENCE                the results directory holds nothing
NOTHING_INSIDE_BOUNDARY    files exist, none of them belongs to this run
HEAD_MISMATCH              the report would be bound to a tree that did not run
```

`NOTHING_INSIDE_BOUNDARY` carries the evidence that separates the two causes the old
message conflated: `result_files_present`, `result_files_inside_boundary`,
`carried_over_files`, and `boundary_source`.

## Evidence

Real `UP-TO-DATE` refusal (`evidence/cdh-h0-evidence-03/uptodate-refusal.json`), after
`:pipeline-events:test` returned 17 `UP-TO-DATE` tasks and `BUILD SUCCESSFUL in 1s`:

```json
{
  "verdict": "INVALID_EVIDENCE",
  "exit_code": 2,
  "refused": { "case": "NOTHING_INSIDE_BOUNDARY", "reason": "..." },
  "result_files_present": 33,
  "result_files_inside_boundary": 0,
  "carried_over_files": 33
}
```

Real fresh run of the same module after `--rerun-tasks`
(`evidence/cdh-h0-evidence-03/fresh-report.json`): `BUILD SUCCESSFUL in 20s`, 148
tests, 33 fresh files, 0 carried over, `NOT_ATTESTABLE` at exit 0 because no catalog
was declared. The positive path is unchanged.

## Mutation evidence

`evidence/cdh-h0-evidence-03/mutations.log`. Control green (42 rows), production bytes
restored and hash-verified, **7/7 killed**.

| ID | Mutation | Killed by |
| --- | --- | --- |
| M17 | `emit()` stops honouring `--json` (reverts to the original stderr-only shape) | `test_no_evidence_refusal_is_json` |
| M18 | refusals collapse onto one `NO_EVIDENCE` discriminant | `test_nothing_inside_boundary_is_json_and_distinguishes_the_cause` |
| M19 | payload drops `exit_code` | `test_every_refusal_carries_the_same_minimum_contract` |
| M20 | refusal blames only the boundary, hiding the `UP-TO-DATE` cause | `test_the_text_mode_still_explains_itself` |
| M21 | payload claims `exit_code: 0` while the process exits 2 | `test_json_always_agrees_with_the_exit_code` |
| M22 | refusal JSON goes to stderr, stdout empty again | `test_json_always_agrees_with_the_exit_code` |
| M23 | `HEAD_MISMATCH` bypasses the emitter | `test_head_mismatch_refusal_is_json` |

## Incorrect assumptions recorded, not erased

**Two rows pinned the defect rather than the intent.**
`test_empty_results_dir_refuses_to_report_green` and `test_unrelated_dirs_are_not_scanned`
asserted `r.stdout.strip() == ""`. That expectation encodes an empty stdout as the
contract. The claims were always "a refusal must not print a report body", so both now
assert that directly (`"fresh"` absent, `PASS` absent). The transition is written out
in the row docstrings rather than silently applied.

**One row of my own was wrong twice.** `test_a_refusal_is_never_a_silent_success` first
omitted the boundary file and asserted exit 2; it failed `0 != 2`. Without an explicit
boundary the boundary is *inferred* as `newest XML mtime - grace`, which makes every
result file fresh by construction, so the run reports `NOT_ATTESTABLE` at exit 0
rather than refusing. The row, not the tool, was wrong. It was rewritten as
`test_json_always_agrees_with_the_exit_code`, which asserts the property that actually
holds on both paths: stdout is never empty, and `exit_code` equals the process status.

That inference is itself an unmeasured boundary, and it is **not** closed here.

## Correction to CDH-H0-EVIDENCE-02

The `WU_CDH_H0_EVIDENCE_02_CATALOG_RECEIPT.md` recommended verifying duplicate
basenames by running `:pipeline-application:test`. A static scan of all 773 test source
files across 22 modules found **773 distinct basenames and zero collisions, including
zero cross-module collisions**. No such verification is possible to fail, and the
recommendation is refuted.

## NOT_RUN, and what is still open

```text
full `cd v2 && ./gradlew check`                     NOT_RUN  (out of scope: this
                                                            change touches two
                                                            Python scripts and
                                                            no Kotlin module)
distribution UAT / releaseGate / coverage            NOT_RUN
external certification, G10                          NOT_RUN (harness-owned)
```

The provenance gap is unchanged and is the next slice. The tool still distinguishes
three states, of which two are indistinguishable:

1. boundary created after the run,
2. the build never re-executed (`UP-TO-DATE`) — **now named explicitly**,
3. valid fresh execution.

`NOTHING_INSIDE_BOUNDARY` names 1 and 2 as distinct causes to investigate and correctly
refuses both, but it does not *prove* which occurred. Closing that requires an external
execution marker or a unique per-run directory. Another reporter patch on the `mtime`
comparison cannot close it, and no such patch is claimed.

## Verification policy

Per AGENTS.md (2026-10-03) there is no remote CI in this repository. No `check`,
`--rerun-tasks`, UAT or certification result is claimed here. The executed evidence is
the reporter's own suite, the mutation runs, and two real Gradle invocations of
`:pipeline-events:test`, all recorded with argv and exit codes above.

## Reference implementation

```text
Reference implementation consulted: none applicable. This is a property of this
                                  repository's own gate CLI, not a Step, so no
                                  Jenkins or ecosystem equivalent governs it.
Behaviour adopted:                n/a (internal producer gate)
Intentional deviations:           none
Security implications reviewed:   n/a for this WU; no credential handling, no
                                  subprocess change, no network
Tests demonstrating the contract: scripts/test_run_scoped_junit_report.py
                                  class RefusalIsAValue (6 rows)
```