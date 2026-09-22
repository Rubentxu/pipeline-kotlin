# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-22T16:46Z. **Tipo de cambio de esta sesión:** **WU-RP-033 CLOSED (código)** — external Step con cuerpo por registro genérico. Head = 554672aa. Receipt: WU_RP_033_RECEIPT.md. CI: SUCCESS 7/7 (run 35756206083, SHA 65365fd8).

**Código auditado:** main @ 554672aa. WU head = 554672aa.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-4 OPEN** — WU-RP-040 R1-R3 cerradas (kover, SHA-pinning, gitleaks+SBOM). RP-3 EXIT REVIEWED (RP3_EXIT_REVIEW.md).
- LAST_CLOSED_WU: **WU-RP-033** (554672aa): RegistryBlockSpec DSL genérico + registryBlock(...); lowering genérico RegistryBlockSpec→BlockStepNode; coordinator compone body-policy (registro abierto primero, fallback tabla canónica en UnknownStep); fail-closed probado (typed Failure, 0 filas journal); paridad atómica intacta.
  - Regresión: scripting-api 50, application 1726, domain 554, sdk-api 393, arch 313 (allow-list +RegistryBlockSpec), gate check green local.
- KNOWN LIMITATIONS (vigentes):
  - UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a WU-RP-042.
  - M5 maxRss ~11 GB: candidato streaming-chunks RP-4.
  - Flake M3 SIGPIPE child 1x, no determinista.
  - UAT-RP-018 PARTIAL (sandbox-profile 'os' → RP-4/5, ADR-0016).
- NEXT_WU: **WU-RP-040 R4** (mutación selectiva pitest sobre codecs/políticas); luego WU-RP-041 (aislamiento runner, incluye deuda cancelación de cuerpos externos).
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
