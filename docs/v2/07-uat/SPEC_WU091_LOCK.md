# WU-091 — `core.lock`: contrato implementado

Estado: SPECIFICATION (no implementado)
Ciclo: `p-1f3622e11c093341/rp6a-lock`, fase `specify`
Predecesor: `docs/v2/07-uat/RP6A_LOCK_CHARACTERIZATION.md` (razonamiento y evidencia)
Base: `main` @ `95582352`

Este documento fija el **contrato**, no el razonamiento. Por qué la forma es esta está
en la caracterización; aquí sólo queda *qué* hay que construir y *qué* no.

---

## 1. Superficie

```kotlin
lock("staging") {          // resource
    sh("./deploy.sh")
}
```

Superficie WU-091, derivada del `lock` oficial de Jenkins:

| Parámetro | Tipo | Defecto | Semántica |
|---|---|---|---|
| `resource` | `String` | — | Nombre del recurso. Obligatorio. |
| `timeoutSeconds` | `Int?` | `null` | Espera máxima por adquirido. `null` = espera indefinida. |
| `reason` | `String?` | `null` | Motivo legible en el evento. |
| `skipIfLocked` | `Boolean` | `false` | Si el recurso está tomado, el body **no** se ejecuta. |

`timeoutUnit` de Jenkins no se expone: se fija a segundos, la única unidad que el motor
ya usa para deadlines. Exponer la enum de Jenkins sería superficie sin consumidor.

**Fuera de WU-091** (y por qué): `label`, `quantity`, `priority`, `inversePrecedence`,
`resourceSelectStrategy` — todos requieren un catálogo de recursos o una cola ordenada,
que son requisitos de RP-8. `variable` requiere proyectar un valor al body, que acopla
este Step al sistema de contexto sin aporta a la semántica de exclusión.

## 2. Contratos de dominio

### 2.1 Puerto de coordinación

```kotlin
sealed interface LockAdmission {
    data class Acquired(val resource: String) : LockAdmission
    data class Denied(val reason: LockDenialReason) : LockAdmission
}

sealed interface LockDenialReason {
    data object Held : LockDenialReason
    data class TimedOut(val waitedMillis: Long) : LockDenialReason
    data object Cancelled : LockDenialReason
}

/** Effectful. Owns the resource hold; the OS/process is the lifetime authority. */
interface LockCoordinator {
    suspend fun acquire(resource: String, waitMillis: Long?, skipIfLocked: Boolean): LockAdmission
    fun release(resource: String)
}
```

`acquire` devuelve un ADT, no un booleano: los tres desenlaces tienen tratamientos
distintos y Jenkins les da tratamientos distintos. `Held` con `skipIfLocked` es éxito
del Step; sin él, es un estado de espera que este diseño no implementa todavía y por
tanto **se traduce a `TimedOut` con espera 0** hasta que exista la cola (§5).

### 2.2 Entrada y salida tipadas

```kotlin
@Serializable
data class CoreLockInput(
    val resource: String,
    val timeoutSeconds: Int? = null,
    val reason: String? = null,
    val skipIfLocked: Boolean = false,
)

data class CoreLockOutput(
    val resource: String,
    val bodyRan: Boolean,
    override val outcome: StepOutcome,
) : TypedStepOutput
```

`CoreLockOutput` **debe** implementar `TypedStepOutput`. Probado en
`LockFeasibilityProofTest.a body that reports failure still releases and fails the
step`: sin ese carrier, `lock` reporta `Success` sobre un body que falló. Es el mismo
mecanismo que `CoreShellOutput` para `core.sh`.

### 2.3 Contrato del Step

```kotlin
key               = PluginStepId("core.lock")
effects           = listOf(Effect.READ_ONLY)   // no muta el workspace; coordina
replayPolicy      = ReplayPolicy.MEMOIZED      // ver §4, es la fila difícil
requiredCapabilities = setOf(
    LOCK_COORDINATION_CAPABILITY,
    BODY_CONTINUATION_CAPABILITY,
)
body              = StepBody.Declared(
    invocation = BodyInvocationPolicy.ONCE,
    execution  = BodyExecution(
        owner  = BodyExecutionOwner.HANDLER_CONTINUATION,
        policy = BodyExecutionPolicy.Sequential,
    ),
    introduces = null,
)
```

La coherencia owner↔capability la verifica `resolveBodyExecutionPolicy` y falla cerrada.

## 3. Contrato de ejecución

El handler, y sólo el handler, decide si el body corre:

```text
acquire(resource)
  ├─ Denied(Held) + skipIfLocked  → NO invocar; devolver LockSkipped
  ├─ Denied(TimedOut)             → NO invocar; devolver fallo
  └─ Acquired                     → invocar continuation
                                       → liberar SIEMPRE tras el retorno
                                       → proyectar el StepOutcome del body
```

Dos leyes que no son negociables, ambas fijadas por el spike:

1. **La liberación es por retorno, no por excepción.** `BodyOutcome` es exactamente
   `Completed | Cancelled` y **nunca lanza**. El contrato es "liberar después de que
   `invoke` retorne, sea lo que retorne". Un `finally` queda como defensa en
   profundidad ante un defecto del motor, y queda **explícitamente fuera de lo probado**.
2. **El motor no sustituye la semántica.** Si el handler no invoca la continuación, el
   body no corre. Verificado por M1 (3 de 5 tests en rojo al anular la detección de
   owner en `StepDispatchEngine`).

## 4. La fila difícil: re-adquisición en reanudación

Jenkins no es durable: al reiniciar el controlador, el programa CPS se re-ejecuta desde
el principio y el lock se re-adquista **como efecto de esa re-ejecución**. PipelineK no
re-ejecuta: reanuda desde el journal. Por tanto la equivalencia ingenua es falsa y esta
es la fila que puede invalidar el diseño.

Requisito: al reanudar, el handler de `core.lock` se re-ejecuta y **re-adquiere antes
de que el body ejecute cualquier efecto fresco**. Los pasos ya journalizados devuelven
su resultado memoizado; los que no, ejecutan. El lock debe estar tomado durante esa
parte fresca.

Consecuencia directa y no negociable:

> El carrier de salida **no** puede memoizar el token de adquisición como si el lock se
> tuviera. `ReplayPolicy.MEMOIZED` sólo es correcto si la re-adquisición ocurre en el
> camino del handler, no en un valor reutilizado.

Ésta es la primera fila a implementar y la primera a mutar.

## 5. Lo que este contrato NO promete

- **No es coordinación entre hosts.** No hay controller ni worker (RP-8). La exclusión
  es por workspace en un mismo host, mediante fichero POSIX. Un `lock` no serializa dos
  máquinas.
- **No hay cola de espera.** `skipIfLocked` sin la cola no puede hacer esperar: o se
  toma o se renuncia. `timeoutSeconds` sin la cola sólo puede implementar "esperar con
  reintento" sobre el fichero, que es lo que hará el adaptador, **no** una cola
  ordenada con prioridad.
- **No arregla la entrega al menos una vez.** `lock("res") { sh("deploy.sh") }` con
  muerte de proceso entre el efecto y el journal ⇒ el resume re-ejecuta ⇒ doble
  despliegue. Eso lo arrastra `core.sh` desde antes; `lock` serializa, no deduplica.
- **No promete nada sobre re-entrada.** `lock("a") { lock("a") { ... } }` está
  **sin decidir** (§7.1 de la caracterización). El adaptador no debe bloquearse a sí
  mismo ni por accidente.

## 6. Eventos

```kotlin
LockRequested   (resource, reason, skipIfLocked)
LockAcquired    (resource)
LockReleased    (resource)
LockSkipped     (resource, reason)      // skipIfLocked y el recurso estaba tomado
LockAcquireFailed(resource, reason)     // timeout o cancelación
```

Todos en `DomainEvent.kt` con `override val kind: String get() = "..."`, siguiendo
`MilestoneReached`. Sin payload sensible: `resource` es un nombre de recurso declarado
por el autor del pipeline, no un secreto.

## 7. Criterios de salida

1. Contrato de dominio + codecs con round-trip verificado.
2. `LockCoordinator` con adaptador de fichero; liberación por muerte de proceso probada.
3. Registro en `CoreStepRegistryFactory` sin bypass de admisión.
4. Las cinco filas del spike, ahora contra producción y no contra un doble.
5. **Fila de re-adquisición en reanudación** (§4), verificada por mutación.
6. Cancelación como vía de retorno propia.
7. Fichero DSL `lock(...)` con compilación positiva y negativa.
8. Ratchet del coordinador intacto en 552.
9. Recibo con SHA exacto.

## 8. Decisiones que este spec fija

| Decisión | Elección | Razón |
|---|---|---|
| Forma | BlockStep `HANDLER_CONTINUATION` | El body es condicional; ninguna `BodyExecutionPolicy` lo expresa. |
| Clave | `core.lock` | Es primitiva del modelo de ejecución, como `retry`/`timeout`/`parallel`. No es concern de protocolo/vendor, así que no es caso de plugin. |
| Backend | Fichero POSIX | Cubre el 100 % de la contención real medida; el SO es la autoridad de ciclo de vida, luego no hay lease huérfano. |
| Cola durable | **No** | Es requisito de RP-8. Construirla ahora es construir RP-8 por comodidad. |
| Nombre de eventos | `Lock*` | Convención de `Dir*`/`Milestone*`. |
