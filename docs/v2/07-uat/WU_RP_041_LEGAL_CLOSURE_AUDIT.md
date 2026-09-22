# RP-041 legal-closure audit — clause-by-clause (2026-09-22T19:15Z)

Método RP-3: cada cláusula del criterio contrastada contra evidencia OBSERVADA
(comando/test/ejecución real), no contra recibos documentales.

**Criterio (ROADMAP L73):** "probar aislamiento del runner local, filesystem,
proceso, límites de CPU/memoria/tiempo, egress y secretos; declarar claramente
modelo de amenaza y diferenciar ejecución confiable de multi-tenant. Cualquier
capacidad que aún sea best-effort queda fuera del perfil de ejecución no
confiable."

| # | Cláusula | Evidencia OBSERVADA | Veredicto |
|---|---|---|---|
| 1 | filesystem aislado/probado | `RunnerIsolationProjectionLawTest` 3/3 (escape relativo → `InvalidInput` pre-efecto; interno dentro de workspace; `a/../b` aceptado); SB-S-001 (cwd=workspace), SB-S-002 (escritura fuera: best-effort, corre sin crash), SB-S-008 (branches cwd aislados) — suites en verde en T2 y CI | CUMPLE (con la reserva honesta de #6: NO es jail) |
| 2 | proceso probado | watchdog `DurableShellExecutor` (flag-then-kill, cookie scan, settle r9) ejercitado por WL-T2 (`sleep 60` killed en <25s, FAILED_TIMEOUT), SB-S-007 (kill mid-step → LOST), S1 (hijo de cuerpo externo FAILED_TIMEOUT) | CUMPLE |
| 3 | límites de TIEMPO probados | WL-T1/T2/T3 + S1 external-body parity (TimeoutScheduled antes del hijo; RunOutcome Failure(TIMEOUT) acotado) | CUMPLE |
| 4 | límites CPU/MEMORIA | NO contenidos en L3. El criterio NO exige contenerlos: exige "probar ... y declarar". La declaración existe (threat model §1: NO CONTENIDO; §2: MULTI_TENANT irrepresentable) y está pinada en `RunnerTrustProfileTest` 3/3 (constructor fail-closed ADR-0016 M5/M9) | CUMPLE (declaración honesta, sin PASS fingido) |
| 5 | EGRESS | NO contenido en L3; declarado FUERA del perfil no confiable (threat model §1/§2 + mensaje de excepción machine-checkable) | CUMPLE (declaración) |
| 6 | Secretos | typed `SecretHandle` channel (materialización única en `pb.environment().putAll`), redacción chunk-boundary-safe consola (Lpr011 UAT) y at-rest (Lpr011r2 UAT), SB-S-003/004/005/009 (env deny-list, PATH, JAVA_HOME) — suites verdes en T2/CI | CUMPLE |
| 7 | Modelo de amenazas declarado | `WU_RP_041_RUNNER_ISOLATION_THREAT_MODEL.md` (vectores, contención por superficie, best-effort fuera de perfil) | CUMPLE |
| 8 | Confiable vs multi-tenant diferenciado | ADT `RunnerTrustProfile`: TrustedSingleTenant {NONE, LOCAL}; MultiTenantConstrained no construible en L3 → ningún run puede CLAIMAR aislamiento multi-tenant | CUMPLE |
| 9 | Best-effort fuera del perfil no confiable | la única forma de representar "no confiable" hoy lanza `SandboxProfileUnsupportedException` → es imposible declarar un perfil no confiable que dependa de capacidades best-effort | CUMPLE (por construcción) |

## Regresión y duplicidad

- Cero cambio de producción en S1/S3-tests (sólo ADT nuevo declarativo en
  sdk/runtime, sin consumidores que migrar: `Main.kt` mantiene `--sandbox-profile`;
  `RunnerTrustProfile` es la capa de DECLARACIÓN de confianza, `SandboxProfile`
  sigue siendo el mecanismo. Sin solapamiento semántico: uno clasifica al runner,
  el otro configura el proceso).
- T2 runtime+application 0 fallos; gate `check` incremental green;
  CI 35767151719 SUCCESS 9/9 (a8068165) y CI SUCCESS en head 991454e8.

## Residuos clasificados (no bloquean el criterio)

- R1: CPU/mem/egress requieren M5/M9 (ya ADR-0016; no es deuda nueva).
- R2: UAT-RP-018 PARTIAL permanece (perfil 'os' → M5/M9), matriz actualizada.
- R3: `MANIFEST.json` de publishHTML (UAT-RP-005 inv 3, ADR-0095) re-evaluación
  obligatoria en WU-RP-042 (release gate) — se aborda en la WU siguiente.

## Veredicto

**WU-RP-041: CUMPLE legalmente** (criterio de declaración+prueba de aislamiento
dentro de las capacidades L3, con lo no contenible declarado y hecho
irrepresentable en el ADT). Cierre legal confirmado.
