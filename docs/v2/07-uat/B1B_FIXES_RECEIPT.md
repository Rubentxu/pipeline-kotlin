# B1b — cierre de los defectos medidos en AUD-02 y AUD-07

**Base:** `66774adc` (rama `s6-plugin-sdk`) · **Fecha:** 2026-10-08
**Alcance:** arreglo de producción + inversión explícita de las aserciones que caracterizaban el defecto.
**Base de la medición:** `B1A_CHARACTERIZATION_RECEIPT.md`. Este recibo **cierra** lo que aquel midió.

---

## 1. Qué cambia, y por qué cada cambio es el mínimo

### AUD-02 (c) — el presupuesto de timeout vuelve a existir

`executeNonDurableInvocation` construía `TaskExecutionRequest(timeoutMs = null)` y nunca leía
`shOptions.timeoutMs`. Ahora la firma recibe `timeoutMs` y lo propaga:

```kotlin
val effectiveTimeoutMs = when {
    timeoutMs == null || timeoutMs == 0L -> null      // "sin timeout" en AMBAS rutas (TMO-S-013)
    timeoutMs > 0L -> timeoutMs
    else -> return ShellInvocationResult.Failed(       // negativo: no es un modo que esta ruta honre
        PipelineFailure(FailureKind.INFRASTRUCTURE,
            "core.sh timeoutMs must be positive or null/0 for no timeout: $timeoutMs"))
}
```

Un presupuesto negativo se **rechaza explícitamente** en vez de ignorarse: era el mismo patrón
(silencio ante un valor que no se puede honrar) en otra forma. El vencimiento produce la misma
clasificación tipada que el brazo durable (`DurableTaskTerminal.Cancelled(TIMEOUT)` →
`Interrupted`), no una semántica nueva.

### AUD-02 (d) — la cancelación deja de disfrazarse de infraestructura

`CancellationException` se relanza sin capturar, en los dos puntos donde se capturaba:

```kotlin
catch (ce: kotlin.coroutines.cancellation.CancellationException) { throw ce }
```

AGENTS.md PAR-D: *«`CancellationException` is an execution mechanism: it MUST NOT be mapped to a
generic infrastructure failure or to a terminal durable outcome.»* La ruta ya no devuelve
`Failed(INFRASTRUCTURE, "StandaloneCoroutine was cancelled")`, que hacía indistinguible «me
cancelaron» de «el sustrato de shell se rompió».

### AUD-02 (higiene) — el directorio propio se borra siempre

La ruta creaba `Files.createTempDirectory("pipeline-sh-non-durable")` cuando el llamante no daba
root, y no lo borraba: un directorio de producción filtrado por ejecución, medido en B1a. Ahora hay
un único punto de borrado en `finally`, y **sólo** para el directorio que esta ruta creó:

```kotlin
val ownsControlDir = controlDirRoot == null
...
finally { if (ownsControlDir) deleteRecursively(controlDir) }
```

Un control root del llamante no se toca nunca. Corre en éxito, fallo, timeout y cancelación.

### AUD-07 — una sola autoridad de `kind` → `BoundPurpose`

Había tres copias del mapeo y una mentía: el motor cubría 3 de los 7 tipos y mandaba el resto a
`API_KEY`, así que `file`, `certificate`, `zip` y `usernameColonPassword` se reportaban como
`API_KEY` en el evento `CredentialUsed` — una observación de auditoría falsa.

```kotlin
// pipeline-domain: TOTAL sobre el sello; el compilador obliga a mapear cada caso
val CredentialBindingSpec.boundPurpose: BoundPurpose
    get() = when (this) { is StringBindingSpec -> API_KEY; ...; is UsernameColonPasswordBindingSpec -> USERNAME_COLON_PASSWORD }
```

`BodyExecutionEngine` y `WithCredentialsExecutor` consumen esa autoridad; sus copias privadas se
eliminan. Un token crudo desconocido **no puede** llegar a este seam: el codec lo rechaza antes
(`CredentialBindingsPayload.kt`, `else -> throw IllegalArgumentException`), comprobado en el código.

---

## 2. Evidencia ejecutada (toda reproducida por el root, con XML fresco)

Canario: se borran los `TEST-*.xml` antes de cada medida y se comprueba que reaparecen.

```text
B1aBodyExecutionEngineCredentialPurposeTest        3 tests  0 fail  (nuevo)
B1aOutputPlaneProviderLifecycleCharacterizationTest 3 tests 0 fail  (sin cambios: sigue caracterizando)
B1aShNonDurableRouteCharacterizationTest            4 tests 0 fail  (aserciones invertidas)
TOTAL                                              10 tests 0 fail / 0 err / 0 skip

regresiones en consumidores (misma corrida, XML fresco):
  CoreShellStepTest                    12 tests 0 fail
  UatLocal008CredentialsTest           27 tests 0 fail, 1 skip
  UatLocal008SshPrivateKeyRoundGateTest 2 tests, 2 SKIPPED  <- no prueba nada: no se cuenta como evidencia
  BasicCredentialsCapabilityContributorTest 8 / WithCredentialsExecutor ports 5 / smoke 2
  CredentialBindingSpec sealed hierarchy 15
```

`^e ` = 0: ningún resultado viene de un árbol que no compiló.

## 3. Mutaciones (XML fresco, nunca por `UP-TO-DATE`, restauradas y verificadas por hash)

```text
M1  timeoutMs = effectiveTimeoutMs -> null (revierte el arreglo (c))
    RED: 2 fallos, filas (c) y (e)   "NON-REGRESSION (was CHARACTERISATION OF A DEFECT, AUD-02 c/e)"
M2  binding.boundPurpose -> when(binding.kind) parcial con else -> API_KEY (revierte AUD-07)
    RED: 1 fallo, "AUD-07 NON-REGRESSION: the CredentialUsed for 'file' must carry purpose=FILE"

restauración: sha256 de ShExecution.kt  b7dcad40…  y de BodyExecutionEngine.kt 435b72a6…
              idénticos al valor medido antes de mutar
```

## 4. Lo que esto NO hace

```text
- No cambia contratos publicados: `boundPurpose` es una propiedad de extensión AÑADIDA en dominio;
  ninguna firma existente cambia.
- No toca retención, `ExplicitReleaseOnly` ni el keying de locks del Output Plane (es B1c).
- No toca la CLI ni su contrato de exit codes (es B1c).
- No toca `WaitUntilOutput.resultOutcome` (AUD-03, con dueño y ley propios).
- No arregla el coste O(salida) del transcript en la ruta no durable: sigue acumulando en el heap
  (medido en B1a). Lo que se arregla aquí es el presupuesto, la cancelación y la limpieza.
- `UatLocal008SshPrivateKeyRoundGateTest` sale entero SKIPPED: no demuestra nada y no se cuenta.
```
