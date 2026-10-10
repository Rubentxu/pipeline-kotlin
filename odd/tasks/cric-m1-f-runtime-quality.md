# M1-F: Runtime, Compiler & Diagnostic Quality

## Goal

Eliminate `sun.misc.Unsafe` from the Kotlin 2.4 scripting host, fix durable-shell
timeout diagnostics, fix stdout/stderr channel sealing, and clean console diagnostics
UX. Four sub-tasks, four work-unit commits on `fix/cric-m1-f-runtime-quality`.

## Why

M1-A/B/C/1.A/1.B/1.C are closed green. This block removes the residual runtime/quality
debt before v0.49.0-rc1 is folded into the release branch.

## Scope

| Sub-task | Target | Output |
|----------|--------|--------|
| M1-F.1 | Eliminate Unsafe from `:pipeline-scripting-kotlin24` | Work-unit commit + push |
| M1-F.2 | Fix durable-shell timeout diagnostics | Work-unit commit + push |
| M1-F.3 | Fix stdout/stderr channel sealing | Work-unit commit + push |
| M1-F.4 | Console diagnostics UX | Work-unit commit + push |

## Constraints

- Conventional Commits, no AI attribution.
- Closed refusal hierarchies; total functions; sealed ADT over exception-as-control-flow.
- Real on-disk stores.
- Tests deterministic; per-case timeouts where the test exercises I/O.
- `git push --no-verify`.
- 4 commits total (one per sub-task). Push each before starting the next.

## Acceptance criteria

- All four sub-tasks produce green work-unit commits and push successfully.
- Final full test gate passes for the modules listed in the prompt.

## Tasks

- [ ] M1-F.1: Eliminate `sun.misc.Unsafe`
- [ ] M1-F.2: Fix durable-shell timeout diagnostics
- [ ] M1-F.3: Fix stdout/stderr channel sealing
- [ ] M1-F.4: Console diagnostics UX
- [ ] Final full test gate: all green