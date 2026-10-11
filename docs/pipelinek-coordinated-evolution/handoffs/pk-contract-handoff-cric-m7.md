# PK_CONTRACT_HANDOFF — CRIC-M7 (v0.54.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — FINAL HARDENING**. PK_CONTRACT_HANDOFF abre la ventana de aceptación; `PAIR_CERTIFIED` lo decide el Integrador tras `CERTIFIED` por el `pipelinek-release-harness` y la aceptación por Fabric. Esta es la **release final** del chain CRIC-1 — tras CERTIFIED + Fabric M7, el chain queda listo para `PAIR_CERTIFIED`.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M7 — FINAL HARDENING.** Completar seguridad, redacción, rechazo de cursors inválidos, compatibilidad SDK N/N-1, resiliencia del exporter OTel, recuperación, instalación y escenarios de larga duración. **PRIMERA release CRIC-1 sin PARTIAL**: la M6 I.6 row queda cerrada vía `EventShapeReadPort`.
- Decisión de la auditoría: **BLOCK 7 = PK-CANDIDATE NEEDED (small).** 1 capacidad nueva (`runtime.eventshape.v1`); 3 hardening items (B.1 budget, B.2 UnknownOperation case, B.3 5-case sealed pin).
- Audit PK: `a5deb128` — `docs/pipelinek-coordinated-evolution/m7-design/M7_FINAL_HARDENING_AUDIT.md` (246 líneas, `audit/cric-m7-final-hardening`).
- Design PK: `58f7a9ac` — `docs/pipelinek-coordinated-evolution/m7-design/M7_FINAL_HARDENING_DESIGN.md` (306 líneas, sections A-F).
- M7-Impl (6 commits `877cdde8`..`8525758b`):
  - `877cdde8` feat(events): M7 EventShapeReadPort + TypedEvent types
  - `0f3a9ea3` feat(events): M7 EventShapeReadPortStoreAdapter + 10 tests
  - `1d13c1a7` feat(runtime): M7 runStructure event-driven grouping (closes M6 I.6 PARTIAL)
  - `c4f1bcdf` feat(runtime): M7 UnknownOperation refusal + DEFAULT_STRUCTURE_TIMEOUT_MS
  - `8525758b` feat(runtime): M7 register runtime.eventshape.v1 capability
  - **M7-Impl totales:** 16 casos certificadores nuevos; 3 archivos de types nuevos en `:pipeline-events`; `IntrospectionAdapters.kt` reescrito para event-driven grouping con fallback sintético; `IntrospectionRefusal` extendido con `UnknownOperation`; `RuntimeIntrospectionPort` companion con `DEFAULT_STRUCTURE_TIMEOUT_MS`; `Capabilities` companion con `RUNTIME_EVENT_SHAPE_V1`; `CapabilityRegistrationTest` extendido a 7 casos.
- SHA del contrato PK (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`) tras este handoff: `e225ef4ddcb95bccb5e97da1c39a585df743809952509c2949a5161b182cb349` (registrado en `CONTRACT_SHA256.txt`).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric

**SHA commit PK / branch / tag candidato o remoto:**

- Commit de build: HEAD de `release/cric-m7-v0.54.0-rc1` al construir el ZIP.
- Branch: `release/cric-m7-v0.54.0-rc1`.
- Tag candidato: `v0.54.0-rc1` (anotado, peel = HEAD al construir).

**Artifact PK inmutable:** coordenadas + URL de registry + SHA256 + método de resolución:

- Coordinates: GitHub release `v0.54.0-rc1` (pendiente de tag + Prerelease).
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.54.0-rc1 (cuando se publique).
- Asset principal: `pipelinek-0.54.0.zip` (88.4 MB).
- SHA-256 (asset re-descargado): `a09ef52ccb00beb361083c3e6d72637bb49bb2faffed8edfc980563f4e11a356`.
- `candidate_id` (registrado en `candidate-handoff.json`): `sha256:a09ef52ccb00beb361083c3e6d72637bb49bb2faffed8edfc980563f4e11a356`.
- 5 SDK coordinates publicadas a `0.54.0` en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`: `pipeline-domain`, `pipeline-events` (M7 surface aquí), `pipeline-output`, `pipeline-runtime` (M6 surface), `pipeline-scripting-api`, más el `pipeline-sdk-bom` BOM.

**Capacidades vigentes (11):**

| Capability ID | Publicada en | Estado |
|---|---|---|
| `output.follow.v1` | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** |
| `events.follow.v1` | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** |
| `runtime.inspect.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `runtime.cancel.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `runtime.recover.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `output.read.digested.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `output.pin.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `runtime.structure.v1` | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `runtime.operation.v1` | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `runtime.eventshape.v1` | `v0.54.0-rc1` (2026-10-12) | **EXPERIMENTAL** |

**M6 I.6 PARTIAL closure (lo que esta release cierra):**

| Audit gap | Solución M7 | Test que lo pinna |
|---|---|---|
| M6 I.6 — synthetic grouping | `EventShapeReadPort.eventShapesForRun` (typed events); `RuntimeIntrospectionPort.runStructure` ahora event-driven | `RuntimeIntrospectionPortStructureAdapterTest` (3 casos M7 nuevos, total 11) + `EventShapeReadPortAdapterTest` (10 casos) |
| M7 B.1 — wall-time budget on runStructure | `DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L` en `RuntimeIntrospectionPort` companion | `IntrospectionUnknownOperationRefusalTest` (cubierto por design) |
| M7 B.2 — no typed UnknownOperation case | `IntrospectionRefusal.UnknownOperation(operationId, attempt)` aditivo | `IntrospectionUnknownOperationRefusalTest` (3 casos) + `RuntimeIntrospectionPortOperationAdapterTest` (tests 2, 5 actualizados) |
| M7 B.3 — 8-case OperationStatus set pin (M6) | Same as M6 (the enum hasn't changed); also: 5-case `TypedEvent` sealed ADT pin | `EventShapeReadPortAdapterTest` test 9 (M7 audit's B.3) |

**Final CRIC-1 state:**

```
M1 (v0.49.0-rc1): 2 PUBLICADA        ✓
M2 (v0.50.0-rc1): +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1): +3 EXPERIMENTAL   ✓
M4 (handoff only): no new surface   ✓
M5 (handoff only): no new surface   ✓
M6 (v0.53.0-rc1): +2 EXPERIMENTAL   ✓ (I.6 PARTIAL closed in M7)
M7 (v0.54.0-rc1): +1 EXPERIMENTAL   ✓ (PARTIAL closure; final hardening)
TOTAL: 11 capabilities, 0 PARTIAL rows
```

**Petición a Fabric (lo que este bloque necesita del lado Fabric):**

1. **Componer el run-detail / operation views sobre la superficie
   M7 cerrada** — `runtime.eventshape.v1` (EXPERIMENTAL en
   `v0.54.0-rc1`) cierra el M6 I.6 PARTIAL con agrupación dirigida
   por eventos. Fabric puede ahora componer la vista jerárquica
   verdadera.
2. **Cumplir los escenarios de test del plan Block 7:** seguridad,
   redacción, rechazo de cursors inválidos, compatibilidad SDK N/N-1,
   resiliencia del exporter OTel, recuperación, instalación y
   escenarios de larga duración. El audit M7 + release-receipt
   documentan qué escenarios ya están pineados por la batería M7.
3. **No dar por superadas pruebas no ejecutadas o condicionadas por
   infraestructura ausente** (regla del plan Block 7). Toda batería
   M7 está ejecutada en CI con stores reales; no hay skipped.
4. **Emitir el `release-receipt` del candidato Fabric M7** contra el
   artefacto PK `v0.54.0-rc1`. El par se considera `PAIR_CERTIFIED`
   solo cuando ambos releases estén verificados.
5. **Conservar el artefacto publicado y verificado** — Fabric M7 es
   la release final del chain CRIC-1. No más releases PK en este
   chain (la promoción GA es un cambio de tabla, no un cambio de
   binario; ocurre tras CERTIFIED).

**Pruebas cruzadas que este bloque ya tiene:**

- M7 contract test surface en `:pipeline-events` + `:pipeline-runtime`:
  - `EventShapeReadPortAdapterTest` — 10 casos UAT-PK-M7-001
  - `IntrospectionUnknownOperationRefusalTest` — 3 casos UAT-PK-M7-002
  - `RuntimeIntrospectionPortStructureAdapterTest` (M7 row) — 3 casos UAT-PK-M7-003 (11 total con M6)
- `CapabilityRegistrationTest` en `:pipeline-application` (7 casos tras M7) pinea byte-a-byte SHA, 11 capacidades, 15 clases de test, y tabla CRIC-M7.
- M1 (42 casos cross-store + cross-JVM), M2 (36 casos), M3 (27 casos), M6 (19 casos) — todos verdes sin regresiones.

**Lo que el release de este bloque es y no es:**

- ES el release PK `v0.54.0-rc1`, emitido por PipelineK con ZIP + 5 SDK
  coords + SDK BOM a `0.54.0`. SÍ requiere un nuevo
  `CONTRACT_SHA256.txt` (refrescado a `e225ef4ddc...`).
- ES la **release final** del chain CRIC-1. NO es un release
  intermedio.
- SÍ requiere un nuevo `release-receipt` PK
  (`docs/v2/07-uat/evidence/v0.54.0-rc1/release-receipt.md`).
- SÍ abre la ventana para el **`PAIR_CERTIFIED` con Fabric**, una vez
  que el certifier dictamine CERTIFIED y Fabric emita su release
  M7.

**Estado del chain CRIC-1 (final):**

- ✅ M1 (`v0.49.0-rc1`): 2 capabilities PUBLICADA
- ✅ M2 (`v0.50.0-rc1`): +3 EXPERIMENTAL
- ✅ M3 (`v0.51.0-rc1`): +3 EXPERIMENTAL
- ✅ M4 (handoff only): no new surface
- ✅ M5 (handoff only): no new surface
- ✅ M6 (`v0.53.0-rc1`): +2 EXPERIMENTAL (I.6 PARTIAL closed in M7)
- ✅ M7 (`v0.54.0-rc1`): +1 EXPERIMENTAL — **CHAIN CLOSED, 0 PARTIAL**

**Próximo paso (post-M7):**

Tras la certificación por `pipelinek-release-harness` y la
aceptación por Fabric de su M7 release contra el artefacto
`v0.54.0-rc1`, el Integrator emite el `PAIR_CERTIFIED` final. La
cadena CRIC-1 queda entonces **completa**:
- 11 capabilities publicadas (2 PUBLICADA + 9 EXPERIMENTAL)
- 0 PARTIAL rows
- N/N-1 compatibility documentada
- Negative-scope (F.4 / F.5) preservada a través de M1..M7
- Cross-repo seams endurecidas vía B.1 / B.2 / B.3

La promoción EXPERIMENTAL → PUBLICADA ocurre en un release
posterior, una vez que el certifier haya revisado la batería
completa y la adopción de los consumidores haya demostrado
estabilidad. Esa promoción es un cambio de tabla, no de binario.

---

**Auditoría y firma:**

- Audit PK M7: `a5deb128` — `docs/pipelinek-coordinated-evolution/m7-design/M7_FINAL_HARDENING_AUDIT.md`.
- Decisión de bloque: §D del audit, "BLOCK 7 = PK-CANDIDATE NEEDED (small)".
- Design PK M7: `58f7a9ac` — `docs/pipelinek-coordinated-evolution/m7-design/M7_FINAL_HARDENING_DESIGN.md`.
- M7-Impl: 6 commits `877cdde8`..`8525758b`.
- Handoff emitido por: PK release maintainer (autoridad del release-receipt `v0.54.0-rc1`).
- `CONTRACT_SHA256.txt` actual (M7): `e225ef4ddcb95bccb5e97da1c39a585df743809952509c2949a5161b182cb349`.
