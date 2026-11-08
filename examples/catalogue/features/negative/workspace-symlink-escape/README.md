# Workspace Symlink Escape

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> A symlink inside the workspace is not a boundary.
> AGENTS.md section 1 makes "no workspace operation may escape the authorized
> root" non-negotiable, so this scenario pins the refusal on the side that
> destroys: a WRITE through a link that leaves the workspace.
> 'normalize()' collapses '.' and '..' but never dereferences a symlink, so
> '<workspace>/escape/pwned.txt' starts with the workspace while 'escape'
> points at the workspace's PARENT. Before that passed the guard and
> 'Files.createDirectories' + the atomic rename both landed outside.
> The runner asserts two things and cannot express a third: the run exits
> nonzero, and the refusal names the escape. That second directive is what
> separates this from a scenario that merely crashes -- a pipeline that died
> of anything else, anywhere, would fail here too.
> The workspace's parent is '$PWD/..' because 'sh' runs with the workspace as
> its cwd. Nothing outside this scenario's private directory is touched, and
> nothing is created there: the guard throws before any effect.

## The stages and the steps

### 1. `workspace-symlink-escape`

- `sh` — `ln -s .. escape`
- `sh` — `test -d escape && echo 'link resolves outside the workspace'`
- `writeFile` — `"escape/pwned.txt", "PWNED"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `escapes workspace` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/workspace-symlink-escape/workspace-symlink-escape.pipeline.kts
pipelinek run      examples/catalogue/features/negative/workspace-symlink-escape/workspace-symlink-escape.pipeline.kts
```

## Its lineage

- **Historical name:** `29-workspace-symlink-escape.pipeline.kts`
- **Origin path:** `examples/negative/29-workspace-symlink-escape.pipeline.kts`
- **Body digest:** `sha256:1b12958a08fae07436516ece8b4e06b7fa574fb6dbef5f36a957409c1d75e81e`
- **Origin digest:** `sha256:60f15153aaa30aad181479b2de838f4ac32e2b07ff98f2e5e01ca9582fdb9d23`
