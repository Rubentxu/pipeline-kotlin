# PK_CONTRACT_HANDOFF — CRIC-M5 (RETENTION/PIN FOR ARCHIVE compatibility)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — COMPATIBILITY HANDOFF ONLY**. No hay release de pareja nuevo en PK; el release del bloque es el de Fabric M5 archive. PK conserva `v0.51.0-rc1` como artefacto publicado y verificado.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M5 — RETENTION/PIN FOR ARCHIVE.** Confirmar que la superficie pin / digest / refusal.retention publicada en M3 satisface los seis ejes de archive (durabilidad, confirmación durable, liberación, corrupción, invalidación de recover, concurrencia) sin nueva superficie PK.
- Decisión de la auditoría: **BLOCK 5 = COMPATIBILITY HANDOFF ONLY.** Sin nueva superficie PK, sin nueva release PK, sin cambio de `CONTRACT_SHA256.txt` en este bloque.
- Audit PK: `d04d295c` — `docs/pipelinek-coordinated-evolution/m5-design/M5_RETENTION_PIN_AUDIT.md` (1142 líneas, `audit/cric-m5-retention-pin`).
- Autoridades previas (sin cambios): M1 `v0.49.0-rc1` (output/events follow), M2 `v0.50.0-rc1` (inspect/cancel/recover), M3 `v0.51.0-rc1` (digest/pin/retention-refusal). M5 sigue la huella de M4 — "conservar artefacto publicado y verificado, adaptado a M5" (§F.5 del audit).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric

**SHA commit PK / branch / tag:**

- Branch PK audit: `audit/cric-m5-retention-pin` (`d04d295c`).
- Tag candidato PK: **ninguno nuevo**. PK conserva `v0.51.0-rc1` (M3) como artefacto publicado.
- Tag candidato Fabric M5: lo emite Fabric; este handoff no prescribe el identificador.

**Artifact PK inmutable (sin cambio):**

- Coordinates: GitHub release `v0.51.0-rc1`.
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.51.0-rc1
- Asset principal: `pipelinek-0.51.0.zip`.
- SHA-256 (asset re-descargado, registrado en release-receipt M3): `4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`.
- Ocho capacidades vigentes PUBLICADA / EXPERIMENTAL — ver bloque "Capacidades vigentes" abajo.

**Resumen del audit y decisión:**

1. **No se nombra gap nuevo en §B.** Los seis ejes M5 (durabilidad, confirmación durable, liberación, corrupción, invalidación de recover, concurrencia) están servidos por la superficie M3 ya publicada. Las ocho verificaciones E.1..E.8 todas devuelven **VERIFIED: PK provides**. No se propone ningún puerto nuevo, ningún `sealed case` nuevo, ningún ID de capacidad nuevo.
2. **Las seis invariantes PASS-by-design + tested.** §D enumera I.1..I.6 con cero `PARTIAL` y cero `UNVERIFIED`. La batería M3 (27 casos: `OutputPinPortAdapterTest` 10, `OutputReadDigestedAdapterTest` 5, `PruneAuthorisationAdapterTest` 5, `OutputRefusalClosedTest` 4, `RecoverRefusalPinExtensionTest` 3) pinea cada invariante; el release `v0.51.0-rc1` la carga.
3. **Sin solape que requiera consolidación PK antes de M5.** §C enumera seis solapes demarcados por su KDoc. Los dos puertos que solapan en pin/prune (`OutputRetentionPort.prune` y `OutputRetentionPort.canPrune`) comparten primitivas de lectura pero ninguno llama al otro — la demarcación es "act vs consult", pineada por M3 design §8.3.
4. **Las autoridades que la auditoría prohíbe no se proponen.** No se introduce lease nuevo, ni fencing scheme, ni scheduler, ni indexer, ni adaptador S3, ni transporte gRPC, ni remote spool. La autoridad PK permanece en "bytes durables + journal + lease + pin". Archive-class retention es la superficie M3. Regla de la sección "liberar únicamente capacidades genéricas que falten" del plan: **cero capacidades genéricas faltan, cero se liberan**.
5. **La decisión es "conservar artefacto publicado y verificado, adaptado a M5".** El release de este bloque es el **Fabric M5 archive release**; PK mantiene `v0.51.0-rc1` como artefacto publicado. No se requiere cambio en `PAIR_RECEIPT.json` desde PK.
6. **Lo que Fabric M5 necesita de PK está enumerado.** §E del audit cruza cada UAT-AR-001..009 y AAT-23..30 que el archive M5 necesita y pinea el puerto PK M3 existente que lo satisface. Ningún caso Fabric requiere una nueva superficie PK; el veredicto es "usar `output.read.digested.v1` + `output.pin.v1` + `output.refusal.retention.v1` (todas EXPERIMENTAL en `v0.51.0-rc1`); el deliverable M5 es el release del lado Fabric".
7. **Sin cambio de celda de capacidades.** La tabla "Capacidades publicadas (CRIC-M3)" de `INTERFACE_CONTRACT.md` ya lista `output.read.digested.v1`, `output.pin.v1`, y `output.refusal.retention.v1` como `EXPERIMENTAL` en `v0.51.0-rc1`. El audit M5 no propone ningún ID de capacidad nuevo. Si la promoción `EXPERIMENTAL → PUBLICADA` ocurre durante el ciclo M5 (driver certifier), las celdas se refrescan; si no, las celdas se quedan como el release M3 las dejó. Ningún trabajo PK implicado.

**Ejes M5 (enforcement por puertos publicados existentes):**

| Eje | Puerto(s) PK publicado(s) | Test que lo pinna |
|---|---|---|
| Durabilidad (I.1) | `OutputPinPort.pin / release / pinsOf / isPinned` (M3) | `OutputPinPortAdapterTest` (10 casos, `:pipeline-output-store`) |
| Confirmación durable (I.2) | `OutputReadPort.readRangeDigested` retorna `OutputDigest` con SHA-256 sobre `[from, to)` (M3) | `OutputReadDigestedAdapterTest` (5 casos, `:pipeline-output-store`) |
| Liberación (I.3) | `OutputRetentionPort.prune` y `OutputRetentionPort.canPrune` (M3); refusal `PinHeldByAnother` (M3) | `PruneAuthorisationAdapterTest` (5 casos, `:pipeline-output-store`) |
| Corrupción (I.4) | `OutputRefusal.Corrupt(detail)` y `OutputRefusal.RangeLostRetention` (M3) | `OutputRefusalClosedTest` (4 casos, `:pipeline-output-store`) |
| Invalidación de recover (I.5) | `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` (M3 aditiva sobre `:pipeline-runtime`) | `RecoverRefusalPinExtensionTest` (3 casos, `:pipeline-runtime`) |
| Concurrencia (I.6) | Pin store TSV-backed con fence por `(stream, range)` (M3) | `OutputPinPortAdapterTest` cubre concurrencia en `pin / release / canPrune` |

**Capacidades vigentes (sin cambios desde M3):**

| Capability ID | Versión | Publicada en | Estado |
|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** |
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** |
| `output.read.digested.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `output.pin.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** |

**Petición a Fabric (lo que este bloque necesita del lado Fabric):**

1. **Componer el archive M5 sobre `output.read.digested.v1` + `output.pin.v1` + `output.refusal.retention.v1`** — todas EXPERIMENTAL en `v0.51.0-rc1`. El archive-class retention es M3; Fabric no necesita un adaptador S3 ni remote spool en PK.
2. **Cumplir los seis escenarios de test del plan:** reinicio, pin persistente, confirmación durable, liberación, corrupción y retención bajo concurrencia. Todos cubiertos por la batería M3 arriba; Fabric los ejecuta contra el artefacto `v0.51.0-rc1`.
3. **Emitir el `release-receipt` del candidato Fabric M5.** Sin cambio de `CONTRACT_SHA256.txt` por parte de PK; el contrato común sigue en el SHA de M3.
4. **No introducir capacidades genéricas nuevas en PK.** La regla "liberar únicamente capacidades genéricas que falten" del plan se preserva: **cero capacidades genéricas faltan** (verificado por §B del audit), **cero se liberan**.
5. **No introducir lease / scheduler / transport nuevos en PK.** Audit §F.4 / §F.5 pinna esto. Si un archive M5 los requiere, son del lado Fabric.
6. **No introducir el Governor, el scheduler de fairness, ni el motor de consulta de Fabric en PK.** Tampoco.

**Pruebas cruzadas que este bloque NO requiere pero deja como evidencia:**

- M3 contract test surface (27 casos sobre digest, pin, prune, refusal extension).
- M1-D cross-JVM `M1DCrossJvmFollowTest` (6 métodos `UAT-PK-M1-001..006`).
- M2 adapter tests (24 casos sobre los verbos runtime).
- M3 release-receipt `60d7df3a` documenta el estado de las ocho capacidades en GitHub Prerelease.

**Lo que el release de este bloque es y no es:**

- ES el release de Fabric M5, emitido por Fabric contra el artefacto PK `v0.51.0-rc1`.
- NO es un release nuevo de PK.
- NO requiere un nuevo `CONTRACT_SHA256.txt`.
- NO requiere un nuevo `release-receipt` de PK (el de `v0.51.0-rc1` sigue vigente; el de Fabric lo emite Fabric).

**Próximo paso del lado PK (M6):** la auditoría M6 CONTEXT/PRESSURE resuelve PK-CANDIDATE NEEDED — tres métodos aditivos sobre `RuntimeIntrospectionPort` (`runStructure`, `inspectOperation`, `StructureRunOptions` / `StructureCursor`). M6 es el primer bloque M-train que sí produce candidata PK (prerelease `v0.53.0-rc1` esperado) y un release-receipt nuevo, además de un refresh de `CONTRACT_SHA256.txt`. Hasta entonces, PK continúa con `v0.51.0-rc1` como artefacto publicado y verificado.

---

**Auditoría y firma:**

- Audit PK M5: `d04d295c` — `docs/pipelinek-coordinated-evolution/m5-design/M5_RETENTION_PIN_AUDIT.md`.
- Decisión de bloque: §F del audit, "BLOCK 5 = COMPATIBILITY HANDOFF ONLY".
- Handoff emitido por: PK release maintainer (autoridad del release-receipt M3 vigente).
- `CONTRACT_SHA256.txt` actual (M3): `233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64` — sin cambio en este bloque.
