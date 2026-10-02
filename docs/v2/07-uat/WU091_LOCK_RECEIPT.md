# WU-091 `core.lock` — Recibo de certificación (RP6-A)

> status: RECIPIDO — WU-091 CERTIFIED sobre el árbol `1907c07f` (ver §3)
> cycle SDDK: `p-1f3622e11c093341/rp6a-lock`
> spec: `docs/v2/07-uat/SPEC_WU091_LOCK.md` §7 (9 criterios de salida)
> caracterización: `docs/v2/07-uat/RP6A_LOCK_CHARACTERIZATION.md`
> plan de gates: `docs/v2/07-uat/WU091_G3_G4_IMPLEMENTATION_PLAN.md`

## 1. Qué se certifica

`lock(resource) { ... }` como Step **nacido detrás del registry**: sin decoder legacy,
sin fila en `LEGACY_PLUGIN_IDS`, sin `when(stepKey)` en el coordinador, sin bypass de
admisión. Recorrido completo:

```text
pipeline.kts → StageScope.lock / BranchScope.lock → StepSpec.Lock (IR declarativo)
  → DslCompiledPipelineCompiler → CoreLockWireCodec (autoridad única de wire)
  → BlockStepNode(core.lock) → CoreStepRegistryFactory → admisión fail-closed
  → StepHandler → LOCK_COORDINATION + BODY_CONTINUATION + EXECUTION_LANE
                 + EVENT_SINK + EXECUTION_BUDGET
  → LockCoordinator (FileLockCoordinator, POSIX) → BodyContinuation
```

## 2. Estado de los 9 criterios (§7)

| # | Criterio | Estado | Evidencia |
| --- | --- | --- | --- |
| 1 | Contrato de dominio + codecs con round-trip | CERTIFIED | `CoreLockStepContractTest` (21 casos), `CoreLockWireCodec` simétrico |
| 2 | `LockCoordinator` con adaptador de fichero | CERTIFIED | `FileLockCoordinatorTest` (28 casos) + `LockFeasibilityProofTest`; liberación por muerte de proceso |
| 3 | Registro en `CoreStepRegistryFactory` sin bypass | CERTIFIED | `CoreLockStep.registerInto`; admisión fail-closed probada (contrato + `Lfc2LockWireAuthorityFitnessTest`) |
| 4 | Las cinco filas del spike contra producción | CERTIFIED | `UatLockBlockDurableTest` WL-L1..WL-L9 (§4) |
| 5 | Fila de re-adquisición en reanudación, por mutación | CERTIFIED | WL-L9 + mutación M-§4 (§5) |
| 6 | Cancelación como vía de retorno propia | CERTIFIED | `LockDenialReason.Cancelled` en el puerto + WL-L6 (deadline que acota la espera) |
| 7 | DSL `lock(...)` con compilación positiva y negativa | CERTIFIED | `DslCompiledPipelineCompilerTest` (4 casos nuevos) + `PipelineDslSealedHierarchyTest` |
| 8 | Ratchet del coordinador intacto en 552 | CERTIFIED | `CanonicalDurableRunCoordinator` = 552 líneas; `CoordinatorGrowthGuardrailTest` verde sin tocar |
| 9 | Recibo con SHA exacto | CERTIFIED | §3 |

## 3. SHA de referencia

```text
base   8311f2f8  (G1 d02bcf0f, G2 d18d5e17, L1 8311f2f8)
G3     20389cd2  feat(dsl): lock block surface + CoreLockWireCodec como autoridad única
G4a    6165461e  feat(events): los cinco eventos de la sección 6
G4b    e7343738  fix(lock): registro en producción + deadline que acota la espera
G4c    1907c07f  test(lock): UAT WL-L1..WL-L9 contra la distribución real
docs   <este recibo>
```

Suite de certificación sobre el árbol con esos cuatro commits:

```text
:pipeline-scripting-api:test  :pipeline-events:test
:pipeline-application:test    :pipeline-architecture-tests:test
→ 2021 tests, 0 failures, 0 errors, 121 skipped  (BUILD SUCCESSFUL, 21m07s)
```

## 4. Escenarios UAT (HF2, CLI real, `--db` y `--control-root` compartidos)

| ID | Ley | Resultado |
| --- | --- | --- |
| WL-L1 | Dos runs se serializan; el waiter queda BLOQUEADO (observado dentro del proceso) y su sección crítica cae dentro de la del holder | GREEN |
| WL-L2 | `skipIfLocked` bajo contención: cuerpo nunca ejecutado, run verde, `LockSkipped` observable | GREEN |
| WL-L3 | `lock` anidado en la MISMA lane re-entra (sin deadlock) | GREEN |
| WL-L4 | Fallo del cuerpo ⇒ `LockReleased` observable y el siguiente run adquiere | GREEN |
| WL-L5 | `timeoutSeconds` bajo contención ⇒ `LockAcquireFailed`, cuerpo sin ejecutar | GREEN |
| WL-L6 | `timeout(2s) { lock { … } }` ⇒ el presupuesto acota la ESPERA (no sólo el hijo shell) | GREEN |
| WL-L7 | Ramas hermanas de `parallel` ⇒ secciones críticas disjuntas (orden no contractual) | GREEN |
| WL-L8 | Re-run de un bloque completado no duplica el cuerpo (MEMOIZED) | GREEN |
| WL-L9 | Reanudación tras cuerpo fallido: el lock RE-ADQUIERE (eventos frescos) y el hijo ya exitoso no se duplica | GREEN |

Las tres primeras filas de este cuadro se certIFICARON contra las falsificaciones
que la propia redacción inicial producía, y por eso el código del UAT cambió:

- un hold temporizado es generador de falsos verdes: el arranque del waiter
  (JVM + compilación del pipeline) puede superar al `sleep` del holder; el hold
  ahora espera un centinela de release que controla el test;
- el orden entre ramas de `parallel` no es un contrato: WL-L7 afirma
  disyunción, no "gana la izquierda";
- WL-L1 tenía un deadlock propio: un waiter sin límite sólo termina tras la
  liberación, así que la liberación compitia con la línea de salida del holder;
  ahora son dos fases y es el plazo de la ejecución, no un sleep, el que decide
  el orden.

## 5. Mutaciones

| ID | Mutación | Resultado |
| --- | --- | --- |
| M4..M8 | Decodificación, denegación, owner dentro de `HoldKey` (G1/G2) | RED, según characterization |
| M9 | Owner por run en vez de `ExecutionLaneId` | 6/28 RED |
| M10 | Espera bloqueante en vez de cooperativa | 1 RED (51ms contra intervalo de 50ms) |
| M-§4 | `DefaultEffectReplayPolicy`: RERUN → SKIP en la reanudación | **WL-L9 ROJO, 9 tests / 1 failed** — la fila §4 es load-bearing y la mutación la discrimina de forma quirúrgica (los otros 8 siguen verdes) |

## 6. Defecto real encontrado y corregido en este bloque

**El deadline de bloque no acotaba una espera suspendido.** El motor proyecta el
presupuesto al hijo (`shOptions.copy(timeoutMs = effective)`) y lo hace cumplir
**únicamente vía el watchdog del shell hijo**. `core.lock` no lanza un proceso: la
espera quedaba `Forever`, el lock adquiría el recurso en el instante en que el
holder lo liberaba y ejecutaba su cuerpo **después** del deadline.

Medido (CLI real, `timeout(2, "SECONDS") { lock("db") { … } }`, holder con 60s):
`LockRequested → LockAcquired → StepFailed(TIMEOUT) → LockReleased`, con el cuerpo
ejecutado. WL-L6 fallaba con `B-ran` presente.

Arreglo, sin tocar el motor ni el ratchet: nueva capability estrecha
`EXECUTION_BUDGET_CAPABILITY` (`ExecutionBudget(remainingMs)`) que el bridge expone
desde el mismo `shOptions` que ya consume el watchdog, y cruce puro
`LockIntent.withBudget` que se queda con el más ajustado de los dos. WL-L6 verde.
Cero ramas por `StepKey`; el presupuesto se lee desde la capability, no desde una
decisión por nombre de Step.

## 7. Clasificación G3.6 (sealed hierarchy)

```text
StepSpec.Lock añadido
        ↓
API/ABI check (binary-compatibility-validator, 4 módulos baselined)
        ↓
inventario de consumidores exhaustivos
        ↓
clasificación
```

**SOURCE_ADDITIVE_WITH_EXHAUSTIVE_WHEN_RISK** (binariamente compatible, con riesgo
de source-compat). Tres consumidores reales, los tres encontrados y arreglados:

1. `BlockStepFlattener.flattenImpl` — `when` exhaustivo sin `else` en
   `pipeline-step-sdk:api`: **no compilaba**. Rama de lock añadida (función privada,
   sin drift de ABI).
2. `PipelineDslSealedHierarchyTest` — pin de 31 variantes: 32 + aserción de superficie.
3. `FArchS0SurfaceManifestTest` — gobernanza de superficie: fila de manifiesto +
   entrada en el mapa de builders.

`pipeline-scripting-api` no está bajo `apiCheck`, de modo que el cambio no altera
ningún `.api`.dump baselined; se registra igualmente como adición source-affecting
para la semántica de versión de la release.

## 8. Deuda registrada, no mezclada en este train

- `DEBT-WIRE-AUTHORITY` (backlog `bl-bl-01M3YGH3FE000387X13PG607G0`): `StepSpec.Dir`,
  `WithEnv`, `TimeoutBlock`, `RetryBlock`… siguen serializando wire a mano en
  `DslCompiledPipelineCompiler`. Convergirlos al patrón de autoridad única demostrado
  aquí es otro evolutivo horizontal, posterior a RP6-A.
