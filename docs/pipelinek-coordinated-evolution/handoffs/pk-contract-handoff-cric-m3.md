# PK_CONTRACT_HANDOFF — CRIC-M3 (v0.51.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED**. No declara release de pareja; es un handoff de
> proveedor. `PAIR_CERTIFIED` lo decide el Integrador tras `CERTIFIED` por
> el `pipelinek-release-harness` y la aceptación por Fabric.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M3** — range / retention sobre los puertos públicos
  segregados de `:pipeline-output` (digest on read, pin/canPrune, typed
  refusal extension) y una aditiva sobre `:pipeline-runtime`
  (`RecoverRefusal.PinnedBytesOutsideRecoveredRegion`).
- Rama de release PK: `release/cric-m3-v0.51.0-rc1` (HEAD de release al
  construir el ZIP: `f6e55aa3599772edbb2733dc4ed4ad060a1aa223`).
- Tag candidato PK: `v0.51.0-rc1` (anotado, peel = `f6e55aa3`).
- Prerelease URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.51.0-rc1
- PRs de CRIC-M3 en PK (orden cronológico en
  `release/cric-m3-v0.51.0-rc1`):
  - audit `dab35001` — M3 RANGE/RETENTION audit
    (`docs/pipelinek-coordinated-evolution/m3-design/M3_RANGE_RETENTION_AUDIT.md`).
  - design `eea9eac1` — M3 RANGE/RETENTION design
    (`docs/pipelinek-coordinated-evolution/m3-design/M3_RANGE_RETENTION_DESIGN.md`).
  - M3-Impl Wave 1 (7 commits `901eea31`..`5ab344d0`) — `OutputRefusal`
    casos `RetentionGap` / `Corrupt` / `Unavailable` / `RangeLostRetention`;
    `RecoverRefusal.PinnedBytesOutsideRecoveredRegion`;
    `OutputDigest`, `OutputReadDigestedResult`, `OutputReadPort.readRangeDigested`;
    `OutputPinPort` + `OutputPin` / `OutputPinId` / `OutputPinResult`
    / `PinRefusal` / `PinReleaseOutcome`;
    `OutputRetentionPort.canPrune` + `PruneAuthorisation` / `PruneRefusal`;
    5 archivos de test con 27 casos contra los stores reales; TSV-backed
    pin store en `:pipeline-output-store`; `non-exhaustive when` cerrado
    en `ApplicationMain` y `MainConsoleCli`.
  - `2b2021a7` — feat(runtime): register CRIC-M3 capabilities in
    `Capabilities` companion. Reconciliación de la doble declaración
    (M2 en `:pipeline-runtime` + M3 introducida en `:pipeline-output`)
    en un único `Capabilities` con las 8 filas (M1+M2+M3). Borrado del
    `:pipeline-output/Capabilities.kt` huérfano.
  - `37240e13` — docs(contract): CRIC-M3 capabilities (digest, pin,
    refusal retention) registradas como **EXPERIMENTAL** en
    `INTERFACE_CONTRACT.md` §6 + tabla "Capacidades publicadas
    (CRIC-M3)". `CONTRACT_SHA256.txt` refrescado a
    `233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64`.
  - `a9114f66` — test(contract): pin CRIC-M3 capability registration
    (`CapabilityRegistrationTest` ahora 5 casos: contract SHA + 8 IDs
    PUBLICADA/EXPERIMENTAL + 3 audit tables con sus test classes).
  - `f6e55aa3` — chore(release): bump `0.50.0 -> 0.51.0`
    (CRIC-M3 train open).
  - `60d7df3a` — docs(uat): v0.51.0-rc1 release-receipt.
  - `0f160078` — docs(uat): v0.51.0-rc1 handoff to release harness (este
    commit cierra el bloque 1.D desde el punto de vista del producto; el
    bloque 1.E — tag + Prerelease en GitHub — es el siguiente paso
    desde la línea de comandos y NO requiere commit adicional).
- SHA del contrato PK
  (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`)
  tras este handoff: registrado en
  `docs/pipelinek-coordinated-evolution/coordination/CONTRACT_SHA256.txt`
  (`233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64`).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric
**SHA commit PK / branch / tag candidato o remoto:**

- Commit de build: `f6e55aa3599772edbb2733dc4ed4ad060a1aa223`
- Branch: `release/cric-m3-v0.51.0-rc1`
- Tag candidato: `v0.51.0-rc1` (anotado, peel = `f6e55aa3`)

**Artifact PK inmutable:** coordenadas + URL de registry + SHA256 +
método de resolución:

- Coordinates: GitHub release `v0.51.0-rc1`.
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.51.0-rc1
- Asset principal: `pipelinek-0.51.0.zip`.
- SHA-256 (asset re-descargado):
  `4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`.
- `candidate_id` (registrado en `candidate-handoff.json`):
  `sha256:4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450`.
- Source commit: `f6e55aa3599772edbb2733dc4ed4ad060a1aa223`.
- Método de resolución: `gh release download v0.51.0-rc1`; el
  certifier (`pipelinek-release-harness`) reconstruye la imagen desde
  estos bytes, no reutiliza una imagen previa identificada solo por
  nombre.
- ZIP reproducible byte a byte entre dos construcciones independientes
  (`4acf1a6c…` en A y B); el SBOM y los manifests no son byte-a-byte
  reproducibles por causas upstream del plugin `org.cyclonedx.bom`
  (timestamp, UUID, orden de `components[]`) — registrado, no
  normalizado.

**Delta ABI compilada (javap):** ninguno. CRIC-M3 publica **capacidades**
(véase la sección siguiente), no cambia la forma binaria publicada. El
paquete `pipeline-application-0.51.0.jar` reemplaza el `0.50.0.jar` con
el mismo set de entry points públicos más las clases públicas del
nuevo módulo `:pipeline-output` (`OutputDigest`,
`OutputReadDigestedResult`, `OutputPinPort`, `OutputPin`, `OutputPinId`,
`OutputPinResult`, `PinRefusal`, `PinReleaseOutcome`, `PruneAuthorisation`,
`PruneRefusal`) y la extensión aditiva `RecoverRefusal.PinnedBytesOutsideRecoveredRegion`
sobre el `RecoverRefusal` que M2 introdujo en `:pipeline-runtime`. Los
consumidores compilados contra la ABI de `0.50.0` siguen funcionando
contra `0.51.0`. La matriz N/N-1 abajo lo confirma.

**Capabilities / feature ranges negociadas (WorkerHello / manifest):**

| Capability ID | Versión | Publicada en | Estado | Test unitario |
|---|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10), carry-forward en `v0.51.0-rc1` | **PUBLICADA** | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10), carry-forward en `v0.51.0-rc1` | **PUBLICADA** | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) |
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (2026-10-10), carry-forward en `v0.51.0-rc1` | **EXPERIMENTAL** | `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`) |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` (2026-10-10), carry-forward en `v0.51.0-rc1` | **EXPERIMENTAL** | `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (2026-10-10), carry-forward en `v0.51.0-rc1` | **EXPERIMENTAL** | `RuntimeRecoverPortAdapterTest` (8 casos, `:pipeline-runtime`) + matriz pura de decisión `RuntimeRecoverDecisionTableFitnessTest` (12 casos, `:pipeline-runtime`) |
| `output.read.digested.v1` | v1 | `v0.51.0-rc1` (2026-10-11), **nueva** | **EXPERIMENTAL** | `OutputReadDigestedAdapterTest` (5 casos, `:pipeline-output-store`) |
| `output.pin.v1` | v1 | `v0.51.0-rc1` (2026-10-11), **nueva** | **EXPERIMENTAL** | `OutputPinPortAdapterTest` (10 casos, `:pipeline-output-store`) |
| `output.refusal.retention.v1` | v1 | `v0.51.0-rc1` (2026-10-11), **nueva** | **EXPERIMENTAL** | `PruneAuthorisationAdapterTest` (5 casos, `:pipeline-output-store`), `OutputRefusalClosedTest` (4 casos, `:pipeline-output-store`), `RecoverRefusalPinExtensionTest` (3 casos, `:pipeline-runtime`) |

El contrato ya registra estas filas en
`coordination/INTERFACE_CONTRACT.md` sección 6 (con los bloques
`> CRIC-M1 (v0.49.0-rc1, 2026-10-10):`, `> CRIC-M2 (v0.50.0-rc1, 2026-10-10):`
y `> CRIC-M3 (v0.51.0-rc1, 2026-10-11):` adyacentes) y en las tres
tablas "Capacidades publicadas (CRIC-M{n})" del mismo contrato. Este
handoff confirma que la PK de origen está alineada con la tabla
canónica del contrato. El test `CapabilityRegistrationTest` (5 casos,
`:pipeline-application`) pin byte-a-byte la presencia de las tres
tablas y los test classes que cada una referencia.

**Identidades / eventos / frames y refusals afectados:**

- `RunId`, `OperationId`, `AttemptId` mantienen el mismo referente a
  través de PK × Fabric (regla 1 del contrato).
- Plano de salida (`output.follow.v1`, carry-forward M1; el M3
  extiende este plano con tres capacidades nuevas):
  `Open`, `Sealed`, `Unavailable`, `RetentionGap`, `Corrupt` se
  exponen como refusals tipados en `OutputRefusal`. Bytes redactados
  antes de persistir, secuencia de offsets por stream, orden observado
  de frames (regla 2). El M3 **añade** cuatro casos nuevos al ADT
  existente (`RetentionGap`, `Corrupt`, `Unavailable`,
  `RangeLostRetention`) — aditivo, no una jerarquía paralela, y una
  clase de digest (`OutputDigest`).
- Plano de eventos (`events.follow.v1`, carry-forward M1):
  `UnknownRun`, `RetentionLost`, `Cancelled` se exponen como refusals
  tipados en `EventFollowRefusal`. Idempotencia, rechazo de huecos,
  `Undecodable` preservado, cursor
  `(runId, sequence, hasMore, nextCursor)` (reglas 3 y 5).
- **Digest on read (M3, `output.read.digested.v1`):**
  `OutputReadPort.readRangeDigested(stream, from, to):
  OutputReadDigestedResult` devuelve `Digested(page, digest) | Refused(reason)`
  cerrado. El digest es SHA-256 de los bytes committed en `[from, to)`;
  el `OutputDigest` value class se computa en el store en single-pass
  (no doble recorrido). Ley idempotency §8:
  `(tenant, run, plane, start, endExclusive, digest, epoch)` se puede
  armar combinando `readRangeDigested` con
  `RuntimeIntrospectionPort.inspect.epoch`. La constante
  `OutputDigest.DEFAULT_ALGORITHM = "SHA-256"` es la MISMA que usa
  `OperationJournal.beginOperation.fingerprint` (mismo algoritmo, dos
  autoridades). Test pinning: `OutputReadDigestedAdapterTest` (5 casos,
  `:pipeline-output-store`).
- **Pin (M3, `output.pin.v1`):**
  `OutputPinPort.pin(stream, range, holder, reason, expiresAtMs?):
  OutputPinResult.Pinned(pinId, expiresAtMs) | .Refused(PinRefusal)`;
  `release(pinId): PinReleaseOutcome` (idempotente;
  `Released | AlreadyReleased | UnknownPin | StorageError`);
  `pinsOf(stream, range?): List<OutputPin>` (orden
  `pinId.lexicographic`);
  `isPinned(stream, offset): Boolean` (consulta O(1)).
  Cap por defecto `OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM = 1024`.
  El pin NO es un lease: no fenzea al writer; retiene bytes contra GC.
  El store vive en `:pipeline-output-store/.../store/OutputPinStore.kt`
  (TSV-backed, parallelo al `FileBackedRunExecutionLeaseStore` pero
  sin la autoridad de fencing). Tests pinning:
  `OutputPinPortAdapterTest` (10 casos, `:pipeline-output-store`).
- **Refusal retention (M3, `output.refusal.retention.v1`):**
  El ADT sellado `OutputRefusal` recibe cuatro casos nuevos
  (`RetentionGap`, `Corrupt`, `Unavailable`, `RangeLostRetention`);
  `RecoverRefusal` recibe `PinnedBytesOutsideRecoveredRegion`; el puerto
  `OutputRetentionPort.canPrune(intent): PruneAuthorisation` devuelve
  `Granted | Consulted(stream, range, pins) | Refused(PruneRefusal)` —
  pair consult-before-act con `prune(intent)` ya existente. Los
  refusing closures están pinados por:
  `PruneAuthorisationAdapterTest` (5 casos),
  `OutputRefusalClosedTest` (4 casos), `RecoverRefusalPinExtensionTest`
  (3 casos). Estos tres paquetes cierran las invariantes I.4/I.5/I.6
  que el audit M3 (`dab35001`) había marcado como `PARTIAL` /
  `UNVERIFIED`.

**Reader / recovery contract y retention hooks:**

- `inspect/recover/cancel/follow` mediante puertos segregados
  (regla 5): el módulo `:pipeline-runtime` declara su `api` dependency
  sobre `:pipeline-domain`, `:pipeline-events` y `:pipeline-output`;
  los adapters (que tocan authorities internas como
  `FileBackedRunExecutionLeaseStore`, `RecoveredExecutionMaterializer`)
  viven en `:pipeline-application`. El módulo `:pipeline-output` declara
  `maven-publish` y exporta el `Capabilities` companion + las clases
  públicas del M3.
- Una lectura (`RuntimeIntrospectionPort`,
  `OutputReadPort.readRange`, `OutputReadPort.readRangeDigested`)
  **no** ejecuta recuperación destructiva; no relanza efectos externos
  automáticamente (audit D.1; regla 5 del contrato). El nuevo
  `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` es la única forma
  en que un `recover` puede cambiar bytes retenidos — y se devuelve
  como refusal tipado antes de cualquier write.
- Retención (regla 12): no se poda ningún rango sin prueba de
  transferencia durable y sin pin activo. El nuevo
  `canPrune(intent)` permite a un consumer pre-fligth un release; el
  prune real (`prune(intent)`) sigue siendo el acto, no la consulta.
  `RecoverDoesNotInvalidatePinsTest` y `RecoverDoesNotInvalidateDigestsTest`
  mantienen la invariante I.5/I.6 contra el recover path.

**Fixtures reales + cada log de test, comandos, resultados, digest:**

Comandos ejecutados (todos `--no-daemon`, sobre `f6e55aa3`):

```bash
# Gate targeted (149 tests, todos PASS):
./gradlew :pipeline-output-store:test
./gradlew :pipeline-runtime:test
./gradlew :pipeline-events-store:test
./gradlew :pipeline-events:test
./gradlew :pipeline-output:test
./gradlew :pipeline-scripting-kotlin24:test
./gradlew :pipeline-step-sdk:runtime:test
./gradlew :pipeline-application:test \
    --tests "*CapabilityRegistration*" --tests "*M1DCrossJvm*"

# Build de la candidata:
./gradlew :pipeline-release:candidateAdmission \
    -Pcandidate.sequence=1 -Pcandidate.tag=v0.51.0-rc1
```

Resultados (targeted subset, cada costura M3+M2+M1+M1-F; **0 failures, 0
errors** en cada fila):

| Test class | Casos | Resultado | Módulo |
|---|---:|---|---|
| `OutputReadDigestedAdapterTest` | 5 | PASS | `:pipeline-output-store` |
| `OutputPinPortAdapterTest` | 10 | PASS | `:pipeline-output-store` |
| `PruneAuthorisationAdapterTest` | 5 | PASS | `:pipeline-output-store` |
| `OutputRefusalClosedTest` | 4 | PASS | `:pipeline-output-store` |
| `RecoverRefusalPinExtensionTest` | 3 | PASS | `:pipeline-runtime` |
| `RuntimeIntrospectionPortAdapterTest` | 8 | PASS | `:pipeline-runtime` |
| `RuntimeControlPortAdapterTest` | 8 | PASS | `:pipeline-runtime` |
| `RuntimeRecoverPortAdapterTest` | 8 | PASS | `:pipeline-runtime` |
| `RuntimeRecoverDecisionTableFitnessTest` | 12 | PASS | `:pipeline-runtime` |
| `EventRecordReadPortAdapterTest` (5 nested classes) | 17 | PASS | `:pipeline-events-store` |
| `SegmentOutputFollowerTest` | 18 | PASS | `:pipeline-output-store` |
| `EventFollowerAdapterTest` | 18 | PASS | `:pipeline-events-store` |
| `M1DCrossJvmFollowTest` (UAT-PK-M1-001..006) | 6 | PASS | `:pipeline-application` |
| `CapabilityRegistrationTest` (5 casos: contract SHA + 8 capabilities + 3 audit tables) | 5 | PASS | `:pipeline-application` |
| `M1F1KotlinCompilerUnsafeEliminationTest` | 5 | PASS | `:pipeline-scripting-kotlin24` |
| `M1F2TimeoutDiagnosticsTest` | 6 | PASS | `:pipeline-step-sdk:runtime` |
| `M1F3ChannelSealingTest` | 8 | PASS | `:pipeline-output-store` |
| `M1F4ConsoleDiagnosticsUxTest` | 3 | PASS | `:pipeline-scripting-kotlin24` |
| **Total targeted** | **149** | **PASS** | — |

Digest de los assets publicados (build A, los que verá el harness tras
re-descargar):

| Asset | SHA-256 |
|---|---|
| `pipelinek-0.51.0.zip` | `4acf1a6c2f09e406b9e15d184470045c5b6249d5362fba129bacc4d7dd34f450` |
| `pipelinek-0.51.0.sbom.json` | `50001f30191e00a30e6b0f1682a18bdf15fa458028bee2753b2c38d63441fff3` |
| `candidate-handoff.json` | (registrado en el recibo `release-receipt.md`, derivado del ZIP) |
| `distribution-manifest.json` | (registrado en el recibo `release-receipt.md`, derivado del ZIP) |

**N/N-1 matrix y feature fallback tipado:**

- N = release de PK que adopta las ocho capacidades
  (`v0.51.0-rc1` y siguientes hasta nuevo breaking). Las dos capacidades
  M1 son **PUBLICADA**; las tres M2 y las tres M3 son **EXPERIMENTAL**.
- N-1 = releases anteriores (`v0.50.0-rc1`, `v0.49.0-rc1`,
  `v0.48.0` GA, etc.). Permanece bajo el contrato
  **"ausencia => fallback o refusal"** sin cambios: la rama de
  negociación debe seguir tratando la ausencia de las tres capacidades
  M3 como `Refused` (vía `OutputRefusal.RetentionGap` /
  `OutputRefusal.Corrupt` / `OutputRefusal.Unavailable` /
  `OutputRefusal.RangeLostRetention` /
  `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` /
  `PruneAuthorisation.Refused`), nunca como LIVE implícito.
- Cliente Fabric anterior × PK nuevo: la rama de negociación de Fabric
  debe aceptar la presencia de las ocho capacidades (M1+M2+M3) cuando el
  `WorkerHello` / manifest del peer PK incluya `product_version >=
  0.51.0`; sin esa actualización el cliente Fabric seguirá disparando
  el fallback tipado y por tanto será **no-certificado** para el par.
- Cliente Fabric nuevo × PK anterior: la rama de negociación de Fabric
  debe detectar la ausencia de las tres capacidades M3 y mantener
  `Refused` para los clientes que consumen PK N-1; ningún consumidor
  nuevo se rompe por la adopción.
- Transición de `EXPERIMENTAL` a `PUBLICADA` para las tres capacidades
  M3: ocurre en el contrato tras la certificación `CERTIFIED` por
  `pipelinek-release-harness`. Hasta entonces, los consumidores que
  adopten `v0.51.0-rc1` deben tratar las capacidades M3 como
  negociables pero todavía no estables.

**Petición concreta al consumidor + criterio de aceptación:**

1. **Actualizar `Rubentxu/pipelinek-fabric/docs/fabric-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`**:
   - Mantener la sección 6 (Compatibilidad y negociación) tal como
     está en la versión PK actual; esa sección ya registra las ocho
     capacidades con sus estados (M1 PUBLICADA, M2+M3 EXPERIMENTAL)
     desde este handoff.
   - Añadir la tabla "Capacidades publicadas (CRIC-M3)" reflejando los
     SHAs canónicos (los de `coordination/INTERFACE_CONTRACT.md` en PK,
     con `CONTRACT_SHA256.txt = 233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64`).
   - Recalcular y versionar `coordination/CONTRACT_SHA256.txt` para
     que el SHA del documento refleje el estado alineado en ambos
     repos.
2. **Actualizar la rama de negociación de Fabric** para reconocer
   `output.read.digested.v1`, `output.pin.v1` y
   `output.refusal.retention.v1` como capacidades ofrecidas (marcadas
   como EXPERIMENTAL) cuando el `WorkerHello` / manifest del peer PK
   incluya `product_version >= 0.51.0`. Mantener el comportamiento de
   `Refused` cuando la versión sea `< 0.51.0` y la capacidad esté
   ausente.
3. **Criterio de aceptación** (lo que el Integrador verificará antes
   de `PAIR_CERTIFIED`):
   - `coordination/CONTRACT_SHA256.txt` de Fabric == el de PK
     (`233495aef8bb3e107e6ed156013f6eb8db5664649a9802ddab24d18135c91a64`).
   - La rama de negociación de Fabric en su build canónico (HEAD
     publicado + este cambio) pasa la batería de UAT cruzada PK ×
     Fabric sobre los bytes `sha256:4acf1a6c…` re-descargados.
   - No hay refactor de la rama de negociación que asuma capacidades
     presentes en PK N-1.

**Rollback de schema:** no aplica. CRIC-M3 no introduce migraciones de
schema; introduce capacidades (tres, todas como EXPERIMENTAL) y
extiende dos ADTs existentes (`OutputRefusal` recibe cuatro casos
nuevos; `RecoverRefusal` recibe un caso nuevo — ambos aditivos, no
replace). Un consumidor que rechace la nueva versión sigue funcionando
contra PK N-1 con la rama de negociación original y la matriz N/N-1
documentada arriba.

**Estado:** `PREPARED`. Pendiente para `PAIR_CERTIFIED`:

1. `pipelinek-release-harness` devuelve `CERTIFIED` sobre los bytes
   `sha256:4acf1a6c…` de esta candidata. Tras `CERTIFIED`, las tres
   capacidades M3 transicionan de `EXPERIMENTAL` a `PUBLICADA` en el
   contrato (no requiere re-edición; el cambio es de estado, no de
   filas).
2. Fabric acepta este handoff y publica su propio release con la rama
   de negociación actualizada.
3. La matriz cruzada PK × Fabric pasa sobre los bytes certificados.

---

No declarar release de pareja con este documento; es un handoff de
proveedor. La promoción del par de releases la decide el Integrador
tras `PAIR_CERTIFIED` mutuo entre PK × Fabric.
