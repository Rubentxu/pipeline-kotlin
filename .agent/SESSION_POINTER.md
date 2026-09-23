# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T10:11Z. **Tipo de cambio de esta sesión:** **Reconciliación de estado tras reporte del operador + arranque de UAT-RP-018**. HEAD = f7ee7e8f (local + remote sincronizados, CI run 35832498398 SUCCESS 10/10). WU-RP-044 cerrada en sesión anterior (no re-trabajar). PENDIENTES: UAT-RP-018 sandbox 'os' (ADR-0016), flake M3 SIGPIPE, reconciliación de tests skipped obligatorios (auditoría: 0 skipped en UAT-RP-001..024; los 115 skipped son snapshots históricos preservados por burn-down, no tests obligatorios). Colisión de identificadores detectada con `docs/pipeline-kotlin-config-overlay-package/`: NO integrar hasta RP-5 cerrado; renumerar ADR-0096/0097 del paquete al integrar.

**Código auditado:** main @ f7ee7e8f. WU head = f7ee7e8f.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-4 → RP-5 transición** — WU-RP-040 R1-R4 cerradas. WU-RP-041 S1-S3 cerradas. WU-RP-042 S1+S2 cerradas. WU-RP-043 cerrada. WU-RP-044 cerrada. **WU-RP-045 OPEN (UAT-RP-018 sandbox 'os', alcance estricto delimitado por el reporte)**.
- LAST_CLOSED_WU: **WU-RP-044** (f7ee7e8f, CI 35831695924 SUCCESS 10/10): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- KNOWN LIMITATIONS (vigentes):
  - UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095/0096, deferida a RP-5 con divulgación obligatoria en release notes (NO se reabre).
  - Flake M3 SIGPIPE child 1x, no determinista (WU-RP-046 candidata para caracterizar).
  - UAT-RP-018 PARTIAL: sandbox-profile 'os' no instanciable en L3 (ADR-0016 M5/M9). Perfil LOCAL es el anuncio de RP-5; OS queda reconocido con mensaje fail-closed explícito.
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado pero NO integrado (colisión de identificadores con ADRs/WUs vigentes). Decisión: incorporar como propuesta tras RP-5 con identificadores libres.
- NEXT_WU: **WU-RP-045 — UAT-RP-018 sandbox 'os' (ADR-0016, RunnerTrustProfile) con alcance estrictamente delimitado**: certificar el perfil LOCAL real de RP-5 (límites verificables: cwd, env deny-list, PATH normalise, cancelación de hijos, procesos descendientes, cleanup) + pruebas negative fail-closed para `os` (rechazo con mensaje ADR-0016 M5/M9). NO iniciar framework OS-level / contenedor / gVisor / Kata / microVM. NO introducir `JobDefinition`, parser YAML, ni nuevas APIs públicas. La UAT puede aportar evidencia para que el contrato genérico `ExecutionProfile` que el paquete overlay necesita después sea identificable — sin implementarlo. Tras UAT-RP-018: WU-RP-046 caracterización M3 SIGPIPE flake → reconciliación de skipped obligatorios → RP-5 Gate completo sobre un SHA y ZIP exactos.
- BLOCKERS: ninguno.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0097.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = f7ee7e8f.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para f7ee7e8f (run 35832498398 SUCCESS); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en f7ee7e8f + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-045 (UAT-RP-018 sandbox LOCAL cert + 'os' fail-closed).
