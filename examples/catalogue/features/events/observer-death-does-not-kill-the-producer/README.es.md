# Observer Death Does Not Kill The Producer

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `events`

**Resultado esperado:** `success`

## Que ejercita

La via de eventos: que le cuenta una corrida a un consumidor, y en que orden

*El ejemplo dice de si mismo:*

> 08-observer-death-does-not-kill-the-producer.pipeline.kts — the observer dies; the run does not.

## Las etapas y los pasos

### 1. `observe`

- `sh` — `""" setsid env -u PIPELINE_OP_COOKIE -u DURABLE_SH_COOKIE -u DURABLE_SH_OPID '${product...`
- `sh` — `""" observer_pids() { local record cmd pid for record in ${'$'}(command grep -l -z -F -...`
- `sh` — `echo 'the producer survived the death of its observer'`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- durable-once
- run-state

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/events/observer-death-does-not-kill-the-producer/observer-death-does-not-kill-the-producer.pipeline.kts
pipelinek run      examples/catalogue/features/events/observer-death-does-not-kill-the-producer/observer-death-does-not-kill-the-producer.pipeline.kts
```

## Su linaje

- **Nombre historico:** `08-observer-death-does-not-kill-the-producer.pipeline.kts`
- **Ruta de origen:** `examples/events/08-observer-death-does-not-kill-the-producer.pipeline.kts`
- **Huella del cuerpo:** `sha256:90ca2ffb2c248ac60d0a21dea71fde3f97301e38f142812fb3f82034ce372827`
