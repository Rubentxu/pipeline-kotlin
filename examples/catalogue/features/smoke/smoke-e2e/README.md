# Smoke E2e

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `smoke`

**Expected outcome:** `success`

## What it exercises

The shortest path through the engine: a pipeline that starts, runs and finishes

*The example says this about itself:*

> Compatibility fixture 10: smoke end-to-end (checkout → build+archive).
> Note: under the Single Runtime Spine each stage gets its own
> workspace, so archiveArtifacts lives in the SAME stage as the build step
> (Jenkins-faithful workspace continuity).
> The fixture repository used to be created at /tmp/smoke-repo and cloned from
> that absolute path. Two rules were broken at once: AGENTS.md section 3 gives
> every example a private temporary directory and forbids shared marker paths,
> so two concurrent runs fought over one directory; and the 'rm -rf' aimed at a
> shared location would have deleted whatever else was sitting there. Both are
> gone with a relative path -- 'repo' resolves inside this stage's own
> workspace, so the clone exercises the same git operation without leaving the
> authorised root.

## The stages and the steps

### 1. `checkout`

- `sh` — `"rm -rf repo && mkdir -p repo && cd repo && git init -q . && git config user.email \"$(...`
- `sh` — `git clone repo checkout`
- `sh` — `"test -s checkout/README.md && test \"$(cat checkout/README.md)\" = smoke"`

### 2. `build`

- `sh` — `mkdir -p build/libs && echo jar > build/libs/smoke.jar`
- `archiveArtifacts` — `artifacts = "build/libs/*.jar", allowEmptyArchive = false`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/smoke/smoke-e2e/smoke-e2e.pipeline.kts
pipelinek run      examples/catalogue/features/smoke/smoke-e2e/smoke-e2e.pipeline.kts
```

## Its lineage

- **Historical name:** `10-smoke-e2e.pipeline.kts`
- **Origin path:** `examples/smoke/10-smoke-e2e.pipeline.kts`
- **Body digest:** `sha256:65b3293cf78aae1f34caf192ce0cf0d8be8270f814fb4aa39bbf4404c5262c1d`
- **Origin digest:** `sha256:65b3293cf78aae1f34caf192ce0cf0d8be8270f814fb4aa39bbf4404c5262c1d`
