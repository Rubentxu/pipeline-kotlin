# PK_CONTRACT_HANDOFF — CRIC-PK1 (v0.56.0-rc2) — completion

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — PK-1 contract surface (complete)**. La candidata `v0.56.0-rc2` cierra el bloque PK-1 con propagación durable de correlación + consumer extension. `v0.56.0-rc1` se preserva como Prerelease histórica; **`v0.56.0-rc2` es la candidata canónica para `PAIR_CERTIFIED` con Fabric**.

---

## Resumen ejecutivo

`v0.56.0-rc2` cierra el **bloque PK-1** sobre `v0.56.0-rc1`:

1. **`--correlation-id <uuid>` propagado al durable state.** El
   `RunIdDirectory` ahora persiste la correlación junto al
   `runId`; el `LaunchReceipt` se emite en stdout tras establecer
   el durable state; el evento `RunCorrelated` se publica en el
   `event-plane` antes del primer `RunStarted`.
2. **Consumer extension end-to-end.** `examples/example-external-consumer`
   corre 5 casos contra la candidata publicada y verifica que
   `pipeline capabilities`, `pipeline sdk-info`, `CorrelationId`,
   `RunCorrelated`, y el `LaunchReceipt` son accesibles / correctos
   desde el SDK.

**Cambio de capability:** ninguno. Las 11 capabilities vigentes
mantienen su estado y sus tests certificadores.

**Cambio de artefacto canónico:** `v0.56.0-rc2` reemplaza a
`v0.56.0-rc1` como la candidata canónica para `PAIR_CERTIFIED`.

---

## SHA commit PK / branch / tag

- **Commit de build:** HEAD de `release/pk1-contract-v0.56.0-rc1` al construir el ZIP.
- **Branch:** `release/pk1-contract-v0.56.0-rc1` (misma branch que rc1; rc2 es un tag posterior).
- **Tag candidato:** `v0.56.0-rc2` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc2.

## Artifact PK inmutable

- **Asset principal:** `pipelinek-0.56.0.zip` (88.4 MB).
- **SHA-256 (asset re-descargado):** `0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.
- **`candidate_id`:** `sha256:0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.
- **5 SDK coordinates** a `0.56.0` (idénticas a rc1):
  - `pipeline-domain-0.56.0.{jar,pom,module}`
  - `pipeline-events-0.56.0.{jar,pom,module}`
  - `pipeline-output-0.56.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.56.0.{jar,pom,module}` (M6+M7 surface)
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

## Superficies de contrato (PK-1 completo)

| Surface | Schema / tipo | Ubicación |
|---|---|---|
| `CorrelationId` (value class) | n/a (typed) | `:pipeline-domain` |
| `RunCorrelated` (DomainEvent) | n/a (typed) | `:pipeline-events` |
| `LaunchReceipt` (JSON line, stdout) | `pipelinek.launch/1` | `:pipeline-application` |
| `CliExit` (sealed + envelope, stderr) | `pipelinek.cli/1` | `:pipeline-application` |
| `pipeline capabilities` (JSON line) | `pipelinek.capabilities/1` | subcomando CLI |
| `pipeline sdk-info` (JSON line) | `pipelinek.sdk/1` | subcomando CLI |
| `--correlation-id <uuid>` CLI flag | n/a (typed) | `CliParser` + `Main.kt` |
| `RunIdDirectory` correlación durable | n/a (filesystem) | `:pipeline-application` |

## Petición a Fabric

1. **Componer la release PK-1 contra el artefacto `v0.56.0-rc2`**
   (no contra `v0.56.0-rc1`; esta es la candidata canónica).
2. **Correr la batería completa** (los 5 casos del consumer +
   cualquier test PK-1 propio de Fabric) contra el artefacto
   publicado.
3. **Verificar el `--correlation-id` end-to-end** invocando
   `pipeline run --correlation-id <uuid>` y parseando la línea
   `pipelinek.launch/1` de stdout.
4. **No dar por superadas pruebas no ejecutadas o condicionadas
   por infraestructura ausente** (regla del plan).
5. **Emitir el `release-receipt` del candidato Fabric PK-1**
   contra el artefacto PK `v0.56.0-rc2`. El par se considera
   `PAIR_CERTIFIED` solo cuando ambos releases estén verificados.
6. **Conservar el artefacto publicado y verificado.** La
   promoción GA es un cambio de tabla, no de binario; ocurre tras
   `CERTIFIED`.

## Pruebas cruzadas que esta release tiene

- `Pk1ContractTypesTest` (8 casos) — verde in-monorepo.
- `Pk1ContractSurfaceTest` (5 casos) — **5/5 verde** en
  consumer extension.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 145+ casos certificadores, todos verdes en CI sobre la
imagen reconstruida desde el ZIP `v0.56.0-rc2`.

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
PK-1 (v0.56.0-rc1): foundation         ✓ (T1-T4, T7-T8, T11, T14-T18)
PK-1 (v0.56.0-rc2): completion         ✓ (T9-T11 + T12-T13)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces, 2 new subcommands
```

## Próximo paso (post-PK-1)

Tras `CERTIFIED` por `pipelinek-release-harness` y la aceptación por
Fabric de su PK-1 release contra el artefacto `v0.56.0-rc2`, el
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
- `--correlation-id` propagado al durable state + emitido en `RunCorrelated` + visible en `LaunchReceipt`

La promoción `EXPERIMENTAL → PUBLICADA` ocurre en un release
posterior, una vez que el certifier haya revisado la batería
completa. Esa promoción es un cambio de tabla, no de binario.

---

## Auditoría y firma

- **ADR PK-1:** `docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md`.
- **PK-1-Impl (rc2):** 4 commits sobre `release/pk1-contract-v0.56.0-rc1` (T9-T11 + T12-T13).
- **Handoff emitido por:** PK release maintainer (autoridad del release-receipt `v0.56.0-rc2`).
- **Candidate ID:** `sha256:0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.
- **Candidatas previas:** `v0.56.0-rc1` (preservada; foundation de PK-1) y `v0.55.0-rc1` (M6.4 corrective, placement fix).
