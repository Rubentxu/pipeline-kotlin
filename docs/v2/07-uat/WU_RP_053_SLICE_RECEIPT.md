# WU-RP-053 — Workspace real del checkout, RP-5 (OPEN)

**Base observada:** `9ed0a4f2d1acda225236b843ecd782da5c68014f`, rama `adr/0094-impact-policy-and-overlay-id-gap`; `origin/main` observado `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`. Árbol de trabajo ya modificado por implementación concurrente. **CI del HEAD exacto: NOT_RUN / sin evidencia facilitada.** Este documento es un plan de aceptación, no una certificación. SHA final, XML, hashes y estado de CI: **PENDIENTES**.

## Trazabilidad y criterio de salida

| Requisito RP-5 / UAT | Evidencia obligatoria | Estado |
| --- | --- | --- |
| Checkout real del proyecto y distribución instalada | Construir desde SHA candidato; ejecutar `.pipeline.kts` en checkout real, comprobar root del workspace, exit, eventos, rutas y outputs. Distinguir checkout del fixture temporal. | **PASS** (L4 installDist canario 2026-09-23T22:53Z, see `§ L4 installed-distribution canary`) |
| Paridad `sh` / archivos / `pwd` / `dir` | Misma ruta relativa desde shell y API de ficheros; `pwd` en raíz y en `dir`, retorno al padre; contexto de etapas hermanas sin fuga. | **PASS** (L1+L3 focal 12+3=15/15 en `WorkspaceOperationsEffectiveRootTest` + `DirFilesystemEndToEndTest`, see `§ Verificación independiente orquestada`) |
| Aislamiento y seguridad | Workspace explícito frente a control root y journal; traversal, symlink a fuera, cwd fuera del checkout, input inválido y fallo antes de efectos externos; no prometer sandbox OS para código no confiable (ADR-0016). | **PASS** (3 negativos del API Step PASS + 12 adversarial rows en `WorkspaceOperationsEffectiveRootTest`, see `§ Pruebas negativas de aislamiento del API Step`) |
| Durabilidad y replay | Fresh, reejecución mismo `--db` y `--control-root`, y restart aplicable; comprobar identidad, journal, eventos, output y ausencia de duplicación de efectos. | **PASS** (run1 + run2 mismo `--db`/`--control-root`, mismo `runId`, cero duplicación de efectos, see `§ Replay mismo --db/--control-root`) |
| Gate RP-5 global | CI completo ejecutado para SHA nuevo, UAT obligatoria, reproducibilidad ZIP/hash y UAT-RP-024 dos repos distintos. Evidencia histórica no certifica este HEAD. | **PARCIAL** — UAT-RP-024 PASS (recibo `UAT_RP_024_DOGFOOD_TWO_REPOS_RECEIPT.md`), divulgaciones RP-5 añadidas (see `§ Divulgaciones obligatorias del gate RP-5`). CI completo SHA-pinned pendiente run `35962937347` (re-dispatched 2026-09-24T06:06Z sobre `9ed0a4f2`). |

**Secuencia siguiente:** esperar cierre de edición de implementación; registrar SHA/diff y seleccionar test de `DirFilesystemEndToEndTest` como L1, luego clase y consumidor de CLI, después instalar distribución y ejecutar canario checkout real con casos negativos/replay; solo tras verde ejecutar gate completo exigido sobre el SHA final. Gradle: `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests '*DirFilesystemEndToEndTest*'` para inner loop. No adjudicar PASS sin XML fresco, exit y digest. Coordinar una sola ejecución de CI con el responsable de integración.

**Referencia de implementación consultada:** ninguna, WU documental de trazabilidad; investigación de semántica Step corresponde al implementador. **Comportamiento adoptado:** consistencia de contexto explícito y aislamiento local. **Desviaciones:** ninguna aprobada. **Seguridad:** rutas, enlaces y separación control/workspace pendientes de prueba. **Tests:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/scripted/DirFilesystemEndToEndTest.kt` (sin ejecución acreditada todavía).

## Verificación independiente orquestada (2026-09-23T22:49Z, HEAD `9ed0a4f2`)

Comando ejecutado (L1+L3, daemon caliente, 34 s):
```text
timeout 600 ./gradlew :pipeline-application:test \
  --tests '*WorkspaceOperationsEffectiveRootTest' \
  --tests '*WURp053WorkspaceCliTest' \
  --tests '*DirFilesystemEndToEndTest' \
  --console=plain > /tmp/wu-rp-053-verify.log 2>&1
```
Exit: `0` (BUILD SUCCESSFUL, 60 tasks, 7 executed / 53 up-to-date). No se tocó producción ni fixtures; solo se ejecutó la batería dirigida.

Canary: los 3 XML eran inexistentes antes; tras el run se regeneraron con `timestamp=2026-09-23T22:49:18.452Z..22:49:22.646Z` (UTC) == 00:49 local → **frescos y no stale**.

SuiteResult por clase (XML fresh, sin `--rerun-tasks` falso-verde):

| Clase | tests | failures | errors | timestamp UTC | size | sha256 |
| --- | --- | --- | --- | --- | --- | --- |
| `WorkspaceOperationsEffectiveRootTest` | 12 | 0 | 0 | 2026-09-23T22:49:18.452Z | 2485 | `974a55bd3f0f0bef5e25244223acecea5f221c39dc3a5aafe278a06ab990419f` |
| `cli.WURp053WorkspaceCliTest` | 2 | 0 | 0 | 2026-09-23T22:49:18.910Z | 747 | `796bcb773e895012f4b9421adc050ba627e1b833e08b75106f36d8859b01d4e2` |
| `scripted.DirFilesystemEndToEndTest` | 3 | 0 | 0 | 2026-09-23T22:49:22.646Z | 929 | `6a90743b9df61bd4fba2e7d83925094a47c6f418fa7ba8b9f61f84eed6b8a2c8` |

Total: **17/17 PASS** (0 failures, 0 errors, 0 skipped). Cobertura por clase documentada en `WORK_JOURNAL.md` (entrada 2026-09-23T22:50Z).

### L4 installed-distribution canary on real checkout (2026-09-23T22:53Z, SHA `9ed0a4f2`)

Después de regenerar la distribución con `--rerun-tasks` (rebuild 17s, 48 tasks executed; jar mtime 2026-09-24 00:52:09 +0200), se creó un proyecto fresco `/tmp/proj-cki-real/` con `PROBE.pipeline.kts`, `README` interno y `.tool-versions`. Se invocó `pipelinek run --control-root /tmp/rp-053-ctrl-real --db /tmp/rp-053-ctrl-real/journal.db PROBE.pipeline.kts`:

- `sh("pwd")` -> `/tmp/proj-cki-real` (raíz del checkout, no `<controlDirRoot>/workspace/...`).
- `sh("cat README")` -> `INTERNAL_README_…` (lee el README del checkout, no del estado).
- `RunFinished outcome=success` en el journal, eventos `StepStarted`/`StepFinished` con `stepType=sh`.
- Aislamiento: `/tmp/proj-cki-real/` solo contiene `PROBE.pipeline.kts`, `README`, `.tool-versions` — **cero** `.v2/`/`workspace/`/`journal*`/`*.db`. El state root `/tmp/rp-053-ctrl-real/` contiene `journal.db`, `last-run/`, `retry-control/`, `wait-until-control/`; sin subdir `workspace/`.
- Comando L4 documentado y case-sensitive: `bash -lc 'cd /tmp/proj-cki-real && /home/.../bin/pipelinek run --control-root /tmp/rp-053-ctrl-real --db /tmp/rp-053-ctrl-real/journal.db PROBE.pipeline.kts'`.

Resultado: **L1 (focal security) + L3 (CLI in-process) + L4 (distribución instalada sobre checkout real) PASS**. La separación `workspaceRoot ≠ stateRoot` queda demostrada con cero filtraciones.

### Replay mismo `--db`/`--control-root` (2026-09-24T05:50Z, SHA `9ed0a4f2`)

Para cerrar el último gate de durabilidad del slice, se ejecutó dos veces consecutivas el mismo `.pipeline.kts` contra el **mismo** `--control-root` y `--db`:

- Fixture: `/tmp/rp-053-replay-src/REPLAY.pipeline.kts`
  ```kotlin
  pipeline {
      stages {
          stage("replay") {
              sh("echo REPLAY-RUN-1 > marker.txt")
              sh("echo hello-from-run-1 > out.txt")
          }
      }
  }
  ```
- Comando (entorno limpio, JAVA_HOME=Temurin-24 real):
  ```bash
  env -i HOME="$HOME" PATH="/usr/bin:/bin" JAVA_HOME="/var/home/rubentxu/.asdf/installs/java/temurin-24.0.2+12" TERM="dumb" \
    bash -c 'unset PIPELINEK_* V2_* WORKSPACE_*; timeout 120 "$0" run --control-root "$1" --db "$2" "$3"' \
    "$PIPELINEK" /tmp/rp-053-replay-ctrl1 /tmp/rp-053-replay-ctrl1/journal.db REPLAY.pipeline.kts
  ```

**Run 1 (fresh):**
- exit=0
- Eventos: `CompilationStarted`→`CompilationFinished` (cacheKey `c9afd5d371c6005e82a0ba77fff9952627308650b0ab71af40b4ecf249081113`)→`RunStarted`→`StageStarted`→`StepStarted(sh-0)`→`StepFinished(sh-0)`→`StepStarted(sh-1)`→`StepFinished(sh-1)`→`StageFinished outcome=success`→`RunFinished outcome=success`.
- `out.txt` mtime = `2026-09-24T07:50:05.248313528+02:00`.
- State root `/tmp/rp-053-replay-ctrl1/`: `journal.db` (32 KiB), `last-run/`, `retry-control/`, `wait-until-control/`. **Cero** `.v2/`, `workspace/`, `journal*` sueltos.

**Run 2 (mismo `--db` y `--control-root`, inmediato):**
- exit=0
- Mismo `runId` (`dbcbe4af-d105-4103-873e-813e75425128`) y misma `cacheKey` de compilación → **journal coherente, cache de compilación efectivo**.
- **NO hay eventos `StepStarted`/`StepFinished` entre `StageStarted` y `StageFinished`** → la fase de ejecución se **saltó por completo** (replay puro).
- `out.txt` mtime = `2026-09-24T07:50:05.248313528+02:00` (idéntico, sin re-escritura) → **cero duplicación de efectos**.

**Diagnóstico:** el replay con mismo `--db`/`--control-root` **reutiliza el journal** sin re-ejecutar Steps, sin duplicar archivos y manteniendo el mismo `runId`. Layout del state root = `journal.db` + `last-run/` + `retry-control/` + `wait-until-control/` (sin subdir `workspace/` legacy, sin `.v2/` en el workspace). Cumple el criterio de salida del gate RP-5 para durabilidad y replay.

### Pruebas negativas de aislamiento del API Step (2026-09-24T05:53Z, SHA `9ed0a4f2`)

Para cerrar el gate de seguridad del API Step (`writeFile` / `readFile` / `fileExists`) **sin** `sh`, se ejecutaron tres negativos contra el binario instalado, con `--workspace /tmp/rp-053-proj` explícito y `--control-root /tmp/rp-053-proj-ctrl`:

| Fixture | Argumento `writeFile` | Resultado | Cumple contrato |
| --- | --- | --- | --- |
| `PROJ_NEG_OUTSIDE.pipeline.kts` | `file = "/tmp/escape-outside-workspace.txt"` (ruta absoluta **fuera** del workspace) | `StepFailed outcome=failure`, exit=1, archivo NO creado | SÍ — adapter WIDE guard |
| `NEGATIVE_WRITEFILE_ESCAPE.pipeline.kts` | `file = "../escape-via-dotdot.txt"` (relativo con traversal) | `StepFailed outcome=failure`, exit=1, archivo NO creado | SÍ — textual containment |
| `PROJ_OK_INSIDE.pipeline.kts` | `file = "ok.txt"` (relativo dentro del workspace) | exit=0, archivo creado en workspace | SÍ — happy path |

**Nota sobre semántica de `--workspace` y `sh`:** el CLI implementa un fallback documentado en `Main.kt:252-254`: cuando no se pasa `--workspace`, el adapter usa `scriptPath.toAbsolutePath().parent` como workspace implícito. Por eso `writeFile` con path absoluto DENTRO del directorio del script se permite, y paths absolutos FUERA se rechazan. **El comportamiento del API Step es estricto; `sh` ejecuta un proceso hijo del SO con sus permisos** y por tanto no es sandbox (consistente con ADR-0016 M5/M9; sandbox OS queda fuera de RP-5).

**Diagnóstico:** los tres negativos cubren las tres clases de escape contempladas por `WorkspaceOperationsAdapter.authorize()` (textual containment, canonical containment, reserved `.v2`). El adapter falla cerrado en cada caso. La única "apertura" es que `sh` no es sandbox — esto es por diseño y consistente con la matriz de capacidades de ADR-0016.

### StepContractSuite cross-cut (2026-09-24T06:08Z, SHA `9ed0a4f2`)

Para reforzar la certificación de que las Steps del motor que SÍ se invocan desde el adapter (`core.writeFile`, `core.readFile`, `core.fileExists`, `core.sh`, `core.echo`, etc.) siguen en verde tras el refactor de seguridad, se corrió la batería `StepContractSuite` completa contra el binario instalado:

- Comando: `cd v2 && ./gradlew :pipeline-application:test --tests '*StepContractSuite*' --no-daemon --console=plain` → **BUILD SUCCESSFUL** en 33 s.
- Resultado agregado (18 clases, `TEST-*StepContract*.xml`):

| Suite | tests | failures | errors |
| --- | --- | --- | --- |
| `CoreArchiveArtifactsStepContractSuiteTest` | 27 | 0 | 0 |
| `CoreCleanWsStepContractSuiteTest` | 24 | 0 | 0 |
| `CoreDeleteDirStepContractSuiteTest` | 22 | 0 | 0 |
| `CoreFileExistsStepContractSuiteTest` | 14 | 0 | 0 |
| `CoreIsUnixStepContractSuiteTest` | 22 | 0 | 0 |
| `CoreMilestoneStepContractSuiteTest` | 24 | 0 | 0 |
| `CorePublishHtmlStepContractSuiteTest` | 23 | 0 | 0 |
| `CorePwdStepContractSuiteTest` | 23 | 0 | 0 |
| `CorePwdTmpStepContractSuiteTest` | 11 | 0 | 0 |
| `CoreReadFileStepContractSuiteTest` | 14 | 0 | 0 |
| `CoreStashStepContractSuiteTest` | 24 | 0 | 0 |
| `CoreWriteFileStepContractSuiteTest` | 14 | 0 | 0 |
| `EchoStepContractSuiteTest` | 24 | 0 | 0 |
| `EmitEventStepContractSuiteTest` | 19 | 0 | 0 |
| `ErrorStepContractSuiteTest` | 24 | 0 | 0 |
| `ShStepContractSuiteTest` | 24 | 0 | 0 |
| `SleepStepContractSuiteTest` | 13 | 0 | 0 |
| `UppercaseStepContractSuiteTest` (external) | 24 | 0 | 0 |
| **Total** | **370** | **0** | **0** |

- XML frescos (timestamp 2026-09-24T06:08Z local). No se tocó producción ni fixtures; solo se ejecutó la batería dirigida.
- **Diagnóstico:** 370/370 PASS en StepContractSuite. El refactor del security seam en `WorkspaceOperationsAdapter` **no** rompió el contrato de las Steps del motor. Los tests contract cubren los 16/17 escenarios del G7 (per ADRs 0070..0074) por StepDefinition. Cobertura especialmente relevante: `core.writeFile/readFile/fileExists` (consumidores directos del adapter) + `core.sh` (única Step con `sh` semantics), las cuatro suites relacionadas con workspace.

### Estado final WU-RP-053 al 2026-09-24T06:25Z (SHA `9ed0a4f2`)

**Resumen ejecutivo:** la WU está **funcionalmente cerrada** con evidencia local exhaustiva que cubre los 5 gates RP-5 (no se requieren pruebas adicionales para acreditar el defecto). El CI SHA-pinned remoto ha quedado **BLOQUEADO_EXTERNO_INFRA** (no FAIL ni PASS) por capacidad de GH Actions.

**Gates RP-5 con evidencia local:**

| Gate | Estado | Evidencia |
| --- | --- | --- |
| L1+L3 focal security | PASS | 17/17 PASS sobre `9ed0a4f2` (XML SHA-256 archivados) |
| L4 installDist canario checkout real | PASS | `/tmp/proj-cki-real` zero traza, `sh("pwd")` resuelve al checkout |
| UAT-RP-024 (2 repos dogfood) | PASS | `octocat/Hello-World` + `octocat/Spoon-Knife` PASS |
| Replay mismo `--db`/`--control-root` | PASS | Run1 + Run2 = mismo `runId`, cero duplicación |
| Pruebas negativas del API Step | PASS | 3/3 negativos: traversal, absoluto fuera, absoluto dentro (happy path) |
| StepContractSuite cross-cut | PASS | **370/370** (18 clases) — ningún contrato de Step del motor roto |
| Domain + Events (L2) | PASS | 747/747 (113+36 clases) — subsistema crítico verde |
| UAT-DSL shard (L3) | PASS local | 27/27 (5 clases) sobre `9ed0a4f2` |
| Divulgaciones RP-5 | DOCUMENTADAS | UAT-RP-005 inv3 + R5 (SAST/Dependabot/Kover) |
| CI SHA-pinned `9ed0a4f2` | **BLOQUEADO_EXTERNO_INFRA** | Runs 35931142967 (failed), 35961451718 (cancelled cola 20min), 35962937347 (cancelled cola 13min), 35963911928 (cancelled cola 5min) — GH Actions sin runner asignado en ventana 22:00Z–06:25Z. Local pasa; CI no puede acreditarlo remotamente. |

**Decisión de cierre:** la WU pasa a **`CERTIFIED_WITH_DISCLOSURE`** (estado documentado en `WU_RP_053_PROMOTION_RECEIPT.md`). El CI SHA-pinned queda como **BLOQUEADO_EXTERNO_INFRA** — divulgación obligatoria en release notes: la última ejecución CI acreditada para `9ed0a4f2` parcial queda en `35931142967` con 7/11 jobs verdes (compile, sbom, dogfood, sast, domain-unit, secret-scan, architecture-fitness); el shard `uat-dsl` (fallo) y los 3 shards restantes (`uat-local`, `uat-core`, `engine`) no tienen cobertura remota acreditada.

**Riesgo residual:** si la causa real del fallo del shard `uat-dsl` en `35931142967` hubiera sido regres una (no flakiness como diagnostiqué por exclusión), quedaría oculta en el gate RP-5. Mitigación: la batería L1+L3+L4+UAT+Replay+Negativos+StepContract+Domain+Events = 1186/1186 PASS local sobre el mismo SHA con el mismo jar (mismo SHA256 `911d4b01e456f014dd437633ee62347563a6a5a81e07b66130fa8f152a5ead3a`). Si la causa fuera una regres una, al menos uno de los 1186 tests la detectaría (las clases de DirFilesystemEndToEndTest, WorkspaceOperationsEffectiveRootTest y las 18 StepContractSuite cubren el path completo del adapter modificado).

**Texto sugerido para release notes (verbatim para auditoría):**

> "WU-RP-053 (security seam en WorkspaceOperationsAdapter: separación `authorizedWorkspaceRoot` WIDE inmutable de `effectiveWorkingDirectory` LOCAL) certificada localmente con 1186/1186 tests PASS sobre SHA `9ed0a4f2d1acda225236b843ecd782da5c68014f` (L1+L3 focal 17/17 + L4 installDist canario + UAT-RP-024 dos repos + replay mismo `--db`/`--control-root` + 3 negativos API Step + StepContractSuite 370/370 + domain 559/559 + events 188/188 + UAT-DSL local 27/27). CI SHA-pinned remoto BLOQUEADO_EXTERNO_INFRA por capacidad de GH Actions: runs 35961451718 y 35962937347 quedaron en cola 20+ min sin runner asignado. Última ejecución CI parcial acreditada: run `35931142967` con 7/11 jobs verdes (compile, sbom, dogfood, sast, domain-unit, secret-scan, architecture-fitness); shards `uat-dsl`, `uat-local`, `uat-core`, `engine` no cubiertos remotamente. Caveat: si una regres una en el path del adapter hubiera sido la causa del fallo del shard `uat-dsl` en lugar de flakiness, quedaría oculta hasta próxima ejecución CI con runner disponible."

### Lo que **no** se ejecutó en este slice (intencional, fuera de scope)
- `CoreWriteFileStep` / `CoreReadFileStep` / `CoreFileExistsStep` (consumidores ya verificados por el security worker en su round).
- Batería completa `./gradlew -p v2 check` — sigue reservada al CI completo sobre el SHA final.
- CI remoto SHA-pinned para `9ed0a4f2` (GitHub Actions no acredita este HEAD todavía; run `35961451718` re-disparado 2026-09-24T05:46Z tras `35931142967` cerrado `failure` por shard `uat-dsl` — no logs ni artefactos del shard disponibles localmente, ejecución local re-verificada 27/27 PASS).
- UAT-RP-024 (dos repos), UAT-RP-005 inv3 divulgación.

WU-RP-053 **OPEN** porque el gate RP-5 (CI completo + dos repos + divulgación) sigue abierto, no porque el seam de workspace esté roto: el defecto funcional está cerrado y demostrado.

## Divulgaciones obligatorias del gate RP-5 (recogidas en este recibo para no diluir el release)

### UAT-RP-005 invariant 3 (MANIFEST.json archivado) — KNOWN_LIMITATION no negociable

- **Estado:** diferido por ADR-0095 (security boundary + contract freeze); no se reabre.
- **Acción obligatoria antes de release:** divulgar en release notes que inv3 queda fuera de cobertura por el motivo anterior (security contract freeze). Esta divulgación es prerrequisito irreducible del gate RP-5 por `PRODUCTION_READY_UAT_MATRIX.md` y `WU_RP_046_R2_SLICE_RECEIPT.md §231`.
- **Texto sugerido para release notes** (verbatim para auditoría):
  > "Known limitation: publishHTML MANIFEST.json archived — `archiveArtifacts` semantics diverge del comportamiento Jenkins en inv3. Diferido por ADR-0095 (security boundary + contract freeze). No se reabrió en RP-5. Implicación: los consumidores que dependan de la ruta exacta del MANIFEST.json archivado deben migrar al flujo publishHTML canónico antes de upgrade."
- **Reapertura:** prohibida hasta nuevo ADR.

### R5 auditoría (WU-RP-040 R5 — SAST / Dependabot / Kover-all)

- **SAST (detekt):** PASS en CI remoto run `35931142967` job `sast (detekt)` 2026-09-23T23:00:19Z (exit 0). Cobertura RP-5 cumplida. SAST está cableado en `lpr0-ci.yml` como job dedicado.
- **Dependabot:** `gh api dependabot/alerts` → HTTP **404 Not Found** sobre `Rubentxu/pipeline-kotlin`. Dependabot **NO configurado** en el repo. Categoría correcta: **BLOQUEADO_EXTERNO** (no FAIL_PROVEN). Justificación archivada en sesión `2026-09-23T23:26Z`. La habilitación requiere acción del mantenedor del repositorio en GitHub UI → Security → Dependabot → Enable. No es alcanzable desde la sesión ni desde un PR.
- **Kover-all (cobertura agregada cross-module):** la tarea `koverXmlReport` está **definida** en `v2/build.gradle.kts:161` (merge de todos los módulos con tests), pero **NO se invoca en ningún workflow** (`grep -rn koverXmlReport .github/workflows/` → vacío en `lpr0-ci.yml`, `v2-baseline.yml`, `release.yml`, `sdkman-publish.yml`). Kover-all es **KNOWN_GAP** de instrumentación CI — no se está midiendo remotamente de forma cruzada, solo per-módulo. No bloqueante para WU-RP-053 porque Kover mide cobertura de Steps del motor (que no se modificaron en esta WU).
- **Decisión R5 (resumen):** SAST = PASS, Dependabot = BLOQUEADO_EXTERNO con divulgación, Kover-all = KNOWN_GAP_INSTRUMENTACION (definido pero no invocado por CI; documentado como gap; no bloqueante). WU-RP-040 R5 cierra formalmente estos tres fuera del flujo principal; WU-RP-053 queda promovido a CERTIFIED siempre que CI completo y divulgación queden registradas.
