# Find Files

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

*The example says this about itself:*

> The subject is the SIDE-EFFECT contract of 'core-utils.findFiles'. The step is
> declared 'Effect.READ_ONLY' and returns a typed value the DSL does not
> expose, so the guarantee the harness can observe is "what files survived the
> step", not "what files did the step enumerate". A finder that enumerated
> nothing passes the latter; a finder that touched anything -- created a
> sidecar, deleted a file, rewrote a byte -- fails this one, because the
> snapshot taken afterwards has a hash the snapshot taken before did not.

## The stages and the steps

### 1. `find-files`

- `sh` — `""" mkdir -p build/utils/scan/sub echo 'one' > build/utils/scan/a.txt echo 'two' > buil...`
- `sh` — `""" (cd build/utils/scan && find . -type f -print0 \ | LC_ALL=C sort -z \ | xargs -0 sh...`
- `findFiles` — `base = "build/utils/scan", glob = "*.txt"` — a step this plugin provides (`core-utils`)
- `findFiles` — `base = "build/utils/scan", glob = "**/*.txt"` — a step this plugin provides (`core-utils`)
- `findFiles` — `base = "build/utils/scan", glob = "**/*.nope"` — a step this plugin provides (`core-utils`)
- `sh` — `""" (cd build/utils/scan && find . -type f -print0 \ | LC_ALL=C sort -z \ | xargs -0 sh...`
- `sh` — `diff -u snapshot.before snapshot.after`
- `sh` — `"test \"$(find build/utils/scan -type f | wc -l)\" -eq 4"`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/find-files/find-files.pipeline.kts
pipelinek run      examples/catalogue/features/steps/find-files/find-files.pipeline.kts
```

## Its lineage

- **Historical name:** `26-find-files.pipeline.kts`
- **Origin path:** `examples/steps/26-find-files.pipeline.kts`
- **Body digest:** `sha256:76731a2bf22887892ba38441f120ae38815f8ed391c4633629d9e2bfbd873333`
