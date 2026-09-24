# WU-RP-053-MERGE — Fase 4-bis RECEIPT: full round gate incremental

**Fecha:** 2026-09-24T20:27Z
**Branch:** `wu/rp-053-merge` @ `4deb28c8`
**Base:** `main @ acc90387` (post SESSION PAUSE MEMO 2026-09-24)
**Modo:** AUTO (operator pre-authorized)
**Recibo previo:** [`docs/v2/07-uat/WU_RP_053_MERGE_RECEIPT.md`](WU_RP_053_MERGE_RECEIPT.md)

## Resultado: ✅ GATE PASSED

`./gradlew -p v2 check` (incremental, default) ejecutado en background con timeout 1270s.
Resultado: **BUILD SUCCESSFUL in 14m 48s**.

```text
Total tests: 3196
Pass:        3072
Skip:        124 (intentional @Disabled — documented in receipts)
Fail:        0
Error:       0
Files:       495 JUnit XML classes
```

## Detekt fix (prerrequisito)

El primer intento del check falló en 7s con:
```
> Task :pipeline-events:detekt FAILED
e: DomainEventRoundTripTest.kt:334:1 Line detected, which is longer than the
defined maximum line length in the code style. [MaxLineLength]
```

**Fix:** refactor del mensaje de aserción de 156 chars a 3 líneas preservando el contenido semántico.
Commit: `4deb28c8 style(events): fix detekt MaxLineLength in DomainEventRoundTripTest`.

## Evidencia L4 ejecutada

### Suite total (`:pipeline-application:test` + todas las dependencias)

```
pipeline-domain:test           PASS (DomainEventL5Variants, BlockFailureContained decode+roundtrip)
pipeline-events:test            PASS (40/40 M1+M2, DomainEventRoundTrip 14/14, characterisation 10/10)
pipeline-architecture-tests:test 313/313 PASS (FArchL7 51→52)
pipeline-step-sdk/runtime:test  PASS (EffectReplayPolicy, MEMOIZED+FAILED=RERUN, FAILED_TIMEOUT)
pipeline-step-sdk/processor:test PASS (KnownJenkinsSurfacesTest, StepDescriptorGeneratorTest)
pipeline-application:test       PASS (UatLocal007 SB-S-001..008, UatLocal011 SC-011-04,
                                     CompatibilityCorpusTest 14/14, fixture10SmokeE2E,
                                     UatCompat001CorpusSmokeRunTest, DirFilesystemEndToEndTest 3/3,
                                     StashOperationsAdapterUatTest 8/8, WorkspaceOperationsEffectiveRootTest 12/12,
                                     DirFailureContainedRuntimeTest 2/2, DirFailureModeTest 5/5,
                                     DirRestoreAfterErrorCharacterizationTest 1/1 (writeFile RE-ENABLED),
                                     DomainEventRoundTripTest 14/14)
pipeline-scripting-kotlin24:test PASS

Módulos kover: ALL PASS
```

### Detekt + kover

```
pipeline-events:detekt        PASS  (1 issue fixed at 4deb28c8)
pipeline-application:detekt   PASS
pipeline-domain:detekt        PASS
pipeline-step-sdk:runtime:detekt  PASS
koverVerify (root)            PASS
```

## Comentarios sobre el resultado

1. **Cero regresiones sobre main `acc90387`.** Los 7 UAT tests que el commit
   `3e9fc4aa` rompía (SB-S-001, SB-S-006, SB-S-008, UAT-L7-TC-004, SC-011-04,
   fixture10SmokeE2E, UatCompat001CorpusSmokeRunTest) están verdes porque el
   commit fue revertido en `e08b063e` y `9023e8ff`. La pieza de modelo de cut5
   (`effectiveWorkingDirectory` threaded por capability bridge) sigue integrada
   y testeada, pero el CLI default change queda detrás de un opt-in flag.

2. **El integration test re-enabled pasa.** `DirRestoreAfterErrorCharacterizationTest.writeFile
   inside dir composes against effective cwd` (previamente `@Disabled`) ahora
   es 1/1 PASS. Esto demuestra que `WorkspaceOperationsAdapter` +
   `effectiveWorkingDirectory` + `dir(...)` trabajan juntos correctamente.

3. **Pre-existing flake documentado.** El suite incluye `WU-LPR-301 core.waitUntil
   canonical registry fitness > installed CLI emits WaitUntilPolled and
   WaitUntilCompleted through registry path()` que históricamente flakeó con
   TimeoutException en main. En esta corrida incremental de 14m 48s **no
   apareció** — la pre-existing flakiness está cuantificada en
   `docs/v2/07-uat/WU_LPR_301_*` y no es regresión de este branch.

4. **Detekt fix es debt cleanup honesto.** El mensaje de aserción de 156 chars
   en `DomainEventRoundTripTest.kt:334` había sido introducido en el commit
   `104be0da` (FArchL7 51→52 bump). La regla 4 (CALIDAD — evalúa regresiones
   y código duplicado antes de plantear cambios) obliga a limpiar lo que
   introducimos. Commit atómico `4deb28c8` con refactor mínimo preservando
   semántica.

## Próximo paso

Fase 5 — Replace canonical: push branch + cerrar PRs originales. **Pendiente
operator gate sobre exact bytes** (regla 6 del operator override). NO se
ejecuta sin gate explícito del operador.

## Refs

- Recibo principal: [`docs/v2/07-uat/WU_RP_053_MERGE_RECEIPT.md`](WU_RP_053_MERGE_RECEIPT.md)
- Plan: [`docs/v2/05-roadmap/WU-RP-053-MERGE/PLAN.md`](../05-roadmap/WU-RP-053-MERGE/PLAN.md)
- Logs: `/tmp/fase4-bis-check2.log`
- Commit detekt fix: `4deb28c8`
- Base: `main @ acc90387`
