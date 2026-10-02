# WU-092 `core.input` — Plan de gates G1..G4 (RP6-B)

> cycle SDDK: `p-1f3622e11c093341/rp6b-input` (fase plan)
> spec `docs/v2/07-uat/SPEC_WU092_INPUT.md`, design `docs/v2/07-uat/RP6B_INPUT_DESIGN.md`
> precedente vivo: los gates G1..G4 de RP6-A, ya ejecutados y verdes en main.

## G1 — Contrato, ADTs y codecs

```text
CoreInputInput, InputDecision, InputDenialReason, CoreInputOutput
CoreInputWireCodec (round-trip; contradicción de message vacío → typed)
CoreInputStep descriptor + handler esqueleto
mutaciones M-in-1..M-in-4 (decodificación, negación, owner, presupuesto)
```

Gate: tests del contrato verdes; cada mutación en ROJO.

## G2 — Puerto + adaptador de fichero

```text
InputDecisions (puerto)          suspend fun decide(request, budget): InputResolution
FileInputDecisions (adaptador)   request.json + espera de response.json
                                 CREATE_NEW atómico en la respuesta
                                 malformada → se ignora, no consume
mutaciones: primera respuesta gana / malformada no termina / presupuesto agota
```

Gate: HF1 con dobles, verdes; las tres mutaciones en ROJO.

## G3 — Superficie DSL con autoridad única de wire

```text
G3.1  StepSpec.Input en la jerarquía sellada
G3.2  StageScope.input(message, ok, submitter, id, timeoutSeconds) { }
G3.3  lowering StepSpec.Input → CoreInputInput
G3.4  CoreInputWireCodec como ÚNICA autoridad del wire
G3.5  fitness anti-inline-wire (core.input, sólo él)
G3.6  clasificación sealed/source-compat, con los consumidores reales
```

Gate: `:pipeline-scripting-api:test`, tests de `*Input*`, mapping del compilador,
fitness de arquitectura. Sin suite completa salvo frontera nueva no prevista.

## G4 — Routing de producción y escenarios duros

```text
registro en CoreStepRegistryFactory (nacido en Registry, sin fila legacy)
INPUT_DECISIONS_CAPABILITY en el bridge, condicional al ancla de control dir
4 eventos nuevos en la ADT cerrada de eventos (4 consumidores exhaustivos)
UAT HF2 con 8 escenarios:
  proceed · abort · malformada seguida de proceed · doble respuesta
  presupuesto que agota la espera · cancelación
  el cuerpo NO corre tras abort · la reanudación NO vuelve a preguntar
mutación M-in-reask: el re-run vuelve a preguntar → WL equivalente ROJO
```

Gate: suite completa de los 4 módulos afectados (punto de certificación) + recibo.

## Orden y disciplina

```text
G1 → G2 → G3 → G4, un commit atómico por gate
sólo tests afectados en desarrollo; suite completa al certificar
cada mutación se aplica, se mide en ROJO y se revierte antes de seguir
```
