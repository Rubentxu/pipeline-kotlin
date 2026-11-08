# Glob Ant Alternation

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `steps`

**Expected outcome:** `success`

## What it exercises

The steps of the DSL, one by one, and what each of them guarantees

## The stages and the steps

### 1. `build`

- `writeFile` — `file = "src/keep.txt", text = "keep"`
- `writeFile` — `file = "src/drop.pem", text = "drop"`
- `stash` — `name = "glob-alternation", includes = "src/**/*.{txt,md}"`

### 2. `use`

- `sh` — `rm -rf src`
- `unstash` — `name = "glob-alternation"`
- `sh` — `test -f src/keep.txt`
- `sh` — `test ! -f src/drop.pem`
- `sh` — `ls src`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `keep.txt` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/steps/glob-ant-alternation/glob-ant-alternation.pipeline.kts
pipelinek run      examples/catalogue/features/steps/glob-ant-alternation/glob-ant-alternation.pipeline.kts
```

## Its lineage

- **Historical name:** `33-glob-ant-alternation.pipeline.kts`
- **Origin path:** `examples/steps/33-glob-ant-alternation.pipeline.kts`
- **Body digest:** `sha256:c005316abe09d9cad99a0c19cf1cfd561a139af5ed1ee4482ce73ff4928e44a6`
