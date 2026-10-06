# PipelineK — User documentation

**Documented against**: development branch, `pipelinek 0.47.0` (`v2/build.gradle.kts:75`), commit `b08fa948`

> **What this directory is.** The entry point to everything you need to write, run and operate
> PipelineK. If you arrived from the [main README](../../README.md), this is where the long version
> starts. If you already know Jenkins, start at [Track A](#track-a--i-want-to-run-pipelines).

**What is it?** A local-first CI/CD engine with a Jenkins-familiar Kotlin DSL. You write a
`.pipeline.kts` file, and PipelineK compiles, validates and runs it **on your machine**.

---

## Pick your track

Three routes, depending on why you are here. You do not need to read all of them.

| | Track | Read it when | Pages |
|---|---|---|---|
| **A** | [**I want to run pipelines**](#track-a--i-want-to-run-pipelines) | You want your first pipeline working today | 4 pages, ~25 min |
| **B** | [**I want to operate it properly**](#track-b--i-want-to-operate-it-properly) | Your runs are stateful, use secrets, or you debug failures | 3 pages |
| **C** | [**I want to contribute code**](#track-c--i-want-to-contribute-code) | You are going to change the code, not just use it | Main README + 2 documents |

---

## Track A — I want to run pipelines

The shortest path from "nothing installed" to "a pipeline that runs". Read in this order; each page
assumes the previous one.

| # | Page | What you will be able to do after it | Time |
|---|---|---|---|
| 1 | [Installation](installation.md) | Install the binary and verify it is intact | 5 min |
| 2 | [Quickstart](quickstart.md) | Write a pipeline, run it, watch it fail on purpose, and read its events | 15 min |
| 3 | [Pipeline DSL](pipeline-dsl.md) | Know which language constructs exist, and **which ones are proven** | 20 min |
| 4 | [CLI reference](cli-reference.md) | Drive the binary from a script with confidence | 15 min |

Keep [Cheat sheet](cheat-sheet.md) open in a second tab. It is the copy-paste table of exit codes,
subcommands, flags and examples.

[Español](README.es.md) · **English**

---

## Track B — I want to operate it properly

Everything about *where* a run happens, *what* it may touch, and *what to do when it breaks*.

| # | Page | What it answers |
|---|---|---|
| 1 | [Configuration & workspace](configuration-and-workspace.md) | Where does my run work? Why did my flags disappear? What does `--db` actually buy me? |
| 2 | [Credentials & security](credentials-and-security.md) | How do I use a secret without leaking it? Why do I get exit 3? |
| 3 | [Events & troubleshooting](events-and-troubleshooting.md) | My run failed — what happened, and what do I run to find out? |

---

## Track C — I want to contribute code

User documentation stops here. From this point the authority is different.

| Step | Document | Why |
|---|---|---|
| 1 | [`../../CONTEXT.md`](../../CONTEXT.md) | The canonical vocabulary, with anti-terms. 45 lines. |
| 2 | [Semantic constitution](../pipelinek-semantic-evolution/01-semantic-constitution.md) | **The laws.** Read before writing code. |
| 3 | [Certification protocol](../v2/07-uat/CERTIFICATION_PROTOCOL.md) | What `STEP-CERT` means and what a gate actually demands. |
| 4 | [`../../AGENTS.md`](../../AGENTS.md) | Work-unit discipline and quality gates. |

Two things to internalise before your first PR:

- **A certification receipt is evidence for its own SHA and inherits nothing.** If the code moved,
  the evidence expired.
- **There is no remote CI in this repository.** A green local gate is the strongest claim available,
  and it is bound to one exact commit.

---

## How we label behaviour — read this before you trust a claim

The DSL contains three very different kinds of construct, and the documentation labels every one.
Getting this distinction wrong is the most common way to lose an afternoon.

| Label | What it means | Can I rely on it? |
|---|---|---|
| **Proven by the examples** | Exercised by `examples/run.sh`, which asserts the exit code, the terminal outcome and, for the interesting ones, the event contract | Yes. This is the subset to start with |
| **Declared, no reference example** | Present in the v2 DSL with a full signature and descriptor, but **no example pipeline uses it** | Not proven. It exists; nobody has demonstrated it end to end |
| **Fails closed** | Compiles, then is **rejected at run time with exit 2**. It is a deliberate refusal, not a missing feature | You can rely on it *refusing*, never on it working |

[Pipeline DSL →](pipeline-dsl.md) has the full tables.

---

## Known divergences

Recorded so that nobody trusts a stale claim again.

### D1 — The `0.39.0` verification claim did not hold · recorded 2026-10-06

`pipeline-dsl.md` and `cli-reference.md` used to carry the header *"Release verified against:
`pipelinek 0.39.0`"* and to state that `parallel`, `retry`, `timeout`, `catchError`, `waitUntil`,
`milestone`, `stash` and `cleanWs` **did not exist**, and that only `core.echo` and `core.sh` were
certified.

That claim **did not hold against the code**. Those constructs are declared in the v2 DSL
(`StageScopeBuilders.kt:230`, `StageScope.kt:141,391,473,515,638,652`) and are exercised by
`examples/07`–`examples/10`, whose exit codes and event contracts `examples/run.sh` asserts.

**Resolution:** both pages now document the development branch (`0.47.0`) with the canonical header
and carry this note. The old header was removed rather than silently repurposed.

### D2 — No remote CI, so no "green build" claim · recorded 2026-10-06

`.github/workflows/` does not exist: commit `754ddda0` (2026-09-30) removed `lpr0-ci.yml`,
`release.yml`, `v2-baseline.yml` and `sdkman-publish.yml`. The PRODUCT-GATE is `BLOCKED_EXTERNAL`.

No page in this directory may claim a passing build or production readiness. Verification is local,
manual and bound to one commit.

### D3 — `docs/user/` was documented for people who already knew CI/CD · recorded 2026-10-06

The pages were accurate but assumed vocabulary they never defined. They have been rewritten so that
each one opens with what you will be able to do, defines every term the first time it appears, and
separates proven behaviour from declared behaviour.

---

## What "documented against" means here

| Claim | Status |
|---|---|
| Version | `0.47.0`, the **development branch** (`v2/build.gradle.kts:75`) |
| Verified against a published binary | **No.** Only the installation facts (digests, `0.39.0`) are |
| How this was checked | Reading the source. Every behavioural claim cites `path:line` |
| What was not done | Gradle was never run, and the binary was never executed while writing these pages |
| Last updated | 2026-10-06, commit `b08fa948` |

If you need a claim bound to a released artifact, use the [installation page](installation.md): the
`0.39.0` ZIP digests are the only executable-and-verified facts in this directory.

---

## Conventions used across these pages

- Version numbers, SHA digests, flags, subcommands, event keys and file paths are **identical in
  every language**. Only the prose is translated.
- Code you can copy is in fenced blocks. Never paraphrase a command inside a block.
- `NOT VERIFIED` never appears as a shortcut. If a claim could not be checked in the source, it is
  either omitted or written as an explicit gap.
- Each page ends with a link back here and a "what's next".

[Español](README.es.md) · **English**