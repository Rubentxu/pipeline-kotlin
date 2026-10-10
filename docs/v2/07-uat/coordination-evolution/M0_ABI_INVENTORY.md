# WIP-1 / WIP-2 — Inventario de ABI publicado + Capabilities de PK (M0)

**Fecha:** 2026-10-10
**Hito:** M0 (evolución coordinada PK × Fabric)
**Tag base:** `v0.48.0-rc2` peel `74c5331e2c242658f6b4c9e83f896ce5f8a4fe98`
**HEAD actual:** `65f97430accfbeec22b7b05157d57d05563c87d5` (sobre `origin/main`)

> Sources: `v2/pipeline-*/api/*.api` (BCV dumps), `v2/contract/published-contract-exceptions.json`
> (P3-E ledger), `v2/pipeline-release/build.gradle.kts` (maven coordinates).

## 1. Contratos publicados y madurez

PK publica **6 artefactos** (los 4 del release model v2 + 2 utilidades). El
`v2/contract/published-contract-exceptions.json` los clasifica y registra
**9 entradas** de cambios breaking deliberados (P3-E ledger).

| Módulo | Coordinate | Public entities | Madurez | Breaking changes registrados |
|---|---|---:|---|---:|
| `pipeline-domain` | `dev.rubentxu.pipeline.v2:pipeline-domain` | 811 | EXPERIMENTAL | 5+ (ledger) |
| `pipeline-events` | `dev.rubentxu.pipeline.v2:pipeline-events` | 159 | EXPERIMENTAL | 1+ |
| `pipeline-scripting-api` | `dev.rubentxu.pipeline.v2:pipeline-scripting-api` | 113 | EXPERIMENTAL | — |
| `pipeline-credentials-api` | `dev.rubentxu.pipeline.v2:pipeline-credentials-api` | 43 | EXPERIMENTAL | — |
| `pipeline-output` | `dev.rubentxu.pipeline.v2:pipeline-output` | 44 | EXPERIMENTAL | — |
| `pipeline-step-sdk:api` | `dev.rubentxu.pipeline.v2:pipeline-step-sdk-api` | 10 | EXPERIMENTAL | — |

Todos los contratos están clasificados **EXPERIMENTAL** según P3 (la taxonomía
cerrada de madurez). El upgrade a STABLE se rige por `B4.4` y requiere consumer
externo publicado + matriz N/N-1 + suite de testigos, **no** se declara aquí.

`pipeline-sdk-bom` se publica **fuera** de `publishedContractModules` (sin ABI que
guardar; no entra en la matriz de compat). El test de ABI
`P3EPublishedContractMaturityFitnessTest` falla el build si una entrada del ledger
nombra un módulo que no está publicado o si la entrada es incompleta.

## 2. Capabilities del Output Plane (puerto público leído por Fabric)

`pipeline-output/api/pipeline-output.api` define el contrato que un consumidor
externo (incluido el adapter de Fabric) usa para leer el Output Plane. Las
interfaces públicas relevantes son:

| Interface | Propósito | Métodos públicos |
|---|---|---|
| `OutputReadPort` | Lectura durable por stream y rango | `committedExtent(streamId)`, `read(streamId, cursor, maxBytes)`, `readRange(streamId, fromOffset, toOffset)` |
| `OutputTailPort` | Estado del tail en vivo | `tailState(streamId)` |
| `OutputTailState` | Estado del tail: `Open` / `Sealed` / `Unavailable` / `RetentionGap` / `Corrupt` | (sealed type) |
| `OutputFrameIndex` | Navegación por ordinal sin escanear todo el run | (varios) |
| `OutputPruneIntent` | Intención de poda declarativa | (sealed type) |
| `OutputPruneReport` | Resultado de la poda | (data class) |
| `OutputRetentionPort` | Política de retención por stream | (varios) |
| `OutputCursor` | Cursor durable: `OutputStreamId + committedOffset` | (data class) |
| `OutputPage` | Página de bytes leídos + next cursor | (data class) |
| `OutputReadResult` | Resultado de lectura: `Page` o `Refused` | (sealed type) |
| `OutputRefusal` | Causas de rechazo tipadas (`DanglingCommit`, `ForeignStream`, `InvalidRange`, etc.) | (sealed type) |
| `RunLifecycle` | Lifecycle observable de un run | (sealed type) |
| `RetainUntil` | Política pin/release | (sealed type) |
| `OutputChannel` | Canal (`STDOUT` / `STDERR`) | (enum) |
| `OutputStreamAddress` | Identidad de stream: `runId / operationId / channel` | (data class) |
| `OperationOutputStreams` | Par `stdout` / `stderr` de una operación | (data class) |

Esta es la **superficie consumible** que el contrato CRIC-1 menciona en §2 ("Plano
de salida: bytes redactados antes de persistir, secuencia de offsets por stream,
orden observado de frames sin fingir reloj global con eventos; `Open`, `Sealed`,
`Unavailable`, `RetentionGap`, `Corrupt` se distinguen; un run terminal no implica
consola completa"). Cada capability listada tiene su implementación concreta en
`SegmentOutputStore` (no público) y la superficie pública arriba.

## 3. Capabilities del Event Plane (puerto público leído por Fabric)

`pipeline-events/api/pipeline-events.api` define:

| Interface / class | Propósito | Métodos públicos |
|---|---|---|
| `EventStore` | Append + read con secuencia estable | `append`, `appendAssigned`, `eventsFor`, `readRecords`, `readSlice` |
| `EventRecordRead` | Resultado de `readRecords` | (data class) |
| `EventSlice` | Resultado de `readSlice` | (data class) |
| `EventField`, `EventHistory`, `EventPublisher`, `EventTail` | Identidad y operación sobre el plano de eventos | (varios) |
| `EventDefinitionContributor` | SPI para añadir definiciones | (interface) |
| `EventDefinitionCreation`, `PluginEventEmission` | Creación de definiciones | (data class) |
| `EmissionOutcome`, `RegistrationOutcome` | Veredictos tipados | (sealed type) |
| `EventPayloadCodec`, `PayloadDecode` | Codec de payloads | (interface) |
| `UndecodableReason` | Razón de no decodificación tipada | (sealed type) |
| `DomainEvent` | Super-tipo del evento | (interface) |
| `EventSink` | Append-only sink (emitter) | (interface) |

Esto materializa CRIC-1 §3 ("Plano de eventos: lectura incremental con secuencia
estable, idempotencia y rechazo de huecos; los DomainEvents no son un sistema de
transporte de stdout, ni representan el `OperationJournal` crudo").

## 4. Step SDK + Dominio + Credenciales

`pipeline-step-sdk/api/api/api.api` (10 entidades públicas) expone
`StepContext`, `BlockStepFlattener`, `FlattenedStep`, `IndexedStep`,
`BlockNestingDepthExceededException` y el cuerpo de `core.dir`/`core.sh` por step.
Es la **interfaz de pasos para plugins externos**, no el runtime de Fabric.

`pipeline-domain/api/pipeline-domain.api` (811 entidades) es el **modelo de
dominio**: `OperationJournal`, `StepOutcome`, `FailureKind`, `PipelineFailure`,
`ShellReturnMode`, `ShellInvocationResult`, `Clock`, `InterpreterPolicy`, etc.
Es el vocabulario compartido que un step usa para componerse; Fabric NO lo
reinterpreta (CRIC-1 §9: "Fabric NO reinterpreta terminalidad por logs ni escribe
directamente el journal privado de PipelineK").

`pipeline-credentials-api/api/pipeline-credentials-api.api` (43 entidades) define
`SecretHandle`, `SecretPatternRegistry`, `TranscriptRedactor`,
`StreamingRedactor`, `SecretResolution`. Es el lado **redactor** que
`ShExecution` aplica antes de persistir (CRIC-1 §5 + §11: "secretos redactados
antes de cualquier almacén público").

`pipeline-scripting-api/api/pipeline-scripting-api.api` (113 entidades) expone el
DSL (`Stage`, `Step`, `StageScope`, `Pipeline.kts`). Fabric **no** consume este
contrato: la compilación DSL es 100% del lado PK.

## 5. Limitaciones de la superficie actual para M0

- **`OutputFrameIndex` ya está público** pero la navegación por ordinal
  sin escanear todo el run es de M3 (PK-L4) — la signatura existe, la
  optimización no.
- **`OutputTailPort.tailState()`** está en el contrato pero el `Wakeup`
  coalescible prometido por CRIC-1 §10 / SPEC-01 PK-L3 es de M1, no de M0.
- **M0 no exige que se cambie nada**; el trabajo de M0 es medir y declarar
  qué está disponible hoy.

## 6. Cómo reproduce Fabric este inventario

```bash
# 1. Tomar el SDK desde el artifact publicado, no del worktree.
curl -L -o pipelinek-0.48.0-rc2.zip \
  https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.48.0-rc2/pipelinek-0.48.0-rc2.zip
sha256sum pipelinek-0.48.0-rc2.zip
# 0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7

unzip -d pipelinek-0.48.0-rc2 pipelinek-0.48.0-rc2.zip

# 2. Listar las clases/interfaces públicas de cada contrato.
for j in pipelinek-0.48.0-rc2/lib/{pipeline-output,pipeline-events,pipeline-domain,pipeline-step-sdk-api,pipeline-credentials-api,pipeline-scripting-api}-*.jar; do
  unzip -p "$j" | javap -p /dev/stdin 2>/dev/null | grep -E 'public (abstract )?(class|interface)' | sort -u
done

# 3. Comparar con el BCV dump versionado (no la fuente del worktree).
diff <(cat v2/pipeline-output/api/pipeline-output.api) <(javap -p pipeline-output.jar)
```

## 7. Estado para M0

- ✅ M0 no exige cambios: el ABI publicado actual de PK cumple con las
  capabilities listadas arriba, con la limitación de que `OutputFrameIndex` y
  `OutputTailPort` están firmados pero su semántica completa es de M1/M3.
- ✅ La madurez `EXPERIMENTAL` de los 6 contratos es lo que el contrato CRIC-1
  espera para M0: el release de Fabric va contra la PK publicada, no contra
  STABLE, y los breaking changes están declarados en
  `published-contract-exceptions.json`.
- ❌ No hay aún una **lista de capabilities explícita** que CRIC-1 §6 menciona
  ("Versionar las capacidades; cambios incompatibles no pueden pasar por
  cambios nominales de versión sin test de consumers"). Esa lista es trabajo
  de M1 (`PK_CONTRACT_HANDOFF.md` materializa la primera versión).

## Próximo paso

WIP-3: redactar el `PK_CONTRACT_HANDOFF.md` (plantilla en
`coordination/PK_CONTRACT_HANDOFF.template.md`) con la información de este
inventario, el SHA del artefacto publicado y los enlaces a las suites verdes.
