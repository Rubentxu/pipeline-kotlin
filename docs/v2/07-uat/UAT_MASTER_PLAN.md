# UAT Master Plan

## Propósito

Validar comportamiento percibido por developer, platform engineer, Jenkins admin y operador. UAT se ejecuta por milestone, no sólo al final.

## Roles

- **Developer:** escribe/migra pipeline.
- **Platform Engineer:** configura workers/providers/policies.
- **Jenkins Admin:** instala/configura plugin y observa runs.
- **Security Reviewer:** valida secrets/provenance/isolation.
- **Operator:** recupera y diagnostica fallos.

## Estados

`NOT_READY -> READY -> RUNNING -> PASS | FAIL | BLOCKED`

Toda ejecución UAT guarda:
- build/runtime versions;
- environment manifest;
- scenario id/version;
- actor;
- timestamps;
- event/run IDs;
- evidence links/attachments;
- result/defects.

## Severidad

- P0: safety/data integrity/security/replay duplicate side effect.
- P1: feature crítica no usable.
- P2: degradación con workaround.
- P3: UX/documentación menor.

GA: 0 P0/P1 abiertos en escenarios críticos.

## Gates por milestone

| Milestone | UAT pack |
|---|---|
| M0 | UAT-M0 |
| M1 | UAT-COMP + UAT-EVT |
| M2 | UAT-DSL + UAT-STEP |
| M3 | UAT-REC + UAT-GRAPH local |
| M4 | UAT-PROT |
| M5 | UAT-K8S + UAT-CRED + UAT-SEC |
| M6 | UAT-JENKINS |
| M7 | UAT-PLUGIN + UAT-E2E |
| M8 | UAT-GRAPH + UAT-SC |
| M9 | UAT-CHAOS + UAT-PERF |
| M10 | full regression + migration UAT |

## Ejecución guiada

Cada scenario debe ser ejecutable desde una página/formulario/wizard futuro con:
- Preconditions;
- “Do this” steps;
- expected evidence auto-captured;
- human checks;
- pass/fail/block reason;
- defect creation hook.

El formato Markdown actual está diseñado para poder transformarse más adelante en formularios HTML/UAT agents sin perder IDs.
