# RP6-A `lock` — Caracterización previa (WU-091)

Estado: CHARACTERIZATION COMPLETE / NOT_STARTED (implementación)
Ciclo: por abrir (H5)
Base: `main` @ `8984843b`
Alcance: decisión de forma arquitectónica ANTES de escribir código de producción.

Este documento **no** es una spec de lo que se va a construir. Es el registro de lo que
se investigó, lo que se encontró y por qué una pregunta concreta tiene una respuesta
concreta. Las decisiones que siguen están ancladas en fuentes del repositorio y en
la documentación oficial de Jenkins, no en preferencia.

---

## 1. La pregunta

`lock` es el primer Step de functionality real que entra **después** de la campaña
de hardening PR-017..PR-020. La hipótesis de partida era:

> `lock` tiene semántica durable propia y probablemente merece una abstracción tipada,
> no un simple wrapper de shell.

Antes de implementar hay que responder cuatro preguntas que decidirían la forma:

```text
¿lock debe ser un Step?
¿o un Directive?
¿o un body policy?
¿o un capability / runtime service?
```

Las cuatro no son excluyentes en abstracto, pero **elegir mal cuesta caro**:
un `lock` modelado como Directive no puede efectuar nada; un `lock` modelado como
body policy no puede decidir si el body se ejecuta; y un `lock` modelado como
servicio puro no aparece en el DSL ni es autorable por el usuario.

---

## 2. Lo que existe hoy (hechos verificados)

### 2.1 No existe ningún Step `lock`

`grep -rn "lock" v2/pipeline-application/src/main/kotlin -il` devuelve ficheros que
contienen la *cadena* `lock` (bloques, `ReentrantLock`, `Clock`), no un Step `lock`.
`find v2 examples -iname "*lock*" -name "*.kt"` devuelve 20 ficheros, **ninguno** de
ellos es un Step `lock`:

| Fichero | Qué es realmente |
|---|---|
| `pipeline-events/.../durable/DbLock.kt` | Serialización de escrituras al journal SQLite entre instancias. Un `object` con `ConcurrentHashMap<String, ReentrantLock>` por ruta de BD. **No tiene nada que ver con Steps ni con el DSL.** |
| `pipeline-domain/.../BlockStepNestingConstants.kt` | Constantes de anidamiento de blocks. |
| `SystemClock.kt`, `Clock.kt` | Menciones a `ReentrantLock` como implementación de reloj. |
| `example/lock/LockDirective.kt` | Directive de ejemplo, ver §2.2. |

`DbLock` es un homónimo peligroso: comparten nombre con el futuro `lock` pero resuelven
un problema de concurrencia dentro del propio runtime (escrituras al journal), no
coordinación entre pipelines. **No debe reutilizarse ni confundirse.**

### 2.2 `acme.lock` existe como Directive y hoy no hace nada

`examples/example-directive-plugin/.../lock/LockDirective.kt`:

```kotlin
@Serializable
data class LockInput(val resource: String, val timeoutSeconds: Int? = null)

object LockDirectiveDefinition : DirectiveDefinition<LockInput, Unit> {
    val KEY = DirectiveKey("acme.lock")
    override val phase: DirectivePhase = DirectivePhase.BEFORE_STAGE
    override val policy: DirectiveExecutionPolicy = DirectiveExecutionPolicy.Evaluate
    ...
}
```

Su propio KDoc lo dice: *"S1-D proves only that an external key ADMITS fail-closed
and is OBSERVED — policy interpretation is not this plugin's business."*

El dato decisivo está en `DirectiveExecutionPolicy.Evaluate` sobre el seam
`BEFORE_STAGE`: **`Evaluate` significa "observa y continúa; nada se consume"**.
Una Directive **no puede efectuar**. Adquirir y liberar un lock es efectuar.

> **Conclusión 1: `lock` NO puede ser un Directive.** Un `Directive` en
> `BEFORE_STAGE` con `Evaluate` es estructuralmente incapaz de adquirir un recurso.
> Convertir `acme.lock` en un Step real es el movimiento correcto, no un rodeo.

### 2.3 Existe `BodyExecutionOwner.HANDLER_CONTINUATION`, y es exactamente el hueco que `lock` necesita

`pipeline-domain/.../step/BodyExecutionPolicy.kt` define tres owners:

| Owner | Significado |
|---|---|
| `CANONICAL_ENGINE` | El motor ejecuta el body automáticamente. |
| `LEGACY_LINEAR` | Semántica aún en el rewrite lineal legacy. Rechazado fail-closed. |
| `HANDLER_CONTINUATION` | **El handler posee la decisión de invocación; el motor sigue siendo el único ejecutor.** |

El KDoc de `HANDLER_CONTINUATION` (WU-RP-035, ADR-0081 como amended 2026-10-01) dice
literalmente:

> *The handler owns the INVOCATION decision; the engine still owns EXECUTION. A Step
> declaring this runs its registered handler through the canonical durable spine, and
> reaches its own body only through a [BodyContinuation] the engine binds for that
> invocation. The engine NEVER auto-executes the children of such a Step: if the handler
> does not invoke the continuation, the body does not run, and that is the declared
> semantics, not a missing effect.*

Y lo justifica así:

> *a wrapper, a retry condition or a plugin-specific rule is not expressible by the engine*

**`lock` es exactamente eso**: un wrapper cuya regla ("sólo entra si se adquiere")
no es expresable por el motor. La maquinaria se construyó en WU-RP-035 anticipando
este caso, y está probada por `ExternalHandlerContinuationProofTest` (§2.4).

### 2.4 Patrón probado, literalmente reutilizable

`ExternalHandlerContinuationProofTest.kt:118-157` declara un Step `handlerblock`
así:

```kotlin
body = StepBody.Declared(
    invocation = BodyInvocationPolicy.ONCE,
    execution = BodyExecution(
        owner = BodyExecutionOwner.HANDLER_CONTINUATION,
        policy = BodyExecutionPolicy.Sequential,
    ),
    introduces = null,
),
requiredCapabilities = setOf(BODY_CONTINUATION_CAPABILITY),
```

y su handler:

```kotlin
val continuation: BodyContinuation = context.capabilities.get(BODY_CONTINUATION_CAPABILITY)
val outcome = continuation.invoke(BodyInvocationContext())
```

`CanonicalStructuralDecisions.kt:206-213` confirma que `HANDLER_CONTINUATION` es
**canonically-durable eligible**, igual que `CANONICAL_ENGINE`.

---

## 3. Semántica de referencia: qué hace `lock` en Jenkins

**El baseline local NO cubre `lock`.** `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`
menciona `lock` una sola vez (línea 94) y es sobre el *version pinning* de dependencias
en YAML, no sobre el Step. La sección 2 del baseline cubre `timeout`, `retry`,
`catchError`, `warnError`, `withEnv`, `readFile`, `pwd`, `fileExists`, `isUnix` —
**no `lock`**.

Es un hueco real del baseline, y AGENTS.md obliga a que los pasos casen con Jenkins.
Se consultó la fuente oficial.

Fuente: <https://www.jenkins.io/doc/pipeline/steps/lockable-resources/>

`lock` es un **block step**: adquiere el recurso, ejecuta el body, libera.

| Parámetro | Semántica Jenkins |
|---|---|
| `resource: String` | Nombre del recurso. Si no existe en configuración global, se crea automáticamente al ejecutar. |
| `label: String?` | Bloquea recursos con esa etiqueta. **Mutuamente excluyente con `resource`.** |
| `quantity` | Cuántos recursos del label se requieren. Vacío o 0 = todos. |
| `inversePrecedence: Boolean?` | Por defecto FIFO. `true` ⇒ el más nuevo entra primero. |
| `priority: Int?` | Mayor número gana. **No combinable con `inversePrecedence`.** |
| `variable: String?` | Nombre de variable de entorno que recibe los recursos bloqueados durante el body. |
| `resourceSelectStrategy: String?` | `SEQUENTIAL` (defecto) o `RANDOM`. |
| `skipIfLocked: Boolean?` | Si hay cola, **el body no se ejecuta**. Sólo entra si se puede tomar inmediatamente. |
| `timeoutForAllocateResource: Long?` | Espera máxima por recurso. `0` = espera indefinida. |
| `timeoutUnit: String?` | `SECONDS`/`MINUTES`/`HOURS`. Defecto `MINUTES`. |
| `reason: String?` | Motivo legible en la UI. |
| `extra` | Múltiples recursos. |

Errores de validación en runtime (Jenkins lanza excepción con estos textos):

```text
"Either resource label or resource name must be specified"
"Label and resource name cannot both be specified"
"Label does not exist: [label-name]"
```

### 3.1 La propiedad que gobierna todo lo demás

De la lista, una columna es de otra categoría:

```text
skipIfLocked
timeoutForAllocateResource   ┐
inversePrecedence            ├── el BODY PUEDE NO EJECUTARSE
priority                     ┘
```

El body de `lock` es **condicional**: puede no llegar a ejecutarse. Eso es lo que
decide la forma arquitectónica, y es la razón por la que `lock` no es un body policy.

---

## 4. Respuesta a las cuatro preguntas

### ¿Body policy (`BodyExecutionPolicy`)?

**No.** Un body policy describe una forma **total**: `Sequential` corre el body una vez,
`Scoped` lo proyecta, `Retrying` lo repite, `Parallel` lo bifurca. Los cuatro dan por
supuesto que el body **se ejecuta**. `lock` puede no ejecutarlo (§3.1).

Añadir un caso `Locking` a `BodyExecutionPolicy` sería un error de dos formas:

1. `BodyExecutionPolicyShape` es una familia **cerrada** usada para admisión de
   capacidad del motor. Un caso nuevo obliga a tocar todos los `when` exhaustivos y
   a `BodyExecutionSupport`, rompiendo la propiedad que hace valuable al ADT.
2. Un body policy no puede expresar "el handler decide", que es la semántica real.

> **Conclusión 2: `lock` no es una quinta forma de body.** Es un Step cuyo *handler*
> posee la invocación, y el motor sigue ejecutando.

### ¿Capability / runtime service?

**Sí, pero como port detrás del Step, no como la superficie de autor.**

El mismo patrón que `core.sh` → `SHELL_OPERATIONS_CAPABILITY`: el autor ve un Step,
el handler sólo ve capacidades declaradas. Un `lock` sin Step sería un
service-locator, que AGENTS.md prohíbe explícitamente.

> **Conclusión 3:** `LOCK_COORDINATION_CAPABILITY` (o nombre equivalente) es la
> capacidad窄 que el handler declara. La coordinación entre runs vive detrás de un
> port tipado, en un adaptador.

### ¿Step?

**Sí.** Y con owner `HANDLER_CONTINUATION` + policy `Sequential`
(patrón exacto de `ExternalHandlerContinuationProofTest`).

> **Conclusión 4 — forma decidida:**
>
> ```text
> DSL:    lock("resource") { body }
>           │
>           ▼
> Step:   core.lock  (BlockStep, owner = HANDLER_CONTINUATION,
>                     policy = Sequential,
>                     requiredCapabilities = { LOCK_COORDINATION_CAPABILITY,
>                                             BODY_CONTINUATION_CAPABILITY })
>           │
>           ▼  (el handler decide; el motor ejecuta)
>   acquire ──► continuation.invoke(ctx) ──► release
>           │
>           ▼
> Port:   LockCoordinatorPort   ← la coordinación real, tipada
>           │
>           ▼
> Adapter: fichero / registro en proceso / (futuro) BD
> ```

### Forma del `StepKey`

El roadmap hereda `WU-091 core.lock`. La dirección HTTP del operador fue a plugin
con `http.request` en vez de `core.httpRequest`, pero el motivo fue distinto: HTTP es
concern de protocolo/vendor. **`lock` es una primitiva de coordinación del propio
modelo de ejecución**, del mismo grupo que `retry`, `timeout` y `parallel`, que ya
viven en core. `core.lock` es defendible y es el nombre que el roadmap ya heredó.

Se registra como decisión abierta (§7.1) porque tiene coste de migración si cambia.

---

## 5. El problema durable — la parte que no estrivial

Esta es la razón por la que `lock` no es "otro wrapper de shell".

Jenkins no es un motor durable: cuando el controlador reinicia, el programa CPS se
**re-ejecuta desde el principio** y el lock se re-adquiere como efecto de esa
re-ejecución. La re-ejecución *es* el mecanismo de recuperación.

PipelineK es lo contrario: un run **no** se re-ejecuta desde el principio. Se reanuda
desde estado durable, reutilizando resultados journalizados y ejecutando sólo lo que
falta. Por tanto la equivalencia ingenua

```text
Jenkins:  lock = acquire → body → release,   y re-ejecutar body's re-adquiere
PipelineK: lock = acquire → body → release,   y re-ejecutar body's NO re-adquiere
```

**es falsa, y ése es el riesgo de diseño central de WU-091.**

### 5.1 Reanudación: hay que re-adquirir, y hay que re-adquirir en el sitio correcto

Al reanudar, el handler de `core.lock` se ejecuta otra vez (vía la spine durable) y
debe **re-adquirir antes de que el body ejecute cualquier efecto fresco**. Los pasos
del body que ya están journalizados devuelven su resultado memoizado; los que no,
ejecutan de verdad. El lock debe estar tomado durante esa parte fresca.

Esto funciona porque con `HANDLER_CONTINUATION` el handler se re-ejecuta: es el
encargado de re-adquirir. Un `lock` implementado como body policy gestionado por el
motor **no tendría quién re-adquiriera**.

> **Implicación de diseño:** `core.lock` debe declarar un `ReplayPolicy` cuyo
> comportamiento en reanudación sea **volver a adquirir**, no reutilizar un
> "ya adquirido" memoizado. Un `MEMOIZED` ingenuo sobre el resultado del handler
> devolvería el token de adquisición como si el lock se tuviera.

### 5.2 Liberación en todos los finales — y en la muerte del proceso

| Final | Mecanismo |
|---|---|
| Éxito | `finally` explícito en el handler. |
| Fallo del body | `finally` explícito; el fallo se propaga **después** de liberar. |
| Abort / cancelación | `finally`; el `BodyOutcome.Cancelled` también cierra la continuación. |
| Muerte del proceso | **El lock de fichero lo libera el SO** (los locks POSIX mueren con el proceso). Un registro en BD **no**: deja fila huérfana. |

Esa última fila es la única que exige diseño propio. Un backend de lock basado en
estado durable necesita **expiración por lease** (o detección de liveness del
titular), o un `lock` puede quedar retenido para siempre por un proceso que ya no
existe.

### 5.3 Re-entrada

`lock("a") { lock("a") { ... } }` — mismo run, mismo recurso.

Jenkins lo resuelve porque el `LockableResourcesManager` es *por build* y el mismo
contexto puede re-entrar. En PipelineK hay que **declararlo explícitamente**: un
registro de recursos tomados con clave `(runId, resource)` y contador de profundidad
lo hace re-entrante. Sin esa decisión, el caso se auto-bloquea (deadlock consigo mismo),
que es un fallo de diseño, no un bug.

### 5.4 Lo que `lock` NO arregla (y no debe prometer)

`lock("res") { sh("deploy.sh") }` con muerte de proceso **entre el efecto y el
journal** ⇒ el resume re-ejecuta `deploy.sh` ⇒ doble despliegue.

Eso es el problema clásico at-most-once vs at-least-once que ya arrastra `core.sh`. Un
lock **no lo arregla** y **no lo empeora**: serializa, no deduplica. Cualquier
documento o recibo de WU-091 que sugiera lo contrario sería incorrecto.

### 5.5 Body completamente replayeado

Si al reanudar **todos** los pasos del body están journalizados, ningún efecto ocurre:
mantener el lock es un coste de contención sin beneficio (bloquea a otros runs sin
protección real).

- Recomendación por defecto: **adquirir siempre.** Es simple y seguro.
- La optimización ("no adquirir si el body está totalmente replayeado") requiere
  demostrar que no queda ningún efecto fresco, lo cual es una precondición fuerte y
  frágil. Se deja **fuera de WU-091** y anotada como refinamiento futuro.

---

## 6. Superficie de Jenkins que se propone para WU-091

Esto **no** es el diseño final; es el recorte honesto que se propone, derivado de §3
y §5:

| Parámetro Jenkins | ¿En WU-091? | Razón |
|---|---|---|
| `resource` | **Sí** | Es lo esencial. |
| `timeoutForAllocateResource` + `timeoutUnit` | **Sí** | Sin timeout, un lock reintenta indefinidamente y un run queda colgado sin límite. Es una ley de seguridad operacional. |
| `reason` | **Sí** (opcional) | Cosmético, pero barato y mejora el diagnóstico. |
| `skipIfLocked` | **Sí** | Es la otra mitad de la condicionalidad; sin él, el body siempre se ejecuta tras adquirir. Es barata una vez que existe la fila de cola. |
| `variable` | Fase 2 | Proyecta un valor al body; interactúa con el sistema de contexto. Añade superficie. |
| `label` + `quantity` | Fase 2 | Selección entre recursos requiere un catálogo de recursos que hoy no existe. |
| `priority`, `inversePrecedence` | Fase 2 | Requieren una cola ordenada; sin cola, son no-ops. |
| `resourceSelectStrategy` | Fase 2 | Depende de `label`. |

**Errores de validación**: replicar los tres mensajes de Jenkins (§3) es
obligatorio por la ley de familiaridad de AGENTS.md, y son ADTs sellados, no strings
libres:

```kotlin
sealed interface LockAdmissionRejection {
    data object NoResourceSpecified : LockAdmissionRejection
    data object BothLabelAndResource : LockAdmissionRejection
    data class LabelDoesNotExist(val label: String) : LockAdmissionRejection
    data class AllocationTimedOut(val waitedMillis: Long) : LockAdmissionRejection
    data object CancelledWhileWaiting : LockAdmissionRejection
}
```

---

## 7. Decisiones abiertas (NO resueltas por esta caracterización)

### 7.1 `core.lock` vs plugin

Recomendación: `core.lock`. Razón: es una primitiva del modelo de ejecución, del
mismo grupo que `retry`/`timeout`/`parallel`. Contra: si `lock` acaba necesitando un
catálogo de recursos configurable (como `label`), quizá sea plugin. **No bloquear por
esto**: `resource` + timeout no necesitan catálogo.

### 7.2 Backend del coordinator — RESUELTO por medición

La precondición se midió antes de decidir, y la respuesta acota el diseño:

| Pregunta | Respuesta verificada | Dónde |
|---|---|---|
| ¿Hay controller / worker remoto? | **No.** | Módulos: `pipeline-application` … `pipeline-testkit`. El controller/worker es RP-8, posterior. |
| ¿Cómo se paraleliza hoy? | **Coroutines dentro de un run.** `async(kotlinx.coroutines.Dispatchers.Default)` sobre branches de `parallel`. | `ParallelStageEngine.kt:203` |
| ¿Cómo se obtienen dos runs concurrentes? | **Dos procesos del SO en el mismo host** (dos invocaciones de la CLI). Es la única vía. | — |

Por tanto la contención real que `lock` debe cubrir **hoy** es exactamente:

```text
(a) branches de `parallel` dentro de un MISMO run   → mismo proceso
(b) dos runs en procesos DISTINTOS del MISMO host   → Fichero POSIX
(c) runs en hosts DISTINTOS                         → NO EXISTE HOY
```

> **Decisión: backend de fichero (lock POSIX del SO), sin cola durable en WU-091.**

Consecuencias, todas favorables:

1. **Cubre el 100 % de la contención real de hoy.** (a) vía `ReentrantLock`,
   (b) vía `FileLock`. (c) no existe.
2. **§5.2 se vuelve trivial y desaparece el problema difícil.** No hay fila durable
   que pueda quedar huérfana: **el SO es el dueño del lock**. La muerte del proceso
   lo libera, no hay lease que expirar, no hay `stale lock` que limpiar. El problema
   más caro de §5.2 no llega a existir.
3. `skipIfLocked` y `timeoutForAllocateResource` funcionan sin cola: son
   `tryLock()` y `tryLock(timeout)` respectivamente. **No requieren ordenación FIFO**
   porque no hay más de un esperante por recurso en la mayoría de los casos reales.

Lo que esta decisión **rechaza explícitamente**: construir una cola durable de
espera. `priority` e `inversePrecedence` existirían **sólo** para ordenar una cola, y
una cola entre hosts es exactamente el requisito de RP-8 (leases, fencing,
reconexión, backpressure). Construirla ahora es construir RP-8 por comodidad, que
AGENTS.md prohíbe. Ambas quedan en Fase 2 con esa justificación, no por pereza.

**Detalle pendiente de diseño**: el namespace. Los ficheros de lock deben vivir bajo
una raíz canónica y estable (p. ej. `<workspace>/.pipelinek/locks/<sanitizado>.lock`)
para que dos procesos que ejecuten el mismo workspace excluded se reconozcan. Eso
también fija qué significa "el mismo recurso" y por tanto qué significa "exclusión":
**exclusión por workspace, no global**. Es coherente con que hoy no hay red de agentes.

### 7.3 Eventos

Nombres candidatos, siguiendo la convención de `DirEntered`/`DirExited`:

```text
LockRequested   LockAcquired   LockReleased   LockAcquireTimedOut
```

Sin decidir si `LockSkipped` merece evento propio o se infiere de
`LockRequested` + ausencia de `LockAcquired`.

---

## 8. Lo que este documento NO afirma

- **NO** afirma que `lock` esté implementado. No hay una línea de producción.
- **NO** afirma que la caracterización esté completa: §7.1 (`core.lock` vs plugin) y
  §7.3 (eventos) siguen abiertas. §7.2 (backend) **sí** está resuelta por medición.
- **NO** certifica nada. No hay gate, no hay SHA de certificación, no hay recibo.
- **NO** afirma que el baseline Jenkins esté completo: se ha documentado **un hueco**
  (§3) que debería cerrarse en el baseline local como parte de WU-091.
- Los puntos de §5 son **razonamiento sobre invariantes**, no evidencia. La evidencia
  llega cuando existan tests que los fijen y una mutación que demuestre que fallan.

---

## 9. Siguiente paso

1. Escribir la caracterización **ejecutable** (tests que fijen
   acquire / release / re-adquisición-en-reanudación / failure / abort / cancelación /
   re-entrada) **antes** de la implementación, y verificar cada test por mutación.
2. Abrir el ciclo SDDK H5 con WorkItem propio y goal correcto.
3. Cerrar §7.1 y §7.3 al escribir la spec de WU-091.

Nada de esto empieza hasta que H4 deje de retener la spine.
