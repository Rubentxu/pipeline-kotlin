# Plugin Directive Lies

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a plugin that declares a Directive nobody contributes.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `declared Directives with no contributor`, `example.uppercase.ghostDirective` |

## What it needs

- distribution

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-directive-lies/plugin-directive-lies.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-directive-lies/plugin-directive-lies.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-directive-lies.pipeline.kts`
- **Origin path:** `examples/negative/plugin-directive-lies.pipeline.kts`
- **Body digest:** `sha256:1456ff014f64d7ff9184d2d8de3b2e63ad0d574809e99cf65852bc1ad2f8e8cc`
