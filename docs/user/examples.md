# PipelineK — Recorded examples

**Recorded against**: `pipelinek 0.47.0` as installed by asdf (`~/.asdf/installs/pipelinek/0.47.0`),
2026-10-06. Not a Gradle build: no compiler output, no build noise.
**No published receipt covers these recordings.** This repository has had no remote CI since
2026-09-30, so this page claims no production gate. See
[`cli-reference.md`](cli-reference.md) → "Authority note".

These are not screenshots. Every frame is the real binary writing to a real terminal, with the
`asdf` shim in the `PATH`. Nothing is staged or hand-edited.

> ### ⚠ These demos are provisional
>
> They were recorded before the question of **what `pipelinek run` should print** was settled, so
> they show the current behaviour, which is a JSON event array on stdout — see
> [`cli-reference.md`](cli-reference.md) → "Known gap". In several of them the JSON dominates the
> frame and buries the execution trace that the demo is actually about. That is a product
> question, not a recording question, and it is open.
>
> These GIFs are being re-recorded once that decision is taken. Do not treat the current set as a
> finished gallery.

## Why each one is different

The first version of this page recorded ten GIFs that were, in practice, the same recording ten
times: same version command, same `pipelinek run`, same `echo $?`. The only thing that changed was
the pipeline filename. That was worthless as documentation.

Each demo below now answers **one specific question** and therefore runs **different commands**. The
question is stated before the GIF, so you know what you are about to see and can tell whether the
answer is on screen.

| # | Question | Distinctive command |
|---|---|---|
| 00 | How do I install it and is my machine OK? | `asdf install`, `pipelinek doctor` |
| 01 | What is a pipeline, and does `validate` predict `run`? | `validate` then `run` |
| 02 | Do the stages run in the order I wrote them? | `events` filtered by stage |
| 03 | Does it really launch operating system processes? | `console` transcript |
| 05 | What happens when a shell step fails? | `run` + `events`, showing `StepFailed` |
| 07 | Can a pipeline end without being clean and still exit 0? | `run`, showing `UNSTABLE` |
| 08 | Do the branches actually run in parallel? | `console` on both branch streams |
| 10 | Is a timeout just another failure? | `run`, showing `[TIMEOUT]` |

There is no demo for `06-durable` or `09-retry`, and that is a deliberate decision, not an
oversight. I measured both and neither leaves a distinguishing signal in the CLI:

- **`06-durable`**: a second run against the same `--db` produces the same event spine as the first.
  Nothing on screen says "reused".
- **`09-retry`**: the retries are **not journaled as separate steps**. The run shows a single
  `StepStarted`/`StepFinished` pair, exactly like a first-try success. The retry loop is invisible
  from outside.

Shipping a GIF that cannot demonstrate its own subject would repeat the original mistake. Both
pipelines remain runnable in [`examples/`](../../examples/) and are covered in prose in
[`pipeline-dsl.md`](pipeline-dsl.md).

## What you see on screen

Each recording shows, in this order:

1. The `pipelinek version` line, so you can see exactly which build is running.
2. The command, echoed at the shell prompt.
3. The full output: the event array on **stdout**, the transcript and summary on **stderr**, and the
   captured console output. Nothing is filtered for readability. `jq` is used only as an identity
   pretty-printer, because a single-line JSON envelope at 140 columns is unreadable and clips
   `RunFinished`.
4. The real exit code, read from `${PIPESTATUS[0]}` when there is a pipe — never from `$?`, which
   would report `jq`.

Only two things are ever removed from a recording, and both are noise from the machine doing the
recording rather than from PipelineK:

- `WARNING:` / `Picked up _JAVA_OPTIONS` lines, which the JDK on this box emits and which contain
  the installation path of the recording machine.
- `mavis-trash:` lines, because `rm` is wrapped by a trash tool here that prints to stdout.

Everything else — UUIDs, `occurredAt`, `eventId`, run ids — is left in. It is noisy, and it is what
the product actually produces.

One consequence is visible in every demo and is worth knowing before you copy a command:
**flags must come before the script path.** `pipelinek run script.kts --db x` silently ignores
`--db`; `pipelinek run --db x script.kts` is the working form.

## 00 — Install with asdf

The plugin verifies the archive against the release `SHA256SUMS` and aborts on mismatch, so this is
an integrity-checked install, not a download.

```bash
asdf plugin add pipelinek https://github.com/rubentxu/asdf-pipelinek.git
asdf install pipelinek 0.47.0
asdf set -u pipelinek 0.47.0
pipelinek version
pipelinek doctor
```

![Installation with asdf](assets/examples/00-install-asdf.gif)

`doctor` prints three lines and nothing else. The `workdir:` line is a real write probe: it creates
a file in the current directory and deletes it.

## 01 — The minimum, and what `validate` does not tell you

![The minimum pipeline](assets/examples/01-el-minimo.gif)

The point of this one is the difference between `validate` and `run`. `validate` prints
`VALIDATION SUCCESSFUL` for scripts that `run` later rejects with exit `2`, because `validate` never
reaches the canonical bridge where constructs like `git()`, `load()`, `node {}` and `ansiColor {}`
are refused. **The real check is `run`.** Full explanation in
[`cli-reference.md`](cli-reference.md) → "`validate` vs `run`".

## 02 — Does the order you wrote match the order that runs?

![Stage order read back from the journal](assets/examples/02-el-orden-manda.gif)

This one is deliberately not just a `run`. It reads the history back out of the journal with
`events` and filters it down to the stage subjects, so you can see the order in the recorded
history rather than infer it from the output. **The pipeline is not re-executed.**

## 03 — Real operating system processes

![Recovering the shell transcript with console](assets/examples/03-procesos-reales.gif)

The most useful thing in this page. The output of a `sh` step does **not** appear in the event
stream — only `echo` steps emit `EchoOutputCaptured`. To see what a shell actually printed, you
read its durable transcript:

```bash
pipelinek console --control-dir ./.d/durable-shell "$RUN_ID" "$OP_ID"
```

The `opId` is composed from the event subject for a linear run, exactly as
[`cli-reference.md`](cli-reference.md) → trap 7 describes.

## 05 — A failing step stops the run

![A failing step and the resulting events](assets/examples/05-un-fallo-para-el-run.gif)

Two things are on screen. The reason line names the failure with its type —
`cause [SCRIPT]: shell exited with code 3` — and the event stream ends with `StepFailed` and
**never opens the next stage**. The run exits `1`.

## 07 — Not clean is not the same as failed

![An UNSTABLE run that still exits 0](assets/examples/07-success-no-es-exit-0.gif)

This is the demo most likely to surprise you, and it is the reason the exit code table in
[`cli-reference.md`](cli-reference.md) lists `Unstable` as `0`. A pipeline can finish **unstable** —
not clean, but not a failure either — and the process still exits `0`. If your CI branches on the
exit code alone, this is the case that will bite you.

## 08 — Branches that really are parallel

![Both branch transcripts](assets/examples/08-ramas-en-paralelo.gif)

Both branch outputs, recovered from the two separate stream files. The `opId`s carry the `-b` and
`-bp` segments, and **they cannot be composed from the event history** — only `stage` and `step`
are recorded there. The stream filenames in the control directory are the only source. This is the
reason trap 7 in the CLI reference warns against composing the id by hand.

## 10 — A timeout is its own kind of failure

![A timeout failure](assets/examples/10-el-timeout-es-otro-fallo.gif)

Same exit code as demo 05, different reason: `cause [TIMEOUT]: durable shell timed out`. The failure
type is part of the message precisely so you can tell a timeout apart from a command that returned a
non-zero status.

## How these were recorded

- **Binary**: the asdf-installed `0.47.0`, not `installDist` from Gradle. The shim was verified with
  `pipelinek version` before each recording.
- **Canvas**: 140x45 at 12 fps. 12 fps is chosen deliberately: at the default 50 fps the same
  recordings are 1317 KB; at 12 fps they are 705 KB, with no visible loss on terminal output, because
  terminal text changes in bursts rather than smoothly.
- **Journal paths always have a directory component.** `--db run.sqlite` crashes — see
  [`cli-reference.md`](cli-reference.md) → trap 11.
- **The recording harness fails closed.** Three gates abort the build: a leak scan for
  machine-specific paths and hostname, a content gate that rejects visible errors and requires the
  distinguishing strings of that specific demo, and a distinctness gate that fails if any two demos
  end up with the same set of commands. That third gate is the regression test for the problem this
  page used to have.
- **Binary files are declared in `.gitattributes`** as `binary`, which git documents as equivalent
  to `-diff -merge -text`, so no line-ending conversion or textual diff is attempted on them.

Regenerating these GIFs is deliberately not automated. Every regeneration adds a new set of blobs
that Git keeps forever, and the recordings are correct for the binary they were made against.

## Next

- [`installation.md`](installation.md) — the three install routes, verified.
- [`cli-reference.md`](cli-reference.md) — every command, flag and exit code.
- [`events-and-troubleshooting.md`](events-and-troubleshooting.md) — reading a failed run.
- [`README.md`](README.md) — the hub.