# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T12:12Z. **Tipo de cambio de esta sesión:** **WU-RP-046 R1 cerrada** — auditoría honesta tras advertencia del operador + UAT-RP-019/020/021 ejecutables (opt-in, 6/6 PASS) + UAT-MATRIX baseline freshened a 87d7f2ef + filas 018 COVERED, 019/020/021 COVERED opt-in, 022/023 PARTIAL, 024 KNOWN_LIMITATION, 025 NO_APLICA. HEAD = e5465ddb (LOCAL + REMOTE sincronizados, CI 35846205928 SUCCESS 10/10). WU-RP-045 cerrada en sesión anterior (no re-trabajar). **NO_GO estricto**: NO_RELEASE, no tocar Step core, no Step framework OS-level, no overlay package.

**Código auditado:** main @ e5465ddb. WU head = e5465ddb.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-5 GATE — preparación honesta, pendiente recertificación 022/023 + flake**. WU-RP-040..045 cerradas individualmente; WU-RP-046 R1 cerrada en e5465ddb (auditoría honesta + UAT-RP-019/020/021 ejecutables opt-in + matriz baseline 87d7f2ef + filas actualizadas). Cierre de RP-5 sigue bloqueado por: (a) recertificación UAT-RP-022 release byte-idéntico en HEAD actual, (b) receipt consolidado UAT-RP-023 cadena suministro, (c) caracterización o quarantined honesto de M3 SIGPIPE flake, (d) divulgación obligatoria de UAT-RP-005 inv3 en release notes.
- LAST_CLOSED_WU: **WU-RP-046 R1** (e5465ddb, CI 35846205928 SUCCESS 10/10): auditoría honesta descubrió 4 brechas no documentadas en matriz (UAT-RP-018 obsoleto, UAT-RP-019/020/021 sin cobertura, UAT-RP-024 sin dogfooding). Esta WU cierra las dos primeras (matriz freshened + 6/6 PASS opt-in Gradle/Maven/Node reales) y reporta honestamente las otras. L1 14/14 PASS (UatLocal007 + WURp019/020/021) + L2 UatLocal007 vecinos + L3 WURp019/020/021 opt-in + L5 application-module 1743/0 (216c, 121s) + L5 incremental check 13s no-op. Slip-guard: cero código de producción tocado.
  - Histórico WU-RP-044 (f7ee7e8f): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- KNOWN LIMITATIONS (vigentes, sin suavizar — actualizadas tras WU-RP-046 R1):
  - **UAT-RP-005 invariant 3 (MANIFEST.json archivado):** FAIL_PROVEN, ADR-0095, deferida a RP-5 con divulgación obligatoria en release notes (NO se reabre).
  - **UAT-RP-019/020/021 (Gradle/Maven/Node real):** COVERED en opt-in (`UAT_RP_019_RUN=1`, etc.); 6/6 PASS verificados en HEAD 87d7f2ef (CI run 35846205928). Por defecto los tests SKIP en CI por su coste.
  - **UAT-RP-022 (Release byte-idéntico):** PARTIAL. Receipt histórico en `WU_RP_042_S1_SLICE_RECEIPT.md`. **PENDIENTE recertificar en HEAD actual** (bloqueante RP-5 Gate honesto).
  - **UAT-RP-023 (Cadena suministro):** PARTIAL. CI jobs `sbom-cyclonedx` y `secret-scan-gitleaks` verdes en runs recientes; **PENDIENTE receipt consolidado único** que reúna SBOM + SCA + secret-scan + fechas y decisiones.
  - **UAT-RP-024 (Dogfooding en dos repos):** KNOWN_LIMITATION. Imposible de cumplir en sesión autónoma (no hay 2 repos ajenos); el roadmap exige uso en ≥2 repos.
  - **Flake M3 SIGPIPE child 1x, no determinista.** Candidato WU-RP-046 R2 / WU-RP-047.
  - **CLI exit-code-0-on-typed-exception defect (detectado durante WU-RP-046 R1).** Cuando un step invoca una herramienta externa que falla, la pipelinek returna exit 0 en lugar de exit !=0. Documentado en `WU_RP_046_R1_SLICE_RECEIPT.md` y `WU_RP_045_SLICE_RECEIPT.md`. **Requiere ADR/RECETA separados**.
  - **UAT-RP-018 PARTIAL→COVERED en matriz (actualizado en WU-RP-046 R1).** LOCAL certificada a 12 caps; `os` sigue fail-closed con ADR-0016 M5/M9 (RP-7+ scope, no se construye aquí).
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado pero NO integrado (colisión de identificadores con ADRs/WUs vigentes). Decisión: incorporar como propuesta tras RP-5 con identificadores libres.
- NEXT_WU: **WU-RP-046 R2** — recertificación UAT-RP-022 release byte-idéntico en HEAD actual (bloqueante RP-5 Gate honesto). Sigue WU-RP-047: caracterización M3 SIGPIPE flake + receipt consolidado UAT-RP-023 cadena suministro. **NO_RELEASE** hasta: (a) UAT-RP-022 verde, (b) divulgación UAT-RP-005 inv3 en release notes, (c) decisión sobre el defecto CLI exit-code-0-on-typed-exception, (d) [opcional] dogfooding en 1 repo para evidencia parcial de UAT-RP-024.
- BLOCKERS: ninguno técnico. Pendiente de recertificación: UAT-RP-022 release byte-idéntico en HEAD actual (WU-RP-046 R2), UAT-RP-023 receipt consolidado cadena suministro (WU-RP-047), caracterización M3 SIGPIPE flake (WU-RP-047).
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0097.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5. **Prerrequisito irreducible:** UAT-RP-019/020/021 ejecutables en HEAD + reproducibilidad UAT-RP-022 + UAT-RP-018 matriz actualizada a COVERED + M3 SIGPIPE caracterizado (resuelto o quarantined honesto).
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 87d7f2ef.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 87d7f2ef (run 35841650754 al cierre); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 87d7f2ef + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-046 (1) matriz UAT-MATRIX baseline update, (2) UAT-RP-019/020/021 Gradle/Maven/Node real (3) caracterización M3 SIGPIPE (4) recertificación UAT-RP-022 (5) emisión CERTIFICATIONS.md con KNOWN_LIMITATION explícito para UAT-RP-024.
