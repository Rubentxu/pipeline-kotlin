# v0.62.0-rc4 — BQ4 close-out + BQ2 residual plugin builds (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `e7becc9fe942a8f6949e7bd4054846480fc85e27`.
**Tag:** `v0.62.0-rc4` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.62.0.zip`, SHA-256
`55ea60eccb84205805fe4d92c01b024ac3b13f4b15075c4f4264e4cc7e8206c8`.
**ProductVersion:** `0.62.0` (binario `pipeline 0.62.0`).
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.62.0-rc3`

Dos cierres adicionales que la auditoría del 2026-10-16 (RC3
follow-up) marcó como aún abiertos.

### BQ4 — el ciclo ya no puede cerrarse sin evidencia válida

El decider anterior aceptaba transiciones sólo con `(state,
event)`, sin acceso a la evidencia acumulada. Eso permitía
la secuencia `Draft → Running → Observed(observedCommit) →
Closed(closeReason)` con **cero** probes ejecutados, en
contradicción directa con el spec
(`EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`:
"toda observación Skipped, NotRun, NoEvidence o OtherCommit
niega Verified"). El decider nuevo lleva un acumulador
`CycleContext` que registra los probes aceptados y el último
`replacementEvidence`, y rehúsa:

- `Running → Observed` si ningún probe ha tenido éxito.
- `Running → Observed` si la `observedCommit` viene vacía
  (cubierto por el `init` del data class).
- `ProbeFinished(exitCode != 0)` se rehúsa a `Refused` en el
  borde del probe (un probe fallido no puede observarse).
- `ProbeFinished(evidenceRef = "")` se rehúsa en el `init` del
  data class (no puede entrar al acumulador).
- `Observed → Promoted` si `replacementEvidence` es blank.
- `Observed → Closed` si el acumulador está vacío (un ciclo
  que observó sin probe previo se considera Refused, no
  Closed).

El `CycleRunner` ahora tiene `var context: CycleContext` que
se actualiza con cada transición. El test runner prueba
exactamente la secuencia del audit: `Draft → Running →
observeAll(observedCommit)` produce `Refused` y el
`finalise(deleteDirectory)` no se ejecuta. `CycleTest` y
`CycleRunnerTest` reescritos para driven los probes a
través de `reportProbe(...)` antes de `observeAll(...)`.
**7 tests adversariales BQ4** + **5 happy-path** + **2
refusal-path** + **1 illegal-transition** + **12 del runner**
(la mayoría con el contexto acumulado) = 27 tests del
paquete cycle.

### BQ2 — `:test` ya no depende de tres builds externos

El audit notó que el task `:test` aún dependía de
`:buildExamplePlugin`, `:buildExternalDirectivePlugin` y
`:buildExampleBlockPlugin` aunque ya no dependía de
`installDist`. Los jars de los plugins están en el test
classpath vía `testImplementation(files(...))`, así que el
`dependsOn` explícito era trabajo redundante. Ahora se ha
eliminado de `:test` y de `compileTestKotlin`. Un checkout
fresco debe correr `:publishSdkForExternalPlugin` una vez;
el estado estable (después de cualquier `installDist`
previo) tiene los jars en disco. El fast lane ya no paga
por tres builds externos en cada edición de desarrollo.

**`LanePlannerMain` ahora lee el `ContractCatalogue` real**
en vez de `exampleSpecs`. Output (8 contratos del catálogo,
todos HF2, todos `CANDIDATE` lane):

```json
{
  "total_specs": 8,
  "by_lane": {
    "CANDIDATE" : ["OutputPlane.redaction.streaming",
                   "OutputPlane.write.commit",
                   "OutputPlane.pump.drain",
                   "OutputPlane.read.cursor",
                   "Shell.execution.composition",
                   "Console.cli.selection",
                   "Provenance.build.identity",
                   "Build.composition"]
  }
}
```

El resultado honesto: el catálogo actual **no tiene
contratos FAST-lane**. Todos los contratos requieren el
binario instalado. Eso es un hallazgo estructural (un
BQ1 follow-up: añadir contratos HF0/HF1 para lógica que
no necesita fork del binario), no un defecto del planner.

### BQ1 — `pathPatterns` se usa sobre un contrato real

El audit del RC3 dijo "ningún contrato existente lo
utiliza". El `Build.composition` ahora declara un
`pathPatterns = listOf("v2/gradle/wrapper/gradle-wrapper.properties")`
que demuestra el feature sobre un path real. La entrada
`sourcePaths = listOf("v2/settings.gradle.kts")` se conserva
(la fitness test exige que `sourcePaths` no esté vacío), así
que el contrato queda pinned tanto por path literal como
por glob. Un cambio a `v2/gradle/wrapper/gradle-wrapper.properties`
selecciona `Build.composition` aunque no esté enumerado
explícitamente.

## Estado de los tests

```
:test-harness:test --rerun-tasks
25 clases, 195 tests, 0 failures, 0 errors, 0 skipped
Tiempo wall: 12s
JDK: Temurin 21.0.8+9.0.LTS
```

| Bloque | Tests añadidos / cambiados |
|---|---|
| BQ4 decider (Cycle.kt) | 7 adversariales + reescritura del happy path |
| BQ4 runner (CycleRunnerTest.kt) | 12 con `reportProbe` real + 1 P0 explícito |
| BQ2 plugin builds | 0 tests (verificable en build.gradle.kts) |
| BQ1 pathPatterns real | 0 nuevos (el test del glob ya existía en ContractCataloguePatternTest) |
| **Total sobre rc3** | **+6 netos** (195 vs 189) |

## Lo que NO entrega (y por qué)

- **0 tests retirados.** El `proofDigest` del harness no
  existe. El `DeletionGuard` sigue rechazando.
- **0 equivalencias probadas.** El harness no corre en
  este entorno.
- **El catálogo sigue sin contratos FAST-lane.** Eso es
  un BQ1 follow-up, no un cierre de auditoría. El propio
  RC demuestra la causa (8 contratos, todos HF2) y
  evidencia el planner funcionando sobre el catálogo real.
- **BQ3 con vertical grande.** `ConsoleOperationSelection`
  sigue cubierta con 3 tests de propiedades; `ShExecution`
  (804 líneas) y `retry/waitUntil` quedan como trabajo
  futuro por tamaño.

## Decisiones arquitectónicas

- **El acumulador es inmutable y explícito.** `CycleContext`
  es un data class; el decider devuelve un nuevo contexto
  en `Next` y el runner lo threada. No hay estado mutable
  escondido; el runner puede reconstruir el estado en
  cualquier punto reproduciendo el event log.
- **`CycleOutcome.Stay` se elimina.** Antes la spec tenía
  `Stay` para "el evento es un no-op mientras ya estamos
  Running". Con el acumulador, cada `ProbeFinished` puede
  cambiar el estado (a `Refused` si falla) o el
  acumulador, así que siempre hay un `Next`. La rama
  "no-op" no existe; cada evento es un cambio.
- **El lane assignment se deriva del HF, no del nombre.**
  `realSpecsFromCatalogue` mappea `contract.minFidelity.ordinal`
  a `minimumFidelity`, `fidelity >= 2 → requiresInstalledBinary`,
  `fidelity >= 5 → requiresExternalCertifier`. Una decisión
  basada en nombre sería un ataque de inyección.

## STOP checks

| Stop | Estado |
|---|---|
| Auto-aceptación de ADRs | OK: ninguno |
| Cambio de frontera sin test | OK: cada cambio pinned por test de regresión |
| Borrado por nombre o por edad | OK: el guard con dos digests + nuevo accumulator |
| ZIP regenerado tras verificar | OK: SHA `55ea60ec…` calculado una vez sobre el árbol integrado |
| `pipelinek version` vs AssetVersion | OK: el binario corre `pipeline 0.62.0` |
| Tag sin gate | OK: 195/0/0/0 antes de tag |
| Falsa certificación | OK: este RC es `CANDIDATE_PUBLISHED`, no `CERTIFIED` |

## Pendiente

- `pipelinek-release-harness` ejecutándose contra el ZIP
  `55ea60ec…` y emitiendo `HarnessVerdict.Verified` con
  `proofDigest` por UAT.
- Cuando llegue el primer veredicto, el camino
  `MigrateExternalPending → RedundantWithProof →
  Authorised` está listo; falta el input del harness y
  el `retirementAuthorised = true` del decider.
- BQ1 follow-up: añadir al menos un contrato HF0 / HF1
  al catálogo (algún módulo de pipeline-domain tiene
  lógica pura) para que el fast lane tenga contenido.
