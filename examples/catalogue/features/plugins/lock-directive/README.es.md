# Lock Directive

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `plugins`

**Resultado esperado:** `success`

## Que ejercita

Los pasos que aporta un plugin, y las capacidades que declara

*El ejemplo dice de si mismo:*

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

## Las etapas y los pasos

### 1. `Acquire lock`

- `directives` — un bloque
  - `directive` — `"acme.lock", """{"resource":"prod"}"""`
- `sh` — `echo acquired`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `acquired` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/plugins/lock-directive/lock-directive.pipeline.kts
pipelinek run      examples/catalogue/features/plugins/lock-directive/lock-directive.pipeline.kts
```

## Su linaje

- **Nombre historico:** `lock-directive.pipeline.kts`
- **Ruta de origen:** `examples/plugins/lock-directive.pipeline.kts`
- **Huella del cuerpo:** `sha256:cd5e93361f821da5a2010c61af781565a8caa0654b5607f43e2b381d05b11cf2`
