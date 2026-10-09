---
type: adr
id: ADR-OBS-003
title: "The child's output descriptors are owned by an agent that outlives the runtime JVM"
status: accepted
date: 2026-10-09
deciders: "Rubentxu (product owner) — ratified §2 in full, 2026-10-09"
supersedes: null
superseded_by: null
related:
  - ADR-OBS-002
  - ADR-M1
  - ADR-0085
  - docs/v2/07-uat/OBS2_LEVEL_B_PRODUCER_SURVIVAL_SPIKE.md
---

# ADR-OBS-003 — Survival of the child when its runtime JVM dies (OBS-2 Level B)

> **Accepted 2026-10-09.** §2 is ratified in full — the ingest agent, one per run, launched and
> supervised by PipelineK — **including §2.3's `SIGPIPE` trap**, whose compatibility cost is stated in
> §2.3 and accepted with it. §5's recommendation is therefore the decision, not a proposal.
> `OBS-PC-208` is unblocked; its acceptance criterion is §4.

## 1. Context

### What was asked

OBS-2 Level B is a product guarantee fixed by the owner: the console must survive not only the death of
an observer or a controller, but the death of the JVM executing the step. `OBS-PC-208` cannot be written
until the *shape* of that guarantee is decided, and the shape cannot be chosen until the failure is
measured.

### What was measured

`ObsPc2ProducerSurvivalSpikeTest`, a real `kill -9` on a real JVM running the genuine `ShExecution`,
pump, redactor and store. Reproduced twice with identical figures:

```text
resumed_after_jvm_death=true              the child survived and carried on
alive_proof_after_jvm_death=true          it wrote a FILE successfully: no watchdog killed it
after_lines_written=0/50                 it produced NONE of its post-death stdout
bytes_after_release == bytes_after_kill   the Output Plane gained nothing
raw_secret_on_disk=0                      at-rest redaction held
```

The failure is **not** "output is lost". The child is **killed by the kernel**.

`DurableShellExecutor` gives the child `Redirect.PIPE` whenever the transcript is pumped, and
`buildWrapperContent` adds no redirect of its own in that mode, so the child's fd 1 *is* the pipe the
pump reads. The pumps are daemon threads inside the runtime JVM. When that JVM is killed the kernel
closes the read end; the child's next `write(2)` returns `EPIPE` and raises `SIGPIPE`, which terminates
the user's `sh` mid-script.

The two positive facts are what make this a finding rather than a story: `RESUMED` proves the child was
still running, and `ALIVE_MARK` proves it could still write to a **file**. Between those and the empty
tail there is exactly one difference — the write went to fd 1.

**A step does not merely lose its console tail when the runtime dies. The user's process is terminated,
and nothing anywhere records why.**

### Why the obvious repairs are excluded

| Candidate | Verdict | Reason |
|---|---|---|
| Spool the child's output to a file, ingest it later | **excluded** | a file written by the child holds raw bytes. `raw_secret_on_disk == 0` is an assertion today and stops being true; plaintext secret spool is forbidden by the owner's constraint. |
| Teach a reader to re-attach to an orphaned child | **excluded** | there is no orphan. `SIGPIPE` already killed it, so there is nothing left to read from. |
| Buffer the child's output in the wrapper and flush it at exit | **excluded** | the exit never comes, and it is the same spool with a different name. |
| A process that outlives the JVM, redacts in memory, commits sanitized bytes to the Output Plane | **only viable** | the only shape that keeps the child alive, keeps secrets out of durable state, and keeps the plane the single byte authority. |

So Level B **requires a process**. That is the cost this ADR exists to make explicit rather than to
discover later.

## 2. Decision

### 2.1 An ingest agent owns the child's output descriptors

For a pumped transcript, the child's stdout/stderr pipes are handed to a small, separate, detached
**output ingest agent** rather than to a pump thread inside the runtime JVM. The agent:

1. holds the read ends of the child's pipes;
2. redacts in memory, per channel, exactly as `StreamingRedactor` does today;
3. commits only already-sanitised bytes through the same `SegmentOutputStore` API the runtime uses;
4. releases each stream reservation, and therefore holds the same `cur.own` ownership lock
   `ADR-OBS-002` introduced — a stream has exactly one writer, and after this change that writer is the
   agent.

Raw bytes exist only in the pipe buffer, i.e. in memory, and never reach a durable file.

### 2.2 The agent is a writer, not a broker

This is the property that makes the whole thing cheap. The agent does not serve readers and does not
answer queries. It appends to the Output Plane. So:

- a reader after a runtime restart reads the plane and sees everything the agent has committed, with no
  re-attach handshake and no new reader protocol;
- losing the runtime does not lose a connection, because there was no connection to lose;
- `ADR-0085`'s non-negotiable law is respected by construction: a slow or dead *consumer* cannot affect
  the agent, because the agent does not know consumers exist.

### 2.3 The runtime ignores `SIGPIPE` in the wrapper — with a compatibility cost

`buildWrapperContent` gains `trap '' PIPE`. If the agent dies too, writes return `EPIPE` instead of
terminating the user's process.

This does **not** deliver Level B — those bytes are still lost — and it is decided anyway, because
losing a tail is a recoverable degradation and killing a user's `sh` mid-script is not. Without it, the
agent is a single point of failure for the user's *process*, not merely for their *output*.

**It is not free, and the cost is stated rather than absorbed.** A `SIG_IGN` disposition is inherited
across `exec`, so the user's script — and everything it spawns — would run with `SIGPIPE` ignored. A
script that pipes internally (`yes | head -n1`) currently kills its producer with `SIGPIPE`; with the
trap, the producer receives `EPIPE` and may exit non-zero instead, which can change the pipeline's exit
status. That is a change in Jenkins-visible behaviour for an unrelated feature, and it may deserve its
own compatibility decision rather than riding on this one.

If that cost is judged too high, the fallback is to ship §2.1 without the trap and accept that losing
the agent kills the child. This ADR recommends the trap; it does not claim the trap is uncontroversial.

### 2.4 The agent is not a second runtime

It owns no Step, no event, no journal, no result and no lifecycle. It cannot start work, cancel work or
decide an outcome. It is one bounded responsibility: drain two descriptors, redact, append. `A9` is
respected; the concern behind it — a parallel execution engine with its own truth — does not arise.

### 2.5 Scope and non-guarantees

| Guaranteed | Not guaranteed |
|---|---|
| Output the child produces after the **runtime JVM** dies reaches the plane | survival if the **agent** also dies (the child is `SIGPIPE`-terminated only if the trap in 2.3 is not in effect; with it, the process survives and the tail is lost) |
| No raw secret at rest, ever | delivery of bytes the child wrote but the agent had not yet redacted and committed |
| One writer per stream, kernel-owned | ordering between stdout and stderr beyond observed arrival |

The honest statement of the guarantee is therefore: **one process of indirection, bought with a second
process to supervise.**

## 3. Consequences

### Accepted costs

- **A new long-lived process.** It needs a lifecycle: launched per run or per step, discoverable in the
  control directory, and killable when a run ends. This is the first daemon-shaped thing in the runtime,
  and it is why the spike was required first.
- **A new failure mode.** The agent can now die where previously only the pump thread could, and the
  user-visible effect is a lost tail rather than a lost prefix.
- **A restart story that must be written down.** Which entity re-attaches, and when, is not decided by
  this ADR and is the first task of the implementation.
- **A deployment obligation.** Whatever hosts PipelineK must also keep the agent alive. That is a real
  change for Fabric and for the SDK publication calendar, and it is the main reason this ADR is
  proposed rather than accepted.

### What is NOT decided here

- Whether the agent lives inside PipelineK, inside Fabric, or is spawned and supervised by PipelineK.
- Per-step or per-run granularity.
- The re-attach protocol after a runtime restart.
- Whether an agent may be shared between runs.

### What this does not touch

OBS-1 stays as closed at `35ffeb05`. `storeForReading` / `storeForWriting`, the ownership lock and the
seam law are unaffected: the agent uses the *writing* opening and obeys the same lock, so it is a
legitimate writer rather than an exception to the seam.

## 4. Verification this decision must earn

The repair inverts a characterisation that is currently green, and the inversion is the proof:

- `ObsPc2ProducerSurvivalSpikeTest` `after_lines_written` goes `0/50` → `50/50`;
- `bytes_after_release` becomes strictly greater than `bytes_after_kill`;
- `RESUMED`, `ALIVE_MARK` and `DONE` all become true, with `DONE` meaning the step completed *after* the
  runtime JVM was gone.

A mutation that makes the agent forward raw bytes must RED the at-rest assertion
(`raw_secret_on_disk == 0`); a mutation that stops the runtime from spawning the agent must RED the
`DONE` marker. A green row before the repair is a defect held in place, never a guarantee.

## 5. Recommendation

Ratify §2 in full, and take the agent **spawned and supervised by PipelineK, one per run**, on the
grounds that it keeps the guarantee available to every consumer (CLI, TestKit, Fabric) rather than only
to a Jenkins deployment, and that per-run granularity amortises the process cost across the run's steps.

Ratification is required before implementation because the decision adds a supervised process to the
product's deployment surface, which is not this ADR's to take alone.