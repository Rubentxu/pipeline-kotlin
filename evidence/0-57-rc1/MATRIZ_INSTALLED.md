# Matriz CLI instalada — binario extraído del distZip
**SHA del head:** `436af662292cd50220144fd33311fe6ff395a1c2`
**Binario:** `v2/pipeline-application/build/install/pipelinek/bin/pipelinek`
**Comando:** `./pipeline-application/build/install/pipelinek/bin/pipelinek validate --output-protocol <id> examples/04-kotlin-control-flow.pipeline.kts`
**Fecha:** 2026-10-13T11:30Z

## Matriz ejecutada

| Caso | Comando | Resultado |
|---|---|---|
| 1 | `--output-protocol pipelinek.output/2` (válido, SeparatedV2) | VALIDATION SUCCESSFUL — negociador acepta /2 |
| 2 | `--output-protocol pipelinek.output/1` (válido, Published) | VALIDATION SUCCESSFUL — negociador acepta /1 |
| 3 | sin `--output-protocol` (default) | VALIDATION SUCCESSFUL — default Published, comportamiento idéntico a R0.3 |
| 4 | `--output-protocol pipelinek.output/bogus` (desconocido) | **exit 2** envelope `pipelinek.cli/1` con mensaje enumerando soportados |
| 5 | `--output-protocol output/v1` (legacy id pre-RC) | **exit 2** envelope `pipelinek.cli/1` — NO se mapea silenciosamente al nuevo id |
| 6 | `--output-protocol ''` (vacío) | `MissingOptionValue` al boundary parser (rechaza antes de negociador) |

## Envelope producido (caso 4)

```json
{
  "schema": "pipelinek.cli/1",
  "exit": "InvalidArgument",
  "code": 2,
  "message": "no acceptable --output-protocol in [pipelinek.output/bogus]. Supported: pipelinek.output/1, pipelinek.output/2",
  "hint": "see ADR-PK1-OUTPUT-FRAMING for the negotiation contract"
}
```

Verifica:
- exit code 2 ✅
- schema `pipelinek.cli/1` ✅
- `message` lista los ids soportados ✅
- `hint` referencia el ADR ✅

## Conclusión

El cambio de wire id (`output/v[12] → pipelinek.output/[12]`) y el nuevo
negociador por intersección funcionan end-to-end contra el binario
extraído del distZip. La fail-closed envelope coincide con lo que el
ADR-PK1-OUTPUT-FRAMING exige.