# Lock Directive

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `plugins`

**Expected outcome:** `success`

## What it exercises

The steps that come from a plugin, and the capabilities they declare

*The example says this about itself:*

> An EXTERNAL directive plugin, discovered through ServiceLoader and admitted
> before the stage runs.
> The example-directive-plugin JAR ships inside the distribution, so this
> scenario proves three things that no unit test can: that a plugin
> contributed outside the product's own module list is discovered from the
> runtime classpath, that its BEFORE_STAGE directive is admitted rather than
> silently skipped, and that 'Evaluate' policy lets the stage proceed.
> The directive itself emits nothing ('DirectiveDefinition<LockInput, Unit>'),
> so the assertion that matters is the stage running at all. An engine that
> failed to discover the contributor would refuse the unresolved key, which is
> what negative/unknown-directive-key.pipeline.kts pins down from the other side.

## The stages and the steps

### 1. `Acquire lock`

- `directives` — a block
  - `directive` — `"acme.lock", """{"resource":"prod"}"""`
- `sh` — `echo acquired`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `acquired` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/plugins/lock-directive/lock-directive.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/lock-directive/lock-directive.pipeline.kts
```

## Its lineage

- **Historical name:** `lock-directive.pipeline.kts`
- **Origin path:** `examples/plugins/lock-directive.pipeline.kts`
- **Body digest:** `sha256:cd5e93361f821da5a2010c61af781565a8caa0654b5607f43e2b381d05b11cf2`
