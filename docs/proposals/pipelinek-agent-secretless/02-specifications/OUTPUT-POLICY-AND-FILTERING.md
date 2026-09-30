# Output policy, filtros y observabilidad

## 1. Regla de seguridad

Orden obligatorio:

```text
raw process output
      ↓
mandatory secret redaction
      ↓
safety/event normalization
      ↓
canonical sanitized events/results
      ↓
user/agent filters
      ↓
renderer
```

Ningún filtro, plugin o renderer extensible recibe bytes no sanitizados salvo componente trusted específicamente justificado.

## 2. Canales

Distinguir:

- stdout;
- stderr;
- semantic events;
- typed Step result;
- artifacts;
- metrics/traces futuras.

No usar el transcript como sustituto del typed result.

## 3. CLI

Ejemplos compatibles con NDJSON actual:

```bash
pipelinek run pipeline.kts |
  jq -c 'select(.kind == "RunFinished") | {runId, outcome}'

pipelinek run pipeline.kts |
  jq -c 'select(.kind == "StepFailed" or .kind == "RunFinished")'
```

Evolución nativa:

```bash
pipelinek run pipeline.kts --events failed,finished
pipelinek command ... --output summary
pipelinek step ... --output result
pipelinek step ... --select 'summary.failed,summary.skipped'
```

## 4. OutputPolicy ADT

```kotlin
sealed interface OutputPolicy {
    data object FullEvents : OutputPolicy
    data object Summary : OutputPolicy
    data object Agent : OutputPolicy
    data class EventSelection(val kinds: Set<EventKind>) : OutputPolicy
    data class TypedSelection(val selector: ResultSelector) : OutputPolicy
}
```

## 5. Agent output

Debe ser compacto y determinista. Ejemplo conceptual:

```json
{
  "runId":"...",
  "outcome":"success",
  "steps":[
    {"id":"release","outcome":"success"}
  ],
  "credentialPosture":"STRONG_SECRETLESS",
  "artifacts":["dist/app.zip"]
}
```

Nunca incluir:

- secret refs que revelen material sensible innecesario;
- env completo;
- config overlay content;
- broker session tokens;
- raw command line si contiene material sensible.

## 6. User-defined filters

Los filtros son pure transformations sobre estructuras sanitizadas. No pueden:

- alterar outcome;
- suprimir la persistencia durable de eventos;
- ejecutarse antes de redaction;
- modificar journal;
- mutar el Step result original.

## 7. Redaction limitations

La redacción por patrones exactos no detecta necesariamente:

- base64/hex transforms;
- fragmentación;
- hashing parcial;
- cifrado;
- exfiltración por timing/network.

Por ello `RAW_PROCESS_EXPOSURE` o `ISOLATED_PROCESS_EXPOSURE` siguen declarándose como tales aunque la salida esté redacted.

