# S4-R1 §3b — la rama de reattach expirada por fin es observable, y lo que dice

**Estado:** `MEASURED` — caracterización del comportamiento actual. No corrige la semántica.
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `b15400335884b7ccac3736d655c9f9c6dde09292` (groundwork del seam en el observer)
**ADR:** `ADR-S4-R1` §2.3 — `ReattachWindowExpired`, política de compatibilidad + decisión diferida **D-1**
**Decisión de ownership:** la autoridad que ya poseía la capacidad es extendida. El observer ya era
dueño del proceso y de su reloj bajo ADR-S4-R1 §1, así que el poll pasa a ser una dependencia que él
declara; la raíz de composición sólo la reenvía. Nadie gana poder nuevo.

---

## 1. Qué se cambió

| fichero | cambio | Δ |
|---|---|---|
| `…/durable/CanonicalDurableRunCoordinator.kt` | `reattachPoll: ((Path, Long) -> Int?)? = null`, reenviado en el punto de composición | **+10** |
| `…/durable/CoordinatorCaps.kt` | `reattachPoll` reenviado desde el bundle de dependencias | +13 |
| `…/durable/RunningSubprocessRecovery.kt` | `pollResult` pasa a nullable; el fallback `null → executor real` vive **dentro** del observer | +19 / −8 |
| `…/architecture/CoordinatorGrowthGuardrailTest.kt` | techo 562 → 572, con la justificación del ratchet | prosa |
| `…/spike/S4RRecIndeterminateEffectSpikeTest.kt` | filas 8, 9, 10 y matriz de 10 filas | test |

**Cero cambios de formato durable. Cero cambios de semántica de producto.** La rama medida sigue
produciendo el mismo terminal que antes; lo único que cambia es que ahora alguien puede verla.

### 1.1 Por qué un tipo función y no el puerto

`CoordinatorCaps` es `public` y `RunningSubprocessRecovery` es `internal`. Nombrar el puerto ahí haría
que una API pública expusiera un tipo interno. La forma `(Path, Long) -> Int?` es la dependencia propia
del observer y está hecha enteramente de tipos públicos.

### 1.2 Por qué el observer es quien resuelve el `null`

La primera versión de este commit ramificaba **en el coordinator** sobre si el llamante había
suministrado un poll, y llegó a 588 líneas. La corrección no fue subir el techo sino preguntarse si el
crecimiento era real: no lo era. El observer ya tenía un default para su poll, así que la rama del
coordinator duplicaba una decisión que no le pertenecía. Mover el fallback dentro del observer borró
la rama, quitó 16 líneas, y devolvió el conocimiento de «cuál es mi poll por defecto» al único
componente que posee el proceso y su reloj.

El techo se sube sobre el **572 corregido**, no sobre el 588. Y la diff es lo que hace que el ratchet
valga: él forzó la pregunta, y la respuesta fue que dieciséis líneas no eran necesarias.

### 1.3 La producción no cambia

Con `reattachPoll == null` — todos los sitios de producción, incluido
`ScriptedFrontendRunner.kt:98`, que sigue usando la forma de dos argumentos — el observer resuelve su
propio default al `DurableShellExecutor` real. Ni la construcción, ni el temporizador de 60 s, ni la
clasificación.

### 1.4 Prosa del ratchet que ya estaba obsoleta

El KDoc de clase decía «552 lines» mientras el valor ya era 562 en HEAD: los incrementos 552→561 y
561→562 actualizaron el valor y el historial, y dejaron esa frase atrás. Se corrige aquí, y el propio
KDoc declara que ya estaba mal. Un guardrail cuya prosa contradice su propio número es un guardrail en
el que nadie puede confiar en la revisión.

## 2. Lo que mide la caracterización

La matriz de `S4-R-REC` tenía siete filas y ninguna alcanzaba el resultado de un `Reattach`. La fila 6
lo afirmaba por escrito: se medía en la CLASIFICACIÓN, no esperando los 60 s del poll. El resto de la
matriz no tocaba esa rama.

Tres filas nuevas, sobre el mismo aparejo, las mismas políticas declaradas, el mismo spine real:

| fila | qué mide | resultado medido |
|---|---|---|
| 8 | `Reattach` + ventana expirada, en el observer | `Recovered(LOST)` |
| 9 | mismo sustrato, terminal **dentro** de la ventana | `Recovered(SUCCEEDED)` |
| 10 | lo mismo, end-to-end por el coordinator | handler no corre, fila `LOST` journalizada |

**El hallazgo.** Un proceso que seguía siendo reatachable, y que puede **seguir vivo**, se
terminaliza como `LOST` — el terminal reservado para un sustrato que se inspeccionó y no tenía nada.
Es exactamente el colapso que ADR-S4-R1 §2.3 nombra `ReattachWindowExpired` y exige que sea un hecho
propio. Consecuencia medida: la fila queda terminalizada, no `RUNNING`, así que una ejecución
posterior bien configurada **no** puede reconciliar una operación que quizá siga en vuelo.

### 2.1 La sustitución del poll no es un stand-in

El valor inyectado (`null`) es el que devuelve la producción cuando se agota la ventana.
`DurableShellExecutor.pollResult` devuelve `null` en dos caminos, y en el fixture de la fila 8 no existe
`result.txt`, así que el camino del `result.txt` malformado es inalcanzable y el deadline es lo único
que puede producir ese `null`. La fila 9 es su control: mismo sustrato, misma rama, dos respuestas del
poll, dos observaciones distintas. Sin ese control, una afirmación de que «el poll decide el terminal»
no tendría contra qué contrastarse.

### 2.2 Límite residual declarado

El camino «`result.txt` presente pero malformado» **no** se testea. Ejercitarlo exigiría reimplementar
el cuerpo de `pollResult` dentro del test, que certifica la reimplementación y no el producto. Queda
registrado como límite, no como cobertura perdida sin nombre.

## 3. Mutaciones

**Ninguna, y es deliberado.**

La HARNESS FIDELITY LAW §5 exige que una afirmación conductual lleve una mutación que la mate, y pone
la condición de que la propiedad esté **abierta**. Aquí no lo está: `ReattachWindowExpired` está
DECIDIDO como política de compatibilidad en ADR-S4-R1 §2.3, y la alternativa no-terminal es la
decisión diferida **D-1**.

Una mutación aquí tendría que matar una aserción que afirma «el código hace hoy lo que el ADR manda que
haga». Eso no protege una propiedad: sólo demuestra que el árbol cambió. Fabricar una mutación que
mutase el terminal sólo para poder decir que hubo mutación sería teatro, y su único contenido sería la
diff de la propia mutación.

**La propiedad sí está protegida, y no por mutación:** la protegen el ADR más este recibo, y el test de
la matriz sigue asertando las igualdades que hacen el colapso visible. La fila 8 es la única de las tres
que puede caer al cerrar D-1, y su mensaje de aserción lo declara hoy: cuando D-1 se cierre, esa
aserción se convierte en el test de no-regresión del cierre, y la transición queda escrita en el
mensaje, no como reescritura silenciosa de lo esperado. Las filas 9 y 10 seguirán verdes porque miden
otra variable.

## 4. Gate

### 4.1 Lo que ya se ejecutó

| evidencia | resultado |
|---|---|
| `:pipeline-application:compileKotlin` + `compileTestKotlin` | `EXIT=0` · `BUILD SUCCESSFUL in 6s` · 0 `^e: ` |
| `S4RRecIndeterminateEffectSpikeTest` | 12 tests · 0 F · 0 E · 0 S — las filas 8, 9 y 10 presentes por nombre, no inferidas por el recuento |
| `HttpInstalledUatTest` + `UatDsl003ParallelTest`, aisladas | 25 tests · 0 F · 0 E — incluidos exactamente `H8-14` y `P6`, las dos que fallaron en el run largo |

### 4.2 Un incidente de proceso que no se contó como evidencia

Un primer run del spike terminó en `Could not stop all services` nombrando
`ParallelBranchConcurrencyDeterminismTest`, una clase que el filtro excluía. El XML de resultados que
se leyó después informaba «9 tests, 0 failures», y **era rancio**:07:26:55, mientras la clase compilada
era de las 07:36:03 y sí contenía las filas 8-10. No se contó. Reejecutado en verde.

La causa fue presión de recursos con otro build vivo de otro checkout (`:rce-proof:test`, en
`pipelinek-runtime-contract-evo`), 2,5 Gi libres y el swap al 100 %.

### 4.3 Dos UAT de distribución instalada que fallaron por `/tmp`, no por este cambio

Un gate focal completo dio tres rojos:

| rojo | veredicto |
|---|---|
| `CoordinatorGrowthGuardrailTest` | **mío, y correcto.** El coordinator había crept de 562 a 588. El ratchet hizo su trabajo. |
| `HttpInstalledUatTest` H8-14 | ambiental — `SQLiteConnection.open: '/tmp/h8-db-…' does not exist` |
| `UatDsl003ParallelTest` P6 | ambiental — `NoSuchFileException: /tmp/uat….stdout` |

Se establecieron como ambientales por control contra tratamiento, no por argumento: el mismo código y
las mismas aserciones pasan aisladas (25 tests, 0 F, 0 E), y el mecanismo está medido — `/tmp` es un
**tmpfs de 48 GB con 26 GB usados y 700 263 de 1 048 576 inodos**, hay **198 ficheros `h8-*` / `uat*`
huérfanos** de runs anteriores, y `systemd-tmpfiles-clean.timer` está **`active`** reapeando por edad.
Un directorio recién creado desaparece de debajo del proceso que iba a usarlo.

Esto es la HARNESS FIDELITY LAW §4 en producción: un aparejo que puede hacer parecer roto a su propio
sujeto. **No es deuda del código de PipelineK y no se arregla aquí.** Se registra como ítem propio,
porque afecta a cualquier gate futuro de distribución instalada y no debe reaparecer como «rojo
raro» dentro de un slice sin relación.

### 4.4 Gate focal final — `PASS`

```text
cd v2 && ./gradlew :pipeline-application:test :pipeline-architecture-tests:test
inicio 08:17:59 · fin 08:46:35 · EXIT=0
BUILD SUCCESSFUL in 28m 35s · 0 "^e: "

pipeline-application          302 clases · 2268 tests · F=0 · E=0 · S=121
pipeline-architecture-tests    87 clases ·  432 tests · F=0 · E=0 · S=10
TOTAL                                   2700 tests · 0 fallos · 0 errores
```

Verificado por nombre y por frescura de XML, no inferido del exit code:

| clase | tests | F/E | edad del XML |
|---|---|---|---|
| `S4RRecIndeterminateEffectSpikeTest` (filas 8-10) | 12 | 0/0 | 76 s |
| `CoordinatorGrowthGuardrailTest` (techo nuevo) | 2 | 0/0 | 2 m 44 s tras el arranque del gate |
| `HttpInstalledUatTest` | 16 | 0/0 | 76 s |
| `UatDsl003ParallelTest` | 9 | 0/0 | 76 s |

`pipeline-architecture-tests:test` aparece en el log **sin** `UP-TO-DATE`, con XML de las 08:20:44
— posterior al arranque del gate — así que el guardrail se ejecutó contra el techo nuevo en lugar de
heredarse de un resultado anterior.

Las dos clases de `/tmp` (§4.3) volvieron a pasar **dentro** del run largo, con 25 tests verdes. Eso refuerza el diagnóstico ambiental: hace una hora el mismo árbol las dejó en rojo, y ahora las
pasa. El árbol no ha cambiado en sus accesos.

Este documento se escribió **antes** de este gate, así que el `.md` no forma parte de lo que aquél
certifica; el código sí, y no cambia entre el gate y esta línea.

## 5. Lo que este documento NO hace

- **No** arregla el colapso. El terminal `LOST` se preserva a propósito, como manda ADR-S4-R1 §2.3.
- **No** cierra D-1. La reconciliación no terminal de un `ReattachWindowExpired` sigue diferida.
- **No** toca el camino scripted. `ScriptedFrontendRunner.kt:98` sigue componiendo el observer con la
  forma de dos argumentos, es decir con el poll real.
- **No** cambia ningún terminal, ni `EffectReplayPolicy`, ni el formato durable.
- **No** reescribe la historia de `S4_R_REC_RECOVERY_OBSERVABILITY_RECEIPT.md`. Ese recibo sigue siendo
  evidencia de su propio SHA, con su matriz de siete filas. Este documento **extiende** esa matriz y
  declara qué filas son nuevas en este SHA.

## 6. Referencias

- `docs/v2/04-adrs/ADR-S4-R1-single-reconciliation-authority.md` §2 (tres estados epistemológicos),
  §2.3 (`ReattachWindowExpired`), D-1
- `docs/v2/07-uat/S4_R_REC_RECOVERY_OBSERVABILITY_RECEIPT.md` — las filas 1-7, evidencia de su SHA
- `docs/v2/04-adrs/ADR-0103-one-replay-authority.md` — R1-E, la corrección de la que esta rama es hija
- `v2/pipeline-step-sdk/runtime/…/DurableShellExecutor.kt` — `pollResult`, el contrato del `null`
- `v2/pipeline-architecture-tests/…/CoordinatorGrowthGuardrailTest.kt` — el ratchet que forzó §1.2
