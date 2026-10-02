# WU-091 `core.lock` — Plan de implementación G3/G4

> status: ACTIVE — plan del ciclo SDDK `p-1f3622e11c093341/rp6a-lock`
> base: `main` @ `8311f2f8` (G1 `d02bcf0f`, G2 `d18d5e17`, L1 lane refactor `8311f2f8`)
> authority: SPEC_WU091_LOCK.md (§7 criterios de salida), RP6A_LOCK_CHARACTERIZATION.md,
> decisión del operador 2026-10-02 (secuencia G3.1..G3.6 + fila sealed/source-compat).

## 0. Estado de entrada

Aplicado y gateado en `main`:

| Gate | Contenido | Evidencia |
| --- | --- | --- |
| G1 | Contrato, ADTs, codecs, `TypedStepOutput`, mutaciones M4/M5 | `d02bcf0f` |
| G2 | `FileLockCoordinator` POSIX, owner durable por run, `CoreLockWireCodec`, M6..M8 | `d18d5e17` |
| L1 | Owner por `ExecutionLaneId` (runId + parallelLineage), espera cooperativa, M9/M10 | `8311f2f8` |

`core.lock` AÚN NO está registrado en producción y NO existe superficie DSL.

## 1. L1 gate (verificación, no código nuevo)

Quirúrgico sobre `8311f2f8`: tests `*Lock*` de pipeline-application +
`ExecutionLaneDerivationTest` + fitness de arquitectura. Si GREEN, L1 queda cerrado
tal cual está commitado; no se tocan fuentes hasta terminar este gate.

## 2. G3 — Superficie DSL con autoridad de wire única

```text
G3.1  StepSpec.Lock en la jerarquía sellada (aditivo)
G3.2  StageScope.lock(resource, timeoutSeconds, reason, skipIfLocked) { body }
G3.3  Lowering StepSpec.Lock → CoreLockInput (compilador)
G3.4  CoreLockWireCodec como ÚNICA autoridad del payload wire
G3.5  Fitness anti-inline-wire
G3.6  Clasificación sealed/source-compat
```

### G3.5 — guard, tamaño exacto

```text
HOY: prohibido sólo para core.lock

DslCompiledPipelineCompiler
    puede conocer:   StepSpec.Lock, CoreLockInput, CoreLockWireCodec
    NO puede conocer: "resource", "timeoutSeconds", "skipIfLocked", wire JSON de lock
```

### G3.6 — sealed hierarchy compatibility

```text
StepSpec.Lock añadido
        ↓
API/ABI check
        ↓
inventario de consumidores exhaustivos (when(step) sobre StepSpec)
        ↓
clasificar:
  BINARY_COMPATIBLE
  SOURCE_ADDITIVE_WITH_EXHAUSTIVE_WHEN_RISK
```

`StepSpec` es sealed pública: añadir un caso es binariamente compatible en general,
pero rompe source-compat de consumidores externos con `when` exhaustivo. No es motivo
para no añadirlo (`lock` es CORE); debe quedar registrado conscientemente y entrar en
la semántica de versión/release, no llamarse simplemente "cambio aditivo".

### Gate G3 (quirúrgico)

```text
:pipeline-scripting-api:test
:pipeline-application:test --tests *Lock*
compiler mapping tests
architecture fitness
binary compatibility / api dump relevante
```

Full suite NO, salvo mutación nueva en frontera compartida no prevista.

## 3. G4 — Routing de producción y recorrido completo

Registro en `CoreStepRegistryFactory` sin bypass de admisión (criterio §7.3), y demostración
del recorrido completo:

```text
pipeline.kts → StageScope.lock → StepSpec.Lock → CoreLockWireCodec
  → BlockStepNode → registry → HANDLER_CONTINUATION → LockCoordinator → BodyContinuation
```

Escenarios duros, de una vez:

1. re-entrada nested en la misma lane;
2. contención entre ramas hermanas de `parallel`;
3. dos runs;
4. `skipIfLocked`;
5. timeout;
6. cancelación;
7. body failure;
8. release en todos los terminales.

Más criterios de la spec: fila de re-adquisición en reanudación verificada por mutación
(§7.5), cancelación como vía propia (§7.6), ratchet del coordinador intacto en 552 (§7.8),
recibo con SHA exacto (§7.9).

## 4. Deuda explícita, FUERA de este train

```text
DEBT-WIRE-AUTHORITY
```

`StepSpec.Dir`, `StepSpec.WithEnv`, `StepSpec.TimeoutBlock`, `StepSpec.RetryBlock`, …
todavía serializan wire en `DslCompiledPipelineCompiler`. Convergerlos es otro evolutivo
horizontal posterior a RP6-A. G3 demuestra el patrón correcto para capacidades nuevas;
no rehabilita el pasado en el mismo train.
