# SPEC — WU-092 `core.input` (RP6-B)

> status: ACTIVE — autoridad de diseño e implementación de la WU-092
> cycle SDDK: `p-1f3622e11c093341/rp6b-input`
> precedente: `SPEC_WU091_LOCK.md` (RP6-A), mismo patrón de gates
> cola RP-6: WU-091 `core.lock` → **WU-092 `core.input`** → WU-093 `core.httpRequest`

## 1. Superficie y su fuente Jenkins

Derivada del paso `input` oficial
(<https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/>):

| Parámetro | Tipo | Por defecto | Nota |
| --- | --- | --- | --- |
| `message` | `String` | — | **obligatorio**; qué se pregunta |
| `ok` | `String` | `"Proceed"` | etiqueta de la respuesta afirmativa |
| `submitter` | `String?` | `null` | atribución, **no** autorización (ver §3.5) |
| `id` | `String?` | `null` | correlación estable de la petición |

**Excluido a propósito, con motivo:**

- `withId` — obsoleto en Jenkins y duplicaría `id`.
- Permisos / roles de Jenkins — este runner es local-first y headless; no hay
  base de usuarios que consultar. Una comprobación de autorización aquí sería
  teatro de seguridad (ver §3.5).

## 2. ADTs de dominio

```kotlin
// Lo que el autor del pipeline declara.
data class CoreInputInput(
    val message: String,
    val ok: String = "Proceed",
    val submitter: String? = null,
    val id: String? = null,
    val timeoutSeconds: Int? = null,
)

// Lo que un humano decidió. Cerrado: exactamente dos respuestas posibles.
sealed interface InputDecision {
    data class Proceed(val submitter: String?, val message: String?) : InputDecision
    data class Abort(val submitter: String?, val message: String?) : InputDecision
}

// Por qué NO hay decisión. Cerrado, sin booleanos ni nulls sendos.
sealed interface InputDenialReason {
    data class TimedOut(val waitedMillis: Long) : InputDenialReason
    data class Cancelled : InputDenialReason
    /** No se puede ni preguntar: p.ej. el ancla de control dir no es escribible. */
    data class Unanswerable(val diagnostic: String) : InputDenialReason
}

data class CoreInputOutput(
    val requested: String,          // message declarado
    val decision: InputDecision?,
    val denial: InputDenialReason?,
)
```

`decision` y `denial` son mutuamente excluyentes por construcción: se decide
**antes** de ejecutar el cuerpo, y una denegación significa que el cuerpo no corrió
nunca.

**Corrección de la primera redacción (dos, no una).** Esta spec declaraba además
`MalformedResponse` y `AlreadyAnswered` como denegaciones, y afirmaba que una
cancelación de corrutina producía `Cancelled`. Al modelar el puerto y medir el
comportamiento, las tres afirmaciones resultaron falsas:

- una respuesta malformada **no termina la espera** (D4), luego no produce
  resultado: produce otro turno de espera;
- la respuesta perdedora de una carrera es un `CREATE_NEW` que falla, es decir un
  hecho del sistema de ficheros del lado de quien responde, no del handler;
- una corrutina cancelada **no puede entregar un valor a quien la canceló**: la
  promesa que completaría ya está cancelada, así que el supuesto resultado tipado
  se descarta igual. Aplanar la cancelación sólo esconde la señal. Por eso
  `Cancelled` queda como caso de la vía de *interrupción* (hilo interrumpido con
  la corrutina viva, el mismo productor que tiene `FileLockCoordinator`), y la
  cancelación de corrutina se propaga.

Mantener lo que no puede ocurrir habría dejado casos muertos en la ADT, que es
justo lo que esta base de código castiga. Se quedan documentadas como
observaciones del bucle de espera y del mecanismo de respuesta, no como
resultados del paso.

## 3. Decisiones que esta spec fija

### 3.1 D1 — La respuesta llega por fichero en el control dir (sin daemon, sin UI)

Un runner CLI headless no tiene executor web. El mecanismo:

```text
<controlDirRoot>/inputs/<opId>/request.json     escrito por el handler (durable)
<controlDirRoot>/inputs/<opId>/response.json    escrito por quien responde
```

El handler **sondea** `response.json` de forma cooperativa (`delay` +
`ensureActive`, el mismo patrón cooperativo que `core.lock` dejó certificado) y
**acota** la espera por el presupuesto de ámbito (`EXECUTION_BUDGET_CAPABILITY`),
igual que lock.

Por qué fichero y no un subcomando que escriba en el journal: el journal es la
verdad del engine y escribir en él desde fuera crea un segundo escritor. El
control dir es territorio del engine y ya es el canal de reconciliación de
`RecoveryPolicy.ExternalSubprocess`; `input` lo reutiliza en vez de inventar un
tercer mecanismo.

Por qué no un daemon: un daemon es infraestructura nueva (ciclo de vida,
autenticación, descubrimiento) para un paso que no la necesita. Si la demanda
real aparece, es otro evolutivo.

### 3.2 D2 — Sin estado de operación nuevo

`OperationStatus` **no** gana un `AWAITING_INPUT`. La espera vive como
suspensión del handler, y la decisión es un hecho durable en el control dir.

Consecuencia medida: si el proceso muere esperando, el journal queda `RUNNING` →
`LOST`, y la matriz de replay (`WU-RP-040 R8`) manda `RERUN`. Al re-ejecutar, el
handler relee `response.json` y converge al mismo resultado. Determinista sin
tocar el spine, sin reconciliador nuevo, sin evento nuevo en el spine.

### 3.3 D3 — `MEMOIZED`: la decisión es un hecho, no un efecto

La decisión ya ocurrió y está durable. Un re-run **no vuelve a preguntar**; reproduce
la decisión registrada. Ésta es la diferencia de fondo con `core.lock`, que sí
re-adquiere: un hold no sobrevive al proceso, una decisión sí.

### 3.4 D4 — Primera respuesta gana, y una respuesta malformada no se consume

`response.json` se crea con `CREATE_NEW` (creación atómica): dos respuestas
simultáneas no se pisan, gana la primera y la segunda recibe
`InputDenialReason.AlreadyAnswered`, un fallo tipado, no un silencio.

Una respuesta malformada **no** termina la espera ni se consume: se ignora y se
sigue esperando, porque un fichero a medio escribir no es una negativa de un
humano. Esto evita el peor fallo posible de un mecanismo de fichero.

### 3.5 D5 — `submitter` es atribución, no autorización

En un runner headless no existe base de usuarios. `submitter` registra **quién
dijo qué**, y la decisión de negocio sobre si esa persona debía responder **no
puede** depender de este runner sin un modelo de identidad que no existe aquí.

La spec lo dice en voz alta para que nadie construya encima `if (submitter ==
"admin")` creyendo que hay una comprobación. La autorización, cuando exista, es un
`when` gate evaluado por una directiva con acceso a las credenciales de la
plataforma — terreno de S1-C, no de este paso.

## 4. Cuerpo y admisión

El cuerpo es **condicional** exactamente como en `lock`: sólo corre con
`Proceed`. Por eso el owner es `HANDLER_CONTINUATION` y no `CANONICAL_ENGINE`
— un cuerpo incondicional ejecutaría el pipeline pese a un `Abort`, que es
precisamente lo que el autor pidió evitar.

Capacidades declaradas (y sólo éstas):

```text
INPUT_DECISIONS_CAPABILITY   puerto de espera/respuesta (adaptador de fichero)
BODY_CONTINUATION_CAPABILITY el cuerpo acotado
EVENT_SINK_CAPABILITY        eventos de la decisión
EXECUTION_BUDGET_CAPABILITY  el presupuesto acota la espera
```

Ausencia de cualquiera ⇒ admisión fail-closed antes del handler. El adaptador se
expone **sólo** cuando hay ancla de control dir, igual que
`LOCK_COORDINATION_CAPABILITY` (una entrada sin ancla sería un namespace inventado).

## 5. Replay / recuperación

```text
descriptor.effects  = [READ_ONLY]
descriptor.replay   = MEMOIZED        (D3)
owner               = HANDLER_CONTINUATION
policy              = Sequential
```

## 6. Eventos

```kotlin
InputRequested(message, submitter, id)   // lo declarado, incluso si es contradictorio
InputProceed(submitter, message)         // alguien dijo que sí
InputAborted(submitter, message)         // alguien dijo que no
InputDenied(reason)                      // sin decisión: timeout, cancelación, malformada, ya respondida
```

Sin payload sensible: `message` la escribe el autor del pipeline. `submitter` es
una cadena de atribución, y se publica **sabiendo** que no es una frontera de
seguridad (D5).

## 7. Criterios de salida

1. Contrato de dominio + codecs con round-trip verificado (incluida la
   contradicción `message` vacío).
2. `InputDecisions` (puerto) con adaptador de fichero: escritura atómica de la
   petición, lectura validada, `CREATE_NEW` en la respuesta.
3. Registro en `CoreStepRegistryFactory` sin bypass de admisión.
4. Fichero DSL `input(...)` con compilación positiva **y negativa**, con
   `CoreInputWireCodec` como autoridad única de wire (patrón G3.4 de RP6-A).
5. Fila de reanudación verificada por mutación: un re-run no vuelve a preguntar.
6. Denegaciones como vías propias: `TimedOut`, `Cancelled` y `Unanswerable`,
   cada una con su diagnóstico; y las dos observaciones que **no** son
   denegaciones (respuesta malformada, doble respuesta) probadas en el puerto.
7. UAT instalada (HF2) con los escenarios duros:
   proceed · abort · respuesta malformada seguida de proceed · doble respuesta ·
   presupuesto que agota la espera · cancelación · cuerpo que no corre tras abort ·
   reanudación que no vuelve a preguntar.
8. Ratchet del coordinador intacto en 552.
9. Recibo con SHA exacto.

## 8. Lo que esta spec NO decide

- Un modo servidor/agente para responder sin tocar el disco.
- Autorización de `submitter` (§3.5, terreno de S1-C).
- `input` como *gate de stage* en BEFORE_STAGE — eso es `when`, que ya existe y
  que es la única política que puede detener una stage antes de empezar.
- Integración con credenciales o secretos en la respuesta.
