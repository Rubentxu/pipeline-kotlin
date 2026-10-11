# PK_CONTRACT_HANDOFF — CRIC-PK1 (v0.56.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — PK-1 contract surface**. Esta candidata NO añade capabilities nuevas al chain CRIC-1; añade las **superficies de contrato** que el roadmap PK-1 exige para un SDK consumible y verificable desde fuera. Las 11 capabilities vigentes se mantienen; las nuevas superficies (CorrelationId, LaunchReceipt, CliExit, `pipeline capabilities`, `pipeline sdk-info`) son aditivas.

---

## Resumen ejecutivo

El chain CRIC-1 cerró con `v0.55.0-rc1` (M6.4 corrective). El roadmap
PK-1 abre el **train 0.56.0** con el **contrato de identidad de
ejecución** y las **superficies machine-verifiable**. Un consumer
externo (Fabric, un script de CI) puede ahora:

1. Pasar su propia identidad estable a una invocación con
   `--correlation-id <uuid>`.
2. Leer de stdout una **línea JSON tipada** con el `LaunchReceipt`
   (runId + correlationId + ownerId + startedAtMs + controlDir).
3. Leer de stderr un **envelope JSON de exit** con código y nombre
   estables (`pipelinek.cli/1`).
4. Consultar la lista de capabilities SDK con `pipeline
   capabilities` (`pipelinek.capabilities/1`).
5. Consultar el manifest SDK con `pipeline sdk-info`
   (`pipelinek.sdk/1`).

**Cambio de capability:** ninguno. Las 11 capabilities vigentes
mantienen su estado y su tests certificadores.

**Cambio de artefacto:** el artefacto canónico para `PAIR_CERTIFIED`
con Fabric es **`v0.56.0-rc1`**.

---

## Hito y SHA del contrato

- **Hito:** PK-1 — Contrato SDK consumible e identidad de ejecución.
- **ADR:** [`docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md`](../pk1-design/ADR-PK1-IDENTITY.md) (5 secciones: contexto, decisión, consecuencias, alternativas, compatibilidad N/N-1).
- **M7-Impl (4 commits en `release/pk1-contract-v0.56.0-rc1`):**
  - `docs(coordination): PK-1 ADR + feature tracker`
  - `feat(identity): T1-T4 PK-1 foundation types (CorrelationId + UuidCorrelationIdGenerator + RunCorrelated event + LaunchReceipt + CliExit + CliExitMapping)`
  - `feat(cli): T7-T8 PK-1 subcommands (capabilities + sdk-info) + dispatch in Main.kt`
  - `chore(build): bump version 0.55.0 → 0.56.0`
  - `release: v0.56.0-rc1 + docs`
- **SHA del contrato PK** (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`): se documenta tras añadir la sección PK-1.

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric

## SHA commit PK / branch / tag

- **Commit de build:** HEAD de `release/pk1-contract-v0.56.0-rc1` al construir el ZIP.
- **Branch:** `release/pk1-contract-v0.56.0-rc1`.
- **Tag candidato:** `v0.56.0-rc1` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc1.

## Artifact PK inmutable

- **Coordinates:** GitHub release `v0.56.0-rc1` Prerelease.
- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc1
- **Asset principal:** `pipelinek-0.56.0.zip` (88.4 MB).
- **SHA-256 (asset re-descargado):** `258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.
- **`candidate_id`:** `sha256:258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.
- **5 SDK coordinates** publicadas a `0.56.0`:
  - `pipeline-domain-0.56.0.{jar,pom,module}`
  - `pipeline-events-0.56.0.{jar,pom,module}` (M7 surface)
  - `pipeline-output-0.56.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.56.0.{jar,pom,module}` (M6+M7 surface reubicada)
  - `pipeline-scripting-api-0.56.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.56.0.{pom}`.

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

## Superficies de contrato nuevas (PK-1)

| Surface | Schema | Ubicación |
|---|---|---|
| `CorrelationId` (value class) | n/a (typed) | `:pipeline-domain` |
| `RunCorrelated` (DomainEvent) | n/a (typed) | `:pipeline-events` |
| `LaunchReceipt` (JSON line) | `pipelinek.launch/1` | `:pipeline-application` (stdout) |
| `CliExit` (sealed + envelope) | `pipelinek.cli/1` | `:pipeline-application` (stderr) |
| `pipeline capabilities` (JSON line) | `pipelinek.capabilities/1` | subcomando CLI |
| `pipeline sdk-info` (JSON line) | `pipelinek.sdk/1` | subcomando CLI |

## Petición a Fabric

1. **Componer la release PK-1 contra el artefacto `v0.56.0-rc1`**.
   Sin cambios sobre `v0.55.0-rc1` en capabilities; añadir cobertura
   para las 6 superficies nuevas (CorrelationId, LaunchReceipt,
   CliExit, `pipeline capabilities`, `pipeline sdk-info`).
2. **Re-correr la batería de certificación Fabric PK-1** — debe
   incluir un test que ejercite los 6 subcomandos/superficies y
   valide los schemas JSON.
3. **Verificar los schemas JSON** parseando las líneas de stdout
   del binario publicado:
   - `pipeline capabilities` → `pipelinek.capabilities/1`
   - `pipeline sdk-info` → `pipelinek.sdk/1`
   - `pipeline run --correlation-id <id>` → `pipelinek.launch/1`
4. **No dar por superadas pruebas no ejecutadas o condicionadas
   por infraestructura ausente** (regla del plan). Toda la batería
   debe ejecutarse en CI; no skipped.
5. **Emitir el `release-receipt` del candidato Fabric PK-1**
   contra el artefacto PK `v0.56.0-rc1`. El par se considera
   `PAIR_CERTIFIED` solo cuando ambos releases estén verificados.
6. **Conservar el artefacto publicado y verificado.** La promoción
   GA es un cambio de tabla, no un cambio de binario; ocurre tras
   `CERTIFIED`.

## Pruebas cruzadas que esta release tiene

- `Pk1ContractTypesTest` (8 casos) — todos verdes.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde (regresión check).
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 220+ casos certificadores, todos verdes en CI sobre la
imagen reconstruida desde el ZIP `v0.56.0-rc1`.

## Estado del chain CRIC-1 + PK-1

```
M1 (v0.49.0-rc1):   2 PUBLICADA        ✓
M2 (v0.50.0-rc1):   +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):   +3 EXPERIMENTAL   ✓
M4 (handoff only):  no new surface    ✓
M5 (handoff only):  no new surface    ✓
M6 (v0.53.0-rc1):   +2 EXPERIMENTAL   ✓ (placement fixed in M6.4)
M7 (v0.54.0-rc1):   +1 EXPERIMENTAL   ✓ (placement fixed by association)
M6.4 (v0.55.0-rc1): corrective         ✓
PK-1 (v0.56.0-rc1): +0 capabilities   ✓ (additive contract surfaces)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces
```

## Próximo paso (post-PK-1)

Tras `CERTIFIED` por `pipelinek-release-harness` y la aceptación por
Fabric de su PK-1 release contra el artefacto `v0.56.0-rc1`, el
Integrator emite el `PAIR_CERTIFIED` final. La cadena CRIC-1 + PK-1
queda entonces **completa**:

- 11 capabilities publicadas (2 PUBLICADA + 9 EXPERIMENTAL)
- 0 PARTIAL rows
- N/N-1 compatibility documentada
- Negative-scope (F.4 / F.5) preservada
- Cross-repo seams endurecidas vía B.1 / B.2 / B.3
- M6 surface correctamente colocada en `:pipeline-runtime-api`
- 3 nuevas superficies de contrato: `CorrelationId`, `LaunchReceipt`, `CliExit`
- 2 nuevas superficies machine-verifiable: `pipeline capabilities`, `pipeline sdk-info`

La promoción `EXPERIMENTAL → PUBLICADA` ocurre en un release
posterior, una vez que el certifier haya revisado la batería
completa. Esa promoción es un cambio de tabla, no de binario.

---

## Auditoría y firma

- **ADR PK-1:** `docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md`.
- **PK-1-Impl:** 4 commits en `release/pk1-contract-v0.56.0-rc1`.
- **Handoff emitido por:** PK release maintainer (autoridad del release-receipt `v0.56.0-rc1`).
- **Candidate ID:** `sha256:258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.
