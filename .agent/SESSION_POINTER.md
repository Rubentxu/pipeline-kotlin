# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T11:18Z. **Tipo de cambio de esta sesión:** **Honestidad documental** — el operador advirtió que el conteo de cierres no equivale a que las condiciones de aceptación del producto estén verificadas. Se inicia auditoría de deuda real (UAT-RP-019..021 sin cobertura, UAT-RP-024 sin dogfooding real, UAT-RP-018 row obsoleta en matriz). HEAD = 87d7f2ef (LOCAL + REMOTE sincronizados; CI run 35841650754 en push doc, en progreso). WU-RP-045 commits: tests f1ea0cf7 (CI 35839625273 S10/10) + receipt 57833497 (CI 35840575377 S10/10) + state 87d7f2ef (en curso). **NO_GO estricto**: NO_RELEASE, no tocar Step core, no Step framework OS-level, no overlay package.

**Código auditado:** main @ 87d7f2ef. WU head = 87d7f2ef.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-5 GATE — preparación honesta**. WU-RP-040..045 cerradas individualmente (cada WU tiene su propio recibo), pero la **tabla UAT-MATRIX sigue sin reflejar la realidad**: UAT-RP-018 sigue marcada PARTIAL (no actualizada con la mejora de WU-RP-045), UAT-RP-019/020/021/024 NO tienen cobertura visible (ausencia de tests Gradle/Maven/Node reales + dogfooding en dos repos), UAT-MATRIX baseline sigue en f4aa20dc. La auditoría de esta sesión descubrió esto antes de añadir más cierres ceremoniales.
- LAST_CLOSED_WU: **WU-RP-045** (57833497, CI 35840575377 SUCCESS; tests commit f1ea0cf7 CI 35839625273 SUCCESS 10/10): LOCAL sandbox certificable a los límites verificables (cwd HOME JAVA_HOME LD_PRELOAD PATH rogues write-outside-workspace kill-mid-step resume parallel cwds) + `pipelinek --sandbox-profile os` fail-closed pin con ADR-0016 M5/M9. L1 14/14 PASS + L2 vecinos (Lpr011 6/11/12/1; TranscriptStreamingEmissionTest 4/4) + L3 SandboxProfileTest 11/11 + RunnerTrustProfileTest 3/3 + L4 application 1737/0 + L5 incremental check BUILD SUCCESSFUL 13s no-op. Slip-guard restaurado: el framework OS-level / `EffectiveRunPlan` / `JobDefinition` / parser YAML / nuevas APIs públicas permanecen EXCLUIDOS del scope (RP-7+).
  - Histórico WU-RP-044 (f7ee7e8f): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- KNOWN LIMITATIONS (vigentes, sin suavizar):
  - **UAT-RP-005 invariant 3 (MANIFEST.json archivado):** FAIL_PROVEN, ADR-0095, deferida a RP-5 Gate con divulgación obligatoria en release notes (NO se reabre).
  - **UAT-RP-019 (Gradle real):** SIN cobertura visible en HEAD; WU-RP-042 S1 menciona "tres runs de proyectos reales" pero los logs no están archivados en este working tree. RECEIPT_REQUIRED en WU-RP-046.
  - **UAT-RP-020 (Maven real):** SIN cobertura visible. RECEIPT_REQUIRED.
  - **UAT-RP-021 (Node real):** SIN cobertura visible. RECEIPT_REQUIRED.
  - **UAT-RP-022 (Release byte-idéntico):** parcialmente cubierta por WU-RP-042 S1; falta ejecutarla de nuevo en HEAD actual. RECEIPT_REQUIRED.
  - **UAT-RP-023 (Cadena suministro):** cobertura parcial via CI (sbom + secret-scan jobs), falta receipt consolidado. RECEIPT_REQUIRED.
  - **UAT-RP-024 (Dogfooding en dos repos):** SIN cobertura. Imposible de cumplir en sesión autónoma porque requiere dos repos ajenos. KNOWN_LIMITATION explícito en release notes.
  - **Flake M3 SIGPIPE child 1x, no determinista (WU-RP-046 candidata para caracterizar).**
  - **UAT-RP-018 PARTIAL:** sandbox-profile 'os' no instanciable en L3 (ADR-0016 M5/M9). La matriz dice PARTIAL desde f4aa20dc; esta sesión NO actualizó la fila aunque la cobertura mejoró (TC-003 + TC-004). RECEIPT_REQUIRED: matrix row update.
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado pero NO integrado (colisión de identificadores con ADRs/WUs vigentes). Decisión: incorporar como propuesta tras RP-5 con identificadores libres.
- NEXT_WU: **WU-RP-046** — auditoría de deuda UAT-MATRIX + cierre honesto (no ceremonial). Precedencia:
  1. Actualizar `PRODUCTION_READY_UAT_MATRIX.md` baseline a 87d7f2ef + fila UAT-RP-018 a COVERED.
  2. Implementar UAT-RP-019/020/021 (Gradle/Maven/Node real desde `install/pipelinek`) — bloqueantes RP-5 Gate.
  3. Caracterizar flake M3 SIGPIPE (deuda activa; bloqueante RP-5 si determinista).
  4. Recertificar UAT-RP-022 release byte-idéntico en HEAD actual.
  5. Emitir `docs/v2/07-uat/CERTIFICATIONS.md` que diga QUÉ se cumple vs QUÉ queda como KNOWN_LIMITATION (UAT-RP-024) o NO_APLICA (025/026/027 por scope).
  6. **NO** cerrar RP-5 Gate hasta que los 5 puntos anteriores estén verdes y la release-artifact reproducible esté en `build/distributions/pipelinek-<next>.zip`.
- BLOCKERS: ninguno técnico. **Bloqueante de política: NO_RELEASE hasta que UAT-RP-019/020/021 tengan recibo real ejecutable en HEAD actual** (RP-5 Gate honesto no se cierra sin esa evidencia).
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0097.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5. **Prerrequisito irreducible:** UAT-RP-019/020/021 ejecutables en HEAD + reproducibilidad UAT-RP-022 + UAT-RP-018 matriz actualizada a COVERED + M3 SIGPIPE caracterizado (resuelto o quarantined honesto).
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 87d7f2ef.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 87d7f2ef (run 35841650754 al cierre); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 87d7f2ef + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-046 (1) matriz UAT-MATRIX baseline update, (2) UAT-RP-019/020/021 Gradle/Maven/Node real (3) caracterización M3 SIGPIPE (4) recertificación UAT-RP-022 (5) emisión CERTIFICATIONS.md con KNOWN_LIMITATION explícito para UAT-RP-024.
