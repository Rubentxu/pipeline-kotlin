# SPIKE S4-R-ID — La identidad de iteración de loop es un contador de llegadas, no un ordinal posicional

**Estado:** `MEASURED` · **cero cambios productivos** · test-only
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` (RP7-SEM S4)
**Base:** `6bb7be44071c94001d68e93339b47a722720212e`
**Pregunta:** *¿la identidad durable que compone producción lleva el índice de iteración, o sólo el número de llegadas al call site?*

**Veredicto: sólo el número de llegadas. AGENTS.md DR-6 está violado en producción, y por tanto ADR-S4-R2 es obligatoria.**

---

## 0. Lo que el spike hace y lo que no

Cero cambio en `src/main`. El test `S4RIdIterationIdentitySpikeTest` (9 casos) cruza las dos mitades
de la autoridad productiva y **no reimplementa ninguna**:

- **mitad compilador:** el `KotlinScriptedSourceMapper` y el `ScriptedSourceLowering` reales, así que
  el `scopeId` que se afirma aquí es el que producción emite;
- **mitad runtime:** el `ScriptedRuntime` → `ScriptedScope.scoped` → `invokeAt`/`nextOrdinal` reales,
  grabando los `ScriptedOperation` que producción compone.

Veredicto de clase: **medición**, nunca certificación.

**No lleva mutación, y es deliberado.** La propiedad estudiada está **abierta, no cerrada**: una
mutación certificaría una afirmación que esta slice no hace. La mutación que debe matar estas filas
es la que I2b tiene que ganarse, y pertenece al slice que cierra el defecto.

---

## 1. El hallazgo, en dos mitades

### 1.1 El compilador emite un scope por SITIO, no por iteración

`KotlinScriptedSourceMapper.visitForExpression` produce un `ScriptedLoopScope` por `for` con cuerpo
de bloque, y `ScriptedSourceLowering.rewrite` lo envuelve así:

```kotlin
steps.scoped(ScriptedDynamicScopeId("loop:<sourceId>:<line>:<col>")) {
    <cuerpo del loop>
}
```

Ese `scopeId` es una **constante evaluada antes de que corra el cuerpo**: no puede llevar índice de
iteración, y el propio `ScriptedExecutionApi.kt:312-318` lo dice — `loopParameter` está
deliberadamente fuera del `scopeId` porque un nombre de parámetro no es único en un fichero.

Medido: `loop:s4-r-id.pipeline.kts:1:1`, sin sufijo de iteración.

### 1.2 El runtime cuenta llegadas, y comparte el contador entre scopes

`ScriptedScope.nextOrdinal` e `invokeAt` usan el mismo mapa:

```kotlin
val ordinalKey = stableScriptedKey(
    listOf(callSiteId.value, dynamicScopePath.size.toString()) + dynamicScopePath,
)
val ordinal = ordinals.getOrDefault(ordinalKey, 0)
ordinals[ordinalKey] = ordinal + 1
```

Y `scoped()` **comparte ese mismo mapa** (`ordinals = ordinals`), así que el contador es de ámbito
de run, con clave `(callSite, scopePath)`.

Como el `scopePath` es **idéntico en cada iteración** —el scope es el mismo sitio de loop— el
ordinal que sale es, literalmente, *el número de veces que se ha llegado a ese call site*. No es
el ordinal de entrada en la iteración.

### 1.3 El discriminante: P5

Dos guiones, un mismo sitio de loop, un mismo call site. En ambos el efecto ocurre en la
**iteración 2** y sólo ahí:

```kotlin
// A: se alcanza en las iteraciones 0 y 2
for (i in 0..2) { if (i != 1) { scoped(loop) { sh("build") } } }
// B: se alcanza sólo en la iteración 2
for (i in 0..2) { if (i == 2) { scoped(loop) { sh("build") } } }
```

Medido: en A la llegada de la iteración 2 es la **segunda** llegada, ordinal **1**. En B es la
**primera**, ordinal **0**. Mismo `scopePath` en ambos. La identidad durable cambia.

**La identidad de un efecto depende de cuántas veces se alcanzó el call site *antes*, no de en qué
iteración se ejecutó.** Eso es exactamente lo que DR-6 prohíbe.

El contraejemplo del brief es la misma forma: `for (i in 0..3) { if (i == 2) sh("only once") }`
produce aquí `sh ordinal = 0`, mientras que una identidad posicional lo situaría en
`loop:<site>[2]`. Las dos descripciones discrepan sobre *qué iteración corrió*, y sólo una lo dice.

---

## 2. Propiedades medidas

| # | Propiedad | Veredicto | Medida |
|---|---|---|---|
| P1 | distinta por iteración | **SATISFECHA** | 3 iteraciones → ordinales `0,1,2` |
| P3 | scope completo antes del primer efecto | **SATISFECHA** | el path completo está en la primera operación |
| P4 | independiente del valor del elemento | **SATISFECHA** | `["x","x","x"]` y `["a","b","c"]` dan la misma secuencia |
| P5 | independiente del número de llamadas al Step | **VIOLADA** | ordinal `1` vs `0` para la misma iteración |
| P6 | los scopes anidados componen | **SATISFECHA** | `outer/inner` × 4 |
| P7 | `continue` no renumera lo anterior | **SATISFECHA, con asimetría** | ordinales `0,1` intactos; ver abajo |
| P8 | `break` no inventa iteraciones | **SATISFECHA** | un solo ordinal, `0` |
| P9 | sin acceso al journal | **SATISFECHA** | identidad completa sin estado durable alguno |
| P10 | misma entrada durable → mismos caminos | **SATISFECHA** | dos ejecuciones ⇒ mismos IDs, mismo orden |

### 2.0 Formas de loop: el hueco que la primera pasada declaró, y era el grande

La primera pasada de este spike (9 casos) declaró que no cubría `repeat`, `repeat` anidado ni bucles
con nombre compartido. Al cubrirlos apareció algo que **empeora el diagnóstico**:

**`repeat`, `while` y `do..while` no reciben scope estructural alguno.** El mapper sobrescribe
exactamente tres visitors — `visitForExpression`, `visitCallExpression`, `visitErrorElement` — y
sólo el primero registra un `ScriptedLoopScope`.

Medido en las dos mitades:

| forma | scope emitido | path de las 3 iteraciones | identidad |
|---|---|---|---|
| `for` | uno, **constante** por sitio | `loop:<site>` (idéntico en las 3) | llegada + scope del sitio |
| `repeat` / `while` / `do..while` | **ninguno** | `[]` raíz, **idéntico en las 3** | **sólo llegada** |

Consecuencia: para un `for`, DR-6 queda violado pero **hay una mitad estructural sobre la que
construir** — el sitio. Para un `repeat`, no hay ni sitio: la identidad es el contador de llegadas
y nada más. Es la violación de DR-6 en su forma más desnuda, y **el alcance de ADR-S4-R2 tiene que
incluirlas o declarar explícitamente que quedan fuera**. Hoy nadie tiene opinión sobre ellas: no es
que el mapper acierte mal, es que nunca se le pidió.

### 2.1 P7 es SATISFECHA pero con una asimetría que conviene no pasar por alto

`continue` no renumera las iteraciones ya ocurridas, que es lo que pedía la propiedad. Pero el
contador **no puede distinguir `continue` de una llegada condicional**: ambos comprimen «qué
iteración» en «qué llegada». Es la misma raíz que P5, vista por otro lado, y por eso la fila lo dice
en su mensaje en vez de declarar la propiedad simplemente satisfecha.

### 2.2 P10 y lo que NO es

Re-ejecutar el mismo cuerpo produce **los mismos `operationId` en el mismo orden**: el mapa de
ordinales se crea fresco por run (`mutableMapOf()`), luego se **re-deriva del script**, no se
recuerda. Eso es la condición **necesaria** para sobrevivir a un restart.

**No es una prueba de restart.** El brief pedía *fresh → kill → recompile SAME compatible artifact
→ resume* comparando el set y el orden de los IDs, y eso exige un proceso forzado. Lo que aquí se
mide es determinismo de reconstrucción **en el mismo proceso**, que es necesario pero no
suficiente. Confundir las dos cosas sería exactamente el salto que la HARNESS FIDELITY LAW
prohíbe en §1.

### 2.3 Por qué el defecto NO es un bug de corrección hoy

P1 muestra que, en una ejecución fresca y determinista, la identidad **sí** es distinta por
iteración. El defecto no es que dos iteraciones colisionen; es que la identidad **no lleva el hecho
que DR-6 exige que lleve**. La distinción importa al fusionar historia, al comparar un run reanudado
contra uno original, y al diagnosticar por qué dos operaciones distintas comparten material de
identidad.

---

## 3. Consecuencia: ADR-S4-R2 es obligatoria — y su nombre ya estaba reservado

El objetivo activo ya la preveía —«el ADR debe salir de evidencia, no de gusto»— y la evidencia
acaba de llegar. **Pero el nombre y el alcance exacto ya estaban escritos**, en
[ADR-0103](../04-adrs/ADR-0103-one-replay-authority.md), cuya sección *Out of scope* nombra
literalmente lo que le toca a esta futura autoridad:

> Loop iteration identity; `nextOrdinal`; `dynamicScopePath` composition; the `while` and
> braceless-`for` laws; `PLUGIN_LOCK_DIGEST`; the `Unstable` outcome carrier. Each has its own
> owner and none of them may be settled by implementing this ADR.

Y su *Addendum to ADR-0093*, aceptado por el owner el 2026-10-03, concluye:

> Production identity is governed by ADR-S4-R2.

**Esto reordena lo que este spike aporta.** Tres consecuencias concretas:

1. **LaPrecondición ya se cumple.** ADR-0103 dice que ADR-S4-R2 «may only be written after
   ADR-0093's overstatement is corrected and the replay semantics above are in force». La
   sobreafirmación está corregida desde el 2026-10-03, y la semántica de replay está en vigor
   desde R1-E (`d0077253`). **No queda ninguna precondición pendiente.**
2. **El hallazgo de `while` no es una sorpresa: es confirmar un scope ya reservado.** ADR-0103
   nombra *the `while` … laws* como trabajo de ADR-S4-R2. Este spike **muestra que ese hueco está
   vacío de verdad**: no es que exista una ley que este spike no encuentra, es que no hay
   ninguna. Eso convierte una incógnita en un hecho medido, que es justo lo que un spike debe hacer.
3. **El spike cubre 3 de los 5 puntos del scope.** `nextOrdinal` y `dynamicScopePath` composition
   son el objeto central de §1-§2. La ley `while` y la `braceless-for` son §2.0. Quedan fuera de
   esta medición: `PLUGIN_LOCK_DIGEST` y el carrier de `Unstable`, que ya tienen su propio recibo.

Los cinco puntos que el brief exigía fijar:

```text
quién produce el índice de iteración   -> la frontera del LOOP, no el facade ni el invoker
cuándo                                   -> computable ANTES del primer efecto (P3 ya lo permite)
qué significa                            -> ordinal de ENTRADA en la iteración
cómo se reconstruye                      -> replay determinista; DR-5 lo hace lawful sin
                                            segundo almacen durable (P9 lo demuestra)
qué lo invalida                           -> cambio del artefacto compatible, per DR-10
```

Y el SPD candidato que el brief nombró queda **medido a favor de D1** (contador lexical en la
frontera del loop) por una razón estructural, no por gusto: D2 (`withIndex()`) reescribe la forma
del iterador sobre Kotlin arbitrario, lo cual es una transformación mucho más invasiva para obtener
un índice que el contador ya disponible en el punto del loop.

### 3.1 Una reserva sobre la reserva

`while` y `braceless-for` tienen unaproperty que `for` no tiene: **no son structuralmente
delimitados por una llave**, así que el lowering no tiene el `bodyStartOffset`/`bodyEndOffset` que
`ScriptedLoopScope` exige hoy. Medir su ley no es sólo decidir qué scope reciben, sino decidir si
el propio `ScriptedLoopScope` necesita otra forma. Eso no se ha medido y no se afirma aquí.

### 3.2 El slice que falta ya tiene nombre

El KDoc de `S4IdentityLoopScopeTest` ya lo dice, y este spike lo confirma:

> *The ordinal remains an arrival counter and remains non-durable across a resume; I2a only makes
> it scoped to an identified loop instead of floating in a global count, which is the structure
> **I2b needs in order to replace that counter with a deterministic occurrence path**.*

I2a (el scope estructural) está entregado. **I2b (el índice posicional) no existe.** Este recibo
confirma que la medición de I2a fue honesta y que la deuda que I2a declaraba es real y medible.

---

## 4. Evidencia

Dos pasadas. La primera (9 casos) cerró el hallazgo de DR-6; la segunda (13 casos) cubre las
formas de loop que la primera declaró y que resultaron ser el hueco mayor.

```text
pASADA 1
cmd    cd v2 && ./gradlew :pipeline-application:test \
                     --tests '*S4RIdIterationIdentitySpikeTest' --rerun-tasks
log    s4rid-spike.log
sha256 49bf3f9808b7193d7a8953c96d4f07928951e9f6e30725cf28474becd27bc6b4
EXIT=0  0 "^e: " · 9 tests · 0 failures · 0 errors · 0 skipped

PASADA 2  (anade repeat / while / do-while, nombre compartido y P10)
log    s4rid-spike2.log
sha256 b78dadb585fedd333ec0cbd90bf12da4d5f3047eb71e3c30ad3b52bf4fab3ae3
EXIT=0  0 "^e: " · 13 tests · 0 failures · 0 errors · 0 skipped
```

Los trece casos nombrados `MEASURED …` están en el XML de JUnit, que da
`tests="13" skipped="0" failures="0" errors="0"`.

Las dos pasadas compilaron de verdad, y eso se comprobó aparte porque un
`BUILD SUCCESSFUL in 3s` sin `^e:` no basta: la clase apareció en
`build/classes/kotlin/test/…/spike/` con 9 y luego 13 métodos públicos. La
segunda pasada además **no compiló a la primera**: dos `()()` en los nombres de
método saltaron como `^e:` reales y se corrigieron antes de volver a ejecutar.

## 5. Lo que este recibo NO hace

- **No implementa el índice posicional.** Cero cambio productivo. Eso es I2b, y es un cambio en la
  columna certificada, luego no se toca sin decisión.
- **No escribe ADR-S4-R2.** La evidencia para decidirla ya está aquí; la decisión es del owner.
- **No afirma que la identidad de loop sea durable.** Sólo que hoy es de llegadas, y que eso
  incumple DR-6.
- **No adjunta mutación**, por la razón de §0: la propiedad está abierta. Adjuntarla certificaría
  algo que no se ha afirmado.

## 6. Ficheros

| fichero | cambio |
|---|---|
| `…/application/spike/S4RIdIterationIdentitySpikeTest.kt` | **nuevo** — 9 casos, test-only |

## 7. Referencias

- `S4IdentityLoopScopeTest` — I2a; su KDoc nombra I2b y declara la deuda que este spike mide.
- `AGENTS.md` DR-5, DR-6, DR-7, DR-9, DR-10 — las leyes que esta medición pone a prueba.
- `ScriptedSourceLowering.kt:108-116` — el envoltorio `steps.scoped(...)` por sitio de loop.
- `ScriptedRuntime.kt:139-143, 157-168, 178-185` — el contador de llegadas y su mapa compartido.
- `ScriptedExecutionApi.kt:307-320` — por qué el nombre del parámetro queda fuera del `scopeId`.
