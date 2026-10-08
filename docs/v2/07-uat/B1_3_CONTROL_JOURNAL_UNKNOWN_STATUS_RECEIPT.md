# B1.3 — Un status durable desconocido se rechaza en el vocabulario del journal

**Slice:** B1.3 del plan por bloques B0..B7 (ROADMAP §13.3), categoría "ramas `else` que
aceptan variantes desconocidas / conversiones que se transforman en resultado por defecto"
**Fecha:** 2026-10-08
**Ciclo SDDK:** `p-733fb505b5a6bd2d/rp7-sem-s6-plugin-sdk` (OPEN/build, lease `orchestrator`)
**Resultado:** `PASS`

---

## 1. Qué capability obtiene el usuario

Un journal de control durable que **rechaza con el tipo que él mismo declara** cuando no puede
interpretar una fila.

Antes, ambos journals de control —el de `retry` (ADR-0075) y el de `waitUntil`— leían el estado
persistido así:

```kotlin
status = ao["status"]?.jsonPrimitive?.content
    ?.let { OperationStatus.valueOf(it) }
    ?: throw RetryControlJournalDivergenceException("status missing in $file")
```

El `?:` maneja un campo **ausente**, y lo rechaza bien. El `?.let` maneja uno **presente pero no
reconocido**, y lo hace dejando que `Enum.valueOf` lance `IllegalArgumentException`. El `catch` de
alrededor solo captura `SerializationException`, así que esa excepción se escapa del vocabulario
declarado del parser.

Tres consecuencias, de menor a mayor gravedad:

1. **El contrato declarado es falso para exactamente un campo.** Cualquier otro campo mal formado
   lanza `…DivergenceException`. Ese no. Un llamador que captura el tipo declarado no lo captura, y
   la recuperación escrita contra el contrato no recupera.
2. **El diagnóstico pierde la evidencia.** El mensaje de `Enum.valueOf` nombra el token pero no el
   fichero, y el fichero se llama por el SHA-256 del `controlOpId`, así que tampoco se puede
   reconstruir. Con un `retry` y un `waitUntil` en vuelo, el operador no puede decir qué fila
   durable es ilegible.
3. **El rechazo no tiene dueño.** Fallar cerrado solo significa algo cuando el llamador reconoce
   el rechazo. El contrato del planificador es precisamente que una fila de control ilegible es
   una divergencia y **nunca** un re-plan; una excepción inesperada no se puede distinguir de un
   fallo de infraestructura por el código que posee el bucle.

## 2. La decisión y por qué no fue un `try`/`catch`

Un `try { valueOf } catch { divergence }` en cada sitio habría sido la forma incorrecta dos veces:
los dos journals crecerían con su propia copia, y un tercer journal reintroduciría el lanzamiento
crudo en silencio.

`operationStatusOrThrow` recibe el rechazo como **función**, así que cada journal conserva la
propiedad de su propio tipo de excepción y de su propio texto. El saber que "un token desconocido
no es un default" vive una vez; el saber que "una divergencia de retry dice `Retry…`" se queda en
el journal de retry.

### Por qué rechazar y no defaultear

Una fila de control es estado durable que decide si un efecto se ejecuta otra vez. Defultear un
token desconocido a `PENDING` **re-ejecutaría** un intento cuyo efecto hijo puede haber ocurrido ya;
defaultearlo a `SUCCEEDED` se tragaría un fallo. Ningún default es seguro, así que no hay default: las
únicas dos funciones totales aquí son "reconocido" y "rechazar". La segunda mutación de §4 existe
precisamente para que nadie introduzca ese default pensando que es más amable.

## 3. Alcance: por qué esto y no las otras tres categorías de B1.3

El plan lista cuatro categorías en B1.3. Se midieron antes de elegir:

| categoría | medición | por qué no es esta rebanada |
|---|---|---|
| `Instant.now()` vs puertos de reloj | 83 coincidencias en **32 ficheros** de producción | Sustitución masiva prohibida explícitamente por el plan. Además el recibo B1d ya movió los 5 sitios de productor único y **dejó con dueño y fila guarda** los 5 compartidos con `RetryEngine`/`WaitUntilEngine`: media-fix de una familia es peor que no tocar. |
| duraciones monotónicas vs timestamps | `System.currentTimeMillis()` presente, reloj inyectado en 20 ficheros | La mezcla **es** el diseño de las filas guarda ya registradas. Reabrirla sin el trabajo de los 5 sitios compartidos rompe la ley de B1d. |
| conversiones `String → StepOutcome` | **0** coincidencias de `StepOutcome.valueOf` en producción | La premisa de la categoría no se sostiene en el código actual. |
| conversiones de enum sin validar / `else` que acepta desconocidos | `FailureKind.valueOf`, `OperationStatus.valueOf`, `PublishHtmlSkipReason.valueOf` | **Aquí está el defecto**, con consecuencia durable real. Los `FailureKind.valueOf` de codecs de salida también escapan como excepción cruda, pero son superficie de **fallo**, no de **decisión de re-ejecución**: un default sería incorrecto pero no puede duplicar un efecto. El de las filas de control sí puede. Se corrigió el que puede hacer daño y el otro queda nombrado abajo. |

## 4. Evidencia

### RED válido (no un error de compilación)

El primer intento del RED no compilaba contra la API real (`recordAttempt`/`read` no existen; son
`beginAttempt`/`readState`, y el fichero se nombra por SHA-256 con subdirectorio `retry-control` /
`wait-until-control`). Un `compileTestKotlin` fallido **no es un RED** — Gradle ejecuta la clase
previamente compilada. Se reescribió contra las firmas reales leídas del código:

```
cd v2 && ./gradlew :pipeline-application:test --tests '*ControlJournalUnknownStatusTest*'
tests=3 failures=2 errors=0
  RED the retry control journal refuses an unknown status with its own divergence type
      Unexpected exception type thrown, expected: <RetryControlJournalDivergenceException>
      but was: <java.lang.IllegalArgumentException>
  RED the waitUntil control journal refuses an unknown status with its own divergence type
      Unexpected exception type thrown, expected: <WaitUntilControlJournalDivergenceException>
      but was: <java.lang.IllegalArgumentException>
  OK  every status the enum defines still reads back, so strictness is not the fix
```

La tercera fila pasaba ya: los seis estados del enum seguían leyéndose, así que el RED era del
**rechazo ausente**, no de un fixture roto. El fixture además afirma que la corrupción aterrizó
antes de leer, con un mensaje `NON-VACUITY`.

### GREEN

```
cd v2 && ./gradlew :pipeline-application:test --tests '*ControlJournalUnknownStatusTest*'
tests=3 failures=0 errors=0
```

Familia completa de consumidores:

```
cd v2 && ./gradlew :pipeline-application:test --tests '*Retry*' --tests '*WaitUntil*' --tests '*ControlJournal*'
138 tests · 0 failures · 0 errors
```

### Mutaciones negativas

Restauradas y verificadas por SHA-256 (`sha256sum -c` → "La suma coincide" en ambos journals;
helper sin rastros de mutación).

| # | Mutación | Muerta por | RED |
|---|---|---|---|
| 1 | El `divergence` vuelve a ser el `IllegalArgumentException` crudo (el defecto original) | la columna de **retry** | 1 fila |
| 2 | El helper **defaultea** `PENDING` en vez de rechazar (el otro modo de fallo) | las columnas de **retry Y waitUntil** | 2 filas |

La mutación 2 es la que importa para el futuro: un default no lanza nada, así que un test que solo
comprobara "falla de alguna manera" lo habría dado por bueno. Aquí lo matan las dos columnas, que es
lo que hace que la columna de waitUntil sea una fila y no una copia decorativa.

## 5. Verificación

| Nivel | Alcance | Resultado |
|---|---|---|
| L0 | compilación | OK, sin `^e:` |
| L1/L2 | `ControlJournalUnknownStatusTest` | 3 tests, 0 F |
| L2 | retry + waitUntil + control journals | 138 tests, 0 F |
| ADVERSARIAL | 2 mutaciones | ambas muertas, restauradas y verificadas |
| L5 | `check` completo (`--no-daemon`) | `BUILD SUCCESSFUL in 27m 28s` |

**Recuento autoritativo** (leído de los XML JUnit, no del exit code):

```
clases=794 tests=5225 failures=0 errors=0 skipped=140
```

Contra el baseline del gate anterior (793 clases / 5222 tests) el delta es **+1 clase / +3 tests**:
exactamente `ControlJournalUnknownStatusTest`. Los 140 skipped son los pre-existentes y **no** se
cuentan como pases.

**Canario.** Los XML de la suite se borraron antes del gate y se regeneraron:

```
TEST-...durable.ControlJournalUnknownStatusTest.xml   tests=3 failures=0
timestamp=2026-10-08T18:11:02.690Z   (gate: 17:48:37Z -> 18:16:13Z)
```

Es fresco y cae dentro de la ejecución. El proceso del gate usó `--no-daemon`, así que no hay
posibilidad de reaprovechar un `UP-TO-DATE` como si fuera ejecución: los XML son la prueba.

**Cumple HARNESS FIDELITY §4:** sin `Files.createTempDirectory` sin padre, sin `cwd`/`env` ambiente,
sin reloj de pared — el estado no puede hacer parecer roto su propio sujeto.

**Deuda declarada, no cerrada:** los 140 skipped son los `UatLocal*`/`UatCompat*` legados que
viven en el harness externo, no un gap introducido aquí.

## 6. Deuda que queda, con dueño

- **Los `FailureKind.valueOf` / `PublishHtmlSkipReason.valueOf` de los codecs de salida** tienen la
  misma forma (`Enum.valueOf` escapando como excepción cruda). **No se corrigieron aquí** y son
  P2: un refusal con el tipo equivocado en un codec de salida produce un diagnóstico confuso, pero
  no puede re-ejecutar un efecto ni convertir un fallo en un éxito. Registrado en
  `IMPLEMENTATION_BACKLOG.md` como candidato al mismo helper, para que la decisión de aplicarlo sea
  deliberada y no una segunda pasada automática.
- **El límite ya declarado de `FArchE4b4`** (ventana de 2 líneas en la cláusula 3) sigue igual.

## 7. Cierre de referencia de implementación

```text
Reference implementation consulted:  ninguno externo. El patrón de rechazo tipado ya existe en
                                      el propio repo (completionFromWireOutcome de B1.2,
                                      PluginAdmission.apiRange de B2.2) y se siguió esa forma.
Behaviour adopted:                   rechazo en el vocabulario del dueño, con el valor Y el
                                      fichero en el mensaje
Intentional deviations:              ninguno
Security implications reviewed:      n/a — no toca credenciales ni superficie expuesta
Tests demonstrating the contract:    ControlJournalUnknownStatusTest (3)
```
