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

## Public compatibility statement

Stable documentation should classify surfaces as:

- `STABLE`
- `PARTIAL` with explicit limitation
- `EXPERIMENTAL`
- `UNSUPPORTED_FAIL_CLOSED`
- `DEPRECATED`

Never advertise Jenkins equivalence merely because a similarly named DSL function exists.
