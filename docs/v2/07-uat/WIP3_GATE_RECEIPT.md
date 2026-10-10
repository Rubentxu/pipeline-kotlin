# WIP-3 — §1.2 Re-correr gate sobre HEAD con recursos reservados

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** `11159258-a538-43a8-9580-57f0f4f04a2b` (ciclo `b1-v0-48-0-rc2`)
**SHA candidato:** `8a0c2fee445e2d09127b0b01de67ee4d7b74d5ae` (cierre de WIP-2; ver también `bdfac8a7` corrección WIP-2.6)

## Veredicto

**NOT_RUN_BLOCKED_BY_LOAD.** El Gradle daemon desapareció durante `pipeline-application:test`. Mismo síntoma
que el intento del gate anterior `2f5ba9aa` (recibido en `BLOCK_E_FULL_GATE_2F5BA9AA_RECEIPT.md`).
No es PASS.

## Datos del run

| Métrica | Valor |
|---|---|
| Comando | `cd v2 && ./gradlew check --rerun-tasks --console=plain --no-daemon` |
| Log | `/tmp/full-gate-8a0c2fee.log` (1.606 líneas, 145 KB) |
| Duración | ~30 min (10:46:21 → ~11:16) |
| Veredicto | NOT_RUN_BLOCKED_BY_LOAD |
| Tests pasados | 303 |
| Tests fallados | 1 real + daemon crash |
| Exit del wrapper bash | 0 (mentira: la última fase no se ejecutó) |
| Exit del gradle | (nunca llegó a imprimirlo, daemon murió) |

## Hallazgos del run

### F-1: Gradle daemon crash (mismo que gate `2f5ba9aa`)

```
FAILURE: Build failed with an exception.
* What went wrong:
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

El log muestra la última fase como `pipeline-application:test`, y luego el wrapper recibe la noticia
de que el daemon desapareció sin completar la fase ni imprimir BUILD SUCCESSFUL/FAILED global.

Causa más probable (misma que el gate anterior): el kernel OOM-killed el daemon bajo presión de
memoria combinada con alta concurrencia de procesos Java en el host (al iniciar este run había
~10 procesos Java no-Gradle con RSS entre 0.1 GB y 5.4 GB; load average 9.59 al inicio, subió
durante la ejecución). `free -h` mostraba 44 GB available, pero la presión combinada más allá
de la disponibilidad nominal parece suficiente para OOM-kill selectivo del Gradle daemon.

### F-2: FAILED real en `DirectivePluginContractSuiteTest`

```
DirectivePluginContractSuiteTest > plugin jar is the certified build and was not rebuilt for this core() FAILED
    org.opentest4j.AssertionFailedError at DirectivePluginContractSuiteTest.kt:126
```

**Esperado:** SHA-256 del JAR externo = `d0a80b9b87fc8cd741035bc68dbd89a5af90f3f7ded33ca7a9dc394164f89407`.
**Real del JAR `examples/example-directive-plugin/build/libs/example-directive-plugin-0.1.0.jar`:**
`283f89d7aae74580d0f57430d8f6ed7a36f0b9428ed48ee515b49136a78c34a8`.

El plugin externo ha **drifteado** desde los bytes certificados. La intención del test (S2-D R4) es
exactamente detectar esto: el pin convierte "no se ha reconstruido" en una aserción medible. El
test no es un fallo del repo sino del estado del artefacto externo.

Esto significa:
- La compatibilidad certificada en S2-D ya **no es válida** con estos bytes.
- Restaurar el JAR certificado, o re-certificar con evidencia si el cambio fue legítimo, son las
  dos rutas permitidas por el propio test.
- **Esto NO es un crash de gate**; es un fallo de producto que el gate ha detectado correctamente.

### F-3: Tests OUT-01 y OUT-02 NO se ejecutaron en este run

Los tests adversariales `OUT-01 prune of a short runId...` y
`OUT-02 distinct runIds whose safe forms are equal...` se añadieron al fichero
`SegmentOutputStoreTest.kt` después de que `pipeline-output-store:test` ya había corrido
en este gate (el módulo se procesó en los primeros minutos del run; los tests se escribieron
cuando ya estaba en `pipeline-application:test`).

Verificado: el XML `TEST-dev.rubentxu.pipeline.v2.output.store.SegmentOutputStoreTest.xml`
contiene 30 testcases (igual que antes del cambio). Los 2 nuevos testcases están en disco pero
no fueron compilados/ejecutados por este gate.

**Implicación para WIP-4 (OUT-01) y WIP-5 (OUT-02):** los tests adversariales necesitan un gate
adicional focalizado en `pipeline-output-store:test --rerun-tasks` para validar que fallan (reproduciendo
los hallazgos del roadmap §1.3 antes de cualquier fix).

## Diagnóstico del crash (siguiendo §1.2 del roadmap)

| Item | Estado |
|---|---|
| Consumo real de memoria y procesos concurrentes | El host tenía ~10 procesos Java externos (~3 GB RSS combinado) + Gradle daemon (~2 GB) + Kotlin compiler daemons. Load subió de 9.59 a >15. |
| Límites del host/cgroup | No verificados explícitamente; 44 GB available al inicio sugiere que no fue OOM global. |
| Logs de Gradle | `/tmp/full-gate-8a0c2fee.log` con la traza del daemon crash. |
| Posibles procesos Java residuales | Sí: `org.gradle.launcher.daemon.bootstrap.GradleDaemon` (PID 1562070) y `GradleDaemon 8.14.5` (PID 3968623) siguen vivos — son de builds anteriores, no del run actual (que usaba `--no-daemon`). |
| Reproducibilidad en entorno con recursos reservados | No intentado en este WIP. El entorno actual es compartido. |

## Acción tomada por este WIP

1. Lanzar el gate con el comando exacto del roadmap §"Pruebas mínimas".
2. Capturar el log completo.
3. Registrar la causa raíz probable y los hallazgos laterales.

## Acción NO tomada y por qué

- **Re-correr el gate en entorno aislado.** Imposible en este WIP sin crear worktree o
  contenedor dedicado. Diferido a un intento posterior.
- **Restaurar el JAR certificado o re-certificar.** Es una decisión de producto que afecta
  a S2-D; pertenece al B2 (certificación) o a un WIP dedicado de §1.3 sobre el plugin
  externo. Lo dejo registrado aquí para no olvidarlo.

## Consecuencias para el roadmap

1. **WIP-4 y WIP-5 (OUT-01, OUT-02)** necesitan un gate focalizado sobre `pipeline-output-store`.
   El código adversarial está en disco pero no fue ejercitado por el run global.
2. **F-2 (plugin JAR drift)** requiere acción separada: o restaurar los bytes certificados
   desde una fuente verificable (qué?) o re-certificar `c424a9b2` → nuevo digest. Esto NO
   es un fallo de WIP-1..WIP-3; es un fallo de S2-D que el gate ha detectado correctamente.
3. **El "objetivo" de WIP-3 no se cumplió**: no hay `BUILD SUCCESSFUL` global sobre HEAD.
   El gate sigue incompleto, como antes de este WIP.

## Próximo paso

WIP-4: ejecutar `cd v2 && ./gradlew :pipeline-output-store:test --rerun-tasks --console=plain --no-daemon`
para validar que los tests adversariales OUT-01 y OUT-02 reproducen los hallazgos del roadmap §1.3.