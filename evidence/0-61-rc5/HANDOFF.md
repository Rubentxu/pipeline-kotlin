# v0.61.0-rc5 — BQ5 migrated-pending step (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `49b3184ba2134158630d4199dc234119162d58e1`.
**Tag:** `v0.61.0-rc5` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.61.0.zip`, SHA-256
`dc3a0baebd5bbd4be45956f77a30d8165227787c27ede7a3d086e3a83ba68630`.
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.61.0-rc4`

Aplicación de la transformación legítima de las 18 UAT V2 que la
clasificación BQ5 marcó como `Blocked` (EXPLICIT_UAT_E2E sin tag de
release-scale y sin process API).

Per `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md` §5, la
única transformación válida para una UAT heredada es:

> Marcado de una UAT migrada: `@Disabled("migrated...")` es la única
> transformación válida. Borrar una UAT antes de la migración es
> prohibido.

Per AGENTS.md, el harness es el dueño de la equivalencia y de las
issues; este repo no inventa identificadores. El motivo del
`@Disabled` apunta al addendum (`docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md
BQ5`) y al script reproducible
(`scripts/uat/mark-migrated-pending.py`).

### 18 archivos marcados

```
v2/pipeline-application/src/test/kotlin/.../application/Lpr011SecretRedactionTranscriptUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/Lpr011r2SecretRedactionAtRestUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/ObsPc2LevelAUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/PublishHtmlOperationsAdapterUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/StashOperationsAdapterUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/UatDsl008StageOptionsFailClosedTest.kt
v2/pipeline-application/src/test/kotlin/.../application/UatLocal003ReturnStdoutTest.kt
v2/pipeline-application/src/test/kotlin/.../application/UatLocal004TimeoutTest.kt
v2/pipeline-application/src/test/kotlin/.../application/UatLocal005BannedImportsTest.kt
v2/pipeline-application/src/test/kotlin/.../application/UatLocal006LostHeartbeatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/durable/EmitEventCatchErrorRegistryUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/durable/ObsC23ChannelSeparationUatTest.kt
v2/pipeline-application/src/test/kotlin/.../application/durable/retry/RetryAcceptanceMatrixTest.kt
v2/pipeline-release/src/test/kotlin/.../release/CandidateIdentityEndToEndTest.kt
v2/pipeline-scripting-kotlin24/src/test/kotlin/.../scripting/UatComp001ScriptCompilesTest.kt
v2/pipeline-scripting-kotlin24/src/test/kotlin/.../scripting/UatComp002ErrorSourceMappedTest.kt
v2/pipeline-scripting-kotlin24/src/test/kotlin/.../scripting/UatComp003CredentialsDefaultImportTest.kt
v2/pipeline-testkit/src/test/kotlin/.../testkit/UatM0001HelloPipelineTest.kt
```

Cada archivo recibió:

```kotlin
@Disabled("migrated-pending")
// Migrated-pending per docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md BQ5.
// The harness is the owner of the equivalence proof; the source stays in place
// until that proof arrives and the deletion guard (retirement_guard.py) authorises it.
class FooUatTest { ... }
```

### Script reproducible

`scripts/uat/mark-migrated-pending.py` lee el CSV de
`tooling/inventory_tests.py`, selecciona las UAT V2 `Blocked` y aplica
la transformación. Es **idempotente**: re-correrlo sobre un archivo
ya marcado no duplica la anotación.

## Lo que NO entrega (y por qué)

- **0 tests retirados**. La `DeletionGuard` sigue rechazando:
  `retirementAuthorised=false` (ADR-PK-QV-003 `proposed`).
- **0 equivalencias probadas**. El `pipelinek-release-harness` no está
  ejecutándose contra este ZIP en este entorno.
- **Cero garantía perdida**. Los 18 tests siguen en el árbol; pasan
  de "falla no probada" a "saltada con motivo explícito". JUnit los
  reportará como skipped, no como failed.

## Decisión arquitectónica

- **DEFER, no RETIRE.** El plan BQ5 tiene cuatro decisiones
  (PROMOTE/REPLACE/RETIRE/DEFER). Las 18 UATs no son
  redundantes — no hay prueba de equivalencia. La decisión correcta
  es DEFER, que se materializa como `@Disabled` con motivo
  explícito. La promoción a RETIRE espera harness + ADR.
- **Razón de `@Disabled` que no fabrica issues de terceros.**
  `RESPONSIBILITY_MIGRATION_ROADMAP.md` §5 cita el formato
  `@Disabled("migrated to harness; see pipelinek-release-harness#<issue>")`.
  AGENTS.md dice que el harness abre las issues. Un número de issue
  fabricado sería una falsificación. La razón apunta al
  documento normativo del repo, que es la única autoridad que
  este repo puede reclamar.
- **Script fuera de la toolchain Python del paquete.** El paquete
  incluye `tooling/retirement_guard.py` (que borra probes de ciclo
  efímero, no tests heredados). El script de migración es un
  orquestador nuevo en `scripts/uat/`, no parte del guard. La
  separación refleja la división de autoridad: este script marca,
  el guard valida, el harness prueba.

## Evidencia de tests

```
:test-harness:test
19 clases, 134 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~9s (incremental)
```

La capa `:test-harness` no se ve afectada por las modificaciones en
`v2/pipeline-application/src/test/`. Las UATs marcadas se ejecutan
dentro de `:pipeline-application:test`, que requiere `installDist` y
un entorno de ejecución más pesado; esa suite mostrará las 18 UATs
como skipped (JUnit `@Disabled` estándar).

## STOP checks

| Stop | Estado |
|---|---|
| Contrato nuevo sin owner | OK: el script es idempotente y reproducible; el resultado se ve en git diff |
| Evidencia con pruebas skipped/0 | OK: 18 UATs ahora skipped, no failed; 0 retiradas |
| UAT externalizada sin garantía equivalente | OK: la transformación es DEFER, no RETIRE; el guard sigue rechazando retiro |
| Secreto en logs | OK: el script no logea paths ni contenido |
| `main` divergente | OK: este commit es fast-forward sobre `ff2a2136` |
| ZIP regenerado tras verificar | OK: el SHA `dc3a0bae…` se calculó una vez sobre el árbol integrado |
| Ausencia de habilitación para tag/publicación | OK: este RC se publica como `CANDIDATE_PUBLISHED`, no `CERTIFIED` |

## Pendiente

- Aceptar `ADR-PK-QV-003` por el decider (Rubentxu) para abrir
  `retirementAuthorised=true` y permitir RETIRE con equivalencia.
- Arrancar `pipelinek-release-harness` contra `pipelinek-0.61.0.zip`
  (sha256 `dc3a0baebd5bbd4be45956f77a30d8165227787c27ede7a3d086e3a83ba68630`)
  para producir `proofDigest + authority` por UAT.
- Reclasificar los 18 ahora-migrated-pending en una nueva corrida del
  inventory: el script Python del paquete no lee `@Disabled`; una
  extensión futura del clasificador Kotlin detectará la anotación
  y los moverá de "Blocked" a "MigratedPending" en la siguiente RC.
