# WU-RP-041 — Aislamiento del runner local — RECEIPT (S1-S3, cierre parcial)

**Fecha:** 2026-09-22. **Base:** 998e8073. **Head:** d9e8f44a (código) + estado.
**Criterio ROADMAP §6:** probar aislamiento del runner local (filesystem, proceso,
CPU/mem/tiempo, egress, secretos); declarar modelo de amenazas; diferenciar
confiable vs multi-tenant; best-effort fuera del perfil no confiable.

## Slices ejecutados

- **S1 (7c54ddc1)** — Paridad de cancelación por deadline en hijos de cuerpo
  EXTERNO (cierra deuda #1 del RP-3 EXIT REVIEW).
  `ExternalBodyDeadlineCancellationProofTest` (2/2):
  - cuerpo externo declarado (`Sequential`/`CANONICAL_ENGINE`) conteniendo
    `core.timeout(1s)` con `core.sh "sleep 30"`:
    `RunOutcome.Failure(FailureKind.TIMEOUT)`, acotado (<25s, sin colgado),
    fila durable del hijo en `FAILED_TIMEOUT` (kill del watchdog),
    `TimeoutScheduled` emitido ANTES del `StepStarted` del hijo (camino
    canónico de planificación, cero ramificación por StepKey);
  - caso antes-de-deadline: `RunOutcome.Success` por el mismo camino.
  Cambio de producción: CERO. Registro: `InMemoryStepRegistry` con la
  definición externa + `CoreEchoStep` + `CoreShellStep`.

- **S2+S3 (d9e8f44a)** — Modelo de amenazas + ADT de perfil de confianza +
  pins de leyes de aislamiento:
  - `docs/v2/07-uat/WU_RP_041_RUNNER_ISOLATION_THREAT_MODEL.md`: vectores,
    estado de contención por superficie, CPU/mem/egress/fs-jail declarados
    NO contenidos en L3 y FUERA del perfil no confiable (ADR-0016 M5/M9).
  - `RunnerTrustProfile` (sdk/runtime): ADT cerrado;
    `TrustedSingleTenant` (sólo NONE/LOCAL);
    `MultiTenantConstrained` NO construible en L3 (constructor fail-closed
    citando ADR-0016 M5/M9, patrón `SandboxProfile.OS`).
  - `RunnerTrustProfileTest` (3/3): pin del ADT y de la ausencia de peldaño
    no confiable en L3.
  - `RunnerIsolationProjectionLawTest` (3/3): `dir` con escape relativo del
    workspace → rechazo tipada `InvalidInput` antes de efectos; ruta interna
    permanece dentro tras normalizar; `a/../b` aceptado.

## Verificación

- L1/L2: los 3 ficheros nuevos en verde (XML frescos, canary regenerado).
- T2: `:pipeline-step-sdk:runtime:test` + `:pipeline-application:test`
  BUILD SUCCESSFUL (839s; 212 clases de test en application, 0 fallos).
- L5 gate: `./gradlew check` incremental BUILD SUCCESSFUL (1m53s).
- CI: PENDING push del head final (registro en journal).

## Referencias consultadas

- Implementación de referencia: watchdog `DurableShellExecutor`
  (flag-then-kill TMO-S-005, settle WU-RP-005 r9), `classifyShellTerminal` /
  `toStepOutcome` (clasificadores únicos), `decodeDeadline` (E-EM-11),
  Jenkins `timeout` step (baseline JENKINS_REFERENCE_BASELINE).
- Desviaciones: ninguna nueva; el perfil OS sigue no instanciable (ADR-0016).

## Estado del criterio WU-RP-041

- Cancelación/tiempo: PROBADO (core y cuerpos externos, misma vía canónica).
- Filesystem: leyes de proyección PINNADAS; jail real FUERA de perfil (M5/M9).
- Secretos: evidencia existente (Lpr011/011r2, SB-S-003/004/005/009) referida
  en el threat model; sin nuevas violaciones.
- CPU/mem/egress: declarados NO contenidos; perfil multi-tenant
  irrepresentable (fail-closed). Criterio "best-effort fuera del perfil no
  confiable" SATISFECHO por diseño del ADT.
- Pendiente para cierre completo de la WU: CI verde del head final y
  validación de que ningún otro consumidor de `SandboxProfile` requiere
  migración a `RunnerTrustProfile` (decisión documentada: ADT declarativo,
  no rompe contrato; Main.kt mantiene --sandbox-profile).
