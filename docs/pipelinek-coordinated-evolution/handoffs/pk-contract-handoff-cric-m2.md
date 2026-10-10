# PK_CONTRACT_HANDOFF — CRIC-M2 (v0.50.0-rc1)

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED**. No declara release de pareja; es un handoff de
> proveedor. `PAIR_CERTIFIED` lo decide el Integrador tras `CERTIFIED` por
> el `pipelinek-release-harness` y la aceptación por Fabric.

---

**Hito y SHA del contrato / PRs enlazados:**

- Hito: **CRIC-M2** — runtime inspect / cancel / recover sobre los
  puertos públicos segregados de `:pipeline-runtime`, con la matriz
  pura de decisión del recover envuelta por un decider público.
- Rama de release PK: `release/cric-m2-v0.50.0-rc1` (HEAD de release al
  construir el ZIP: `bc5861b70dbffee5e22144c04b488c04bf4825b1`).
- Tag candidato PK: `v0.50.0-rc1` (anotado, peel = `bc5861b7`).
- Prerelease URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.50.0-rc1
- PRs de CRIC-M2 en PK (orden cronológico en
  `release/cric-m2-v0.50.0-rc1`):
  - audit `31de0db9` — M2 INSPECT/RECOVER/CANCEL audit
    (`docs/pipelinek-coordinated-evolution/m2-design/M2_INSPECT_RECOVER_CANCEL_AUDIT.md`).
  - design `8fc563a5` — M2 INSPECT/RECOVER/CANCEL design
    (`docs/pipelinek-coordinated-evolution/m2-design/M2_INSPECT_RECOVER_CANCEL_DESIGN.md`).
  - `7eaea18b` — M2 public types: `RuntimeIntrospectionPort`,
    `RuntimeControlPort`, `RuntimeRecoverPort`, `RuntimeRecoverDecision`,
    y sus `*Refusal` ADTs sellados.
  - `6b8fe207` — M2 production adapters sobre los stores existentes
    (`OperationJournal`, `ReplayCursorStore`, `OutputRecoveryPort`,
    `RecoveredExecutionMaterializer`, `RunExecutionLease` decider).
  - `83d15127` — tests M2 inspect/control/recover — 36 casos contra los
    stores reales; matriz pura de decisión del recover.
  - `d2a423cd` — chore(odd): mark M2 runtime-ports tasks done — full M2
    gate green.
  - `6d2d0c85` — feat(runtime): `Capability` value class +
    `Capabilities` companion con los 5 IDs (M1+M2).
  - `9dfe00d7` — docs(contract): CRIC-M2 capacidades registradas como
    `EXPERIMENTAL` en `INTERFACE_CONTRACT.md` §6 + tabla "Capacidades
    publicadas (CRIC-M2)".
  - `66144066` — test(contract): pin CRIC-M2 capability registration
    (`CapabilityRegistrationTest` ahora 4 casos).
  - `bc5861b7` — chore(release): bump `0.49.0 -> 0.50.0` (CRIC-M2 train
    open).
- SHA del contrato PK
  (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`)
  tras este handoff: registrado en
  `docs/pipelinek-coordinated-evolution/coordination/CONTRACT_SHA256.txt`
  (`79f0aa3facb111d750263e8457956b27a14befa2c1c8f501364ac5b4d7e3e1b5`).

**PK repo:** https://github.com/Rubentxu/pipeline-kotlin
**Fabric consumer repo:** https://github.com/Rubentxu/pipelinek-fabric
**SHA commit PK / branch / tag candidato o remoto:**

- Commit de build: `bc5861b70dbffee5e22144c04b488c04bf4825b1`
- Branch: `release/cric-m2-v0.50.0-rc1`
- Tag candidato: `v0.50.0-rc1` (anotado, peel = `bc5861b7`)

**Artifact PK inmutable:** coordenadas + URL de registry + SHA256 +
método de resolución:

- Coordinates: GitHub release `v0.50.0-rc1`.
- URL: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.50.0-rc1
- Asset principal: `pipelinek-0.50.0.zip`.
- SHA-256 (asset re-descargado):
  `b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d`.
- `candidate_id` (registrado en `candidate-handoff.json`):
  `sha256:b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d`.
- Source commit: `bc5861b70dbffee5e22144c04b488c04bf4825b1`.
- Método de resolución: `gh release download v0.50.0-rc1`; el
  certifier (`pipelinek-release-harness`) reconstruye la imagen desde
  estos bytes, no reutiliza una imagen previa identificada solo por
  nombre.
- ZIP reproducible byte a byte entre dos construcciones independientes
  (`b5a257f0…` en A y B); el SBOM y los manifests no son byte-a-byte
  reproducibles por causas upstream del plugin `org.cyclonedx.bom`
  (timestamp, UUID, orden de `components[]`) — registrado, no
  normalizado.

**Delta ABI compilada (javap):** ninguno. CRIC-M2 publica **capacidades**
(véase la sección siguiente), no cambia la forma binaria publicada. El
paquete `pipeline-application-0.50.0.jar` reemplaza el `0.49.0.jar` con
el mismo set de entry points públicos más las clases públicas del
nuevo módulo `:pipeline-runtime` (`RuntimeIntrospectionPort`,
`RuntimeControlPort`, `RuntimeRecoverPort`,
`RuntimeRecoverDecision`); los consumidores compilados contra la ABI
de `0.49.0` siguen funcionando contra `0.50.0`. La matriz N/N-1 abajo
lo confirma.

**Capabilities / feature ranges negociadas (WorkerHello / manifest):**

| Capability ID | Versión | Publicada en | Estado | Test unitario |
|---|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10), carry-forward en `v0.50.0-rc1` | **PUBLICADA** | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10), carry-forward en `v0.50.0-rc1` | **PUBLICADA** | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) |
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`) |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeRecoverPortAdapterTest` (8 casos, `:pipeline-runtime`) + matriz pura de decisión `RuntimeRecoverDecisionTableFitnessTest` (12 casos, `:pipeline-runtime`) |

El contrato ya registra estas filas en
`coordination/INTERFACE_CONTRACT.md` sección 6 (con el bloque
`> CRIC-M2 (v0.50.0-rc1, 2026-10-10):` adyacente al bloque M1) y en la
tabla "Capacidades publicadas (CRIC-M2)"; este handoff confirma que
la PK de origen está alineada con la tabla canónica del contrato. El
test `CapabilityRegistrationTest` (4 casos, `:pipeline-application`)
pin byte-a-byte la presencia de ambas tablas.

**Identidades / eventos / frames y refusals afectados:**

- `RunId`, `OperationId`, `AttemptId` mantienen el mismo referente a
  través de PK × Fabric (regla 1 del contrato).
- Plano de salida (`output.follow.v1`, carry-forward M1):
  `Open`, `Sealed`, `Unavailable`, `RetentionGap`, `Corrupt` se
  exponen como refusals tipados en `OutputRefusal`. Bytes redactados
  antes de persistir, secuencia de offsets por stream, orden observado
  de frames (regla 2).
- Plano de eventos (`events.follow.v1`, carry-forward M1):
  `UnknownRun`, `RetentionLost`, `Cancelled` se exponen como refusals
  tipados en `EventFollowRefusal`. Idempotencia, rechazo de huecos,
  `Undecodable` preservado, cursor
  `(runId, sequence, hasMore, nextCursor)` (reglas 3 y 5).
- **Inspección (M2, `runtime.inspect.v1`):**
  `RuntimeIntrospectionPort.introspect(runId): RuntimeIntrospectionResult`
  produce un `RuntimeObservation` compuesto (read-only, sin recovery
  destructivo) leyendo `EventHistory.history`, `EventTail.readAfter`,
  `OutputFrameIndex.streamsOfRun` + `framesOfRun`,
  `OutputTailPort.tailState`, `OperationJournal.listForRun` +
  `getEndedAt`, y `ReplayCursorStore.load`. El refusal ADT
  sellado es `IntrospectionRefusal` (`UnknownRun`, `RetentionLost`,
  `Cancelled`, `InMemoryOnly`, `StorageError`). **El introspección no
  invoca recovery destructivo** (regla 5; audit D.1); un test de
  contrato (`IntrospectionDoesNotMutateStateTest`) pin la invariante.
- **Cancel (M2, `runtime.cancel.v1`):**
  `RuntimeControlPort.cancel(runId, reason): CancelOutcome` consulta el
  decider `RunExecutionLease` (puro — **no** el
  `FileBackedRunExecutionLeaseStore` interno-to-PK) y escribe la fila
  terminal vía el journal existente; además sella el output vía
  `OutputSealPort.seal`. El refusal ADT sellado es `CancelRefusal`
  (`AlreadyTerminal`, `LeaseDenied`, `UnknownRun`,
  `ConcurrentMutation`, `StorageError`). Es single-shot, terminal e
  idempotente (regla 5).
- **Recover (M2, `runtime.recover.v1`):**
  `RuntimeRecoverPort.recover(runId, options): RecoverOutcome` envuelve
  el `EffectReplayPolicy.decide` (matriz normativa pinada por
  `EffectReplayPolicyTableFitnessTest`), el
  `OperationJournal.append`/`beginOperation`, el
  `ReplayCursorStore.advance`, el `OutputRecoveryPort.recover` y el
  `RecoveredExecutionMaterializer.materialize`. La decisión pura se
  expone como `RuntimeRecoverDecision.decideRecovery(observation,
  journal): RecoveryChoice` (ver
  `RuntimeRecoverDecisionTableFitnessTest`, 12 casos). El refusal ADT
  sellado es `RecoverRefusal` (`UnknownRun`, `RetentionLost`,
  `IncompatibleJournal`, `RefusedByPolicy`, `StorageError`).

**Reader / recovery contract y retention hooks:**

- `inspect/recover/cancel/follow` mediante puertos segregados
  (regla 5): el módulo `:pipeline-runtime` declara su `api` dependency
  sobre `:pipeline-domain`, `:pipeline-events` y `:pipeline-output`;
  los adapters (que tocan authorities internas como
  `FileBackedRunExecutionLeaseStore`, `RecoveredExecutionMaterializer`)
  viven en `:pipeline-application`.
- Una lectura (`RuntimeIntrospectionPort`) **no** ejecuta recuperación
  destructiva; no relanza efectos externos automáticamente (audit
  D.1; regla 5 del contrato).
- Retención (regla 12): no se poda ningún rango sin prueba de
  transferencia durable y sin pin activo. Los planes
  `OutputPlaneConformanceTest` y `EventSliceParityLawsTest` mantienen
  la invariante; `RuntimeIntrospectionPort` y `RuntimeRecoverPort`
  consultan el journal pero no invocan recovery destructivo desde el
  camino read-only.

**Fixtures reales + cada log de test, comandos, resultados, digest:**

Comandos ejecutados (todos `--no-daemon`, sobre `bc5861b7`):

```bash
# Gate targeted (119 tests, todos PASS):
./gradlew :pipeline-runtime:test
./gradlew :pipeline-events-store:test
./gradlew :pipeline-events:test
./gradlew :pipeline-output-store:test
./gradlew :pipeline-output:test
./gradlew :pipeline-scripting-kotlin24:test
./gradlew :pipeline-step-sdk:runtime:test
./gradlew :pipeline-application:test \
    --tests "*CapabilityRegistration*" --tests "*M1DCrossJvm*"

# Build de la candidata:
./gradlew :pipeline-release:candidateAdmission \
    -Pcandidate.sequence=1 -Pcandidate.tag=v0.50.0-rc1
```

Resultados (targeted subset, cada costura M2+M1+M1-F; **0 failures, 0
errors** en cada fila):

| Test class | Casos | Resultado | Módulo |
|---|---:|---|---|
| `RuntimeIntrospectionPortAdapterTest` | 8 | PASS | `:pipeline-runtime` |
| `RuntimeControlPortAdapterTest` | 8 | PASS | `:pipeline-runtime` |
| `RuntimeRecoverPortAdapterTest` | 8 | PASS | `:pipeline-runtime` |
| `RuntimeRecoverDecisionTableFitnessTest` | 12 | PASS | `:pipeline-runtime` |
| `EventRecordReadPortAdapterTest` (5 nested classes) | 17 | PASS | `:pipeline-events-store` |
| `SegmentOutputFollowerTest` | 18 | PASS | `:pipeline-output-store` |
| `EventFollowerAdapterTest` | 18 | PASS | `:pipeline-events-store` |
| `M1DCrossJvmFollowTest` (UAT-PK-M1-001..006) | 6 | PASS | `:pipeline-application` |
| `CapabilityRegistrationTest` (4 casos: contract SHA + M1/M2 capabilities + audit tables) | 4 | PASS | `:pipeline-application` |
| `M1F1KotlinCompilerUnsafeEliminationTest` | 5 | PASS | `:pipeline-scripting-kotlin24` |
| `M1F2TimeoutDiagnosticsTest` | 6 | PASS | `:pipeline-step-sdk:runtime` |
| `M1F3ChannelSealingTest` | 8 | PASS | `:pipeline-output-store` |
| `M1F4ConsoleDiagnosticsUxTest` | 3 | PASS | `:pipeline-scripting-kotlin24` |
| **Total targeted** | **119** | **PASS** | — |

Digest de los assets publicados (build B, los que verá el harness tras
re-descargar):

| Asset | SHA-256 |
|---|---|
| `pipelinek-0.50.0.zip` | `b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d` |
| `pipelinek-0.50.0.sbom.json` | `65f9f794af143356ae9a864c4ac8912f146ad575b0f31a616f55cb8da40312ce` |
| `candidate-handoff.json` | `d39bd3bd20f59bcb5245fe5771fa8da7edbdfc89fa29c0abbd023bbaa606e7a1` |
| `distribution-manifest.json` | `7edf75ea800eb024d0f9443fc431297f9f55ef7a9a1aeeb771bb1d63720250c2` |

**N/N-1 matrix y feature fallback tipado:**

- N = release de PK que adopta las cinco capacidades
  (`v0.50.0-rc1` y siguientes hasta nuevo breaking). Las dos capacidades
  M1 son **PUBLICADA**; las tres M2 son **EXPERIMENTAL**.
- N-1 = releases anteriores (`v0.49.0-rc1`, `v0.48.0` GA, etc.).
  Permanece bajo el contrato **"ausencia => fallback o refusal"** sin
  cambios: la rama de negociación debe seguir tratando la ausencia de
  `runtime.inspect.v1` / `runtime.cancel.v1` / `runtime.recover.v1`
  como `Refused` (vía `OutputRefusal` / `EventFollowRefusal` /
  `IntrospectionRefusal` / `CancelRefusal` / `RecoverRefusal`), nunca
  como LIVE implícito.
- Cliente Fabric anterior × PK nuevo: la rama de negociación de Fabric
  debe aceptar la presencia de las cinco capacidades (M1+M2) cuando el
  `WorkerHello` / manifest del peer PK incluya `product_version >=
  0.50.0`; sin esa actualización el cliente Fabric seguirá disparando
  el fallback tipado y por tanto será **no-certificado** para el par.
- Cliente Fabric nuevo × PK anterior: la rama de negociación de Fabric
  debe detectar la ausencia de las tres capacidades M2 y mantener
  `Refused` para los clientes que consumen PK N-1; ningún consumidor
  nuevo se rompe por la adopción.
- Transición de `EXPERIMENTAL` a `PUBLICADA` para las tres capacidades
  M2: ocurre en el contrato tras la certificación `CERTIFIED` por
  `pipelinek-release-harness`. Hasta entonces, los consumidores que
  adopten `v0.50.0-rc1` deben tratar las capacidades M2 como
  negociables pero todavía no estables.

**Petición concreta al consumidor + criterio de aceptación:**

1. **Actualizar `Rubentxu/pipelinek-fabric/docs/fabric-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`**:
   - Mantener la sección 6 (Compatibilidad y negociación) tal como
     está en la versión PK actual; esa sección ya registra las
     cinco capacidades con sus estados (M1 PUBLICADA, M2 EXPERIMENTAL)
     desde este handoff.
   - Reflejar la tabla "Capacidades publicadas (CRIC-M2)" con los SHAs
     canónicos (los de `coordination/INTERFACE_CONTRACT.md` en PK, con
     `CONTRACT_SHA256.txt = 79f0aa3facb111d750263e8457956b27a14befa2c1c8f501364ac5b4d7e3e1b5`).
   - Recalcular y versionar `coordination/CONTRACT_SHA256.txt` para
     que el SHA del documento refleje el estado alineado en ambos
     repos.
2. **Actualizar la rama de negociación de Fabric** para reconocer
   `runtime.inspect.v1`, `runtime.cancel.v1` y `runtime.recover.v1`
   como capacidades ofrecidas (marcadas como EXPERIMENTAL) cuando el
   `WorkerHello` / manifest del peer PK incluya `product_version >=
   0.50.0`. Mantener el comportamiento de `Refused` cuando la versión
   sea `< 0.50.0` y la capacidad esté ausente.
3. **Criterio de aceptación** (lo que el Integrador verificará antes
   de `PAIR_CERTIFIED`):
   - `coordination/CONTRACT_SHA256.txt` de Fabric == el de PK
     (`79f0aa3facb111d750263e8457956b27a14befa2c1c8f501364ac5b4d7e3e1b5`).
   - La rama de negociación de Fabric en su build canónico (HEAD
     publicado + este cambio) pasa la batería de UAT cruzada PK ×
     Fabric sobre los bytes `sha256:b5a257f0…` re-descargados.
   - No hay refactor de la rama de negociación que asuma capacidades
     presentes en PK N-1.

**Rollback de schema:** no aplica. CRIC-M2 no introduce migraciones de
schema; introduce capacidades (tres, todas como EXPERIMENTAL). Un
consumidor que rechace la nueva versión sigue funcionando contra PK
N-1 con la rama de negociación original y la matriz N/N-1
documentada arriba.

**Estado:** `PREPARED`. Pendiente para `PAIR_CERTIFIED`:

1. `pipelinek-release-harness` devuelve `CERTIFIED` sobre los bytes
   `sha256:b5a257f0…` de esta candidata. Tras `CERTIFIED`, las tres
   capacidades M2 transicionan de `EXPERIMENTAL` a `PUBLICADA` en el
   contrato (no requiere re-edición; el cambio es de estado, no de
   filas).
2. Fabric acepta este handoff y publica su propio release con la rama
   de negociación actualizada.
3. La matriz cruzada PK × Fabric pasa sobre los bytes certificados.

---

No declarar release de pareja con este documento; es un handoff de
proveedor. La promoción del par de releases la decide el Integrador
tras `PAIR_CERTIFIED` mutuo entre PK × Fabric.