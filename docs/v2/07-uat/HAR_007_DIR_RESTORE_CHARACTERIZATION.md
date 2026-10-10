# HAR-007 — Caracterización: `dir(...)` restaura cwd tras error y continúa el pipeline

**Estado actual:** RED (main) / RED (PR #90+#95+#96) — el `cwd` se restaura, pero la pipeline aborta tras el `StepFailed` dentro del bloque `dir(...)`.
**Harness scenario:** [`Rubentxu/pipelinek-release-harness/scenarios/smoke/har007-dir-restore.pipeline.kts`](https://github.com/Rubentxu/pipelinek-release-harness/blob/main/pipelinek-release-harness/scenarios/smoke/har007-dir-restore.pipeline.kts)
**Commit de caracterización (esta WU):** ver `git log -- docs/v2/07-uat/HAR_007_DIR_RESTORE_CHARACTERIZATION.md`
**Base SHA observado:** `70e3d55e` (main) y `ad1f9c5b` (rama rebased cut5-stash-cwd)
**BINARIO probado:** `v2/pipeline-application/build/install/pipelinek/bin/pipelinek` construido desde `ad1f9c5b`.

## Contexto

El operador identificó que el contrato `dir(path) { ... }` debe cumplir tres propiedades simultáneamente:

1. **Cambia cwd** al directorio indicado dentro del bloque.
2. **Restaura cwd** cuando el bloque termina, incluso si un Step dentro del bloque falló.
3. **Continúa la pipeline** con la siguiente instrucción hermana tras un `dir(...)` que falla — el fallo queda confinado al bloque, NO aborta el stage completo (paridad con la semántica Jenkins).

El escenario HAR-007 del harness asume las tres. El comportamiento actual del motor cumple (1) y (2) — el evento `DirExited` registra `restoredTo` correctamente incluso tras un fallo. Pero **(3) NO se cumple**: tras un `StepFailed` el bucle del stage re-lanza y el `dir(...)` siguiente nunca se ejecuta.

## Evidencia en binario (corte 2026-09-24T16:45Z sobre cut5-stash binary)

Comando:

```bash
mkdir -p /tmp/har007-test && cd /tmp/har007-test
mkdir -p ws
cat > har007.pipeline.kts <<'EOF'
pipeline {
    stages {
        stage("dir-restore") {
            dir("errdir") { sh("exit 1") }
            dir("chk") {
                sh("pwd > pwd.txt && grep -qx /tmp/har007-test/ws /tmp/har007-test/ws/chk/pwd.txt && printf 'cwd-restored\n' > /tmp/har007-test/out/marker.txt || echo DID-NOT-RESTORE")
            }
        }
    }
}
EOF

mise x java@temurin-24.0.2+12 -- /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2/pipeline-application/build/install/pipelinek/bin/pipelinek run \
    --db db.sqlite --control-root ctl --workspace ws har007.pipeline.kts
```

Salida observable (eventos relevantes del JSON final):

```json
[
  { "kind": "DirEntered", "path": "ws/errdir", "previousPath": "ws" },
  { "kind": "StepStarted", "stepName": "dir-restore/dir-body-0/sh-0", "stepType": "sh" },
  { "kind": "StepFailed",  "failureKind": "SCRIPT", "message": "shell exited with code 1" },
  { "kind": "StepFinished" },
  { "kind": "DirExited",   "path": "ws/errdir", "restoredTo": "ws" },
  { "kind": "RunFinished", "outcome": "failure" }
]
```

Y en disco:

```
$ test -f /tmp/har007-test/out/marker.txt && cat it
NO
$ test -f /tmp/har007-test/ws/chk/pwd.txt
NO (file does not exist)
```

**El segundo `dir("chk")` nunca corre.** El evento `DirExited` se emite con `restoredTo=ws` correctamente (cwd restaurado), pero el `RunFinished outcome=failure` corta el stage.

## Causa raíz (estado actual)

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt`
en la rama de bloques (`is BlockShellScope.Directory` y siguientes) **no envuelve el cuerpo del bloque en un `try/catch`** que aísle un `StepFailed`. El error sube sin filtro al bucle del stage, que re-lanza y aborta.

Jenkins `dir()` documenta explícitamente *"restored when the block exits (including on exception)"* — la versión actual honra la mitad (restauración de cwd) pero no la continuación del pipeline.

## Contraste con PR #90/#95/#96

Los tres PRs abiertos por el operador (commits `d44579b5`, `082a4e93`, `9bfa4e28`, ahora rebased sobre `70e3d55e` como `75633b80`/`aebd6207`/`95f36e34`/`ad1f9c5b`) introducen el seam `authorizedWorkspaceRoot` vs `effectiveWorkingDirectory` y propagan `effective cwd` a `writeFile`/`readFile`/`fileExists`/`deleteDir`/`stash`/`unstash`. **Ninguno de los tres aborda la propagación de error desde el cuerpo del bloque `dir(...)`.** Verificado en binario:

- Cut5 (con PR #90+#95+#96): `dir("errdir") { sh("exit 1") }` → `RunFinished outcome=failure`. Idéntico a main. El cwd restore funciona, pero el stage aborta.

Por lo tanto el GREEN de HAR-007 requiere **una segunda WU independiente**, con su propio slice y su propia autorización. La presente WU (coherencia del contrato de contexto) **NO la cierra**.

## GREEN-phase propuesto (no ejecutado)

Dos paths posibles; cualquiera requiere su propio slice y ADR:

### Path A — `dir.failureMode` ADT

```kotlin
sealed interface DirFailureMode {
    data object Contained : DirFailureMode           // Jenkins default; stage continues
    data object AbortStage : DirFailureMode          // legacy behaviour; explicit opt-in
}
```

Default en DSL: `dir(path) { ... }` → `Contained`. Escape hatch: `dir(path, mode = DirFailureMode.AbortStage) { ... }`.

### Path B — `try/finally` isolation alrededor del cuerpo del bloque

Implementar `try { body.invoke() } catch (e: StepFailed) { throw e } finally { restore cwd; emit DirExited }` dentro del coordinator. **NO requiere ADT**; convierte el comportamiento en un invariante. Más invasivo (afecta `try`/`timeout`/`withEnv` también), más sencillo de razonar.

Recomendación: **Path A**, porque (i) hace la semántica visible para el usuario, (ii) respeta el principio "Haskell-inspired ADT over flag bag" de AGENTS.md §STRICT TYPED FUNCTIONAL DESIGN, y (iii) deja `dir(...)` con un único camino canónico en lugar de un `try/finally` implícito en el coordinator.

## Coordinación con el harness

El escenario HAR-007 vive en `Rubentxu/pipelinek-release-harness/scenarios/smoke/har007-dir-restore.pipeline.kts`. La matriz UAT del harness (`docs/UAT-MATRIX.md`) marca HAR-007 como `PASS` con la nota *"Cancel/retry no cubierto (nota)"* — pero la ejecución real contra `0.39.1-rc1` NO incluyó el escenario (el `verdict.json` rc1 sólo cubrió `smoke-failure+smoke-success`).

**Acción concreta:**

- El operador (o quien ejecute H0.3 en el harness) debe correr `har007-dir-restore.pipeline.kts` contra el binario actual y contra rc5/rc6 cuando la GREEN-phase exista.
- Mientras la GREEN-phase no exista, HAR-007 queda **NOT_RUN honesto** (no PASS) en la matriz del harness. La divergencia entre la tabla y el veredicto JSON debe corregirse allí.

## Conclusión

| Aspecto | Estado actual | Estado tras PR #90/#95/#96 |
|---|---|---|
| Cwd composition en `dir(...)` | ✅ Honra `workingDirectory` de `ShOptions` | ✅ Honra + validado por `WorkspaceOperationsEffectiveRootTest` 12/12 |
| `dir(...) { sh }` cambia cwd para sh | ✅ vía `ShOptions.workingDirectory` | ✅ Igual |
| Cwd se restaura al salir | ✅ vía `DirExited.restoredTo` | ✅ Igual |
| Pipeline continúa tras `dir(...)` que falla | ❌ Stage aborta | ❌ Igual — gap NO abordado |
| `deleteDir` honra cwd efectivo | ❌ Stage-workspace only | ✅ Vía PR #95 |
| `stash`/`unstash` honra cwd efectivo | ❌ Stage-workspace only | ✅ Vía PR #96 |
| WIDE guard (symlink + `.v2` reservado) | ❌ Substrate solo | ✅ Vía PR #90 |

**El contrato de contexto queda COHERENTE con la directiva** una vez se rebasen los PRs (los tres rebases limpios sobre `70e3d55e`, todos los tests quirúrgicos verdes). **HAR-007 queda NOT_RUN honesto hasta que se autorice e implemente el GREEN-phase** (path A o B).

## Próximo paso

1. El operador decide si promueve el rebased cut1/cut4/cut5 (PR #90+#95+#96) como una pila atómica.
2. Tras promover, WU-RP-053 queda **CLOSED** en su slice de contexto (workspace authorization + cwd propagation).
3. El gap HAR-007 (continuación tras `dir(...)` con error) se aborda como una WU separada — `WU-RP-053-DIR-FAILURE-MODE` o similar — bajo autorización del operador.
4. Hasta entonces, ningún veredicto del harness sobre HAR-007 cuenta como PASS sin ejecución real.
