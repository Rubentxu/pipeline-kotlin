# Milestone Gates y Definition of Done

| Hito | Demo visible | Gate crítico | Riesgo que elimina |
|---|---|---|---|
| M0 | V2 build limpio | ningún exclude V2 | deuda/baseline falsa |
| M1 | `.pipeline.kts` local | scripting reproducible | riesgo Kotlin host |
| M2 | Jenkins-like DSL | no FIR/IR required | DX/Step architecture |
| M3 | kill+resume local | no side effect replay | viabilidad durability |
| M4 | worker remoto | fencing/ACK | viabilidad distribuida |
| ML | ecosistema local (sh durable, sandbox, creds, steps) | kill-durante-sh sin replay | usabilidad/ejecución real local |
| M5 | Pod efímero | Pod loss recovery | K8s/credentials |
| M6 | Jenkins UI | controller lightweight | integración producto |
| M7 | pipeline real | plugin ecosystem | cobertura funcional |
| M8 | provenance/fork | graph from events | diferenciación |
| M9 | chaos/perf | SLO/security | producción |
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
- tech debt nueva registrada con owner/fecha/condición de salida.
