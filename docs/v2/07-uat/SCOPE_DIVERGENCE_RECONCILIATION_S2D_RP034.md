# Scope divergence reconciliation — S2-D cycle carrying WU-RP-034 work

> **This reconciles provenance. It does not rewrite provenance.**
>
> No commit is reassigned. No closed receipt is edited. No SDDK ledger event is
> altered. Git already holds the history; this document only states what the
> history actually contains, which record is authoritative from this point, and
> which record was never what it appeared to be.

## Facts

```text
declared cycle scope : p-1f3622e11c093341/train-s2-directive-plugin
                       title: "TRAIN S2-D (train-s2-directive-plugin)"
                       recorded scope: S2-D / Directive Plugin
actual observed work : WU-RP-034 — Workspace & Execution Location Semantic Remediation
orphan WorkItem      : 3f55b43f-a080-4f46-9eea-01aa230913cf
                       cycle: p-1f3622e11c093341/train-s2-directive-plugin
                       title: "WP034-A: supersede premature local-first flip
                               and freeze execution-location reality"
                       status: Active
first affected commit: aeae1e4c35a83da78889a7fb3eeeb787058a5506
last affected commit : e01323d835222d946f20ae3fda8644af9de96588
affected range       : aeae1e4c^..e01323d8   (27 commits)
```

## Why the divergence is real, not cosmetic

The WorkItem is named `WP034-A` and lives in a cycle declared for S2-D. So the
*item* is an RP-034 item, but the *cycle* is not an RP-034 cycle. The work was
therefore governed by a cycle whose recorded scope does not contain it.

Two independent facts confirm this rather than a naming coincidence:

1. `build-report.md` of that cycle documents T2/T4/T5 as S2-D directive-plugin
   tasks, with T6 (installed UAT) explicitly listed as *"Fuera de esta fase
   (verify)"*. It contains no RP-034 task and no WP-034 exit criterion.
2. `sddk plan roadmap graph` lists the S2-D Detekt finding
   (`097b2463`, done) and `core.sh` default working directory (`b578a3aa`,
   done) as separate terminal items, with `3f55b43f` active. The roadmap plane
   never contained an RP-034 closure item.

## Why it was detected only now

Every per-commit closeout receipt bound to this WorkItem already carried the
correct statement — for example at `320b8312`:

> "The cycle remains OPEN in Build with a failed phase.build.complete gate…"

No receipt ever claimed RP-034 was the cycle's scope, and none claimed the
`phase.build.complete` gate had passed. The divergence was therefore **recorded
honestly at the time and simply never reconciled**: the work was logged against
a cycle that could not close it, and the mismatch only became actionable when
the question "what is the next roadmap cycle?" was asked, because SDDK had
nothing queued behind the active item.

## Commit → RP-034 slice mapping

Range `aeae1e4c..e01323d8`, all 27 commits:

| commit | slice | subject |
|---|---|---|
| `aeae1e4c` | A | revert premature local-first flip; freeze execution-location reality |
| `772b46af` | A | accept workspace execution model (ADR-0100..0102), close RP034-A |
| `320b8312` | B | typed workspace lease and execution-location domain model |
| `9726a01d` | C | project typed execution location as a Step capability |
| `9a0d90bd` | A | freeze core path semantics, record the cwd divergence |
| `481834eb` | D | scope file Steps to the same cwd the shell observes |
| `b193be9f` | G | typed ownership becomes the authority for root destruction |
| `9df69c31` | H | explicit isolated workspace mode and typed origin resolution |
| `3cd0d0d2` | H | attach the invocation directory as the default workspace |
| `5ff77482` | H | certify the local-first default on the installed distribution |
| `19577730` | E-a | `core.pwd` reports the cwd, workspace root stays distinct |
| `b797ca27` | E-b | capability value becomes the `ExecutionLocation` ADT; utilities anchor to cwd |
| `4d615fff` | E-b | scm checkout and junit results anchored to the location |
| `7cf249a5` | E-b | stash source and unstash target read from the shared location |
| `d3dcd365` | E | receipt: execution-location consumer migration |
| `1f451b98` | F | resolve the archiveArtifacts and publishHTML differentials |
| `e0dbeaf8` | F | receipt: archive and publishHTML differentials |
| `9ef19694` | I-a | remove the ambient-state escape hatch from scm and junit |
| `0a667272` | I-b | delete the VCS-marker ownership heuristic |
| `e3e2140b` | I-c | stop two CLI tests writing into the source tree |
| `9d2e999a` | I-d | carry ownership to the runtime; a user root is never destructible |
| `3f232a31` | I-d | keep control-plane output out of the captured stdout channel |
| `459f431c` | I-d | each corpus fixture states the workspace mode it requires |
| `c06331af` | I-d | declare the workspace ADTs; align SC-011-04 with ADR-0102 |
| `e08a28be` | I-d | receipt: RP034-I closure and the destructive-safety correction |
| `494654d0` | I-d | receipt: installed multi-toolchain and self-hosting UAT |
| `e01323d8` | I-d | declare version 0.45.0 derived from history since v0.44.1 |

Immediately before the range, and **not** part of it:

| commit | scope | note |
|---|---|---|
| `8e838e6d` | pre-RP034 | premature local-first flip; **reverted** by `aeae1e4c` |
| `d7bb2556` | pre-RP034 | verification record for that premature flip |
| `a5406ae7` | **S2-D** | `fix(directives): wrap S2-D denial expression` — genuine cycle work |

`a5406ae7` is real S2-D work and stays attributed to S2-D. It is the reason the
cycle has legitimate content of its own and must not be closed with RP-034.

## What remains untouched

- `build-report.md` of `train-s2-directive-plugin` is **not modified**. It is
  the evidence of the declaration that actually existed. It was never authority
  over RP-034, and this receipt does not pretend otherwise.
- The 27 closeout receipts in `.git/sddk-agent-gate/` are **not edited**. Each
  already recorded the true gate state.
- The SDDK ledger is **not rewritten** and no event is synthesised.
- Commit authors, order, and content are untouched.

## Authority from this point

1. For RP-034 scope and exit criteria: this receipt plus
   `RP034_ID_CERTIFICATION_AND_SECURITY_CORRECTION.md` and
   `RP034_I_INSTALLED_MULTITOOLCHAIN_UAT.md`, all bound to an exact SHA.
2. For the commit range and slice mapping: the table above, derivable from Git.
3. For S2-D: the cycle's own `build-report.md` and its terminal roadmap items.
   **S2-D stays open.** It is not closed by RP-034 commits.

## Outstanding SDDK defect

WorkItem `3f55b43f-a080-4f46-9eea-01aa230913cf` is not resolvable as a graph
node (`sddk graph why --entity` → `found: false`; the rebuilt graph holds 9
nodes and 62 edges), and it is not listed by any work-item enumeration that
resolves cleanly. It is handed to the commit gate as the WorkItem to acknowledge
against, yet no supported SDDK command can display, transition or close it.

This is a **deficiency of SDDK, not of PipelineK**, and it belongs to SDDK's own
backlog: a WorkItem that the governance gate mandates but the planning ledger
cannot resolve is a hole in the authority chain. It is recorded here as debt and
is deliberately **not** repaired from inside this repository — doing so would
mean editing SDDK's internal storage from a project that does not own it.

RP-034 must not be blocked indefinitely attempting to fix this engine.