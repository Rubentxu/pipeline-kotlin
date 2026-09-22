# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T18:40Z. **Tipo de cambio de esta sesión:** **WU-RP-041 S1-S3 CLOSED** — aislamiento runner local (deuda cancelación RP-3 + threat model + ADT confianza). Head = a8068165. Receipt: WU_RP_041_RECEIPT.md. CI: SUCCESS 9/9 (run 35767151719, SHA a8068165; 1er intento infra ECONNRESET, rerun --failed verde).

**Código auditado:** main @ 554672aa. WU head = 554672aa.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-4 OPEN** — WU-RP-040 R1-R3 cerradas (kover, SHA-pinning, gitleaks+SBOM). RP-3 EXIT REVIEWED (RP3_EXIT_REVIEW.md).
- LAST_CLOSED_WU: **WU-RP-041 S1-S3** (a8068165): S1 paridad de cancelación por deadline en hijos de cuerpo externo (deuda #1 RP-3 cerrada; cero cambio de producción); S2 threat model runner (WU_RP_041_RUNNER_ISOLATION_THREAT_MODEL.md); S3 RunnerTrustProfile ADT (multi-tenant irrepresentable en L3, fail-closed ADR-0016 M5/M9) + pins de leyes dir. CPU/mem/egress fuera del perfil no confiable.
  - Regresión: scripting-api 50, application 1726, domain 554, sdk-api 393, arch 313 (allow-list +RegistryBlockSpec), gate check green local.
- KNOWN LIMITATIONS (vigentes):
  - UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a WU-RP-042.
  - M5 maxRss ~11 GB: candidato streaming-chunks RP-4.
  - Flake M3 SIGPIPE child 1x, no determinista.
  - UAT-RP-018 PARTIAL (sandbox-profile 'os' → RP-4/5, ADR-0016).
- NEXT_WU: **WU-RP-042 S2** (cierre de la WU): regresión JUnit del fix credentials-CLI, gate L5 del head, reevaluación UAT-RP-005 inv 3 / ADR-0095, receipt final. S1 ya ejecutado (76e3015d): reproducibilidad bit-a-bit, zero-install, proyectos reales, credenciales e2e, receipt WU_RP_042_S1_SLICE_RECEIPT.md. Primer comando: cd v2 && ./gradlew :pipeline-credentials-local:test.
- BLOCKERS: ninguno.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR. Próximo ADR libre: ADR-0096.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 554672aa.
2. Leer ROADMAP (§5 RP-3), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI ya verificado para 65365fd8 (run 35756206083 SUCCESS); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: leer dispatchBody de CanonicalDurableRunCoordinator y caracterizar cómo RegistryBodyPolicyResolver consume StepDescriptor antes de la auditoría declaración-vs-ejecución.
