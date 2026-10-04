# S4-R1 F1-B — Una sola autoridad durable: D-4 cerrada por eliminación

**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` · **ADR:** `ADR-S4-R1` §4
**Base:** `9966b9977b9cf2336bceb949966c7e96e86a2c77` · **Rama:** `s4-a1b-scripted-shell-spine`
**Veredicto:** D-4 **CERRADA**. `implementation conformance` pasa de «una deuda» a «dos
manifestaciones de la misma ley», ambas de F1-C.

---

## 1. La decisión, y por qué eliminar en vez de declarar excepción

ADR-S4-R1 §4 declaraba `JournaledScriptedOperationRuntime` como **segunda autoridad durable** en
`src/main`. Declararla era correcto entonces; mantenerla ya no lo era, y la pregunta que había que
contestar **antes** de escribir código era una de ownership, no de estilo:

> ¿esta clase es API/ABI publicada? Si lo es, se convierte en shim delegante. Si no lo es, se
> elimina, y la compatibilidad no es un argumento para conservarla.

**Hecho medido, no supuesto:** `v2/pipeline-application/build.gradle.kts` **no** declara
`maven-publish`. Sólo lo publican `pipeline-domain` y `pipeline-scripting-api`, y los cuatro módulos
que dependen de `pipeline-application` — `pipeline-architecture-tests`, `pipeline-release`,
`pipeline-testkit`, `pipeline-step-sdk/junit` — son internos del repositorio.

La clase no era API ni ABI publicada. **La regla aplicable era eliminar.** Un shim habría sido
complejidad conservada a cambio de nada.

Descartada también, por contradicción directa con el ADR: la opción de «reconocer a
`JournaledScriptedOperationRuntime` como una autoridad durable con ownership propio». Si el ADR
aceptado dice *single authority*, esa clase no puede tener ownership propio y seguir siendo
consistente con él.

## 2. Lo eliminado: 357 líneas de `src/main`

| pieza | líneas | qué poseía que no le correspondía |
|---|---|---|
| `JournaledScriptedOperationRuntime.kt` | 270 | `Fingerprint.compute(input, SCRIPTED_SHELL_STEP_ID, ReplayPolicy.MEMOIZED, ATTEMPT)` hardcodeado, tabla de replay propia, status propio |
| `DurableScriptedOperationReconciler.kt` | 87 | segunda ruta de recovery scripted |
| puerto `RunningScriptedOperationReconciler` + `ScriptedRunningResolution` | — | el gancho por el que una segunda autoridad podía volver a enchufarse |

El puerto merece párrafo propio, porque su eliminación no es hygiene. Con las dos clases fuera,
quedaba con **cero** consumidores de producción. Eso es peor que código muerto: código muerto
compila, ensucia y no engaña; un puerto vivo en `src/main` con forma de parte del contrato es una
invitación con firma. `ScriptedRuntime.kt` conserva ahora el KDoc que explica la retirada y por qué
no debe volver.

## 3. Los 5 sitios migrados, ninguno relajado

| sitio | antes | después |
|---|---|---|
| *reuse* | `JournaledScriptedOperationRuntime` | `ScriptedRegistryInvoker` vía `RegistryScriptedShellRuntime` |
| *divergencia* | ídem | ídem |
| *procedencia* | lector JSON interno de la clase eliminada | **más fuerte**: lee `durableFailure` del **cable durable**, decodificado por el `outputCodec` del `StepContract` tomado de `registry.definition(CoreShellStep.KEY)` |
| *reattach* | `DurableScriptedOperationReconciler` | crea una fila real, la clona a `RUNNING`, e inyecta la observación por el fixture extendido |

El caso de procedencia merece subrayarse por qué es una **mejora** y no un trasno. El lector JSON que
usaba la fila original existía **sólo dentro de la clase eliminada**: la fila certificaba una
serialización que nada más en el repositorio podía producir ni consumir. La fila migrada lee la
misma información del journal real, decodificada por el mismo `CoreShellCodec` que usa un `core.sh`
de verdad. Eso es lo que «sobrevive a la replay terminal» tiene que significar si ha de significar
algo durable.

`ScriptedScopeTest` queda en **13 tests, 0F/0E**. Ninguno se eliminó para hacer verde el bloque.

### 3.1 El fixture extendido, y por qué la extensión era la alternativa

`ScriptedInvokerFixture.build` gana `runningSubprocessRecovery: RunningSubprocessRecovery? = null`,
que **sustituye la observación, nunca la decisión**. El criterio: extender la autoridad que ya posee
el caso, frente a cablear un segundo invoker a mano — que es justo lo que el KDoc del fixture
advertía.

`shellSpine` se extendió con `resultFor` y devuelve `Triple<invoker, journal, registry>`.
`resultFor` va **primero** a propósito: añadirlo al final re-enlazaba silenciosamente las llamadas
`shellSpine { launches += 1 }` a `resultFor` y fallaban por tipo de retorno. Un cambio de aparejo que
rompía llamadores que nadie tocó — registrado porque el error que produce no señala su causa.

## 4. La ley que queda defendida: `SingleDurableAuthorityFitnessTest`

4 tests. Ninguno comprueba que una clase no exista **por su nombre** — eso es un test de presencia
frágil. Los cuatro comprueban propiedades que un refactor podría violar sin borrar nada:

| # | ley | por qué no es un nombre |
|---|---|---|
| 1 | ninguna de las cuatro piezas retiradas se **declara** en producción | cierra la puerta a reintroducirlas con el mismo nombre **o con otro** |
| 2 | sólo la autoridad de reconciliación pide una decisión de replay | `effectReplayPolicy.decide(` aparece en **exactamente 1** fichero productivo (`DurableInvocationResolver.kt:129`); una segunda tabla no podría aparecer sin delatarse |
| 3 | la superficie scripted nunca **lee** un status durable para decidir | en `/application/scripted/`, ninguna forma de comparación; sólo se **escribe** `OperationStatus.PENDING` |
| 4 | ninguna superficie scripted hardcodea una `ReplayPolicy` | el `MEMOIZED` embebido era la firma exacta de la autoridad duplicada |

Los cuatro leen **código sin comentarios**, vía `codeOnly()`, que borra KDoc y comentarios de
línea/bloque respetando literales de cadena. Sin esa stripping el propio KDoc de la pieza retirada
satisfaría la ley 1, y el fitness no distinguiría código de prosa.

## 5. Una ley mía era falsa, y el fitness la rechazó

El primer intento de ley 4 fue capar el número de call sites de `Fingerprint.compute` a ≤3, leyendo
el constructor puro como una señal de autoridad duplicada. **Falló con 6**, y los 6 son legítimos:
es un valor constructor, y ninguno **decide**.

| call site | qué construye |
|---|---|
| body loop | fingerprint de una operación de body en loop |
| body simple | fingerprint de una operación de body simple |
| parallel aggregate | fingerprint del agregado parallel |
| step journal | fingerprint de la fila de journal del step |
| comparación del resolver | identidad con la que el resolver compara |
| adaptación scripted | identidad de la superficie scripted |

Un umbral normativo inventado habría sido un defecto disfrazado de ley — y habría producido un
fitness que se puede hacer verde **borrando trabajo legítimo**. Se reemplazó por las cuatro leyes
reales de §4. Registrado porque el modo de fallo «convertir una observación en un número umbral» es
recurrente en este repositorio.

## 6. Mutación `M-F1-B` — `KILLED`, con atribución 1:1

| | |
|---|---|
| diana | `ScriptedRegistryInvoker.kt:389` |
| pre-hash | `c5193a759dbc03ecfc7714755b0d0f815006076047567567ac25e3d247659a66c03` |
| cambio | `input, stepId, metadata.replayPolicy, ATTEMPT` → `input, stepId, ReplayPolicy.MEMOIZED, ATTEMPT` |
| ley que mata | **4** — `no scripted surface hardcodes a replay policy` |
| resultado | 4 tests, **1 failed**, 0 errores, 0 `^e: `, `BUILD FAILED in 3m 58s`, EXIT=1 |

Las otras tres leyes quedaron **GREEN** bajo la misma mutación, lo que es la atribución 1:1 que se
exige: la mutación voltea exactamente la fila que dice voltear.

**Restauración.** Backup validado por hash **antes** de tocar el target; restauración con `cp --`;
exigencia de `restored_hash == pre_mutation_hash`; `sha256sum -c` desde la raíz del repo →
`La suma coincide`. **Sin** `git restore` ni `git checkout` — el snapshot correcto de este árbol
incluye trabajo legítimo no commiteado que un `restore` habría destruido.

Re-verificación posterior a la restauración: 4/4 GREEN, EXIT=0, 0 `^e: `, XML fresco.

### 6.1 Dos falsos hallazgos que produzco yo, y cómo se detectan

Merece registro, porque los dos habrían creado un informe falso si no se contrastaran:

1. **`sha256sum -c` falló** con `No existe el fichero o el directorio` — la `.sha` guarda ruta
   relativa al repo y se ejecutó desde el directorio de logs. Fallo de ruta, no de hash; la
   comparación directa de hashes ya lo demostraba.
2. **`grep -rn "ReplayPolicy.MEMOIZED" src/main` devolvió 30 coincidencias** y gritó «residuo
   encontrado». Falso: el alcance del grep era todo `src/main`, e incluye los **sitios legítimos de
   declaración** (`replayPolicy = ReplayPolicy.MEMOIZED` en cada `StepDescriptor` — eso *es* la
   autoridad) y KDoc. La ley 4 está acotada a `/application/scripted/` y sólo a código. Las dos
   ocurrencias que quedan dentro de ese ámbito son texto de comentario, demostrable por sus
   marcadores ` * ` y `//`.

El Fitness no suffered ninguno de los dos: su `codeOnly()` **borra** el bloque de comentario
(`i = end + 2` sin `append`), donde un stripper ad-hoc que lo sustituye *en el sitio* lo preserva.
La lección es que un verificador escrito a mano para «comprobar al verificador» es exactamente la
herramienta que introduce el error que dice cazar.

## 7. Reencuadre de la fila de reattach — hueco canónico actual, no ley

Esta sección existe porque el primer enunciado de la fila era **incorrecto**, y un recibo que
certifica una_capacity inexistente como comportamiento deseado es peor que un recibo ausente.

**Lo que decía:** «un terminal recuperado no lleva salida codificada, así que no hay `42` que
devolver; materializar un valor sería fabricarlo».

**Lo correcto:** el spine canónico actual **no materializa todavía** el valor tipado de una
operación recuperada. El substrate conserva hechos suficientes para **algunos** return modes — el
`exitCode` de `STATUS` es el ejemplo — pero esa evidencia **se estrecha antes de alcanzar la
proyección Step-specific**. Un `exitCode` observado **no** es un valor fabricado. Eso es un hueco, y
lo posee F1-C.

La distinción que la fila vuelve afilada, y que es la frontera entre fabricar y conservar:

```text
terminal SIN hechos de valor   →  fallar cerrado es CORRECTO
  LOST, sin exit code observado     el substrate nunca dijo qué calculó el programa.
                                    Status(0) / "" / Unit sería fabricación.
  TIMEOUT sin salida capturada

terminal CON hechos de valor   →  fallar cerrado es un HUECO CANÓNICO ACTUAL
  Exited(exitCode = 42)            el exit code está en el control dir. Observado.
  Exited(0, capturedStdout="abc")  el fichero de salida está en el control dir. Observado.
                                    sh(returnStatus=true) con salida 42 es Status(42) · Success,
                                    y la autoridad que lo sabe ya existe:
                                    classifyShellTerminal(terminal, returnMode).
                                    Fallar cerrado aquí pierde returnMode ANTES de que llegue
                                    a la autoridad que puede interpretarlo.
```

La fila de `ScriptedScopeTest` inyecta deliberadamente `Lost` — el caso correcto — y su mensaje de
aserción **dice en voz alta qué caso no certifica**. Una fila que inyectara `Exited(42)` y afirmara
el mismo fallo tipado estaría certificando un defecto como comportamiento deseado.

`ScriptedRegistryInvoker` tenía el mismo enunciado falso en su KDoc de clase y en el mensaje de
fallo que el usuario lee. **Corregidos ambos en producción**, porque un KDoc que enuncia una ley
falsa deja de avisar al siguiente que lo lea.

## 8. Evidencia de gate

### 8.1 Gate focal de F1-B — verde

```text
cd v2 && ./gradlew :pipeline-application:test :pipeline-architecture-tests:test \
    --tests '*scripted*' --tests '*Scripted*' --tests '*durable*' --tests '*Durable*' \
    --tests '*Recovery*' --tests '*Reconciliation*' --tests '*Replay*' --rerun-tasks
EXIT=0   ^e: 0   BUILD SUCCESSFUL in 30m 36s   arrancado 10:15:04, fin 10:45:41
```

| módulo | clases | tests | F | E | skips | XML (freshness) |
|---|---|---|---|---|---|---|
| `pipeline-application` | 302 | 2269 | 0 | 0 | 121 | 10:45:41 > arranque |
| `pipeline-architecture-tests` | 15 | 61 | 0 | 0 | 0 | 10:15:19 > arranque |

Verificado **por nombre y frescura de XML**, no por exit code: `SingleDurableAuthorityFitnessTest`
4/4 y `ScriptedScopeTest` 13/13, ambos con XML posterior al arranque del gate. `compileTestKotlin`
figura `UP-TO-DATE`, lo que es legítimo: la compilación de este árbol ya se había producido tras
las ediciones de F1-B, y `^e: 0` confirma que no hubo error de compilación que pudiera hacer correr
una clase vieja.

### 8.2 Verificación del reencuadre de §7

El reencuadre tocó dos ficheros de producción (`ScriptedRegistryInvoker.kt`: KDoc de clase y mensaje
de fallo) y uno de test (`ScriptedScopeTest.kt`: KDoc y mensaje de aserción). Un cambio de KDoc no
puede romper la compilación, pero un cambio de literal de cadena sí recompila `src/main`, así que
ambos se verificaron sobre el árbol rehecho:

```text
cd v2 && ./gradlew :pipeline-application:test --tests '*ScriptedScopeTest*' \
    :pipeline-architecture-tests:test --tests '*SingleDurableAuthorityFitnessTest*' --rerun-tasks
EXIT=0   ^e: 0   arrancado 11:04:32, fin 11:06:00
ScriptedScopeTest                     tests=13 F=0 E=0  XML 11:06:00
SingleDurableAuthorityFitnessTest     tests=4  F=0 E=0  XML 11:05:12
```

Nótese que esto **no** es evidencia de que el reencuadre sea correcto. Es evidencia de que el
reencuadre no rompió nada. Que el texto sea además *cierto* es un juicio sobre el código, y su
prueba es la matriz de F1-C0, que congela la verdad real de cada return mode antes de tocar
producción.

### 8.3 Lo que este gate NO es

No es el cierre de F1. El PRODUCT-GATE sigue `BLOCKED_EXTERNAL` — no hay superficie de CI remota
desde `754ddda0`, y «CI verde» no es una evidencia disponible en este repositorio. El full local
`./gradlew check --rerun-tasks` sobre el SHA de cierre de F1 es lo que sustituye a esa superficie, y
se ejecuta **una sola vez**, al final del bloque, no por sub-slice.

## 9. Lo que F1-B NO cerró

| # | por qué no aquí |
|---|---|
| **R14** — `CommonExecutionResult` → `StepOutcome` | execution carrier narrowing; F1-C3 |
| **recovery evidence narrowing** — hechos del terminal → terminal semántico demasiado pronto | F1-C1 + F1-C2, bajo ADR-S4-R1 §2.7 |
| **D-1** `ReattachWindowExpired` → reconciliación no terminal | `DEFERRED`: cambiaría el resultado observable de un caso certificado |
| **D-2 / D-3** terminal semántico durable de `Unstable` | `DEFERRED`: tocaría esquema y serialización |
| formato/schema/protocolo durable | F1 no lo cambia |

`implementation conformance` sigue siendo `PARTIAL`, y ahora dice **por qué** con dos filas
nombradas en lugar de una lista de tareas sueltas. La conformidad no mejoró: cambió de dibujar una deuda sin nombre a dos deudas con dueño.

## 10. La diferencia que F1-B deja

Antes: producción contenía dos autoridades de reconciliación, una de ellas inalcanzable pero
presente, con su propia tabla de replay y su propio status, y cinco tests certificándola.

Ahora: **una** autoridad, un fitness que la defiende por propiedades y no por nombres, y los cinco
sitios de test apuntando al spine canónico — uno de ellos más fuerte que antes, porque lee el
cable durable en lugar de un lector que sólo la clase retirada podía producir.

Lo que sigue abierto no es una lista de trabajo: es **una ley con dos manifestaciones pendientes**, y su
dueño es F1-C.
