# B1 — Composición S4 + M1: recibo de cierre

**WorkItem:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `9deaa9f9` (merge de `m1/output-plane` @ `356eb354` sobre `fa72c901`)
**Rama:** `s4-a1b-scripted-shell-spine`
**Bloque:** B1 del mandate del owner — «cerrar la composición S4 + M1 como una sola plataforma»

---

## 1. Qué cierra este bloque

B1 no añade capacidad de producto. Cierra la **composición**: que M1 (Output Plane) y S4
(spine durable)vivan en una sola línea de código, con una sola autoridad para la salida de
proceso, y que el gate local vuelva a decir la verdad sobre lo que ejecuta.

La premisa de entrada era que el merge de M1 era «cero solape textual». Era cierto y no
suficiente: **el solape real estaba en la capa de observación**, no en la de producción. La
auditoría de seams que cerré antes de este bloque cubrió `src/main` y declaró limpia la
integración. Los tests decían otra cosa. Una seam puede estar intacta en producción y rota en
el lugar donde se observa.

---

## 2. Absorción de M1 (recordatorio de lo ya cerrado)

`9deaa9f9` — merge `--no-ff` de los 8 commits de `m1/output-plane`, 34 ficheros, +5271/−231,
0 conflictos, **SHAs de M1 preservados como ancestros** para que sus recibos sigan anclando a
sus propios commits.

Solape recomputado contra el HEAD real de M1: **cero ficheros comunes** sobre merge-base
`d0fa34e8`. Cuatro seams indirectas auditadas y cuatro propiedades de ownership verificadas
(`settings.gradle.kts`, `pipeline-application/build.gradle.kts`,
`pipeline-output/build.gradle.kts`, `EventViewProjection.kt`).

Eso era cierto y no bastaba. Lo que faltaba era el tercer consumidor: **los tests**.

---

## 3. Los 10 RED: una sola causa, y no era la que parecía

### 3.1 Causa superficial

M1 movió los bytes de salida de proceso del plano de eventos al Output Plane. Diez tests
seguían leyéndolos de `EchoOutputCaptured.content`, un campo que ahora está estructuralmente
vacío para cualquier paso `sh`. Fallaban con **cadena vacía**, no con valor equivocado.

| clase | REDs | aserción que caía |
|---|---|---|
| `S3EnvironmentSemanticWitnessTest` | 4 | `capturedStdout` vacío |
| `ShStepContractSuiteTest` | 2 | C3 stdout observable · C4 stderr observable |
| `Lpr011r2SecretRedactionAtRestUatTest` | 1 | `null registry` esperaba 1, vio 0 |
| `S0SemanticWitnessMatrixTest` | 1 | `sh stdout capture missing` |
| `UatEvt002MultiStepReplayTest` | 1 | `Expected 20 events` |
| `TrapFormNegativeFixtureTest` | 1 | faltaba `bad substitution` |

### 3.2 Causa real, y por qué re-apuntar no bastaba

Reapuntar las lecturas al Output Plane **no las arregló**. Los siete primeros REDsunjaron
`java.lang.AssertionError` desnudo desde dentro del helper, no `AssertionFailedError` de
JUnit. Eso significa que el plano no estaba donde el test creía, y la investigación produjo
dos hallazgos de producto que no son de harness:

#### H-1 · `--control-root` era un flag muerto en la ruta por defecto

`CliParser` parsea `--control-root` y `Main.kt:472` lo honra **sólo en la rama durable**
(`--db` presente). En la rama in-memory — **la ruta por defecto** — `Main.kt:252` hacía:

```kotlin
val controlDirRoot: Path = Files.createTempDirectory("pipelinek-inmem-run")
```

El flag se aceptaba, se validaba, y se descartaba en silencio.

Consecuencia de producto, no de test: desde M1 la salida de proceso vive en el plano
(ADR-M1 D2/D3), así que «dónde se escribe el plano» **es** la pregunta del producto, y
`pipeline console --control-dir X` no podía apuntarse a ningún run de la ruta por defecto. La
superficie de consola del Output Plane era inalcanzable salvo que añadieras un `--db` que
nadie pidió.

**Arreglo:** `Main.kt` honra `config.controlRoot` en ambas ramas, con la misma validación
`validateControlRoot`. El default **no cambia**: sin `--control-root` sigue creando su temp
privado.

**Prueba que lo fija:** fila nueva en `CanonicalInMemoryCliTest` —
`run without a database honours --control-root, so the Output Plane is reachable`. No
comprueba el flag; camina la cadena entera que un consumidor recorre (run por la ruta
in-memory → plano en el control root pedido → lectura por `ConsoleReadService`). Una
mutación que restaure el temp incondicional falla **en la lectura**, que es el síntoma del
que habla el contrato, no en una aserción de ruta incidental.

#### H-2 · Las opciones tras el script path se ignoran en silencio

`CliParser.kt:144`:

```kotlin
while (index < args.size && args[index].startsWith("--")) { ... }
```

El parseo de opciones termina en el primer argumento que no sea un flag, y ese es el script
path. `pipeline run foo.kts --rerun` es indistinguible de `pipeline run foo.kts`: el usuario
cree haber pedido algo que el producto no hizo, y no hay ni error ni aviso.

No he cambiado el parser en B1: es comportamiento de superficie pública de CLI y la decisión
merece su propio bloque. Lo dejo **registrado como deuda** (§7) y todos los llamadores
tocados llevan el flag en la posición que el parser realmente honra, con el motivo escrito
en el sitio.

#### H-3 · El recuento de UatEvt002 era aritmética mía, no una propiedad

`assertEquals(20, events.size)` era la aserción original. Al migrarla la bajé a 16 contando 6
eventos por stage. El dato medido ejecutando el binario instalado sobre el fixture es **18**:
son 7 por stage (`StageStarted` + 3 del `echo` + 2 del `sh` + `StageFinished`), más 3 de
arranque y 1 de cierre. El count se fijó al valor observado, no al calculado. Anotado porque
el error es del tipo que un recibo debe dejar escrito: una timeline recounts desde una
suposición en lugar de desde el hecho.

### 3.3 La regla que hace seguros los reapuntados

`ConsolePlaneProbe` es nuevo (`pipeline-application/src/test/.../support/ConsolePlaneProbe.kt`).
Su regla central:

> **Una lectura que no se puede contestar lanza. Nunca devuelve `""`.**

Una página vacía es una respuesta legítima — el proceso no imprimió nada. Un refusal significa
que el reader preguntó por un stream que no existe. Un helper que colapsa ambos en `""`
reproduce, un nivel más arriba, exactamente el defecto que esta migración existe para
arreglar: un test que no distingue «el producto no hizo nada» de «estoy leyendo donde no es»
pasa cuando debería fallar.

Resuelve la identidad de los streams desde `RunStarted` + `StepStarted` que registró el motor,
no desde posiciones que el test re-cuenta. Eso es una **búsqueda**, no una segunda autoridad:
los bytes siguen viniendo del plano y el cursor sigue siendo el de la página. Es la misma
resolución que `MainConsoleCli` hace para un humano.

---

## 4. Lo que SÍ cambié más allá de los 10 RED (y por qué)

El mandato era «las aserciones NO cambian». Se cumple, pero un RED no era todo: había
aserciones **verdes por la razón equivocada**, que son el mismo defecto esperando a que
alguien mida.

1. **`Lpr011r2SecretRedactionAtRestUatTest`** — ocho `assertEquals(0, eventRawCount(...))`
   leían un canal que ya no lleva los bytes. Para una suite cuya ley entera es «cero bytes
   crudos en una superficie observable», un cero leído de un canal vacío no demuestra
   redacción: demuestra que el reader miraba donde el secreto nunca estuvo. Reapuntados a
   `planeRawCount` sobre el plano.

2. **`Lpr011r2` test 9** (`null registry preserves legacy raw`) pasa a ser el **control
   negativo** de la fila anterior. Sin registry el pump de redacción no está, así que el
   secreto debe sobrevivir crudo. Sin esa fila, los ceros de arriba no significan nada; con
   ella, significan «redacción activa».

3. **`S0SemanticWitnessMatrixTest`** — añadida la aserción inversa: el texto de `sh` **no**
   debe aparecer en `EchoOutputCaptured`, y el texto de `echo` no debe aparecer en el
   transcript del proceso. Un solo sentido deja pasar la mitad de las confusiones.

4. **`S3EnvironmentSemanticWitnessTest`** — el caso de stage omitido ahora afirma también que
   **no hubo `StepStarted`**. Antes, la ausencia de output era corroboración; sola, es
   indistinguible de un reader mal apuntado.

Lo que **no** toqué: los `EchoOutputCaptured` de `core.echo` (`S0` líneas de `never-reached`,
`done`, `after-catch`; `Lpr011r2` test 11). `core.echo` es un Step **semántico** y su texto es
un hecho sobre el run: pertenece legítimamente al plano de eventos. Migrarlos habría sido
equivocación en la dirección contraria. Por eso el fichero nombra los dos accesores por lo
que son (`processOutput` / `echoContents`) y no ofrece uno único.

---

## 5. Verdad del gate local

| objetivo | estado |
|---|---|
| detekt dentro del gate local autoritativo | hecho — `check.dependsOn(detekt)` por módulo |
| referencias a la CI eliminada como autoridad | 0 restantes (era 1, en `build.gradle.kts`) |
| `pipeline-output` en el check agregado | verificado por dry-run (§6) |
| `apiCheck` de módulos publicados | ya cableado en 4 módulos; `pipeline-output` deliberadamente fuera (§5.1) |
| un único comando local de certificación | `./gradlew check` |

El hallazgo de detekt **no se resolvió cambiando el comentario**. El comentario afirmaba que
detekt no estaba en `check` porque lo corría `lpr0-ci.yml`; ese workflow lo eliminó
`754ddda0` (2026-09-30) tras 60 runs cancelados contra runners self-hosted offline. Es decir:
la afirmación era falsa desde hace días y SAST no lo ejecutaba nadie. Se cablea de verdad.

### 5.1 Decisión: `pipeline-output` no entra en BCV todavía

`bcvModules` son los cuatro módulos con superficie JVM ABI estable y **publicada**
(`pipeline-domain`, `pipeline-events`, `pipeline-step-sdk:api`, `pipeline-credentials-api`).
`pipeline-output` es infraestructura interna y su API está en movimiento: B2 va a definir la
política de retención y a tocar esta superficie. Congelarla ahora con `apiCheck` sería
certificar como estable una API que el bloque siguiente va a mover. Se añade cuando la
retención esté decidida y los recibos de B2 la hayan congelado.

Lo que sí está congelado: `StageBody.Scripted` y `ScriptedStageRef` viven en
`pipeline-domain`, que sí está en BCV, y su dump ABI se regeneró en `e0500bf8`.

---

## 6. Veredicto del gate

Comando (leído por exit code directo, nunca por pipe):

```text
cd v2 && ./gradlew :pipeline-output:test :pipeline-application:test \
  :pipeline-architecture-tests:test :pipeline-domain:apiCheck detekt \
  -PexcludeSlowTests=true --rerun-tasks
```

| | |
|---|---|
| `BUILD SUCCESSFUL in 19m 20s` | 114 tareas ejecutadas, **EXIT=0** |
| errores de compilación `^e:` | **0** |
| `FAILED` | **0** |

| módulo | clases | tests | F | E | skipped |
|---|---|---|---|---|---|
| `pipeline-application` | 301 | 2240 | 0 | 0 | 120 |
| `pipeline-architecture-tests` | 90 | 442 | 0 | 0 | 10 |
| `pipeline-output` | 2 | 34 | 0 | 0 | 0 |
| **total** | **393** | **2716** | **0** | **0** | **130** |

Freshness comprobada: los XML de los siete ficheros tocados tienen `mtime` 16:51:18, la hora de
fin de esta ejecución, no de una anterior.

Grafo del gate agregado, medido con `check --dry-run` (490 tareas):

- `:pipeline-output:check` arrastra `:pipeline-output:test` **y** `:pipeline-output:detekt`.
- **25 de 25** módulos de `settings.gradle.kts` aparecen con su tarea `detekt` en el grafo.
- `apiCheck` presente para los cuatro módulos BCV.

### 6.1 Mutaciones

Una afirmación conductual sin una mutación que la mate es caracterización, y la caracterización
tiene que decirlo. Estas dos convierten las filas nuevas en no-vacuas, con atribución 1:1.

| # | mutación | RED atribuidos | veredicto |
|---|---|---|---|
| M1 | `Main.kt` vuelve al temp incondicional (deshace el arreglo de `--control-root`) | 1 de 2 en `CanonicalInMemoryCliTest` — exactamente la fila nueva; la anterior sigue verde | `EXIT=1` |
| M2 | `ConsolePlaneProbe.transcriptsOfSteps` pierde el filtro por `stepType` | 1 de 17 en `S0SemanticWitnessMatrixTest` — exactamente `W-script sh echo` | `EXIT=1` |

M2 merece el detalle: sin el filtro, el probe pide el stream de `core.echo`, que no tiene proceso
y por tanto no existe, y el **refusal** lo delata. Eso es la fila `_core.echo_ != _sh_` del
helper convertida en RED: distinguir «este paso no produce output de proceso» de «este paso
produjo output vacío» es load-bearing, no decorativo.

Restauración verificada por hash (`sha256sum -c`, sin `git restore`), y confirmación en verde de
los dos focales tras restaurar: `POST-MUT EXIT=0`.


---

## 7. Deuda que sale de B1 explícitamente

1. **Flags del CLI tras el script path se ignoran en silencio** (H-2). Owner: `CliParser`.
   No es STOP: no invalida ninguna premisa, es comportamiento público a improves. Decisión de
   su propio bloque, no aquí.
2. **El temp in-memory no se borra.** `createTempDirectory("pipelinek-inmem-run")` deja un
   directorio con `output-plane/`, `leases/`, `last-run/` por cada run. Con `--control-root`
   esto deja de GROWING en el caso por defecto, pero el default sigue filtrando directorios
   temporales. Owner: `Main`.
3. **Cuatro clases pueden seguir verdes-por-canal-vacío** y no las toqué porque su sujeto no es
   la salida de proceso y no hay evidencia de que estén midiendo lo que dicen:
   `UatLocal008CredentialsTest` (24 `sh(`), `UatLocal010SmokeE2ESandboxTest` (14),
   `UatLocal005RegressionGateTest` (3), `WULpr010CliCharacterizationTest` (1). Se auditan en B2,
   que ya tiene el harness de crash/restart real para dar la evidencia.
4. **Fila `C4 empty stderr` marcada como vacua a propósito.** Afirma que stderr vacío no produce
   evento, lo cual desde M1 es cierto porque ese canal no lleva nada. La versión que discrimina
   requiere saber si un capture-mode sin stderr **abre un stream vacío** o **no abre stream** —
   dos contratos distintos (página vacía vs. refusal) que no voy a adivinar. Se cierra en B2 con
   la medición real, y el test lo dice en su propio cuerpo.
