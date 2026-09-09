# LB-02 / S6.7 — stderr grounding and contract classification

## S6.7.1 — observed stderr/stdout semantics (installed distribution, registry path)

Controlled fixtures run through the installed distribution (`sh` on `core.sh`
through the registry family). `echo OUT` → stdout, `echo ERR >&2` → stderr.

| Fixture | Command | EchoOutputCaptured | Run outcome | Stdout seen? | Stderr seen? |
| --- | --- | --- | --- | --- | --- |
| only-out | `echo ONLYOUT` | `ONLYOUT\n` | success | yes | n/a |
| only-err | `echo ONLYERR >&2` | `ONLYERR\n` | success | n/a | yes |
| out-err (plain) | `echo OUT; echo ERR >&2` | `ERR\n` **only** | success | **NO (dropped)** | yes |
| out-err-rt | `echo OUT; echo ERR >&2` (`returnStdout=true`) | **none** | success | no (carried as value) | no |
| err-fail | `echo ERR >&2; exit 7` | `ERR\n` then `StepFailed(SCRIPT)` | failure | n/a | yes (before failure) |

### Layer table (from source + observation)

| Surface | stdout | stderr | merged/separate | observable as |
| --- | --- | --- | --- | --- |
| `ShExecution.invokeShell` (captureStdout=false) | via wrapper; **dropped when stderr also present** | via wrapper log | observed: inconsistent (both → stderr only) | `EchoOutputCaptured` |
| `ShExecution.invokeShell` (captureStdout=true) | captured as value (not event) | in log | separate-ish | typed `Stdout` value; no event |
| `ShellInvocationResult` | `Stdout.value` only | **not represented** | stdout only | closed result value |
| `CoreShellOutput` | carries result | **not represented** | stdout only | typed output envelope |
| `outputCodec` | encodes result | **not represented** | stdout only | durable encoded output |
| `EchoOutputCaptured` / events | single stream | (in plain path) | single log content | event log |
| public DSL `sh(...)` | per above | per above | inconsistent | events/value |
| installed distribution | per above | per above | inconsistent | events + exit code |

## S6.7.2 / S6.7.7 — authority separation

The three concepts are distinct today:

```text
process stderr        → NOT a first-class typed output (no field on ShellInvocationResult/CoreShellOutput)
typed Step output     → stdout/status only (ShellReturnMode)
observable event log  → single EchoOutputCaptured whose content is whatever the wrapper log captured
```

`stderr` is therefore **not part of `CoreShellOutput` / the codec**. It belongs to
the event/log substrate only. That separation is acceptable; forcing stderr into
`CoreShellOutput` to "fix" the codec would be an artificial channel merge.

## S6.7.5 — classification: contract gap, not a certifiable stable row

The plain `sh` path is **inconsistent**: when BOTH stdout and stderr are present,
only stderr reaches `EchoOutputCaptured` and stdout is dropped. When only one
stream is present, that stream is emitted. This is not a documentable contract
and not a clean "separate" or "merged" model:

- **A (separate stream):** false — plain path collapses to one event and drops stdout on collision.
- **B (merged stdout+stderr):** false — merged case emits only stderr, not both.
- **C (emitted only as events):** partially — but stdout is lost when stderr is present.
- **D (preserved in typed output):** false — stderr has no typed field.
- **E (combination):** true only in the sense that behavior varies by flags; it is not an intentional, documented combination.

This is a **contract gap / implementation inconsistency in the Sh output
substrate**, not a certifiable stable public behaviour. Certifying it as-is would
freeze a bug (silent stdout loss when stderr is present) as a "contract".

Per S6.7.5, I do **not** change production substrate semantics inside the
certification step, and I do **not** fabricate an assertion that encodes the
inconsistent behaviour.

## S6.7.6 — failure case (characterization, no new mandatory row needed)

`echo ERR >&2; exit 7` → `EchoOutputCaptured('ERR\n')` is emitted, then
`StepFailed(SCRIPT)` and `RunFinished(failure)`. So stderr **is preserved before
failure**; there is no `Failure → stderr discarded` regression in the observed
path. This characterization is recorded for the substrate work.

## S6.7.8 — installed distribution path

All grounding went through the installed `.pipeline.kts` → compiler →
coordinator → registry `core.sh` → subprocess path (no fake). This also surfaced
and led to the fix of a real regression: removing the legacy `core.sh` metadata
row (S6) had broken the canonical-core gate for installed execution
(`canonicalCoreStepIds` was legacy-only). The gate is now registry-aware
(`8c4cbbae`), restoring installed `core.sh`/`core.echo` execution. Grounding
fixtures then passed through the registry path.

## Certification status

```text
core.sh = IMPLEMENTED_UNCERTIFIED
LB-02 != REMOVED
```

The **stderr row** is classified as a **contract gap requiring a deliberate
substrate design decision** (separate streams, full merge preserving both, or
event-only), which is separate work, not a certification-test fabrication. Until
that decision is made and a stable public behaviour is asserted, `core.sh`
cannot honestly be `CERTIFIED`.
