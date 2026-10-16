# BQ1..BQ5 — UAT/AAT de ciclo (propuesta, efímeras)

**Esta matriz es el contrato declarativo de aceptación, no la implementación JUnit.** Si se acepta, persiste; los scripts de sesión UAT/E2E/AAT se borran al finalizar su bloque una vez sustituidos según `ADR-PK-QV-003` y la normativa actual.

| Hito | ID | Tipo / HF | Entrada | Oráculo observable | Futura garantía |
|---|---|---|---|---|---|
| BQ1 | AAT-BQ1-01 | AAT/HF0 | XML `tests=0`, fallos=0 | `Refused(EMPTY)` | evidence contract |
| BQ1 | AAT-BQ1-02 | AAT/HF0 | XML all-skipped | `Refused(ALL_SKIPPED)` | evidence contract |
| BQ1 | AAT-BQ1-03 | AAT/HF0 | cambio ServiceLoader sin binding | `Refused(Unclassified)` | impact fitness |
| BQ1 | UAT-BQ1-01 | UAT/HF1 | `validationPlan` con cambio en fuente real | explica owners, suites y frontera | CLI test + property |
| BQ1 | AAT-BQ1-04 | AAT/HF2 | binario informa otro buildCommit | se niega veredicto | provenance contract |
| BQ2 | UAT-BQ2-01 | UAT/HF1 | cambio de reducer, ejecutar lane rápida | no arranca `installDist` | DAG fitness |
| BQ2 | AAT-BQ2-01 | AAT/HF1 | romper tarea de dist con cambio domain | test afectado sigue siendo ejecutable | lane separation |
| BQ2 | AAT-BQ2-02 | AAT/HF1 | suite seleccionada no ejecuta | rechazar veredicto | dispatch contract |
| BQ2 | UAT-BQ2-02 | UAT/HF2 | dist instalada, 3 pasos + error | exit/steps/outcome correcto | external certifier |
| BQ3 | UAT-BQ3-01 | UAT/HF2 | script real por ruta refactorizada | mismo outcome/events/effects que baseline | scenario + contract |
| BQ3 | AAT-BQ3-01 | AAT/HF0 | mutación de rama ADT | property test falla por causalidad | reducer property |
| BQ3 | AAT-BQ3-02 | AAT/HF3 | reanudar con side effect memorizado | no side effect repetido | replay contract/harness |
| BQ3 | AAT-BQ3-03 | AAT/HF0 | esquema durable no soportado | rechazo tipado sin default | decode contract |
| BQ4 | UAT-BQ4-01 | UAT/HF2 | test temporal contra artefacto actual | evidencia SHA + exit + side effect | closure contract |
| BQ4 | AAT-BQ4-01 | AAT/HF0 | XML vacío/falso green en cycle | `close` se niega | pure lifecycle |
| BQ4 | AAT-BQ4-02 | AAT/HF0 | manifest con archivo fuera de cycle root | guard niega borrado | path safety |
| BQ4 | AAT-BQ4-03 | AAT/HF2 | canario secreto | no se filtra ni se almacena secreto | secret contract/certifier |
| BQ5 | UAT-BQ5-01 | UAT/HF2..HF4 | ZIP mismo digest en productor/harness | mismo fallo detectado y veredicto | external receipt |
| BQ5 | AAT-BQ5-01 | AAT/HF2 | manifest/API range adulterado | rechaza candidato antes de efecto | release admission |
| BQ5 | AAT-BQ5-02 | AAT/HF3 | matar run antes del seal | recuperación/no duplicación | recovery certifier |
| BQ5 | AAT-BQ5-03 | AAT/HF4 | canario secreto en sandbox | cero fugas, aislamiento de proceso | security certifier |

## Condición universal de borrado

Cada fila del ciclo se acompaña de `cycleId`, `sourceSHA`, `artifactSha256` si aplica, `fidelity`, `testCase`, `observedVerdict`, `mutationOrNegative`, `replacementPath` y `replacementEvidence`. Si alguno falta: `NOT_RUN` o `BLOCKED`, no borrar.

La capacidad UAT de `PRODUCTION_READY_UAT_MATRIX.md` sigue siendo obligatoria incluso después de borrar un probe que la ensayaba; el sustituto definitivo debe referenciar ese mismo ID cuando corresponda.
