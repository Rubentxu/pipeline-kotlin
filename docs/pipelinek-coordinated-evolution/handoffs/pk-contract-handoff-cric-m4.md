# PK_CONTRACT_HANDOFF — CRIC-M4 (Jenkins LIVE compatibility)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — COMPATIBILITY HANDOFF ONLY**. No hay release de pareja nuevo en PK; el release del bloque es el de Fabric. PK conserva `v0.51.0-rc1` como artefacto publicado y verificado.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M4 — JENKINS LIVE.** Compatibilidad del productor con lectura simultánea desde Jenkins y un segundo inspector sobre el mismo run. Cursores independientes, salida completa, terminalidad legítima, ninguna cancelación inducida por observación.
- Decisión de la auditoría: **BLOCK 4 = COMPATIBILITY HANDOFF ONLY.** Sin nueva superficie PK, sin nueva release PK, sin cambio de `CONTRACT_SHA256.txt` en este bloque.
- Audit PK: `92706181` — `docs/pipelinek-coordinated-evolution/m4-design/M4_JENKINS_LIVE_AUDIT.md` (1265 líneas, `audit/cric-m4-jenkins-live`).
- Autoridades previas (sin cambios): M1 `v0.49.0-rc1` (output/events follow), M2 `v0.50.0-rc1` (inspect/cancel/recover), M3 `v0.51.0-rc1` (digest/pin/retention-refusal).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric

**SHA commit PK / branch / tag:**

- Branch PK audit: `audit/cric-m4-jenkins-live` (`92706181`).
- Tag candidato PK: **ninguno nuevo**. PK conserva `v0.51.0-rc1` (M3) como artefacto publicado.
- Tag candidato Fabric: lo emite Fabric; este handoff no prescribe el identificador.

**Artifact PK inmutable (sin cambio):**

- Coordinates: GitHub release `v0.51.0-rc1`.
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.51.0-rc1
- Asset principal: `pipelinek-0.51.0.zip`.
- SHA-256 (asset re-descargado, registrado en release-receipt M3): `4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`.
- Cinco capacidades vigentes PUBLICADA / EXPERIMENTAL — ver bloque "Capacidades vigentes" abajo.

**Resumen del audit y decisión:**

1. **No se nombra gap nuevo en §B.** Los cuatro ejes M4 (cursores independientes, salida completa, terminalidad legítima, sin cancelación inducida por observación) están servidos por la superficie M1+M2+M3 ya publicada. La auditoría no propone ningún puerto nuevo, ningún `sealed case` nuevo, ningún ID de capacidad nuevo, ninguna celda nueva del contrato.
2. **Las seis invariantes PASS-by-design + tested.** §D de `M4_JENKINS_LIVE_AUDIT.md` enumera I.1..I.6 con cero `PARTIAL` y cero `UNVERIFIED`. La batería M1-D cross-JVM (`M1DCrossJvmFollowTest` con `UAT-PK-M1-001/003`) ya cubre el caso "segundo proceso lee mientras PK sigue corriendo" desde el lado del productor; la batería M3 cubre retention + digest; la M2 cubre los verbos runtime.
3. **Sin solape que requiera consolidación PK antes de M4.** §C enumera seis solapes demarcados por su KDoc actual. La auditoría M4 no propone ningún cambio. Los dos puertos que solapan en estado de doble-observador (`RuntimeIntrospectionPort.inspect` y el primer evento del follow) comparten primitivas de lectura, pero el vocabulario de cada puerto no llama al otro.
4. **Las autoridades que la auditoría prohíbe no se proponen.** No se introduce dependencia Jenkins en módulos publicados; no se duplica reducer Jenkins en PK; no se introduce scheduler, lease, fencing scheme, ni retry loop nuevos. La regla de la sección "negative scope" del plan se preserva.
5. **La decisión es "conservar artefacto publicado y verificado".** El release de este bloque es el release de Fabric M4. PK mantiene `v0.51.0-rc1` como artefacto publicado. No se requiere cambio en `PAIR_RECEIPT.json` desde PK.
6. **Watchpoints identificados.** §E.2 de la auditoría nombra `UAT-PK-M4-001..004` sugeridos y §E.5 una métrica FITNESS sugerida. Ninguno es un cambio de superficie PK; son traceability hooks para el lado Fabric.

**Ejes M4 (enforcement por puertos publicados existentes):**

| Eje | Puerto(s) PK publicado(s) | Test que lo pinna |
|---|---|---|
| Cursores independientes (I.1) | `EventRecordReadPort.readRecords` (M2 events), `OutputReadPort.read / readRange / readRangeDigested` (M1/M3 output) | `M1DCrossJvmFollowTest` UAT-PK-M1-001..006 |
| Salida completa (I.2) | `OutputReadPort.readRangeDigested` (M3), `EventRecordReadPort.readRecords` con `EventQuery.All` (M1) | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`), `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) |
| Terminalidad legítima (I.3) | `EventPage.hasMore` (M1 events), `OutputReadPort.committedExtent` (M1 output) | `M1DCrossJvmFollowTest` UAT-PK-M1-001/003 |
| Sin cancelación inducida (I.4) | `RuntimeControlPort.cancel` (M2) — se requiere acción explícita, el follow es observacional | `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) |
| Observador no avanza estado del escritor (I.5) | Composición de read ports — la inspección es SELECT-only por construcción | `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`) |
| Reconnects preservan identidad (I.6) | `EventCursor(runId, lastSequence)` value type (M1), `OutputCursor(stream, committedOffset)` value type (M1) | `EventFollowerAdapterTest`, `SegmentOutputFollowerTest` |

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

1. **Identificar el shim Jenkins que lee a través de `output.follow.v1` + `events.follow.v1`** — sin shim específico PK, sin duplicación de reducer Jenkins. El shim es decisión de Fabric.
2. **Identificar el segundo inspector primario** para los UAT-PK-M4-001..004 (la auditoría no asume un inspector específico; CLI, debug, réplica, indexer son todos elegibles mientras respeten los cuatro ejes).
3. **Decidir si `UAT-PK-M4-001..004` se añaden al suite de contrato del productor en `v0.51.0-rc1` o en un futuro pre-release.** La recomendación de la auditoría PK es "follow-up pre-release": los e2e M1-D + M2 + M3 ya cubren los ejes; los UAT-PK-M4 son traceability hooks opcionales, no contrato.
4. **Emitir el `release-receipt` del candidato Fabric M4.** Sin cambio de `CONTRACT_SHA256.txt` por parte de PK; el contrato común sigue en el SHA de M3.
5. **Confirmar la regla "observador no enumera otros observadores"** — la auditoría PK pinna esto como non-feature deliberada (§G.6). No añadir `OutputFollowersOf(runId)` ni `EventFollowersOf(runId)`.
6. **Confirmar la regla "segundo observador no afecta lease"** — la auditoría PK pinna esto como non-feature deliberada (§G.7). El observador lee el lease vía `RuntimeIntrospectionPort.inspect.leaseHolder`; no lo adquiere, no lo libera, no lo re-fence.
7. **Confirmar la regla "segundo observador no afecta retention"** — la auditoría PK pinna esto como non-feature deliberada (§G.8). Un observador que quiera pre-flight de sus propios pins usa `OutputPinPort.pinsOf(stream, range?)` y `OutputRetentionPort.canPrune(intent)` directamente; no hay `releaseOtherObserverPins`.

**Pruebas cruzadas que este bloque NO requiere pero deja como evidencia:**

- M1-D cross-JVM `M1DCrossJvmFollowTest` (1064 líneas, 6 métodos `UAT-PK-M1-001..006`, dos JVMs con stores reales). El caso de "segundo proceso leyendo mientras PK corre" ya está cubierto a nivel de productor; Fabric compone.
- M2 adapter tests (8+8+8 = 24 casos sobre los verbos runtime).
- M3 retention tests (27 casos sobre digest, pin, prune, refusal extension).

**Lo que el release de este bloque es y no es:**

- ES el release de Fabric M4, emitido por Fabric contra el artefacto PK `v0.51.0-rc1`.
- NO es un release nuevo de PK.
- NO requiere un nuevo `CONTRACT_SHA256.txt`.
- NO requiere un nuevo `release-receipt` de PK (el de `v0.51.0-rc1` sigue vigente; el de Fabric lo emite Fabric).

**Próximo paso del lado PK (M5):** la auditoría M5 RETENTION/PIN FOR ARCHIVE también resuelve "compatibility handoff only" — PK conserva `v0.51.0-rc1`. PK emite handoff M5 con la misma plantilla cuando la auditoría M5 cierre. El cambio de superficie real vuelve en M6 (CONTEXT/PRESSURE), donde la auditoría nombra tres métodos aditivos sobre `RuntimeIntrospectionPort` (`runStructure`, `inspectOperation`, `StructureRunOptions` / `StructureCursor`); M6 es el primer bloque M-train que sí produce candidata PK (prerelease `v0.53.0-rc1` esperado).

---

**Auditoría y firma:**

- Audit PK M4: `92706181` — `docs/pipelinek-coordinated-evolution/m4-design/M4_JENKINS_LIVE_AUDIT.md`.
- Decisión de bloque: §F del audit, "BLOCK 4 = COMPATIBILITY HANDOFF ONLY".
- Handoff emitido por: PK release maintainer (autoridad del release-receipt M3 vigente).
- `CONTRACT_SHA256.txt` actual (M3): `233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64` — sin cambio en este bloque.
