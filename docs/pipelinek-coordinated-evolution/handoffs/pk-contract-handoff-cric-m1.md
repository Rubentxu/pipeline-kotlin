# PK_CONTRACT_HANDOFF — CRIC-M1 (v0.49.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED**. No declara release de pareja; es un handoff de
> proveedor. `PAIR_CERTIFIED` lo decide el Integrador tras `CERTIFIED` por
> el `pipelinek-release-harness` y la aceptación por Fabric.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M1** — coordinated read API (output + events).
- Rama de release PK: `release/cric-m1-v0.49.0-rc1` (HEAD de release al
  construir el ZIP: `a96c70ae000b6f223b76bc5bb409033acd47859b`).
- Tag candidato PK: `v0.49.0-rc1` (anotado, peel = `a96c70ae`).
- Prerelease URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.49.0-rc1
- PRs de CRIC-M1 en PK (orden cronológico en `feat/cric-m1-output-events-live` y
  merge en `release/cric-m1-v0.49.0-rc1`):
  - M1-A `e257e381` — public read-side ports + adapter
  - M1-B `7514bb35` — real `SegmentOutputFollower`
  - M1-C `08a83717` — real `SqliteEventFollower`
  - 1.A `e182f7a7` — M1-A/B/C corrections (idle/complete, UntilAllSealed
    pending-bytes, close-releases-wait, intra-frame resume, EventQuery
    refusal, cursor mismatch, lease authority, slice preservation)
  - 1.B `ec57684a` — M1-D cross-JVM e2e (6 UAT cases)
  - 1.C `ac1490e6` — capability registration (INTERFACE_CONTRACT.md update +
    CapabilityRegistrationTest)
  - M1-F (merge `a96c70ae`) — runtime quality: `sun.misc.Unsafe` eliminated,
    per-channel seal `SealOutcome` ADT, durable-shell timeout diagnostics,
    console UX cleaned of internal noise.
- SHA del contrato PK (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`)
  tras este handoff: registrado en `docs/pipelinek-coordinated-evolution/coordination/CONTRACT_SHA256.txt`.

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric
**SHA commit PK / branch / tag candidato o remoto:**

- Commit de build: `a96c70ae000b6f223b76bc5bb409033acd47859b`
- Branch: `release/cric-m1-v0.49.0-rc1`
- Tag candidato: `v0.49.0-rc1` (anotado, peel = `a96c70ae`)

**Artifact PK inmutable:** coordenadas + URL de registry + SHA256 + método
de resolución:

- Coordinates: GitHub release `v0.49.0-rc1`.
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.49.0-rc1
- Asset principal: `pipelinek-0.49.0.zip`.
- SHA-256 (asset re-descargado): `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583`
- `candidate_id` (registrado en `candidate-handoff.json`):
  `sha256:24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583`.
- Source commit: `a96c70ae000b6f223b76bc5bb409033acd47859b`.
- Método de resolución: `gh release download v0.49.0-rc1`; el certifier
  (`pipelinek-release-harness`) reconstruye la imagen desde estos bytes,
  no reutiliza una imagen previa identificada solo por nombre.
- ZIP reproducible byte a byte entre dos construcciones independientes
  (`24f9e967…` en A y B); el SBOM y los manifests no son byte-a-byte
  reproducibles por causas upstream del plugin `org.cyclonedx.bom`
  (timestamp, UUID, orden de `components[]`) — registrado, no normalizado.

**Delta ABI compilada (javap):** ninguno. CRIC-M1 publica **capacidades**
(véase la sección siguiente), no cambia la forma binaria publicada. El
paquete `pipeline-application-0.49.0.jar` reemplaza el `0.48.0.jar` con el
mismo set de entry points públicos; los consumidores compilados contra la
ABI de `0.48.0` siguen funcionando contra `0.49.0`. La matriz N/N-1 abajo
lo confirma.

**Capabilities/feature ranges negociadas (WorkerHello/manifest):**

| Capability ID | Versión | Publicada en | Test unitario | Test cross-JVM | Estado |
|---|---|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003, `:pipeline-application`) | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006, `:pipeline-application`) | **PUBLICADA** |

El contrato ya registra estas filas en `coordination/INTERFACE_CONTRACT.md`
sección "Capacidades publicadas (CRIC-M1)"; este handoff confirma que la
PK de origen está alineada con la tabla canónica del contrato.

**Identidades/eventos/frames y refusals afectados:**

- `RunId`, `OperationId`, `AttemptId` mantienen el mismo referente a través
  de PK × Fabric (regla 1 del contrato).
- Plano de salida (`output.follow.v1`): `Open`, `Sealed`, `Unavailable`,
  `RetentionGap`, `Corrupt` se exponen como refusals tipados en
  `OutputRefusal`. Bytes redactados antes de persistir, secuencia de offsets
  por stream, orden observado de frames (regla 2).
- Plano de eventos (`events.follow.v1`): `UnknownRun`, `RetentionLost`,
  `Cancelled` se exponen como refusals tipados en `EventFollowRefusal`.
  Idempotencia, rechazo de huecos, `Undecodable` preservado, cursor
  `(runId, sequence, hasMore, nextCursor)` (reglas 3 y 5).

**Reader/recovery contract y retention hooks:**

- `inspect/recover/cancel/follow` mediante puertos segregados (regla 5):
  - `OutputFollowEventPort` (read-only) y `SegmentOutputFollower`
    (implementación real, sin relanzar efectos externos).
  - `EventRecordReadPort` / `EventFollowerPort` (read-only) y
    `SqliteEventFollower` (implementación real).
- Una lectura **no** ejecuta recuperación destructiva; no relanza efectos
  externos automáticamente.
- Retención (regla 12): no se poda ningún rango sin prueba de transferencia
  durable y sin pin activo. Los planes `OutputPlaneConformanceTest` y
  `EventSliceParityLawsTest` mantienen la invariante.

**Fixtures reales + cada log de test, comandos, resultados, digest:**

Comandos ejecutados (todos `--no-daemon`, sobre `a96c70ae`):

```bash
# Gate targeted (84 tests, todos PASS):
./gradlew :pipeline-events-store:test --tests "*EventRecordReadPortAdapter*" \
                                    --tests "*EventFollowerAdapter*"
./gradlew :pipeline-output-store:test --tests "*SegmentOutputFollower*" \
                                     --tests "*M1F3ChannelSealing*"
./gradlew :pipeline-application:test --tests "*M1DCrossJvm*" \
                                   --tests "*CapabilityRegistration*" \
                                   --tests "*ObsFChunkCost*" \
                                   --tests "*LiveOutputDrain*" \
                                   --tests "*ObservationWakeup*" \
                                   --tests "*OutputPlaneConformance*"
./gradlew :pipeline-scripting-kotlin24:test --tests "*M1F1*" \
                                          --tests "*M1F4*"
./gradlew :pipeline-step-sdk:runtime:test --tests "*M1F2*"

# Build de la candidata:
./gradlew :pipeline-application:distZip
./gradlew :pipeline-application:cyclonedxBom
./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=1 -Pcandidate.tag=v0.49.0-rc1
```

Resultados:

| Test class | Casos | Resultado | Módulo |
|---|---:|---|---|
| `EventRecordReadPortAdapterTest` (5 nested classes) | 17 | PASS | `:pipeline-events-store` |
| `SegmentOutputFollowerTest` | 18 | PASS | `:pipeline-output-store` |
| `EventFollowerAdapterTest` | 18 | PASS | `:pipeline-events-store` |
| `M1DCrossJvmFollowTest` (UAT-PK-M1-001..006) | 6 | PASS | `:pipeline-application` |
| `CapabilityRegistrationTest` | 3 | PASS | `:pipeline-application` |
| `M1F1KotlinCompilerUnsafeEliminationTest` | 5 | PASS | `:pipeline-scripting-kotlin24` |
| `M1F2TimeoutDiagnosticsTest` | 6 | PASS | `:pipeline-step-sdk:runtime` |
| `M1F3ChannelSealingTest` | 8 | PASS | `:pipeline-output-store` |
| `M1F4ConsoleDiagnosticsUxTest` | 3 | PASS | `:pipeline-scripting-kotlin24` |
| `ObsFChunkCostMeasurementTest` | 1 | PASS | `:pipeline-application` |
| `LiveOutputDrainTest` | 7 | PASS | `:pipeline-application` |
| `ObservationWakeupTest` | 11 | PASS | `:pipeline-application` |
| `OutputPlaneConformanceTest` | 9 | PASS | `:pipeline-application` |
| **Total targeted** | **112** | **PASS** | — |

(El plan del bloque 1.D nombraba 11 tests por 84 casos; este handoff amplía
la lista con los tres que el M1-F sub-agent también exercitó para cubrir
cada costura del runtime quality. Total 112 casos PASS sobre el targeted
subset.)

Digest de los assets publicados:

| Asset | SHA-256 |
|---|---|
| `pipelinek-0.49.0.zip` | `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583` |
| `pipelinek-0.49.0.sbom.json` | `41efccf96fdd63171160fad4221487637c01fabce5b348793ecfd61ac371b977` |
| `candidate-handoff.json` | `a321a531b8b0f8a78454d10bd1b0fe3703662435507e09799d4c1569cad7ea18` |
| `distribution-manifest.json` | `99f759c511ef23d639741fa8edadf2ef68986bce256a0da99180806e596eec8f` |

**N/N-1 matrix y feature fallback tipado:**

- N = release de PK que adopta ambas capacidades (`v0.49.0-rc1` y siguientes
  hasta nuevo breaking).
- N-1 = releases anteriores (`v0.48.0-rc3`, `v0.48.0` GA, etc.). Permanece
  bajo el contrato **"ausencia => fallback o refusal"** sin cambios: la rama
  de negociación debe seguir tratando la ausencia de `output.follow.v1` /
  `events.follow.v1` como `Refused`, nunca como LIVE implícito.
- Cliente Fabric anterior × PK nuevo: la rama de negociación de Fabric debe
  aceptar la presencia de las dos capacidades y enrutar el follow real; sin
  esa actualización el cliente Fabric seguirá disparando el fallback
  tipado y por tanto será **no-certificado** para el par.
- Cliente Fabric nuevo × PK anterior: la rama de negociación de Fabric
  debe detectar la ausencia y mantener `Refused` para los clientes que
  consumen PK N-1; ningún consumidor nuevo se rompe por la adopción.

**Petición concreta al consumidor + criterio de aceptación:**

1. **Actualizar `Rubentxu/pipelinek-fabric/docs/fabric-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`**:
   - Mantener la sección 6 (Compatibilidad y negociación) tal como está en
     la versión PK actual; esa sección ya registra las dos capacidades con
     `PUBLICADA` desde este handoff.
   - Reflejar la tabla "Capacidades publicadas (CRIC-M1)" con los SHAs
     canónicos (los de `coordination/INTERFACE_CONTRACT.md` en PK).
   - Recalcular y versionar `coordination/CONTRACT_SHA256.txt` para que el
     SHA del documento refleje el estado alineado en ambos repos.
2. **Actualizar la rama de negociación de Fabric** para reconocer
   `output.follow.v1` y `events.follow.v1` como capacidades ofrecidas
   cuando el `WorkerHello` / manifest del peer PK incluya `product_version
   >= 0.49.0`. Mantener el comportamiento de `Refused` cuando la versión
   sea `< 0.49.0` y la capacidad esté ausente.
3. **Criterio de aceptación** (lo que el Integrador verificará antes de
   `PAIR_CERTIFIED`):
   - `coordination/CONTRACT_SHA256.txt` de Fabric == el de PK.
   - La rama de negociación de Fabric en su build canónico (HEAD publicado
     + este cambio) pasa la batería de UAT cruzada PK × Fabric sobre los
     bytes `sha256:24f9e967…` re-descargados.
   - No hay refactor de la rama de negociación que asuma capacidades
     presentes en PK N-1.

**Rollback de schema:** no aplica. CRIC-M1 no introduce migraciones de
schema; introduce capacidades. Un consumidor que rechace la nueva versión
sigue funcionando contra PK N-1 con la rama de negociación original y la
matriz N/N-1 documentada arriba.

**Estado:** `PREPARED`. Pendiente para `PAIR_CERTIFIED`:

1. `pipelinek-release-harness` devuelve `CERTIFIED` sobre los bytes
   `sha256:24f9e967…` de esta candidata.
2. Fabric acepta este handoff y publica su propio release con la rama de
   negociación actualizada.
3. La matriz cruzada PK × Fabric pasa sobre los bytes certificados.

---

No declarar release de pareja con este documento; es un handoff de
proveedor. La promoción del par de releases la decide el Integrador tras
`PAIR_CERTIFIED` mutuo entre PK × Fabric.