# PipelineK v0.56.0-rc1 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — primera candidata con el **contrato SDK
consumible y la identidad de ejecución** publicados. El binario
contiene las 11 capabilities vigentes y las dos superficies
machine-verifiable nuevas (`pipeline capabilities` y `pipeline sdk-info`).
Pendiente: `CERTIFIED` por `pipelinek-release-harness`; `PAIR_CERTIFIED`
con Fabric contra este artefacto.

> **Origen y procedencia.** Esta candidata abre el **train 0.56.0**
> (MINOR). El train `0.55.0` (M6.4 corrective) cerró el chain CRIC-1
> con la surface M6+M7 en `pipeline-runtime-api`; el train `0.56.0`
> añade los **contratos de identidad y surface machine-verifiable**
> del roadmap PK-1 sobre el chain cerrado. **Sin cambios de
> capabilities** (las 11 vigentes se mantienen); el bump es MINOR
> porque se añaden nuevas superficies (subcomandos CLI, nuevos tipos
> en `:pipeline-events` y `:pipeline-application`).

## Identidad y procedencia

- **Producto:** PipelineK (`Rubentxu/pipeline-kotlin`).
- **Versión declarada:** `0.56.0` (de `v2/build.gradle.kts` línea 94).
- **Rama de release:** `release/pk1-contract-v0.56.0-rc1`.
- **Tag candidato:** `v0.56.0-rc1` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):**
  `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc1`.
- **Train:** 0.56.0 (MINOR, primer train con contrato SDK completo).
- **TRAIN P3 identity law:** la identidad de la candidata es
  `CandidateId = SHA256(ZIP)`, no la versión.

## Capacidades vigentes (11) — sin cambios

| Capability ID | Publicada en | Estado |
|---|---|---|
| `output.follow.v1` | `v0.49.0-rc1` | **PUBLICADA** |
| `events.follow.v1` | `v0.49.0-rc1` | **PUBLICADA** |
| `runtime.inspect.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `runtime.cancel.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `runtime.recover.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `output.read.digested.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `output.pin.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `runtime.structure.v1` | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.operation.v1` | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.eventshape.v1` | `v0.54.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |

## Cambios sobre la candidata anterior (`v0.55.0-rc1` → `v0.56.0-rc1`)

### PK-1 — Contrato de identidad y surface machine-verifiable

1. **`CorrelationId`** (nuevo value class en `:pipeline-domain`).
   El invocador puede pasar `--correlation-id <uuid>` a `pipeline
   run`; PK la preserva a través del durable state. Aceptado por
   `UuidCorrelationIdGenerator` cuando el invocador no la
   proporciona.
2. **`RunCorrelated`** (nuevo `DomainEvent`). Emitido cuando el
   durable state se establece; lleva `correlationId`, `ownerId`,
   `startedAtMs`. Decodificable y re-emitible (probado por
   `DomainEventExhaustiveRoundTripTest` con 72 variantes).
3. **`LaunchReceipt`** (nuevo en `:pipeline-application`). Data
   class con JSON encoder manual. Schema `pipelinek.launch/1`.
   Se emite en stdout como una sola línea JSON tras establecer
   durable state; permite al consumer correlacionar su identidad
   con el run sin parsear logs ni consultar la tabla `events`.
4. **`CliExit`** (nuevo sealed interface en `:pipeline-application`).
   8 casos cerrados con código estable y nombre estable: `Success`,
   `PipelineFailed`, `InvalidArgument`, `IncompatibleVersion`,
   `RuntimeNotAvailable`, `UnknownExecution`, `ReadFailure`,
   `IntegrityViolation`. Cada caso tiene `encodeEnvelope()` que
   emite una línea JSON schema `pipelinek.cli/1`. El mapping
   `CliError → CliExit` es exhaustivo (24+ branches) — un nuevo
   `CliError` sin mapping falla el build.
5. **`pipeline capabilities`** (nuevo subcomando). Emite una
   línea JSON schema `pipelinek.capabilities/1` con las 11
   capabilities (id, status, firstPublished). Sin efectos
   secundarios.
6. **`pipeline sdk-info`** (nuevo subcomando). Emite una línea
   JSON schema `pipelinek.sdk/1` con las 5 coordenadas SDK + la
   BOM. Sin efectos secundarios.
7. **`v2/build.gradle.kts` bumped** `0.55.0` → `0.56.0` (MINOR).
8. **PK_CONTRACT_HANDOFF CRIC-PK1 a Fabric:** sigue a este
   release-receipt como
   `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-pk1.md`.
9. **ADR** `docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md`
   (5 secciones: contexto, decisión, consecuencias, alternativas,
   compatibilidad N/N-1).
10. **Tests:** `Pk1ContractTypesTest` (8 casos) cubre los
    schemas JSON, los códigos CliExit, el generador de
    CorrelationId, la preservación de RunCorrelated y la lista
    de SdkCapabilities.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.56.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.
- **5 SDK coordinates** publicadas a `0.56.0`:
  - `pipeline-domain-0.56.0.{jar,pom,module}`
  - `pipeline-events-0.56.0.{jar,pom,module}` (incluye `EventShapeReadPort`, `TypedEvent`, `EventShapeResult`)
  - `pipeline-output-0.56.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.56.0.{jar,pom,module}` (incluye M6+M7 surface completa)
  - `pipeline-scripting-api-0.56.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.56.0.{pom}`.
- **`candidate_id`:** `sha256:258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.

## Tests certificadores (PK-1)

| Test class | Módulo | Casos |
|---|---|---|
| `Pk1ContractTypesTest` | `:pipeline-application` | 8 (LaunchReceipt, CliExit envelope, CliExit codes, UuidCorrelationIdGenerator, CorrelationId validation, RunCorrelated fields, SdkCapabilities 11 entries, CliError→CliExit mapping) |
| `DomainEventExhaustiveRoundTripTest` | `:pipeline-events-store` | 72 (era 71; RunCorrelated añadido) |
| `DomainEventRoundTripTest` | `:pipeline-events-store` | 72-variant seal hierarchy |
| **Total PK-1** | — | **80+ casos** |

Baterías previas (M1, M2, M3, M6, M7) sin regresiones (132+ casos).

## Verificación

- ✅ `git sddk-align` acknowledged para cada commit de la release branch
- ✅ `git sddk-close` registrado para cada commit
- ✅ `:pipeline-domain:compileKotlin` + `:pipeline-events:compileKotlin` + `:pipeline-events-store:compileKotlin` + `:pipeline-application:compileKotlin` BUILD SUCCESSFUL
- ✅ `:pipeline-events-store:test` verde (72 variantes, RunCorrelated round-trip)
- ✅ `:pipeline-events:test` verde
- ✅ `:pipeline-application:test --tests "*Pk1ContractTypesTest*"` 8/8 verde
- ✅ `:pipeline-application:test --tests "*CapabilityRegistration*"` 7/7 verde (regresión check)
- ✅ `./gradlew publishSdkForExternalPlugin` 5 SDK coords + BOM a 0.56.0
- ✅ `pipeline-runtime-api-0.56.0.jar` contiene M6+M7 surface completa (verificado con `unzip -l`)
- ✅ `pipeline-events-0.56.0.jar` contiene `EventShapeReadPort` (verificado con `unzip -l`)
- ✅ `./gradlew :pipeline-application:distZip` `pipelinek-0.56.0.zip` (88.4 MB)
- ✅ `pipeline capabilities` y `pipeline sdk-info` ejecutables (probados vía `installDist`)
- ✅ `git push -u origin release/pk1-contract-v0.56.0-rc1` rama publicada
- ✅ `git tag -a v0.56.0-rc1` + `git push origin v0.56.0-rc1` etiqueta publicada
- ✅ `gh release create v0.56.0-rc1 --prerelease` Prerelease publicada con el ZIP asset

**Estado actual:** `CANDIDATE_PUBLISHED` en GitHub.
**Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc1

## Pendiente (no bloqueante, en commits futuros sobre la misma release branch)

- **T5 / T6** — `MainEntrypoint` extraction + refactor de `Main.kt` para
  usar `CliExit` en todos los paths. El mapping `CliError → CliExit`
  ya está exhaustivo; falta cablearlo en el flujo principal.
- **T9 / T10** — Propagar `CorrelationId` a través de
  `RunIdDirectory` y `CanonicalDurableRunCoordinator` para emitir
  el evento `RunCorrelated` en el path durable. `LaunchReceipt`
  se emite al final del run.
- **T11** (extensión) — Test de coherence `SdkCapabilities` ↔
  `:pipeline-runtime-api` ABI.
- **T12 / T13** — `example-external-consumer` con `Pk1IdentityTest`
  (2 runs concurrentes con `--correlation-id` distintos, restart
  preserva identidad) + `Pk1CliExitTest` (cada `CliExit`).

## Pendiente externo

- `CERTIFIED` por `pipelinek-release-harness` — el harness
  reconstruye la imagen desde el ZIP publicado y emite el
  veredicto.
- `PAIR_CERTIFIED` con Fabric — Fabric publica su release M6.4
  contra este artefacto (o contra `v0.55.0-rc1` si la cadena CRIC-1
  ya está certificada).
- **Promoción EXPERIMENTAL → PUBLICADA** post-`CERTIFIED`.

## Estado de la cadena CRIC-1 + PK-1

```
M1 (v0.49.0-rc1):  2 PUBLICADA        ✓
M2 (v0.50.0-rc1):  +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):  +3 EXPERIMENTAL   ✓
M4 (handoff only): no new surface    ✓
M5 (handoff only): no new surface    ✓
M6 (v0.53.0-rc1):  +2 EXPERIMENTAL   ✓ (placement fixed in M6.4)
M7 (v0.54.0-rc1):  +1 EXPERIMENTAL   ✓ (placement fixed by association)
M6.4 (v0.55.0-rc1): corrective re-publish ✓ (M6 surface en :pipeline-runtime-api)
PK-1 (v0.56.0-rc1): 0 capabilities   ✓ (additive surfaces: 3 new types + 2 subcommands)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces (CorrelationId, LaunchReceipt, CliExit)
```

**Ready for `CERTIFIED` y `PAIR_CERTIFIED` con Fabric.**

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-1 vigente).
