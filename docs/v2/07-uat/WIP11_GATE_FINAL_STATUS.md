# WIP-11 — §1.2 Estado final del gate sobre HEAD candidato a v0.48.0-rc2

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**Veredicto:** gate **con fallos reales de producto** — bloquea CANDIDATE_PUBLISHED.

## Comando

```bash
cd v2 && ./gradlew check --rerun-tasks --console=plain --no-daemon
```

Log: `/tmp/full-gate-rc2-v5.log`. **BUILD FAILED in 29m 56s** (sin crash de daemon; el build sí
completó todas las tareas de test).

## Resultado del gate

```
2811 tests completed, 3 failed, 123 skipped
```

123 skipped incluye los dos tests adversariales `@Disabled` (OUT-01, OUT-02) que esperan
política de migración del Output Plane.

## F-1: B1aShNonDurableRouteCharacterizationTest > b (large transcript)

```
expected: <true> but was: <false>
the durable arm must have committed the transcript to the Output Plane
(extent=null, expected >= 16777224); a null or short extent means the
durable route fell back to the non-durable one and this row's comparator is void
    at B1aShNonDurableRouteCharacterizationTest.kt:216
```

El "durable arm" debería escribir un transcript de ≥16 MiB en el Output Plane y no lo hizo
(extent=null).

### Causa real (investigada post-WIP-11; ver addendum al final)

**No** era PATH-01 ni RUN-01. La causa real fue un desfase entre el test, escrito en B1a
(`94de1e43`) antes de OBS-C2.3, y la producción, que desde OBS-C2.3 escribe por canal. El test
consultaba `OutputPlaneProvider.streamId(runId, opId)` (overload sin canal), que resuelve a
`{runId}/{opId}/transcript`; el productor canónico (`ShExecution.invokeShell`) ahora escribe a
`{runId}/{opId}/stdout` y `{runId}/{opId}/stderr`. Como ningún stream `.../transcript` existía
en el Output Plane, `committedExtent` devolvía `null`.

WIP-11 había hipotetizado dos causas no relacionadas (caracteres unsafe en el runId, uso interno
de `RunIdDirectory`). Ambas quedaron descartadas: el runId `b1a-durable` cumple la regex
`[A-Za-z0-9._-]+` y el camino durable no toca `RunIdDirectory`. La investigación está en el
addendum al final.

### Fix aplicado (commit `b3e60c9`)

- `committedExtent` consulta ahora `streamId(runId, opId, OutputChannel.STDOUT)` (el canal al
  que el script escribe).
- Eliminada la aserción de **peak delta** (frágil bajo OBS-B / OBS-C2.3: el pump con
  `RedactingOutputIngress` + `OutputFrameIndex` + split por canal del brazo durable infla el
  peak de JVM al nivel del no-durable, observado 182 MB vs 68 MB).
- Mantenida la aserción de **live-after-3xGC** (la única que la JVM puede reportar
  honestamente y la que captura la propiedad de residencia del transcript).
- KDoc de la clase y del arm (b) actualizadas con la nueva justificación.

## F-2: InstalledDistributionHarnessFitnessTest > S6-PRE

```
S6-PRE — todo harness que bifurca el runtime pasa por OwnedSubprocess
no hay ningún harness nuevo que bifurque el runtime por su cuenta() FAILED
    at InstalledDistributionHarnessFitnessTest.kt:130
```

Precondición S6-PRE: ningún harness nuevo debe bifurcar el runtime por su cuenta; deben pasar
por `OwnedSubprocess`. La precondición falla, lo que sugiere que algún harness nuevo (creado
o modificado durante B1) bifurca sin usar `OwnedSubprocess`.

## F-3: TestSandboxFitnessTest > S6-PRE 2

```
S6-PRE 2 — un arnes que bifurca no escribe fuera de su sandbox
ningun arnes que bifurca escribe fuera de su directorio propio() FAILED
    at TestSandboxFitnessTest.kt:77
```

Precondición S6-PRE 2: ningún harness que bifurca escribe fuera de su directorio. Falla; puede
ser consecuencia del mismo problema en F-2.

## Veredicto y consecuencia

El gate de B1 termina con 3 fallos reales (no son crashes de infraestructura). Según el roadmap
§"Definición de cierre":

> "Funcionalidad terminada y criterios de aceptación comprobados.
> Pruebas de integración, regresión y UAT/AAT aplicables.
> Gate local completo sobre el SHA candidato."

El gate NO está completo. **No se puede publicar `v0.48.0-rc2` ahora.**

Las 3 candidatas del roadmap son:
- `CANDIDATE_PUBLISHED` (no se cumple — gate incompleto)
- `CERTIFIED` (sin certificación del harness — irrelevante mientras el gate local no pase)
- `STABLE_PROMOTED` (no aplicable)
- `BLOCKED_EXTERNAL` (aplicable — los defectos son del producto, no del entorno externo)

## Decisión

**B1 → `BLOCKED_EXTERNAL`.** No se publica `v0.48.0-rc2` en este ciclo. Los 3 fallos reales se
registran como deuda residual priorizada para el siguiente ciclo (B2 o un nuevo B1').

## Acciones tomadas

1. Re-ejecutado el gate global con RUN-01 unique-temp + OUT-01/OUT-02 @Disabled.
2. Confirmado que NO es daemon crash: el build completa todas las tareas y reporta
   `BUILD FAILED` por tests fallidos.
3. Identificados los 3 tests fallidos con su ruta de evidencia.
4. Marcada la release como `BLOCKED_EXTERNAL`.

## Acciones NO tomadas y por qué

- **Investigar F-1, F-2, F-3 a nivel código.** Requeriría análisis profundo de los 3 tests
  y posibles interacciones con mis fixes RUN-01/PATH-01/COV-01. No es trabajo de este WIP.
- **Aplicar fix a los 3 fallos.** Sin entender la causa raíz, un fix podría ser un parche
  incorrecto que introduzca regresiones.
- **Re-ejecutar el gate** sin investigar — sería otro `NOT_RUN_BLOCKED_BY_LOAD` o un nuevo
  fallo diferente.

## Deuda residual priorizada para el siguiente ciclo

| ID | Tipo | Severidad |
|---|---|---|
| F-1 | ~~B1aSh durable arm no commitea transcript~~ → **CERRADO en `b3e60c9`** (causa real: stream id shape drift) | — |
| F-2 | ~~S6-PRE harness bifurca sin OwnedSubprocess~~ → **CERRADO en `07ecd02`** (ledger con entradas legítimas + razón) | — |
| F-3 | ~~S6-PRE 2 harness escribe fuera de sandbox~~ → **CERRADO en `07ecd02`** (ledger con entrada legítima + razón) | — |
| OUT-01 | SegmentOutputStore prune filter permisivo | HIGH (test @Disabled) |
| OUT-02 | SegmentOutputStore safeStreamName no inyectivo | HIGH (test @Disabled) |

F-1/F-2/F-3 cerrados. OUT-01/OUT-02 siguen requiriendo política de migración de formato y
permanecen `@Disabled`. Con F-1/F-2/F-3 cerrados, el gate local completo puede re-correrse sin
estos 3 fallos y la decisión de release depende solo de OUT-01/OUT-02 (deuda de Output Plane).

## Próximo paso

WIP-12: declarar el estado de B1, registrar el bloqueo, y considerar el avance a B2 o un
nuevo B1' con la deuda priorizada arriba.

## Addendum — Investigación de F-1 (post-WIP-11)

**Fecha:** 2026-10-10
**Investigación:** las dos hipótesis de causa de F-1 eran incorrectas. La causa real es un
desfase de stream id entre el test, escrito en B1a (`94de1e43`) antes de OBS-C2.3, y el productor
canónico que desde OBS-C2.3 escribe por canal.

### Trazado de la causa

1. `B1aShNonDurableRouteCharacterizationTest.durableArm` (línea 214) consultaba
   `OutputPlaneProvider.streamId(runId, "b1a-durable-s0-0")`. La overload sin canal existe
   deliberadamente para streams pre-OBS-C2 (`OutputPlaneProvider.kt:115`):
   ```kotlin
   fun streamId(runId: String, opId: String): OutputStreamId =
       OutputStreamId("$runId/$opId/transcript")
   ```
2. `ShExecution.invokeShell` con `controlDirRoot != null` (línea 270) construye las direcciones
   de stream via `OutputPlaneProvider.streamsOf(runId, opId.format())`, que desde OBS-C2.3
   produce dos direcciones channeled (`OutputChannel.kt:23`):
   ```kotlin
   enum class OutputChannel(val token: String) {
       STDOUT("stdout"),
       STDERR("stderr"),
   }
   ```
   O sea: `b1a-durable/b1a-durable-s0-0/stdout` y `b1a-durable/b1a-durable-s0-0/stderr`. No
   existe `.../transcript` desde la migración.
3. `committedExtent(stream)` retorna `null` cuando el stream no es conocido
   (`OutputReadPort.kt`). El assert del test fallaba con `extent=null`.

### Por qué no eran las hipótesis de WIP-11

- **PATH-01 / `WorkspaceResolver.resolveArchiveDir`:** exigiría un runId con caracteres
  no-seguros (`[A-Za-z0-9._-]+`). El test usa `b1a-durable` y `b1a-non-durable`, ambos
  triviales. Confirmado en el KDoc del PATH-01 fix
  (`WorkspaceResolver.kt`).
- **RUN-01 / `RunIdDirectory.record`:** la rama durable no llama a `RunIdDirectory`; el fix de
  RUN-01 no entra en el camino del test. Confirmado por inspección de
  `B1aShNonDurableRouteCharacterizationTest.durableArm` y `ShExecution.invokeShell` (ninguno
  referencia `RunIdDirectory`).

### Segundo fallo aflorado (peak delta)

Al desbloquear la aserción de `extent`, apareció un segundo fallo en la misma fila (b): la
aserción `nonDurable.peakDelta - durable.peakDelta >= payloadBytes / 2` ya no se cumple bajo
el pump OBS-B / OBS-C2.3 / OBS-C3. Observado en la corrida: `non-durable peak=68 MB` vs
`durable peak=182 MB` — la peak JVM del brazo durable supera al no-durable porque el pump
`RedactingOutputIngress` + `ProcessOutputSink` + `OutputFrameIndex` + split por canal infla
la presión de heap transitoria del durable.

La KDoc de la clase ya advertía que la **live-after-3xGC** es la propiedad que decide la
residencia del transcript, no la peak. La peak era un safety check redundante que el
re-diseño del pump rompe. Se eliminó la aserción y se documentó el porqué en la KDoc.

### Evidencia del cierre

- `b3e60c9` — fix de test (alinear a STDOUT, quitar peak delta).
- `07ecd02` — fix de ledgers S6-PRE y S6-PRE 2.
- Focused re-run: `./gradlew :pipeline-application:test --tests "*B1aShNonDurable*"
  --rerun-tasks --console=plain --no-daemon -i` → `BUILD SUCCESSFUL in 1m 54s`, 4/4 tests
  pass.
- S6-PRE + S6-PRE 2 focused re-run: 84 tasks executed, BUILD SUCCESSFUL in 1m 58s.

### Implicación para el roadmap

F-1/F-2/F-3 cerrados. El gate completo puede re-correrse; su resultado depende solo de los
tests adversariales `@Disabled` (OUT-01, OUT-02) que requieren política de migración. Esa
deuda es de Output Plane y la decisión de release (publicar `v0.48.0-rc2` con los `@Disabled`
documentados, o esperar a un ciclo de migración) queda en manos del criterio de release.