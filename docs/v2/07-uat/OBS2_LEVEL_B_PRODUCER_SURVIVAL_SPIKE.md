# OBS-2 Nivel B — spike: qué ocurre con la salida del hijo tras morir su JVM

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **Base SHA** | `35ffeb05` |
| **Status** | **DEFECT OBSERVED** — measured across a real JVM kill, not reasoned from code |
| **Harness level** | HF3 — real `kill -9`, real child, genuine `ShExecution`/pump/redactor/store |
| **Decision** | the user fixed the guarantee (Nivel A **and** Nivel B). This block fixes the *mechanism question*. |
| **Blocks** | OBS-2 / `OBS-PC-208` |

## The question

OBS-B already answers the previous one: bytes a child **acknowledged before** a `kill -9` survive it,
and that row is green. The Level B question had never been asked on this branch:

> When the JVM that owns the child's stdout/stderr pipes dies while the child keeps running, does the
> output the child produces **afterwards** reach the Output Plane?

## What was measured

```text
resumed_after_jvm_death=true              the child DID survive and carried on
alive_proof_after_jvm_death=true          it wrote a FILE successfully: no watchdog killed it
after_lines_written=0/50                 it produced NONE of its post-death stdout
bytes_before_kill=4970
bytes_after_kill=4970
bytes_after_release=4970                  the Output Plane gained nothing
has_before_lines=true
has_after_lines=false
raw_secret_on_disk=0                      at-rest redaction held throughout
```

## The finding, and it is not the one the framing expected

The failure is **not** "output is lost". The child is **killed** — by the kernel — on its first write to
stdout.

`DurableShellExecutor` gives the child `Redirect.PIPE` when the transcript is pumped, and the wrapper
adds no redirect of its own in that mode, so the child's fd 1 *is* that pipe. The pumps are daemon
threads inside the JVM. When the JVM is `SIGKILL`ed the kernel closes the read end of the pipe, the
child's next `write(2)` returns `EPIPE` and raises `SIGPIPE`, and the user's `sh` dies mid-script.

The two discriminating facts are what make this a finding rather than a plausible story:

- `RESUMED` exists — the child was still running after the JVM died, so nothing killed it passively.
- `ALIVE_MARK` exists — it wrote a **file** successfully moments later, so no watchdog took it down.

Between those two facts and the empty tail there is exactly one difference: the write went to **fd 1**.
A child killed by a cookie-scan watchdog and a child killed by `SIGPIPE` both present as "no output
after the kill", and repairing one does not repair the other. That is why the row spends two assertions
separating them rather than reporting a single boolean.

**Consequence for the product:** a `sh` step does not merely lose its console tail when the runtime JVM
dies — it is terminated, with no explanation recorded anywhere. Level B, as the user has now fixed it
as a product guarantee, is a genuinely larger commitment than "keep the bytes".

## What this rules in and out

| Option | Verdict |
|---|---|
| Spool the child's raw output to a file, then ingest it | **Excluded by the user's constraint.** A file written by the child holds raw bytes, so the transcript would exist in clear on disk. `raw_secret_on_disk=0` is an assertion today and would stop being one. |
| A surviving owner of the child's descriptors that redacts in memory and commits sanitized bytes to the Output Plane | **The only shape consistent with all three constraints**: the child is not killed, no secret is at rest, and the Output Plane stays the single byte authority. It requires a process that outlives the JVM. |
| Teach readers to re-attach to an orphaned child | **Ruled out.** There is no authority to re-attach to: with `SIGPIPE` the child is already gone, so there is nothing left to read from. |

The roadmap's "no new daemon without a spike" condition is now discharged for the *question*; it is not
discharged for the *shape*, which is an ADR.

**Shape proposed in `ADR-OBS-003-child-output-descriptor-ownership.md` (status `proposed`, pending
ratification).** It is deliberately *not* accepted here: it adds a supervised process to the product's
deployment surface, and that is the owner's call, not this block's.

## Fidelity

HF3. `kill -9` on the real JVM; the child's process tree deliberately not killed. The genuine
`ShExecution`, pump, redactor and store run inside the killed JVM, so the crash is real at every layer
under study. Barriers are files: `BARRIER` (first emission finished), `GO` (released **only after** the
JVM is dead), `DONE`, plus `RESUMED` / `ALIVE_MARK` / per-line `AFTER-N`. `@TempDir`, no pipes, no
wall-clock assertions. The full observation is written to a report file outside the temp tree.

## Four harness defects this spike paid for

Each produced a wrong or empty answer, and the last two were false negatives:

1. **No post-death barrier.** The first version let the child continue emitting without waiting for the
   JVM to die, so an empty tail could not be told apart from "had not got there yet".
2. **The secret used as a marker.** The durable bytes are redacted, so searching for the canary always
   fails — the row was reporting `contains_before_lines=false` for a producer that had plainly emitted.
   Identification lines are now plain text, and exactly one dedicated line carries the canary.
3. **Killed too early.** The first version killed as soon as anything had been acknowledged and recorded
   `bytes_before_kill=1`. That proves the ingress flushed its first window and nothing else; the row now
   waits for a real prefix.
4. **The report inside `@TempDir`.** JUnit deletes the temp tree when the row ends, so the spike's
   entire output was deleted on success. The report is now written outside it.

## NOT_RUN

- Any architecture. This block measures and stops; it changes no product file.
- STEP-CERT / PRODUCT-GATE on this SHA.
- The ADR for the surviving-descriptor owner, and any implementation of it.

## Verdict

**The Nivel B gap is real, bounded and precisely characterised.** The child survives its JVM and dies on
its pipe. A repair must give the child's descriptors an owner that outlives the JVM and that redacts
before anything durable is written; redirecting to a file is excluded because it puts secrets on disk in
clear.

`OBS-PC-208` stays `NOT_RUN` until that owner exists. Nothing in this block changes OBS-1, which remains
closed at `35ffeb05`.