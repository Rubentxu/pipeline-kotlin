# PK-1 — Contrato SDK consumible e identidad de ejecución

**Feature:** `pk-1-contract`
**Release objetivo:** v0.56.0-rc1
**Branch:** `release/pk1-contract-v0.56.0-rc1`
**Base:** `f384c109` (main con M6.4 reconciliation merge)
**ADR:** [`docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md`](../../pk-pk1/docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md)
**Prioridad:** P0 (bloquea PAIR_CERTIFIED con Fabric hasta que el artefacto canónico esté certificado)

## Objetivo

PipelineK conserva la autoridad sobre `RunId`, pero un invocador externo
dispone de (a) una correlación estable, tipada y durable de su propia
identidad con el run, (b) un receipt de lanzamiento tipado y machine-
verifiable, (c) códigos de salida normalizados con taxonomía cerrada, y
(d) una superficie machine-verifiable de capabilities y SDK info.

## Acceptance criteria

1. CLI emite `LaunchReceipt` JSON line en stdout por cada invocación
   que llega a durable state. Schema `pipelinek.launch/1`.
2. CLI acepta `--correlation-id <uuid>` y la propaga al evento
   `RunCorrelated` + `last-run.json` + `LaunchReceipt`.
3. Cada `CliError` se mapea a un `CliExit` con código documentado y
   nombre estable. El envelope de error en stderr usa schema
   `pipelinek.cli/1`.
4. `pipeline capabilities` emite la lista de capabilities SDK con
   estado y first-published. Schema `pipelinek.capabilities/1`.
5. `pipeline sdk-info` emite las 5 coordenadas SDK + SHA-256 + BOM
   version. Schema `pipelinek.sdk/1`.
6. ZIP de v0.56.0-rc1 contiene `pipeline-runtime-api-0.56.0.jar` con
   las 11 capabilities visibles y `pipeline-events-0.56.0.jar` con
   `EventShapeReadPort`.
7. `example-external-consumer` extendido con `Pk1IdentityTest` (2 runs
   concurrentes, restart preserva identidad) + `Pk1CliExitTest` (cada
   caso de `CliExit` ejercitado).
8. Compatibilidad N/N-1 con v0.55.0-rc1: consumidores existentes sin
   `--correlation-id` siguen viendo el mismo `runId` generado por PK.

## Tareas

- [ ] **T1** — `domain/CorrelationId.kt`: value class + `UuidCorrelationIdGenerator`.
- [ ] **T2** — `events/CorrelatedRunStarted.kt` (typed event `RunCorrelated`).
- [ ] **T3** — `application/LaunchReceipt.kt`: data class + JSON encoder.
- [ ] **T4** — `application/CliExit.kt`: sealed interface + mapping desde `CliError`.
- [ ] **T5** — `application/MainEntrypoint.kt`: extracción del dispatch de sub-handlers.
- [ ] **T6** — `application/Main.kt`: refactor para usar `CliExit` y emitir envelopes.
- [ ] **T7** — `application/MainCapabilitiesCli.kt`: subcomando `pipeline capabilities`.
- [ ] **T8** — `application/MainSdkInfoCli.kt`: subcomando `pipeline sdk-info`.
- [ ] **T9** — `application/RunIdDirectory.kt`: extender con `correlationId` y `LaunchReceipt` JSON.
- [ ] **T10** — `durable/CanonicalDurableRunCoordinator.kt`: propagar `CorrelationId` al `RunCorrelated` event.
- [ ] **T11** — Tests adapter: `LaunchReceiptJsonEncoderTest`, `CliExitMappingTest`, `CorrelationIdGeneratorTest`.
- [ ] **T12** — `examples/example-external-consumer/Pk1IdentityTest.kt` (2 concurrent runs + restart).
- [ ] **T13** — `examples/example-external-consumer/Pk1CliExitTest.kt` (cada `CliExit`).
- [ ] **T14** — `v2/build.gradle.kts`: bump 0.55.0 → 0.56.0.
- [ ] **T15** — `INTERFACE_CONTRACT.md` + `CONTRACT_SHA256.txt`: sección PK-1.
- [ ] **T16** — `docs/v2/07-uat/evidence/v0.56.0-rc1/release-receipt.md` + `handoff-to-release-harness.md`.
- [ ] **T17** — `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-pk1.md`.
- [ ] **T18** — Build + publish SDK + ZIP + tag `v0.56.0-rc1` + Prerelease.

## Decisiones de implementación

- `MainEntrypoint` se introduce por la presión de tamaño de `Main.kt`
  (1100+ líneas). La función `main` queda como dispatcher puro.
- El `LaunchReceipt` se emite a stdout en una línea separada con
  prefijo `{"schema":"pipelinek.launch/1"`. Consumidores deben usar
  `--format json` o filtrar por esa línea.
- El `CliExit` envelope se emite a stderr **antes** del texto libre
  existente. Consumidores pueden parsear la primera línea que empieza
  con `{"schema":"pipelinek.cli/1"` para mapear exit codes a typed
  reasons.
- `pipeline capabilities` y `pipeline sdk-info` se documentan como
  side-effect-free. Exit 0 siempre, incluso si la lista está vacía.
- Compatibilidad: el bump es MINOR (0.55.0 → 0.56.0) porque se añaden
  nuevas superficies (subcomandos, schemas JSON). No se cambia ningún
  contrato existente.

## Verificación

- `./gradlew :pipeline-domain:compileKotlin :pipeline-events:compileKotlin :pipeline-application:compileKotlin` BUILD SUCCESSFUL
- `./gradlew :pipeline-application:test --tests "*LaunchReceipt*" --tests "*CliExit*" --tests "*Correlation*"` todos verdes
- `./gradlew publishSdkForExternalPlugin` 5 SDK coords a 0.56.0
- `./gradlew :pipeline-application:distZip` ZIP con SHA-256 publicado
- `cd examples/example-external-consumer && ./gradlew test -PsdkVersion=0.56.0` todos verdes
- `gh release create v0.56.0-rc1 --prerelease` Prerelease publicada con ZIP

## Pendiente (externo, post-release)

- `CERTIFIED` por `pipelinek-release-harness`.
- `PAIR_CERTIFIED` con Fabric contra el artefacto `v0.56.0-rc1`.
- Promoción `EXPERIMENTAL → PUBLICADA` post-`CERTIFIED`.

## Evidencia y trazabilidad

- `git log --oneline release/pk1-contract-v0.56.0-rc1` muestra la secuencia de commits.
- `docs/v2/07-uat/evidence/v0.56.0-rc1/release-receipt.md` documenta la candidata.
- `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-pk1.md` es el input al Pair Integrator.
- `gh release view v0.56.0-rc1` muestra la Prerelease con el ZIP y su SHA-256.
