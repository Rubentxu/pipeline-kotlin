# Interaction with the next Release Candidate

## 1. Do not block stable on the entire evolution

The full directive/reactivity architecture is valuable but too large to smuggle into a stabilization RC.

Use two gates.

## Gate R0 — Honest Stable Surface

Must happen before the next immutable RC:

- reconcile actual branch/remote identity;
- `whenCondition` fail closed;
- `scmGit` pure and `git` exactly one checkout;
- no silent retroactive retry;
- self-host/release pipeline does not rely on unsupported semantics;
- `post`, `agent`, `options` claims corrected to actual behavior;
- DSL Surface Manifest covers every advertised stable surface;
- semantic witness tests for `local-core` claims;
- full suite / installed distribution / fresh clone.

After R0, the final RC may be produced and certified.

## Gate R1+ — Semantic Evolution

Real typed `when`, real `post`, extensible directives, generic scripted runtime, event registry/reactors and plugin SDK v2 can land after the stable baseline under the roadmap in `08-roadmap.md`.

This preserves product momentum while preventing another false-green stable.

## Release law

Any byte-changing fix after RC freeze creates RC+1.

Never move a published tag.

## Hardening does not authorize removing a published surface

Added 2026-10-03, from a real failure. `RP7-SEM-S3-R1` closed an unguarded door in
`OptionsScope` by making `var timeout` private. The door was real: the certified
`UatLocal004TimeoutTest` fixtures write `options { timeout = 2L }`. The full gate on
`297e0ec4` failed with four tests, because the hardening had turned into a source break
in a published DSL surface — a change that reads as tidying and costs a downstream
pipeline its build.

The rule:

- A member that a `.pipeline.kts` can name is a **published surface**, whatever its
  KDoc says and however unguarded it looks.
- Closing a hole in a surface you must keep is done by **validating** the surface, not
  by deleting it. The setter validates and the function delegates to it.
- A change is a **breaking change** when it removes or renames a published member, even
  if it is semantically neutral and lands in a corrective described as hardening.
- Targeted tests do not catch this. The 34 that were green before the gate used the
  member the way a unit test does, not the way a pipeline script does. Only the full
  gate, and only installed-distribution witnesses, exercise the published surface.

Corollary for release cuts: a corrective that is breaking needs a deprecation path or a
major-version boundary, not a corrective commit.

## Public compatibility statement

Stable documentation should classify surfaces as:

- `STABLE`
- `PARTIAL` with explicit limitation
- `EXPERIMENTAL`
- `UNSUPPORTED_FAIL_CLOSED`
- `DEPRECATED`

Never advertise Jenkins equivalence merely because a similarly named DSL function exists.

## Version-cut policy

Decided 2026-10-03. Two parts, deliberately separated, because conflating them is how a
disposition becomes an unexamined commitment.

### 1. The law (binding)

**Cut a release when a consumible product capability exists, not when a train ends.**

An enabling capability is not a product. None of these justifies a cut on its own:

- an envelope or registry type with no producer;
- a manifest or capability type no plugin can yet contribute through;
- a credential kernel with no provider and no projection;
- a spike, adopted or not;
- a single remote worker;
- a slice boundary inside a train.

While a train is open, intermediate slices stay commits and certified receipts. They are
not releases. Archiving internal architecture as release noise trains everyone to ignore
version numbers.

**A release states one claim a user could verify.** If the honest sentence is "this is
useful infrastructure", it is not a release yet.

### 2. The mapping (provisional, revisable)

Proposed cuts from the current stable `v0.46.0`. The trains are real and named in
`08-roadmap.md` (S4.1–S4.5, S5, S6, S7, S8), `docs/v2/05-roadmap/ROADMAP.md`
(ASX-0..ASX-7) and the roadmap (RP8, RP9). **The version numbers are disposition, not
contract** — a number moves when the evidence forces it, and moving one is not a
regression.

| Cut | After | The claim it makes |
|---|---|---|
| `v0.47.0` | S4 | Durable Kotlin scripted runtime with typed runtime values |
| `v0.48.0` | S5 + S6 | Extensible platform: a plugin contributes Step, Directive, Event, observer/reactor, DSL and capabilities with no core change |
| `v0.49.0` | S7 + S8 | Certified semantic platform: HF0–HF5, automated semantic witnesses, external plugin certification, restart/resume, compatibility corpus, frozen schemas |
| `v0.50.0` | ASX-0..ASX-6 | Agent-first and secretless: an agent uses a profile and never receives the credential |
| `v0.51.0` | ASX-7 + RP7 hardening | Hardened local execution: resource limits, sandbox, egress policy, disposition, provenance |
| `v0.60.0` | RP8 | Distributed controller and workers |
| `v0.70.0` | RP9 | Jenkins and Kubernetes/OpenShift over the control plane |

Three constraints on this table, each for a stated reason:

- **0.47 is not contingent on S4.5.** S4.5 removes the old lowering path *only if the
  replacement wins*. If it does not win, 0.47 ships with the old path retained. The
  release must not silently inherit that condition.
- **0.49 depends on a surface this repository does not currently have.** External plugin
  certification, the compatibility corpus and the exact installed artifact all touch
  `PRODUCT-GATE`, which is `BLOCKED_EXTERNAL` while no CI exists (see `AGENTS.md`, the
  2026-10-03 verification policy). Deciding that dependency now is cheaper than
  discovering it at the cut.
- **The 0.51 → 0.60 → 0.70 jumps are a deliberate signal, not an arithmetic accident.**
  They mark a change in the KIND of release, and they are written down here precisely so
  a later maintainer does not "correct" them back to 0.52 and 0.53.

### 3. Candidate cadence inside a release

```
train functionally closed
    -> API freeze
    -> vX.Y.0-rc1        (not on the last day)
    -> certification, dogfood, external harness
         byte-changing fix -> rc2, rc3 …
         no change         -> vX.Y.0
```

`vX.Y.0` is promoted only when the **exact** RC artifact has passed the required gates:
same ZIP, same SHA, same installed witnesses, same compatibility corpus. Per the release
law above, no published tag is ever moved.
