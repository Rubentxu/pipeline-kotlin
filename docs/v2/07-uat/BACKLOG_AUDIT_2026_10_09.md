# Auditoría del backlog contra `main` — 2026-10-09

- **Base**: `main` @ `57774c9250a0da146a4d9eeb71648e85f48c2553`
- **Alcance**: los ítems vivos del ledger (`registered` + `triaged`) del proyecto
  `p-733fb505b5a6bd2d`
- **Independiente del gate**: sí. No requiere `evaluate-gate → transition` ni issue #12.

## Estado real del backlog (medido, no heredado)

`backlog_items_v1` contiene **68** ítems:

| Estado | Nº |
|---|---|
| `discarded` (terminal) | 46 |
| `triaged` (vivo) | 12 |
| `promoted` (terminal) | 5 |
| `registered` (vivo) | 5 |

**Ítems vivos: 17.** Los 42 "sin priorizar" que mencionaba el ítem de auditoría
(`bl-bl-01M4GNDJK30003891ATJG34KM0`) son casi todos `discarded` — ya terminales. Ese ítem
**describe un estado que ya cambió** y su premisa ("31 de 40 sin priorizar") no describe el
ledger actual.

## Verificación individual de los 17 vivos

### Ejecutables sin gate — trabajo real en PipelineK

**`bl-bl-01M4GNTCY00003891BPQN5KGW0` (registered) — C8: default destructivo en API pública.**
Re-verificado en `57774c9`, no en el `6eb1d0ac` que cita el ítem. **Sigue vigente:**

```text
v2/pipeline-scripting-api/.../dsl/StageScope.kt:70   fun deleteDir(path: String = ".")
v2/pipeline-application/.../CoreDeleteDirStep.kt:36 val path: String = "."
v2/pipeline-scripting-api/.../StepSpec.kt:277        data class DeleteDir(val path: String = ".")
```

El guard de dominio (`authorizeRootDestruction`) impide el borrado en runtime, pero un
`deleteDir()` a secas en el `.pipeline.kts` del usuario **sigue siendo escrito como válido**.
El tipo no hace la combinación ilegal irrepresentable (AGENTS.md regla 5/8). El gap es real y
está en la superficie pública, que es la que el usuario ve.

**`bl-bl-01M4GNJQC20003891B5PTRK940` (registered) — consola viva no entregada a `main`.**
Re-verificado: `ConsolePrintingEventSink` **no existe** en `main` (grep vacío sobre `v2`), y **sí**
existe en OBS:

```text
par/cli-observation:v2/pipeline-application/.../observation/ConsolePrintingEventSink.kt
par/cli-observation:v2/pipeline-application/.../observation/LiveOutputPresentation.kt
par/cli-observation:v2/pipeline-application/.../Main.kt   (único consumidor)
```

**Es la vertical OBS más pequeña y autocontenida identificada**: un fichero propio, un
presentador, y un único punto de integración en `Main.kt`. Frente a las 78 ficheros del árbol
OBS completo, esta es la candidata natural para el primer corte — *cuando #12 lo permita*.

**`bl-bl-01M4GNJKJG0003891B42D0KY80` (registered) — contrato CLI `--view`/`--format` (ADR-0088).**
Misma familia que el anterior; verificar antes de integrar para no duplicar alcance.

### Bloqueados por decisión o externo (no ejecutables aquí)

| Ítem | Motivo |
|---|---|
| `bl-bl-01M4GKJSWZ000389177ZH5M3G0` (P0) | ADR-0105 D3 prohíbe reintroducir CI; la capacidad vive en el harness |
| `bl-bl-01M4GKJXDX0003891754K2WQG0` (P1) | B0-F4: 74 commits sin pushear en el harness; dueño es el harness |
| `bl-bl-01M4GNA0XJ0003891AM64C9S40` (P1) | Recovery texts erróneos **del CLI de SDDK** — pertenece al kernel, prohibido tocarlo |
| `bl-bl-01M4GNGVAP0003891B336SJYM0` | RP-5: ratificación de 5 ADRs PROPOSED; decisión de producto |
| `bl-bl-01M4GNH5JW0003891B2HFR0100` | `sddk-mode-selftest` roto (0/14); herramienta externa |
| `bl-bl-01M4BA8P3A000388PMKT3ECQ40` (P1) | Fuga de `java.io.tmpdir` en `ShOptions`; deuda real pero ortogonal a OBS |

## Conclusión

1. El backlog **no está agotado**: hay **17 ítems vivos**, y al menos **dos** describen trabajo
   ejecutable hoy en `main` sin depender del gate.
2. La premisa del ítem de auditoría (`31 de 40 sin priorizar`) **está desactualizada** respecto al
   ledger actual; el trabajo real es la auditoría de los 17 vivos, no de los 42 terminales.
3. El candidato más sólido para el **primer corte OBS** es
   `bl-bl-01M4GNJQC20003891B5PTRK940` (consola viva): 2 ficheros + 1 punto de integración.
   **No arranca hasta que `software-development-decision-kernel#12` esté corregido.**
4. `bl-bl-01M4GNTCY00003891BPQN5KGW0` (default destructivo) es deuda propia de `main`,
   independiente de OBS y del gate, y verificable por compilación.
