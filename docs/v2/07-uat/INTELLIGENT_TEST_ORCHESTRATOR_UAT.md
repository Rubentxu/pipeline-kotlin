# UAT — Intelligent Test Orchestrator (ITO)

**Estado inicial: PLANNED/NOT_RUN para TODAS las filas; no se han ejecutado pruebas.** ADR-0094 propuesto; contrato: ../03-specifications/INTELLIGENT_TEST_ORCHESTRATOR.md. Este documento prueba ITO, no sustituye ni renombra UAT-RP-001..027, STEP_PLUGIN_CERTIFICATION C01..C19 o G0..G8. Cada ejecución debe indicar SHA y tree/snapshot, config digest, comandos/exit, tests reales/failures/skipped/not-run, assertions, OS/JDK, artifacts y ubicación/digest del log fuera del repo, duración y estado PASS/FAIL/BLOCKED/INCOMPLETE/NOT_RUN.

## A. Motor Git / planificación (HF0/HF1)
| ID | Preparación y ejecución | Oráculo inequívoco |
|---|---|---|
| UAT-ITO-001 | Git repo temporal con commit base, staged, unstaged, untracked y rename; simular archivo secreto | ChangeSet incluye cada path/tipo una sola vez; no vuelca secreto. |
| UAT-ITO-002 | Rama con varios commits; calcular merge-base vs destino y diff de WU | Plan refleja todos los cambios, no solo último commit; base explícita. |
| UAT-ITO-003 | Cambios aislados en Step, event codec y DSL contra ownership + consumidores | explain señala paths, contratos y tests pertinentes; no propone suite completa por simple cambio local. |
| UAT-ITO-004 | Archivo nuevo sin owner, Gradle manifest/lockfile y contrato público modificado | UNKNOWN provoca ampliación conservadora y registra razón; nunca plan vacío verde. |
| UAT-ITO-005 | YAML correcto, versión desconocida, typo de clave obligatoria, contrato cíclico, tags arbitrarios | versión buena compila plan estable; errores fail-closed antes de correr comandos. |
| UAT-ITO-006 | Romper build V2 intencionadamente; iniciar CLI desde otro build limpio | CLI plan/doctor funciona sin instalar pipelinek ni importar application. |

## B. Supervisor, runners, estado fuera del repo (HF1/HF2)
| ID | Preparación y ejecución | Oráculo inequívoco |
|---|---|---|
| UAT-ITO-007 | Runner fake produce stdout y stderr >capacidad de pipe y termina | finaliza dentro de deadline, ambos canales drenados, sin deadlock/bloqueo de Process.waitFor. |
| UAT-ITO-008 | Proceso con hijo/descendiente bloqueado; cancelar y activar timeout | reporta CANCELLED/TIMED_OUT según causa; mata descendientes o informa fuga con PID; cleanup ALWAYS. |
| UAT-ITO-009 | Runner devuelve 0 tests, XML anterior/ausente/truncado o solo skipped | NO_TESTS/INCOMPLETE según contrato; jamás PASS usando archivo antiguo. |
| UAT-ITO-010 | Ejecutar PASS, FAIL, skipped obligatorio y timeout | evidencia y contadores distinguen los cuatro estados; CI no declara verde por exit 0 de wrapper. |
| UAT-ITO-011 | Repetir inputs idénticos en dev y luego tocar fuente/test/YAML/lock/JDK o worktree durante corrida | REUSED explícito solo bajo fingerprint válido, STALE con modificación concurrente; release no hereda cache dev. |
| UAT-ITO-012 | Dos clones/worktrees mismo nombre + procesos concurrentes + kill durante escritura JSON | identidades separadas, índice recuperable, no corrupción, política de GC. |
| UAT-ITO-013 | Ejecutar ephemeral/local/ci sobre repo cuyo Git status se compara antes/después | ITO no escribe estado, cache ni reports bajo repo; ci exporta solo artifacts configurados; runners ajenos aislados cuando se exige repositorio intacto. |
| UAT-ITO-014 | Ejecutar Gradle/JUnit de prueba y command con selector no representable; inyectar shell metacaracteres | argv/cwd/env seguros, ampliar selección o bloquear con razón; nunca shell eval ni 0 tests false green. |

## C. Perfiles, UAT first-class y gates (HF1–HF5)
| ID | Preparación y ejecución | Oráculo inequívoco |
|---|---|---|
| UAT-ITO-015 | Un archivo cambia en módulo A mientras B tiene tests requeridos para integración | dev ejecuta selección mínima justificada; integration ejecuta TODOS los obligatorios A+B sin mirar diff. |
| UAT-ITO-016 | Cambiar contrato compartido con consumidores en dos módulos | verify incluye consumer tests/fitness/UAT por dependencia inversa, registra ampliación. |
| UAT-ITO-017 | Fixture CLI real: setup externo → ejecución installed → oráculo exit/evento/bytes/hash final → cleanup | assertions comprueban efectos y contenido, no solo exit=0; paths externos y snapshot inalterado. |
| UAT-ITO-018 | UAT con efecto durable: guardar marcador, kill, restart y compare antes/después | efecto no se duplica sin autorización; rerun distinto de restart y evidencia de ambas fases. |
| UAT-ITO-019 | Sandbox OS habilitado y prueba de traversal/egress prohibido | rechazo sin efectos, registra capacidad efectiva; ausencia de sandbox = BLOCKED para perfil no confiable. |
| UAT-ITO-020 | Full gate shard A PASS, shard B CANCELLED, shard C ausente | cobertura de IDs disjunta/exhaustiva falla: INCOMPLETE, no PASS parcial. |
| UAT-ITO-021 | Evidencia/ZIP de commit A y candidato B diferente, o worktree sucio | fingerprint invalida B; no certificar por similitud de SHA/artefacto. |
| UAT-ITO-022 | UAT obligatoria skipped, fallo infra, XML faltante y una aserción UAT falla | gate no pasa, receipt indica exactamente qué falta, no modifica UAT/recibos históricos. |
| UAT-ITO-023 | Shadow mode con cambios Step, codec y DSL: comparar plan con oráculo de impacto y suite completa independiente | ninguna UAT/test obligatorio perdido en conjunto representativo; los riesgos UNKNOWN quedan visibles. |
| UAT-ITO-024 | 3+ repeticiones cold/warm con hardware/SHA fijos; medir first failure, wall, compile y selection | datos, p50/p95 y presupuesto propuesto documentados; no prometer ahorro sin comparación controlada. |
| UAT-ITO-025 | Proyecto de muestra Gradle/JUnit y otro pytest (runner command permitido) | tests reales de dos lenguajes ejecutados; reportes frescos/contadores y ningún acoplamiento V2. |
| UAT-ITO-026 | Policy YAML intenta eliminar UAT-RP obligatoria o Gradle selector "exclusión" vacío | validador detecta la reducción o el comando no-excluyente; matriz V2 intacta y gate existente se sigue ejecutando. |

## D. Step oficial externo (solo RP-7; HF0–HF3)
| ID | Preparación y ejecución | Oráculo inequívoco |
|---|---|---|
| UAT-ITO-027 | Compilar plugin independiente contra SDK y ejecutarlo sin modificar core | contributor, manifest/digest, input/output codec, DSL/IR y registro genérico correctos; zero per-Step coordinator changes. |
| UAT-ITO-028 | Pipeline.kts real con testing.verify y plugin JAR instalado | handler ejecuta suite real, typed output, eventos y Event Harness de ciclo/resultado coinciden. |
| UAT-ITO-029 | Denegar capability process/workspace con contador de efectos | missing capability rechaza ANTES del efecto, counter=0. |
| UAT-ITO-030 | Cancelar, agotar deadline y reanudar un intento de testing | árboles de proceso cerrados y replay/cancelación según contrato; ninguna reejecución oculta ni PASS cacheado. |
| UAT-ITO-031 | Distribución limpia con plugin JAR/digest válido y luego ABI/provenance inválida | success/error fail-closed; G0..G8, C01..C19 aplicables, plugin external real y evento comprobados. |

## E. Compatibilidad posterior y dogfooding (RP-7)
| ID | Preparación y ejecución | Oráculo inequívoco |
|---|---|---|
| UAT-ITO-032 | Maven/JUnit y Gradle/JUnit con selector de método/clase | selección exacta o fallback explícito, XML fresco y conteos correctos. |
| UAT-ITO-033 | pytest + Jest/Vitest sobre fichero/test, caso negativo y cero coincidencias | reportes correctos y NO_TESTS cuando proceda. |
| UAT-ITO-034 | Cargo + Go en packages con filtros limitados | test de selección soportada y ampliación conservadora cuando no existe granularidad. |
| UAT-ITO-035 | Dos proyectos reales de tecnologías distintas, cambio pequeño, regresión intencional y candidata release | menos wall/first-fail comprobado frente a baseline, regresión capturada, full gate íntegro y cero morralla ITO en repo. |

## Principios de prueba
- UAT del motor autónomo ≠ UAT del plugin ≠ UAT del proyecto consumidor; no heredar PASS entre ellas.
- UAT-RP-001..024 del producto local son externas a ITO y continúan obligatorias según contrato de perfil; ITO puede invocarlas y recolectar evidencia pero no cambiar el oráculo ni dar PASS si falta infraestructura.
- El test de selección NO puede certificarse exclusivamente con su propia selección. Independencia: fixture de referencia y full gate no gobernados por el selector bajo prueba.
- Failure injection debe ser controlada: test positivo demuestra resultado y test negativo demuestra que el gate rechaza un incumplimiento sin ocultar la causa.