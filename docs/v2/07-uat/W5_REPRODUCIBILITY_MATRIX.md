# W5 — Reproducibility matrix for the 0.48.0 distribution ZIP

- **Date:** 2026-10-09
- **HEAD at authorship:** `e01bd7a259245602eb9ba3889c87033cb2438f79`
- **Branch:** `s6-plugin-sdk`
- **Cycle:** `p-733fb505b5a6bd2d/train-1-rp2-characterization` (OPEN)
- **WorkItem:** `f8fc07e6-6f98-4b4a-81c0-3f5b717bd146`

**Tree under measurement:** `e01bd7a2`, clean.
**Subject:** `v2/pipeline-application/build/distributions/pipelinek-0.48.0.zip`.
**Status:** every row below is OBSERVED on that SHA. No row is projected, and no
row is inherited from an earlier candidate.

The reference digest for a clean build of this tree:

```text
a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93
```

## Why this matrix exists

The W4 candidate was superseded for shipping two product versions in one archive.
Rebuilding it produced a ZIP that satisfied every existing guard and was still not
reproducible: two clones of the same SHA produced different bytes. A candidate whose
digest depends on where the release engineer cloned is not a candidate, so the
property had to be measured before any admission, not asserted.

## R1 — Repeated build, same checkout

Two consecutive `distZip --rerun-tasks` on the clean tree produced the identical
digest `ea9078de…` on both runs. **PASS** — and measured at the time, on the tree
that still contained the `junit` defect described in R4.

## R2 — Dirty tree: a relevant input must move the digest

`DurableShConfig.kt` is an owned Step SDK source. Mutating an owned constant
(`HEARTBEAT_MINIMUM_DELTA_DEFAULT = 2L` to `3L`) and rebuilding moved the digest
`ea9078de… → 15e966e1f0e9f97e00ea3c5644a055d9160b80b69b42d5c6900f18af5943a53c`.
Restoring the file and rebuilding returned the digest to `ea9078de…` exactly, and
`git status` reported zero changes afterwards. **PASS** — the digest is a function
of the sources.

### Two probe failures recorded here, because they nearly became findings

The first dirty-tree probe resolved its target file to an empty string, modified
nothing, and printed `DEFECTO REAL: el arbol sucio produce el mismo ZIP`. It was a
broken probe, not a broken build. The second mutated a Kotlin comment, which cannot
change bytecode, and produced the same misleading line. Both were discarded and
re-run against a mutation that reaches compiled material.

This is HARNESS FIDELITY LAW §4 applied to the agent rather than to a test: a
harness must not be able to make its own subject look broken. Here the inverse also
held — the first probe would have made the build look healthy had the mutation been
inert in the other direction.

## R3 — Two independent checkouts

This is the row that found the defect.

| Checkout | Digest before the fix |
| --- | --- |
| primary | `ea9078ded548cf93bcf8f6c3c127d81d497aae7070e0af31e2beb25b64835585` |
| clone at `~/.jcode/scratch/checkout2` | `ba0b1d07f6b5826475e98fb9efc34e37a71dd811a80be879d21f4beb6edbd3d8` |

Comparing per-entry CRCs narrowed the difference to two jars, `http` and `junit`,
so this was content and not archive metadata. Reading the two `junit-release.properties`
directly gave the cause:

```text
checkout1  pipeline.junit.release.digest=sha256:563804512db1e747d1565919b9ff991a7e0424c4e171a2b6eadaa678107900bd
checkout2  pipeline.junit.release.digest=sha256:3ca0fce6ae20de23ce3f1fcd42b7f6c0908beba271aa015ab219c0e3b26ac1fc
```

`junit/build.gradle.kts` hashed **absolute paths** through `sha256sum`, and
`sha256sum` prints the filename it is given, so the checkout path entered the
provenance identity. Demonstrated independently:

```text
cd /home/rubentxu/.jcode/scratch  ->  8c4efd866bd0ad0b
cd /                               ->  192fa448d232ecc7
```

Same file, same bytes, different digest. **FAIL**, fixed in `e01bd7a2`.

`AUD-01` had already migrated `http`, `scm-git` and `utilities` off absolute paths
and extracted a shared `ProvenanceDigest`. `junit` was not migrated, and the law
enforcing the migration (`HttpProvenanceDigestSourceLawTest`) listed only those three
modules by explicit name. The omission was therefore invisible to the guard by
construction: not a bug in the guard, a bug in its subject list.

## R4 — After the fix: three checkouts agree

| Checkout | Digest |
| --- | --- |
| primary, clean rebuild | `a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93` |
| independent clone | `a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93` |
| clone under a path containing spaces | `a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93` |

`pipeline.junit.release.digest` is `3bfb00beea078bc894a1dc8ac2df3a15999029aebdd393f33e87267b7b75a927`
in all three. **PASS** on independent checkouts and on a path with spaces.

## R5 — Incremental build must not perturb the digest

`distZip` without `--rerun-tasks` after a clean build reported 79 UP-TO-DATE tasks
and left the digest at `a4620df4…`. **PASS** — an incremental run is not a silent
rewrite of a candidate.

## R6 — Excluded outputs are excluded, behaviourally not textually

The exclusion set is asserted by two guards as text. Text is not evidence of
behaviour, so it was checked by poisoning the real inputs:

| Probe | Effect on `pipeline.junit.release.digest` | Verdict |
| --- | --- | --- |
| stale `plugin-manifest.json` rewritten to carry `sha256:dedede…` | unchanged at `3bfb00be…` | exclusion holds |
| owned source `JUnitReportCodec.kt` mutated | moved to `3e55d5b3…` | inclusion holds |
| that source restored | returned to `3bfb00be…`, `git status` clean | round-trips |

The digest therefore responds to real content and ignores the two documents that
would otherwise form a fixed-point loop. This is the property `PluginProvenanceDigestSelfReferenceFitnessTest`
exists to protect, verified here by mutation rather than by reading the set.

## Residue found and deliberately not fixed

`http-0.48.0.jar` in the primary checkout shipped
`META-INF/http-release.properties.digest`, a file dated **2026-10-08**, two days
before `http` migrated off the shell pipeline. It is excluded from the digest but
never deleted, so `processResources` packaged it. `:pipeline-step-sdk:http:clean`
removes it, and `build/` is not versioned, so this is local residue from an older
implementation rather than a live defect. It is recorded rather than absorbed into
the fix commit, because absorbing it would have mixed a live defect with dead
state and made the change harder to review.

## Git author identity varies across this branch, and it is not a repository defect

Measured while recording the matrix, because candidate provenance depends on it.

```text
git var GIT_AUTHOR_IDENT        ->  Rubentxu <rubentxu74@gmail.com>
git config user.name            ->  Rubentxu          (from ~/.gitconfig)
git config user.email           ->  rubentxu74@gmail.com
GIT_AUTHOR_EMAIL / GIT_COMMITTER_EMAIL in the shell   ->  (unset)
```

Yet the last twelve commits on this branch carry five different identities, and the
branch as a whole carries seven:

```text
19  haizea.cabrera.g@gmail.com
17  nomeacuerdodelputocorreo@gmail.com
14  rubentxu74@gmail.com
13  socketstuido@gmail.com
13  pabloformacion13@gmail.com
10  rubentxudev@gmail.com
10  ilargia.c.g@gmail.com
```

What this rules out, and how:

| Hypothesis | Check | Result |
| --- | --- | --- |
| repository-local config | `git config --local --list` | no `user.*` entry |
| the SDDK git hooks rewrite it | `grep` over `core.hooksPath` hooks | no hook writes `GIT_AUTHOR_*` |
| the identity guard sets it | `identity-guard.sh` | `guard_pending_identity` only **reads** identity to reject corporate emails; `guard_deny` never substitutes one |
| a later amend or rebase rewrote it | author vs committer over 12 commits | 0 of 12 differ, so no commit was rewritten after creation |

Git itself would sign as `Rubentxu` right now. The other identities therefore enter
the commit process from outside this repository — the environment of whatever agent
or tooling ran `git commit`. That component is not identifiable from here and is out
of scope for this work.

**History is not rewritten to normalise this.** The commits are pushed; rewriting
published history to change an attribution field would be a larger and less honest
intervention than recording the fact. It is logged so that an auditor reading a
release receipt is not surprised by seven author emails on one branch, and so the
variance is not later mistaken for tampering.

## What this matrix does NOT establish

- **No candidate has been admitted from this tree.** Reproducible bytes are a
  precondition for admission, not admission.
- **`PRODUCT-GATE` remains `BLOCKED_EXTERNAL`.** There is no CI surface in this
  repository since `754ddda0`, so no row here substitutes for one.
- **The `junit` plugin fingerprint changed** (`3bfb00be…`) because the framing is no
  longer the shell pipeline's. No junit artifact was published, so no published
  fingerprint is invalidated, but no earlier candidate is reproducible from this SHA.
- **Third-party jars were excluded from every digest check.** They carry their own
  upstream versions and pinning them to `0.48.0` would be wrong in a different way.
