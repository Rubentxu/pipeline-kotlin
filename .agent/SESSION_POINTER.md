# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T11:02Z. **Tipo de cambio de esta sesión:** **WU-RP-045 cerrada** (UAT-RP-018 LOCAL sandbox cert + 'os' fail-closed pin). HEAD = 57833497 (local + remote sincronizados, CI 35840575377 SUCCESS en push receipt; CI 35839625273 SUCCESS 10/10 en f1ea0cf7 test commit). WU-RP-044 cerrada en sesión anterior (no re-trabajar). PENDIENTES: WU-RP-046 (M3 SIGPIPE flake + reconciliación skipped obligatorios + RP-5 Gate completo). Colisión de identificadores detectada con `docs/pipeline-kotlin-config-overlay-package/`: NO integrar hasta RP-5 cerrado; renumerar ADR-0096/0097 del paquete al integrar.

**Código auditado:** main @ 57833497. WU head = 57833497.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-4 → RP-5 transición** — WU-RP-040 R1-R4 cerradas. WU-RP-041 S1-S3 cerradas. WU-RP-042 S1+S2 cerradas. WU-RP-043 cerrada. WU-RP-044 cerrada. **WU-RP-045 CERRADA (f1ea0cf7 tests + 57833497 receipt; CI 35840575377 SUCCESS en 57833497; CI 35839625273 SUCCESS 10/10 en f1ea0cf7)** — UAT-RP-018 LOCAL sandbox cert (14/14 PASS en UatLocal007SandboxProfileTest) + CLI fail-closed pin para `os` (SandboxProfileUnsupportedException ADR-0016 M5/M9).
- LAST_CLOSED_WU: **WU-RP-045** (57833497, CI 35840575377 SUCCESS; tests commit f1ea0cf7 CI 35839625273 SUCCESS 10/10): LOCAL sandbox certificable a los límites verificables (cwd HOME JAVA_HOME LD_PRELOAD PATH rogues write-outside-workspace kill-mid-step resume parallel cwds) + `pipelinek --sandbox-profile os` fail-closed pin con ADR-0016 M5/M9. L1 14/14 PASS + L2 vecinos (Lpr011 6/11/12/1; TranscriptStreamingEmissionTest 4/4) + L3 SandboxProfileTest 11/11 + RunnerTrustProfileTest 3/3 + L4 application 1737/0 + L5 incremental check BUILD SUCCESSFUL 13s no-op. Slip-guard restaurado: el framework OS-level / `EffectiveRunPlan` / `JobDefinition` / parser YAML / nuevas APIs públicas permanecen EXCLUIDOS del scope (RP-7+).
  - Histórico WU-RP-044 (f7ee7e8f): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- KNOWN LIMITATIONS (vigentes):
  - UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095/0096, deferida a RP-5 con divulgación obligatoria en release notes (NO se reabre).
  - Flake M3 SIGPIPE child 1x, no determinista (WU-RP-046 candidata para caracterizar).
  - UAT-RP-018 PARTIAL: sandbox-profile 'os' no instanciable en L3 (ADR-0016 M5/M9). Perfil LOCAL es el anuncio de RP-5; OS queda reconocido con mensaje fail-closed explícito.
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado pero NO integrado (colisión de identificadores con ADRs/WUs vigentes). Decisión: incorporar como propuesta tras RP-5 con identificadores libres.
- NEXT_WU: **WU-RP-046** — caracterización M3 SIGPIPE flake 1x non-determinista → reconciliación skipped obligatorios (auditados en sesión WU-RP-045: 0 skipped en UAT-RP-001..024; 115 skipped son snapshots históricos) → **RP-5 Gate completo sobre un SHA y ZIP exactos** (UAT-MATRIX row update, CERTIFICATIONS update, WU-RP-044/045 sum).
- BLOCKERS: ninguno.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0097.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 57833497.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 57833497 (run 35840575377 SUCCESS); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 57833497 + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-046 (M3 SIGPIPE flake → reconciliación skipped → RP-5 Gate completo).
