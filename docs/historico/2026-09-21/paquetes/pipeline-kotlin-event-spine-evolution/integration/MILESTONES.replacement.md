# Milestone Gates y Definition of Done

| Hito | Demo visible | Gate crítico | Riesgo que elimina |
|---|---|---|---|
| M0 | V2 build limpio | ningún exclude V2 | deuda/baseline falsa |
| M1 | `.pipeline.kts` local | scripting reproducible | riesgo Kotlin host |
| M2 | Jenkins-like DSL | no FIR/IR required | DX/Step architecture |
| M3 | kill+resume local | no side effect replay | viabilidad durability |
| M4 | worker remoto | fencing/ACK | viabilidad distribuida |
| ML | ecosistema local (sh durable, sandbox, creds, steps) | kill-durante-sh sin replay | usabilidad/ejecución real local |
| EM | modelo durable Kotlin canónico | única authority + replay tipado | ambigüedad de ejecución |
| EVT | histórico estructurado + examples verificados + live tail aislado | observer crash/lag no afecta run; cursor/replay probado | observabilidad verificable y base M4/M6 |
| POL | Cedar shadow + simulación histórica | audit no afecta PipelineOutcome; enforcement separado | guardrails medibles antes de bloquear efectos |
| M5 | Pod efímero | Pod loss recovery | K8s/credentials |
| M6 | Jenkins UI | controller lightweight + event→FlowNode live | integración producto |
| M7 | pipeline real | plugin ecosystem | cobertura funcional |
| M8 | provenance/fork | graph from events | diferenciación |
| M9 | chaos/perf/policy enforcement | SLO/security | producción |
| M10 | GA | migration/soak | adopción |

## Definition of Done de cualquier milestone

- código integrado en módulos definitivos o marcado spike;
- no TODO crítico escondido tras excludes;
- tests unit/integration/contract adecuados;
- ADR/spec actualizados;
- metrics/logging mínimo;
- failure paths principales probados;
- UAT ejecutada y evidencia guardada;
- demo reproducible desde README del milestone;
- tech debt nueva registrada con owner/fecha/condición de salida;
- una capability observable soportada tiene example installDist real o una excepción justificada;
- example acceptance valida outcome + contrato observable, no sólo compilación;
- observers/relays prueban aislamiento de fallo y, cuando son live, presupuesto/lag;
- cambios de envelope/identidad/event ordering documentan compatibilidad/migración;
- ningún global gate NON-ZERO se etiqueta PASS: se aplica Rule-16 para separar deuda previa del delta.
