# Evidence bundle STATUS — 0.57.0-rc1

**Última actualización:** 2026-10-13T08:05Z
**HEAD actual:** `ff7e6bac43f9e25bc8ed72c14bfa1b4c10c3787dd` (rama `pk-release-0-57-consolidation`)
**Ciclo:** pk-release-0-57-consolidation

## Estado honesto

`0.57.0 — IMPLEMENTED_UNCERTIFIED / RELEASE_BLOCKED / BLOCKED_ENVIRONMENT**

## Lo que cambió en esta sesión (2026-10-13)

Tres commits del operador para arrancar el mandato autónomo de hoy:

| SHA | Mensaje |
|---|---|
| `a904438c` | `docs(roadmap): operator 2026-10-13 directives for PK-57A..PK-59A and Fabric coordination` |
| `5ee66d0e` | `fix(cli): real intersection-based --output-protocol negotiation per ADR-PK1-OUTPUT-FRAMING` (no compilaba) |
| `ff7e6bac` | `fix(cli): repair OutputProtocol KDoc and Main.kt return after the conflict fix landed broken` (compila) |

`5ee66d0e` introduce los tres arreglos que el `docs/Roadmap_Agente_PipelineK_2026-10-13.md`
nombraba explícitamente:

1. Wire ids renombrados a `pipelinek.output/1` y `pipelinek.output/2` (eran
   `output/v1`/`output/v2`, nunca en ADR previo). Alineados con el patrón
   `pipelinek.*/N` que exige `ADR-PK1-OUTPUT-FRAMING`.
2. Negociador de `--output-protocol` reescrito: `tokens.last()` →
   `OutputProtocol.negotiate(tokens)` con intersección + prioridad cliente
   + `null` fail-closed. El wrapper en Main.kt emite el envelope
   `CliExit.InvalidArgument` con la lista de soportados.
3. `CliParser.kt:915` corrige el comentario "the last one wins" → "lista
   de preferencia (ordenada por prioridad); resolución por intersección".

11 casos nuevos en `OutputProtocolNegotiationTest.kt`:
vacío→DEFAULT, único válido, `[válido,inválido]`, `[inválido,válido]`,
todos desconocidos→null, duplicados con colapso por prioridad, blank
tokens, ids legacy (`output/v1`,`output/v2`) NO silenciosamente mapeados
(regression net del renombrado), `SUPPORTED_IDS` exactos, `fromId`
round-trip.

`ff7e6bac` corrige dos defectos del commit anterior que lo dejaban sin
compilar: la KDoc contenía la secuencia literal `*/` (cierre del
comentario); `Main.kt:1297` perdía el `return` antes de
`OutputProtocol.DEFAULT`.

## Compilación verificada

`./gradlew --no-daemon --max-workers=1 :pipeline-application:compileKotlin`
**BUILD SUCCESSFUL in 28s** (después de aplicar `ff7e6bac`). El código
es sintácticamente correcto.

## Lo que NO se ha podido ejecutar (BLOCKED_ENVIRONMENT)

| Gate | Estado | Bloqueante |
|---|---|---|
| `:pipeline-application:test --tests *OutputProtocolNegotiationTest*` | NO EJECUTADO | host load 1-min 16.24 > umbral 5 (`BLOCK_E_RUN_21`); watchdog killed a 300s |
| `:pipeline-application:test` (suite completa) | NO EJECUTADO | mismo |
| `:pipeline-release:releaseGate` | NO EJECUTADO | mismo |
| `:pipeline-release:uatInstalled` (binario ZIP) | NO EJECUTADO | mismo |
| Regenerar ZIP/SBOM/POMs/JAR/SHA256SUMS | NO EJECUTADO | mismo |

**Carga del host durante esta sesión** (mediciones sucesivas):

| Hora | 1-min | 5-min | 15-min |
|---|---|---|---|
| 09:22Z | 3.06 | 5.60 | — |
| 09:45Z | 7.03 | 4.84 | 5.07 |
| 10:05Z | 5.89 | 4.93 | 5.08 |
| 10:30Z | 7.21 | 5.46 | 5.26 |
| 10:45Z | 3.99 | 4.89 | 5.09 |
| 11:00Z | 16.24 | 11.60 | 7.99 |
| 11:30Z | 12.76 | 12.05 | 8.49 |

El umbral <5 no se ha mantenido el tiempo suficiente para correr el
test suite completo. Cualquier intento anterior terminó en watchdog
timeout.

## Lo que falta para el cierre del certifier

| Bloque | Estado | Bloqueante |
|---|---|---|
| R1-F (gate de plugins) | CERRADO bajo BLOCKED_ENVIRONMENT con 5 mutaciones ROJO | Re-medir a load < 5 |
| **PK-57A.1** Wire ids → `pipelinek.output/N` | CERRADO en código (`ff7e6bac`); compila | Re-medir tests a load < 5 |
| **PK-57A.2** Negociador por intersección | CERRADO en código (`ff7e6bac`); compila | Re-medir tests a load < 5 |
| **PK-57A.3** Matriz 8 tests del decoder | CERRADO en commits previos (`41727c5e`) | Re-medir a load < 5 |
| **PK-57A.4** releaseGate con falsificadores | NO EJECUTADO | load < 5 |
| **PK-57A.5** UAT/AAT contra binario instalado | NO EJECUTADO | load < 5 |
| R3 (WURp023, UatS2R0, smoke-e2e) | PENDIENTE | load < 5 |
| R4 (push, PR, merge, tag v0.57.0-rc1, gh release) | NO EJECUTADO | gates verdes primero |
| R5 (certifier 13 escenarios) | PENDIENTE | autoridad independiente |

## Por qué NO se publica `v0.57.0-rc1`

El propio mandato dice:

> "Si el gate queda rojo/omitido, NO publicar una release de ese alcance:
> subir commits y evidencias de bloqueo, conservar candidata o rama pero
> reportar `BLOCKED` (no `PASS`)."

El gate de tests no se ejecutó aCompletion por timeout de watchdog, no
por un fallo real. Eso es **omitido**, no `PASS`. Publicar
`v0.57.0-rc1` en este punto sería falsear el estado del gate.

## Procedimiento para re-intento cuando load <5

1. `./gradlew --no-daemon --max-workers=1 :pipeline-application:test --tests "*OutputProtocolNegotiationTest*"`
   — Debe pasar los 11 casos.
2. `./gradlew --no-daemon --max-workers=1 :pipeline-application:test`
   — Suite completa, incluyendo decoder y distribution plugins.
4. `./gradlew --no-daemon --max-workers=1 :pipeline-release:releaseGate`
   — Gate de release con falsificadores.
5. Regenerar artefactos ZIP/SBOM/POMs/JAR; regenerar `SHA256SUMS`;
   re-correr `git-head.txt`; actualizar este STATUS con nueva fecha y
   estado `CANDIDATE_PUBLISHED` SOLO si los 4 anteriores son PASS.
6. `git push origin pk-release-0-57-consolidation`
7. `gh pr create --base main --head pk-release-0-57-consolidation`
8. `gh pr merge --merge` (vía policy del repo)
9. `git tag -a v0.57.0-rc1 <merge-sha> -m "PipelineK v0.57.0-rc1 — ..."`
10. `gh release create v0.57.0-rc1 --prerelease ...`
11. Emitir `PK_CONTRACT_HANDOFF` a Fabric (estado `CANDIDATE_PUBLISHED`,
    NO `PRODUCT_GATE_PASS` ni `PAIR_CERTIFIED`).

## Artefactos en disco

| Artefacto | Generado en SHA | Estado |
|---|---|---|
| `pipelinek-0.57.0.zip` | `a95956b` | **STALE-RES-REQUIRED** si el operador exige ZIPs regenerados contra HEAD `ff7e6bac` |
| `pipelinek-0.57.0.sbom.json` | `a95956b` | Idem |
| `pipeline-application-0.57.0.jar` | `a95956b` | Idem |
| `pipeline-domain-0.57.0.pom` | `a95956b` | Idem |
| `pipeline-sdk-bom-0.57.0.pom` | `a95956b` | Idem |
| `sdk-domain-0.57.0.module` | `a95956b` | Idem |
| `HANDOFF-TO-CERTIFIER.md` | c9df67f1 | Vigente (sin cambios en esta sesión) |
| `SHA256SUMS` | regenerado 2026-10-12 | Vigente para los artefactos en disco |
| `git-head.txt` | c9df67f1 | **STALE** — debe regenerarse a `ff7e6bac` cuando se ejecute R3 |

## Working tree actual: árbol no commiteado

`examples/04-kotlin-control-flow.pipeline.kts` tiene `M countdown 3→13 + sleep 1`
(no commiteado). No es parte del scope PK-57A; queda fuera.