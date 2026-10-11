# PK_CONTRACT_HANDOFF — CRIC-M6 (v0.53.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED**. PK_CONTRACT_HANDOFF abre la ventana de aceptación; `PAIR_CERTIFIED` lo decide el Integrador tras `CERTIFIED` por el `pipelinek-release-harness` y la aceptación por Fabric.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M6 — CONTEXT/PRESSURE.** Aportar contexto indexable de run/stage/step/operation sobre los puertos públicos segregados de `:pipeline-runtime`, con lecturas eficientes que tienen presupuestos explícitos (bounded scan + resume cursor).
- Decisión de la auditoría: **BLOCK 6 PK-CANDIDATE NEEDED.** Tres métodos aditivos sobre `RuntimeIntrospectionPort` (M2 segregation rule preservada), dos capacidades EXPERIMENTAL nuevas (`runtime.structure.v1`, `runtime.operation.v1`).
- Audit PK: `83e1c28d` — `docs/pipelinek-coordinated-evolution/m6-design/M6_CONTEXT_PRESSURE_AUDIT.md` (1392 líneas, `audit/cric-m6-context-pressure`).
- Design PK: `0b53a9c9` — `docs/pipelinek-coordinated-evolution/m6-design/M6_CONTEXT_PRESSURE_DESIGN.md` (365 líneas, sections A-F).
- M6-Impl (4 commits `71aca83a`..`d9b39489`):
  - `71aca83a` feat(runtime): M6 runStructure + inspectOperation types and method signatures
  - `788e3c8b` feat(runtime): M6 runStructure + inspectOperation adapter wire + 19 tests
  - `d9b39489` feat(runtime): M6 register runtime.structure.v1 and runtime.operation.v1 capabilities
  - **M6-Impl totales:** 3 archivos de test nuevos (StructureAdapterTest 8 casos, OperationAdapterTest 6 casos, StructurePaginationTest 5 casos; 19 M6 cases), 3 archivos de types nuevos (RunStructure.kt, OperationInspection.kt, StructureRunOptions.kt), 2 archivos modificados (RuntimeIntrospectionPort.kt, IntrospectionAdapters.kt), Capabilities companion extendido, CapabilityRegistrationTest extendido.
- SHA del contrato PK (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`) tras este handoff: `87e8ac99f71d88da38cbf4b39432927e69ba7957e3ab389778d59a2dc18c36f3` (registrado en `CONTRACT_SHA256.txt`).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric

**SHA commit PK / branch / tag candidato o remoto:**

- Commit de build: HEAD de `release/cric-m6-v0.53.0-rc1` al construir el ZIP.
- Branch: `release/cric-m6-v0.53.0-rc1`.
- Tag candidato: `v0.53.0-rc1` (anotado, peel = HEAD al construir).

**Artifact PK inmutable:** coordenadas + URL de registry + SHA256 + método de resolución:

- Coordinates: GitHub release `v0.53.0-rc1` (pendiente de tag + Prerelease).
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.53.0-rc1 (cuando se publique).
- Asset principal: `pipelinek-0.53.0.zip` (88.3 MB).
- SHA-256 (asset re-descargado): `f18981efceb8c2e4c08275777d1a6da6dd7e4cd3222a8014643838aa2de76965`.
- `candidate_id` (registrado en `candidate-handoff.json`): `sha256:f18981efceb8c2e4c08275777d1a6da6dd7e4cd3222a8014643838aa2de76965`.
- 5 SDK coordinates publicadas a `0.53.0` en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`: `pipeline-domain`, `pipeline-events`, `pipeline-output`, `pipeline-runtime` (M6 surface aquí), `pipeline-scripting-api`, más el `pipeline-sdk-bom` BOM.

**Capacidades vigentes (10):**

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

**Gaps M6 que esta release cierra:**

| Audit gap | Solución PK | Test que lo pinna |
|---|---|---|
| F.1 — No public indexed enumeration of stages / steps / operations | `RuntimeIntrospectionPort.runStructure(runId, options): RunStructureResult` | `RuntimeIntrospectionPortStructureAdapterTest` (8 casos, UAT-PK-M6-001) |
| F.2 — No public per-operation terminality typed query | `RuntimeIntrospectionPort.inspectOperation(operationId, attempt): OperationInspectionResult` | `RuntimeIntrospectionPortOperationAdapterTest` (6 casos, UAT-PK-M6-002) |
| F.3 — Bounded scan + resume cursor | `StructureRunOptions { pageSize: 1..4096, cursor: StructureCursor?, includeTerminalStages }` + `StructureCursor(stageOrdinal, stepOrdinal, operationOrdinal)` | `RuntimeIntrospectionPortStructurePaginationTest` (5 casos, UAT-PK-M6-003) |
| F.4 (negative scope) — No Governor / fairness scheduler / query engine in PK | PRESERVED. La composición es solo `OperationJournal.listForRun / get / getStartedAt / getEndedAt / getDeadlineMs` + `OutputFrameIndex.streamsOfRun` (published M3). | Audit §F.4; design §C composition table. |
| F.5 (negative scope) — No new lease / scheduler / transport in PK | PRESERVED. No se introduce lease nuevo, fencing scheme, scheduler, ni transport. | Audit §F.5; design §C composition table. |

**PARTIAL closure notes (audit I.6):**

- **Síntesis sintética single-stage / single-step** en M6 first-cut. El
  envelope `PipelineEventEnvelope` descarta los campos específicos de
  tipo de los eventos tipados; la agrupación dirigida por eventos
  requiere un follow-up M-block con un `EventShapeReadPort` tipado.
- Los tests contractuales pinean explícitamente la agrupación
  sintética; un follow-up que añada agrupación dirigida por eventos
  actualizará los tests.
- El `release-receipt` documenta este PARTIAL honestamente.

**Petición a Fabric (lo que este bloque necesita del lado Fabric):**

1. **Componer run-detail / operation views sobre `runtime.structure.v1` + `runtime.operation.v1`** (EXPERIMENTAL en `v0.53.0-rc1`). El run-detail view y per-operation terminality query son M6; Fabric no necesita implementar un Governor, scheduler de fairness, ni motor de consulta propio en PK.
2. **Cumplir los escenarios de test del plan Block 6:** salida grande con lecturas pequeñas, filtros combinados, lectores lentos, presión, memoria acotada, ausencia de cambios en outcomes. Los primeros cinco están cubiertos por la batería M6 (`StructureAdapterTest` + `StructurePaginationTest` + `OperationAdapterTest`); el sexto escenario (absence of outcome changes) lo cubre I.5 PASS-by-design + tested.
3. **Emitir el `release-receipt` del candidato Fabric M6** contra el artefacto PK `v0.53.0-rc1`. El par se considera `PAIR_CERTIFIED` solo cuando ambos releases estén verificados.
4. **Esperar al follow-up M-block (typed EventShapeReadPort)** antes de depender de agrupación dirigida por eventos. El M6 first-cut entrega agrupación sintética; un consumer que necesite la jerarquía verdadera debe esperar al follow-up.
5. **No introducir el Governor, scheduler de fairness, ni motor de consulta de Fabric en PK.** Tampoco.

**Pruebas cruzadas que este bloque ya tiene:**

- M6 contract test surface en `:pipeline-runtime`:
  - `RuntimeIntrospectionPortStructureAdapterTest` — 8 casos UAT-PK-M6-001
  - `RuntimeIntrospectionPortOperationAdapterTest` — 6 casos UAT-PK-M6-002
  - `RuntimeIntrospectionPortStructurePaginationTest` — 5 casos UAT-PK-M6-003
- `CapabilityRegistrationTest` en `:pipeline-application` (6 casos tras M6) pinea byte-a-byte SHA, capacidades (10), tests (13 clases), y tabla CRIC-M6.
- M2 adapter tests (24 casos sobre `inspect / cancel / recover`) — sin regresiones.
- M3 retention tests (27 casos sobre digest / pin / prune) — sin regresiones.
- M1 follow tests (36 casos cross-store + 6 cross-JVM) — sin regresiones.

**Lo que el release de este bloque es y no es:**

- ES el release PK `v0.53.0-rc1`, emitido por PipelineK con ZIP + 5 SDK
  coords + SDK BOM a `0.53.0`. SÍ requiere un nuevo
  `CONTRACT_SHA256.txt` (refrescado a
  `87e8ac99f71d88da38cbf4b39432927e69ba7957e3ab389778d59a2dc18c36f3`).
- ES un release de PipelineK. NO es un release de Fabric. La
  `PAIR_CERTIFIED` se decide cuando Fabric publique su propio release
  contra el artefacto `v0.53.0-rc1`.
- SÍ requiere un nuevo `release-receipt` PK
  (`docs/v2/07-uat/evidence/v0.53.0-rc1/release-receipt.md`), adjunto a
  este handoff.

**Próximo paso del lado PK (M7):** la auditoría M7 FINAL HARDENING está
pendiente de diseño. M7 es el bloque final del train CRIC y debería
cerrar los `PARTIAL` remanentes (incluido el I.6 de M6 con un
`EventShapeReadPort` tipado) y endurecer los seams cross-repo antes
del `PAIR_CERTIFIED` final. El diseño de M7 se publicará cuando la
auditoría M7 cierre.

---

**Auditoría y firma:**

- Audit PK M6: `83e1c28d` — `docs/pipelinek-coordinated-evolution/m6-design/M6_CONTEXT_PRESSURE_AUDIT.md`.
- Decisión de bloque: §F del audit, "BLOCK 6 PK-CANDIDATE NEEDED".
- Design PK M6: `0b53a9c9` — `docs/pipelinek-coordinated-evolution/m6-design/M6_CONTEXT_PRESSURE_DESIGN.md`.
- Handoff emitido por: PK release maintainer (autoridad del release-receipt `v0.53.0-rc1`).
- `CONTRACT_SHA256.txt` actual (M6): `87e8ac99f71d88da38cbf4b39432927e69ba7957e3ab389778d59a2dc18c36f3`.
