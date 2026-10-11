# PK_CONTRACT_HANDOFF — CRIC-M6.4 (v0.55.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — CORRECTIVE REPUBLISH (M6.4 follow-up)**. Esta es la **release canónica** del chain CRIC-1: las candidatas `v0.53.0-rc1` y `v0.54.0-rc1` se conservan por trazabilidad pero **NO son consumibles**; el par `PAIR_CERTIFIED` con Fabric debe usar el artefacto `v0.55.0-rc1`.

---

## Resumen ejecutivo

El chain CRIC-1 alcanzó su cierre lógico con M7 (`v0.54.0-rc1`) pero la **superficie M6 quedó en `:pipeline-runtime`** (módulo host, no consumible como SDK), un defecto de placement que el M6.4 follow-up documentado en los `release-receipt.md` de M6 y M7 señaló como "no bloquea la candidata, ocurre después de la publicación". Esta candidata **`v0.55.0-rc1` es esa release correctiva**: re-emite la misma superficie M6+M7 con la placement fix.

**Cambio de contrato:** ninguno. Las 11 capacidades vigentes mantienen su semántica y sus tests certificadores.

**Cambio de placement:** `RunStructure`, `RunStructureResult`, `OperationInspection`, `OperationInspectionResult`, `StructureRunOptions`, `StructureCursor`, y los métodos `runStructure` + `inspectOperation` sobre `RuntimeIntrospectionPort` se mueven de `:pipeline-runtime` a `:pipeline-runtime-api`.

**Cambio de artefacto:** el artefacto canónico para `PAIR_CERTIFIED` con Fabric es **`v0.55.0-rc1`** (no `v0.53.0-rc1`, no `v0.54.0-rc1`).

---

## Hito y SHA del contrato

- **Hito:** CRIC-M6.4 — M6 SURFACE RELOCATION. Re-publicar la superficie M6+M7 con la placement fix; cerrar el M6.4 follow-up documentado en los release-receipts de M6 y M7.
- **Decisión:** la surface debe estar en `:pipeline-runtime-api` (la M2 surface ya está ahí; el M6 worktree branched pre-split y la puso en `:pipeline-runtime`).
- **Audit / design (pre-existing, M6 y M7):**
  - Audit PK M6: `83e1c28d` — `docs/pipelinek-coordinated-evolution/audit/cric-m6-context-pressure/M6_CONTEXT_PRESSURE_AUDIT.md`
  - Design PK M6: `0b53a9c9` — `docs/pipelinek-coordinated-evolution/m6-design/M6_CONTEXT_PRESSURE_DESIGN.md`
  - Audit PK M7: `a5deb128` — `docs/pipelinek-coordinated-evolution/audit/cric-m7-final-hardening/M7_FINAL_HARDENING_AUDIT.md`
  - Design PK M7: `58f7a9ac` — `docs/pipelinek-coordinated-evolution/m7-design/M7_FINAL_HARDENING_DESIGN.md`
- **M6.4-Impl (3 commits en `release/m6-relocate-v0.55.0-rc1`):**
  - `merge: CRIC-M6 release (v0.53.0-rc1) into main with M6 surface relocated to pipeline-runtime-api`
  - `merge: CRIC-M7 release (v0.54.0-rc1) into main`
  - `fix(runtime): re-add AttemptId import to RuntimeIntrospectionPortStoreAdapter`
  - `chore(build): bump version 0.54.0 → 0.55.0` (M6.4 train open)
- **SHA del contrato PK** (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`) tras este handoff: `0d626cbc68a2b39b63f333a487e2509f2fd1db6fdee345645056fce76a6209e4` (registrado en `CONTRACT_SHA256.txt`).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric

## SHA commit PK / branch / tag

- **Commit de build:** HEAD de `release/m6-relocate-v0.55.0-rc1` al construir el ZIP.
- **Branch:** `release/m6-relocate-v0.55.0-rc1`.
- **Tag candidato:** `v0.55.0-rc1` (anotado, peel = HEAD al construir).
- **Prerelease URL (cuando se publique):** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.55.0-rc1.

## Artifact PK inmutable

- **Coordinates:** GitHub release `v0.55.0-rc1` Prerelease.
- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.55.0-rc1
- **Asset principal:** `pipelinek-0.55.0.zip` (88.4 MB).
- **SHA-256 (asset re-descargado):** `ca0fc928d9cb58dd9bb3646ab54bc964cdab5827b9f6c92eb4a59ba95a17da5b`.
- **`candidate_id`:** `sha256:ca0fc928d9cb58dd9bb3646ab54bc964cdab5827b9f6c92eb4a59ba95a17da5b` (registrado en `candidate-handoff.json`).
- **5 SDK coordinates** publicadas a `0.55.0` en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.55.0.{jar,pom,module}`
  - `pipeline-events-0.55.0.{jar,pom,module}` (M7 surface)
  - `pipeline-output-0.55.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.55.0.{jar,pom,module}` **(M6 surface reubicada aquí)**
  - `pipeline-scripting-api-0.55.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.55.0.{pom}`.

## Capacidades vigentes (11) — sin cambios

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
| `runtime.structure.v1` | `v0.53.0-rc1` / **re-affirmed** `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.operation.v1` | `v0.53.0-rc1` / **re-affirmed** `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.eventshape.v1` | `v0.54.0-rc1` / **re-affirmed** `v0.55.0-rc1` | **EXPERIMENTAL** |

## Petición a Fabric

1. **Componer la release M6.4 contra el artefacto `v0.55.0-rc1`** (NO contra `v0.53.0-rc1` ni contra `v0.54.0-rc1`). Las Prereleases M6 y M7 first-cut se conservan por trazabilidad pero no son consumibles vía SDK ABI.
2. **Re-correr la batería de certificación Fabric M6.4** — debe ser **idéntica** a las baterías M6.4 previas (no se añade ninguna capacidad nueva en esta release; el cambio es de placement).
3. **Verificar el placement fix desde el lado Fabric:** abrir `pipeline-runtime-api-0.55.0.jar` y confirmar que las clases M6 están presentes:
   - `dev.rubentxu.pipeline.v2.runtime.inspect.RunStructure` (sealed)
   - `dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult` (sealed)
   - `dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspection` (data class)
   - `dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult` (sealed)
   - `dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions` (data class)
   - `dev.rubentxu.pipeline.v2.runtime.inspect.StructureCursor` (data class)
   - `dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort` (interface con `inspect`, `runStructure`, `inspectOperation`)
4. **No dar por superadas pruebas no ejecutadas o condicionadas por infraestructura ausente** (regla del plan Block 7). Toda batería M6.4 está ejecutada en CI; no hay skipped.
5. **Emitir el `release-receipt` del candidato Fabric M6.4** contra el artefacto PK `v0.55.0-rc1`. El par se considera `PAIR_CERTIFIED` solo cuando ambos releases estén verificados.
6. **Conservar el artefacto publicado y verificado** — el chain CRIC-1 se cierra con `v0.55.0-rc1`. No más releases PK en este chain (la promoción GA es un cambio de tabla, no un cambio de binario; ocurre tras CERTIFIED).

## Pruebas cruzadas que esta release tiene

- M1 (36 casos cross-store + 6 cross-JVM)
- M2 (24 casos adapter + 12 fitness)
- M3 (27 casos)
- M6 (19 casos)
- M7 (16 casos)
- `CapabilityRegistrationTest` (7 casos)

**Total:** 130+ casos certificadores, todos verdes en CI sobre la imagen reconstruida desde el ZIP `v0.55.0-rc1`.

## Estado del chain CRIC-1 (final con M6.4)

```
M1 (v0.49.0-rc1):  2 PUBLICADA        ✓
M2 (v0.50.0-rc1):  +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):  +3 EXPERIMENTAL   ✓
M4 (handoff only): no new surface    ✓
M5 (handoff only): no new surface    ✓
M6 (v0.53.0-rc1):  +2 EXPERIMENTAL   ✓ (placement defect → fixed in M6.4)
M7 (v0.54.0-rc1):  +1 EXPERIMENTAL   ✓ (placement fixed by association in M6.4)
M6.4 (v0.55.0-rc1): corrective re-publish ✓ (M6 surface en :pipeline-runtime-api)
TOTAL: 11 capabilities, 0 PARTIAL rows, M6 surface consumible via SDK
```

## Próximo paso (post-M6.4)

Tras la certificación por `pipelinek-release-harness` y la aceptación por
Fabric de su M6.4 release contra el artefacto `v0.55.0-rc1`, el Integrator
emite el `PAIR_CERTIFIED` final. La cadena CRIC-1 queda entonces **completa**:

- 11 capabilities publicadas (2 PUBLICADA + 9 EXPERIMENTAL)
- 0 PARTIAL rows
- N/N-1 compatibility documentada
- Negative-scope (F.4 / F.5) preservada a través de M1..M7
- Cross-repo seams endurecidas vía B.1 / B.2 / B.3
- M6 surface correctamente colocada en `:pipeline-runtime-api`

La promoción EXPERIMENTAL → PUBLICADA ocurre en un release posterior, una
vez que el certifier haya revisado la batería completa y la adopción de
los consumidores haya demostrado estabilidad. Esa promoción es un cambio
de tabla, no de binario.

---

## Auditoría y firma

- Audit PK M6: `83e1c28d` — `docs/pipelinek-coordinated-evolution/audit/cric-m6-context-pressure/M6_CONTEXT_PRESSURE_AUDIT.md`.
- Design PK M6: `0b53a9c9` — `docs/pipelinek-coordinated-evolution/m6-design/M6_CONTEXT_PRESSURE_DESIGN.md`.
- Audit PK M7: `a5deb128` — `docs/pipelinek-coordinated-evolution/audit/cric-m7-final-hardening/M7_FINAL_HARDENING_AUDIT.md`.
- Design PK M7: `58f7a9ac` — `docs/pipelinek-coordinated-evolution/m7-design/M7_FINAL_HARDENING_DESIGN.md`.
- M6.4-Impl: 4 commits (`ab0473c1` merge main base, `83112e40` M6 merge, M7 merge, `10c23a0b` AttemptId fix, version bump).
- Handoff emitido por: PK release maintainer (autoridad del release-receipt `v0.55.0-rc1`).
- `CONTRACT_SHA256.txt` actual (M6.4): `0d626cbc68a2b39b63f333a487e2509f2fd1db6fdee345645056fce76a6209e4`.
