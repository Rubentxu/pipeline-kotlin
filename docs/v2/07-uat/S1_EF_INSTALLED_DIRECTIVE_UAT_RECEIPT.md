# S1-E + S1-F receipt — Directive plugin on the INSTALLED distribution

- Fecha: 2026-09-29 · Ciclo SDDK: s1-installed-uat-e
- HEAD de construcción: 1910083ec388974612a347dbc8c8e41fe4229fec (== origin/main)
- Fuente: `v2/pipeline-application:installDist` y `:distZip` sobre HEAD; el
  árbol NO cambió (git status limpio salvo BACKLOG.md untracked pre-existente).
- binary_sha256 (distribución completa, pipelinek-0.43.0-rc1.zip):
  **8a5d240de29f0b35ea2d851bc5b23682a9cf274c5d4776b02d528f1f2333a1a7**
  (92.173.850 bytes)
- binary_sha256 (binario de aplicación dentro del zip,
  pipelinek-0.43.0-rc1/lib/pipeline-application-0.43.0-rc1.jar):
  **0dbce99bbec5cfb6be45ce2d...** (primer hex; completo en SHA256SUMS del
  ciclo)
- Plugin JAR: examples/example-directive-plugin-0.1.0.jar,
  sha256 33ec2c3e9527bb725e2d4e8166656830787daccb3eb0b7afc91ee331638ccb11
  (build de b6e1b28b, bytes sin cambios; S1-D ya lo certificó).
- Binario reporta: `pipeline 0.43.0-rc1` (manifest Implementation-Version).

## Entorno

- JDK: temurin-24.0.2+12 (pin .tool-versions del repo; anotación: el launcher
  requiere un `java` resoluble en el CWD — con asdf, ejecutar desde el repo
  o con pin local; se documentó como lección operativa).
- Scripts: /tmp/s1e-smoke/ws/directive.pipeline.kts (directiva
  `acme.lock` = `{"resource":"prod-db"}` + `echo("body under lock ran")`)
  y badargs.pipeline.kts (args malformados). El script real corre con steps
  directos en stage (sin bloque `steps{}`): sintaxis del fixture canónico
  01-basic.pipeline.kts.

## Resultados (todos OBSERVADOS, no inferidos)

### E1 — run con plugin (installDist) → GREEN
- Invocación: `pipelinek run --workspace ... --db e1.db --control-root e1-ctrl
  --plugin-jar <jar> directive.pipeline.kts` desde la raíz del repo. exit=0.
- stderr: `Discovered external directive plugins: example.lock.LockContributor`.
- Eventos (stdout NDJSON, 10 eventos): CompilationStarted/Finished →
  RunStarted → **DirectiveAdmitted(key=acme.lock, phase=BEFORE_STAGE,
  policy=evaluate)** (seq 4) → StageStarted(locked) → StepStarted →
  EchoOutputCaptured → StepFinished → StageFinished(success) →
  RunFinished(success). El body SE EJECUTÓ bajo la directiva admitida.

### E2 — aislamiento sin plugin (installDist) → DENIED fail-closed
- Misma invocación SIN `--plugin-jar`. exit=1.
- Eventos: RunStarted → **DirectiveDenied(key=acme.lock)** (seq 4) →
  RunFinished(failure). CERO StageStarted/StepStarted (el body nunca corrió).
- reason: `unresolved directive 'acme.lock' in stage 'locked': no definition
  registered; refusing to run the stage` (typed USER; sin stacktrace).

### EZ1/EZ2 — réplica desde el ZIP extraído (S1-F) → mismos resultados
- EZ1 (con plugin): exit=0, DirectiveAdmitted acme.lock, RunFinished success.
- EZ2 (sin plugin): exit=1, DirectiveDenied, RunFinished failure, 0 efectos.

### E3 — argumentos malformados (`{not-json`) → Admitted (hallazgo de alcance)
- Con plugin y args no-JSON: DirectiveAdmitted y run success.
- CAUSA (por lectura de código, no inferencia): la ADMISIÓN es registry-key
  match (StageDirectivePlanner.decide no llama decode). El decode del input
  pertenece a la FASE DE INTERPRETACIÓN de la policy (S1-R0 / RUN-CONCURRENCY-1),
  que en S1 NO está implementada por diseño: Evaluate se admite y se observa
  (el evento lleva policy="evaluate"), no se interpreta. El caso
  DirectiveDecodeResult.Malformed existe como ADT pero aún no tiene llamador.
- CLASIFICACIÓN: comportamiento por-diseño de S1 ( Evaluate = admitir +
  observar), NO defecto de este slice; el contrato de interpretación con
  decode-fail-closed es la frontera explícita S1-R0 (bloqueo registrado
  RUN-CONCURRENCY-1). Queda documentado como input del diseño S1-R0.

## Contrato S1-E/F: estado

| Criterio (plan S1) | Resultado |
| --- | --- |
| Harness owns installed-distribution directive scenarios | Ejecutado aquí (harness repo aún no existe; scenarios + receipts son del repo) |
| Installed-dist smoke vía plugin JAR | E1/EZ1 GREEN con discovery observable |
| Par de aislamiento instalado | E2/EZ2: denied typed, 0 efectos |
| Receipts bajo docs/v2/07-uat | Este fichero |
| binary_sha256 registrado | ZIP 8a5d240d… + app-jar 0dbce99b… |
| Sin release/tag hasta UAT verde (regla 6) | Sin tag creado; la decisión de tag/release es del operador (S1-F verde alcanzado en local) |

## Limitaciones honestas

- CI 36616907051 (1910083e) seguía queued al cierre de esta medición local;
  la autoridad de esta fila es el gate local L5 (BUILD SUCCESSFUL 19m39s,
  3323/3323) + los smokes de arriba. Si CI cae rojo: triage CI-vs-local
  ANTES de promover candidata (regla del handoff S1).
- La candidata formal (manifest.json/SHA256SUMS estilo v0.43.0-rc1) sobre
  HEAD actual queda lista para el operador; NO se generó porque el bump de
  versión con S1 dentro es decisión de release (semver MINOR 0.44.0-rc1
  propuesto por los 2 feats S1-C/S1-D según historial).
