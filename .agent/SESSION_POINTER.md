# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T09:34Z. **Tipo de cambio de esta sesión:** **WU-RP-044 CERRADA (CI 35831083258 + 35831695924 SUCCESS 10/10 ambos)**. HEAD = 7c8d6e52. Streaming de transcript end-to-end (Main/ShExecution/JsonEventLog/SqliteEventStore/EventHistoryReader/DurableShellExecutor/DurableTaskTerminalAdapter) + cleanup race fix (retención condicional de console.log) + TranscriptStreamingEmissionTest 4/4 + .gitleaks.toml allowlist para fixtures intencionales (gitleaks no determinista full-history). Soak 1GiB -Xmx1g: 129s / maxRss ~1,4GB (baseline 137s/~10GB), lossless 1073741824 chars. M5 RSS debt cerrada por construcción.

**Código auditado:** main @ 7c8d6e52 (WIP local+remote, push limpio 5c683bf8..7c8d6e52). WU head = 7c8d6e52.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-4 OPEN** — WU-RP-040 R1-R4 cerradas. WU-RP-041 S1-S3 cerradas. WU-RP-042 S1+S2 cerradas. WU-RP-043 cerrada. **WU-RP-044 CERRADA (7c8d6e52, CI 35831695924 SUCCESS 10/10)**.
- LAST_CLOSED_WU: **WU-RP-044** (7c8d6e52): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL. CI 35831083258 SUCCESS 10/10 + docs commit 35831695924 SUCCESS 10/10.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- KNOWN LIMITATIONS (vigentes):
  - UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a RP-5 (WU-RP-042 cerró con KNOWN_LIMITATION mantenida).
  - Flake M3 SIGPIPE child 1x, no determinista.
  - UAT-RP-018 PARTIAL (sandbox-profile 'os' → RP-4/5, ADR-0016).
- NEXT_WU: **UAT-RP-018 sandbox 'os' (ADR-0016, RunnerTrustProfile)** — cierra el último UAT PARTIAL de RP-2. Alternativa: WU-RP-045 dogfooding N2 ampliado. Decisión inteligente: priorizar UAT-RP-018 por ser la última deuda UAT de RP-2 antes de RP-5 Gate. WU-RP-042 cerrada con release-gate verificado; NO hacer release/publicar ZIP sin autorización expresa (NO_GO vigente).
- BLOCKERS: ninguno.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR. Próximo ADR libre: ADR-0097.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 7c8d6e52.
2. Leer ROADMAP (§5 RP-3, §6 RP-4), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 7c8d6e52 (run 35831695924 SUCCESS); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 7c8d6e52; verificar que nada más cambió y proseguir con la WU elegida (UAT-RP-018 sandbox 'os' o WU-RP-045 dogfooding).
