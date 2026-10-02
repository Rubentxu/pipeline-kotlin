# WU-092 `core.input` — Diseño de seams (RP6-B)

> cycle SDDK: `p-1f3622e11c093341/rp6b-input` (fase design)
> spec: `docs/v2/07-uat/SPEC_WU092_INPUT.md` — este documento NO repite la spec;
> mapea cada decisión a la costura de producción que la implementa.

## Mapa de costs y dirección de dependencias

```text
pipeline-domain      CoreInputInput, InputDecision, InputDenialReason   (ADTs puros)
                     StepDescriptor / BodyExecutionOwner (compartidos)
        ▲
        │  depende hacia dentro, nunca al revés
pipeline-application CoreInputStep        handler + contrato + codecs
                     InputDecisions       PUERTO (interface, sin efectos)
                     FileInputDecisions   ADAPTADOR (fichero, POSIX)
                     INPUT_DECISIONS_CAPABILITY  token neutro
        ▲
        │
pipeline-events      InputRequested/Proceed/Aborted/Denied
durable bridge       InputDecisionsAdapter + budget (ya existe de RP6-A)
```

Regla que este diseño respeta y que fitness comprobará: el **puerto** no nombra
fichero, y el **handler** no nombra `Path`, `File` ni `controlDirRoot`. El handler
ve `InputDecisions` y `ExecutionBudget`, dos capabilities; nada más.

## Costuras nuevas y su justificación

| Costura | Tipo | Por qué no hay alternativa |
| --- | --- | --- |
| `InputDecisions` | puerto (`application`) | El handler necesita *preguntar y esperar*. Sin puerto tendría que tocar el sistema de ficheros, y la capacidad de lock demonstró que el puerto + adaptador + token es lo que hace testeable el comportamiento. |
| `FileInputDecisions` | adaptador (`application`, zona de efectos) | El mecanismo de respuesta está en el control dir por D1. El adaptador es el único que conoce rutas. |
| `INPUT_DECISIONS_CAPABILITY` | token neutro | Mismo patrón que `LOCK_COORDINATION_CAPABILITY`: el puente y el handler referencian el MISMO token sin que la costura de ejecución dependa de ninguna definición de Step concreta. |
| `CoreInputWireCodec` | autoridad única de wire | El compilador delegará el encoding, exactamente como hizo `core.lock` en RP6-A G3.4. El precedente de `core.sh` (divergencia de dialectos) es lo que se evita. |

## Puntos de extensión sobre los que NO se toca nada

```text
OperationStatus          sin estado nuevo            (D2)
RecoveryPolicy           sin variante nueva         (D2)
DefaultEffectReplayPolicy sin cambio                (MEMOIZED ya está soportado)
canonical coordinator    sin crecimiento (ratchet 552)
journal / replay / spine sin cambios
```

## Comportamiento de la espera (lo único que hay que acertar)

```text
acquireDecisions(request)
  ├─ escribe request.json (idempotente: se puede reescribir)
  ├─ bucle cooperativo:
  │    ├─ response.json ausente            → delay, otro turno
  │    ├─ response.json malformado         → ignora, sigue (D4)
  │    ├─ response.json válido             → CREATE_NEW atómico → decisión
  │    └─ presupuesto agotado              → InputDenialReason.TimedOut
  └─ cancelación del padre                → InputDenialReason.Cancelled
```

Dos propiedades que este diseño garantiza y que UAT deben verificar:

1. **La primera respuesta gana; la segunda es un fallo tipado**, no un silencio.
2. **Una respuesta malformada no termina la espera**: un fichero a medio escribir
   no es la decisión de un humano, y convertirlo en negativa sería la peor
   decisión posible de un mecanismo de fichero.

## Cuerpo condicional

`owner = HANDLER_CONTINUATION`, igual que lock: el cuerpo sólo corre con `Proceed`.
Un `Abort` es una decisión explícita de no continuar, y ejecutar el cuerpo pese a
ella sería mentir sobre la semántica del paso.

## Verificación prevista (sin sorpresas de diseño)

```text
HF0  contrato, ADTs, cruce de presupuesto, round-trip de codecs
HF1  puerto con dobles: proceed, abort, malformada, doble respuesta, timeout
HF2  UAT con el CLI real: 8 escenarios de la spec §7.7
mut  M-in: replay del re-run que vuelve a preguntar → debe poner rojo
fit   fitness anti-inline-wire + exhaustividad de la familia de bloques
```
