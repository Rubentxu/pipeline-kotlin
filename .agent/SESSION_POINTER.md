# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T13:50Z. **Tipo de cambio de esta sesión:** **CIERRE WU-RP-050 — consolidación `LinkedSecretRef` resolution**. WU-RP-049 R1 cerrada en `25818c10` (CI 35855686796 SUCCESS 10/10). Sesión actual implementó el slice WU-RP-050 con 5 commits (PLAN + ADR + RED + GREEN + REFACTOR + RECEIPT) cerrando las 2 duplicaciones auditadas en WU-RP-049 R1 dentro de `GitCredentialsApplier`. **HEAD = 4fb79b01** (LOCAL divergente de REMOTE `25818c10` — push pendiente). **NO_GO estricto**: NO_RELEASE, no tocar Step core, no Step framework OS-level, no overlay package.

**Código auditado:** main @ 4fb79b01. WU head = 4fb79b01.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-5 GATE — preparación honesta, pendiente WU-RP-040 R5 + UAT-RP-024 + UAT-RP-005 inv3 disclosure**. **WU-RP-050 CERRADA** (`4fb79b01`): 2 duplicaciones de `LinkedSecretRef` resolution eliminadas vía nuevo adapter `SecretStoreLinkedSecretResolver` en `:pipeline-credentials-api` + refactor `GitCredentialsApplier` para consumirlo. ADR-0098 firmado + 5 commits (47bf75d1..4fb79b01). 12/12 criterios PASS. 0 regresiones en scm-git (26/26), UAT git auth (15 ejecutados / 2 skipped / 0 fails), credentials modules (64 tests / 0 fails).
- LAST_CLOSED_WU: **WU-RP-050** (`4fb79b01`, local divergente de remote `25818c10` — push pendiente): 2 duplicaciones eliminadas en `GitCredentialsApplier.resolveSecret` + `resolveAndEncode` → ahora delegan al adapter compartido. `SpiCredentialLinkedSecretResolver` se mantiene (legítimo: opera sobre el SPI port `CredentialProvider`, no sobre `SecretStore`).
- KNOWN_LIMITATIONS adicional detectada en esta sesión (cerrada en WU-RP-050 — no requiere divulgación nueva):
  - **WU-RP-050 consolidación parcial:** Las 2 duplicaciones de `LinkedSecretRef` en producción están consolidadas. La única llamada directa restante a `SecretStore.getAsSecretHandle` fuera del adapter es `LocalCredentialProvider.resolve` (legítimo: SPI implementation). `SpiCredentialLinkedSecretResolver` permanece como adapter sobre `CredentialProvider` SPI con semántica más rica (validación de tipo, audit); colapsarlo con el nuevo adapter requeriría leaky abstraction.
- WUs previas cerradas (histórico, sin suavizar):
  - **WU-RP-049 R1** (25818c10, CI 35855686796 SUCCESS 10/10): LF-0403 cerrado vía port domain hexagonal. ADR-0097 firmado + 6 commits (9649872e..25818c10). 18/18 tests projector PASS. 3 KNOWN_FLAKE pre-existentes documentados en `WU_RP_049_R1_SLICE_RECEIPT.md` como no-regresiones.
  - **LF-0403 SSH/cert passphrase-password LinkedSecretRef (defecto funcional):** CERRADO en WU-RP-049 R1 (25818c10). Port `CredentialLinkedSecretResolver` + adapter `SpiCredentialLinkedSecretResolver` resuelven `LinkedSecretRef` → `SecretHandle` real. SSH keystore handshakes ahora funcionan. Tests: 18/18 PASS (5 nuevos en `Lf0403LinkedSecretResolverTest` + 13 pre-existentes en `DefaultCredentialProjectorTest`).
  - Histórico WU-RP-044 (f7ee7e8f): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0099.
- KNOWN LIMITATIONS (vigentes — sin suavizar, arrastradas de WU-RP-046 R2 + auditorías previas):
  - **UAT-RP-005 inv3 (MANIFEST.json archivado):** FAIL_PROVEN, ADR-0095, deferida a RP-5 con divulgación obligatoria en release notes (NO se reabre).
  - **UAT-RP-019/020/021 (Gradle/Maven/Node real):** COVERED opt-in (`UAT_RP_019_RUN=1`, etc.); 6/6 PASS HEAD 87d7f2ef (CI 35846205928). SKIP por defecto en CI por coste.
  - **UAT-RP-022 (Release byte-idéntico):** COVERED HEAD 2a66317c (recertificado WU-RP-046 R2, ZIP sha256 `6c30e6b6...` doble build idéntico).
  - **UAT-RP-023 (Cadena suministro):** COVERED en parte (R3.1 SBOM + R3.2 secret-scan). SAST/detekt (R3.3) y Dependabot (R3.4) son KNOWN_GAP — bloqueante RP-5 Gate.
  - **UAT-RP-024 (Dogfooding en dos repos):** KNOWN_LIMITATION. Imposible de cumplir en sesión autónoma.
  - **M3 SIGPIPE flake:** NO REPRODUCIBLE en HEAD 2a66317c (10 ejecuciones todas exit=0). QUARANTINED + NO_REPRODUCIBLE_AT_CURRENT_HEAD.
  - **CLI exit-code-0-on-typed-exception:** RECLASIFICADO — no es defecto del binario (retorna 0/1/2 correcto); artefacto de bash `... | tail`. Workaround `|| { ... }` válido en scripts bash de tests.
  - **UAT-RP-018 LOCAL COVERED a 12 caps.** `os` fail-closed ADR-0016 M5/M9 (RP-7+ scope).
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado NO integrado (colisión identificadores con ADRs/WUs vigentes); incorporar tras RP-5.
  - **WU-RP-040 R1 Kover PARTIAL:** domain (82.64%) + events (77.62%); 12 módulos sin cobertura.
  - **WU-RP-040 R4 pitest PARTIAL:** mutation 42% domain / 50% SDK; 128 mutantes sobrevivientes.
- NEXT_WU: **WU-RP-040 R5** (SAST/detekt + Dependabot + Kover-all + triage mutantes). Sigue **WU-RP-048** (dogfooding 1-repo fork para UAT-RP-024). **NO_RELEASE** hasta: (a) WU-RP-040 R5 verde, (b) UAT-RP-024 evidencia 1-repo dogfooding, (c) divulgación UAT-RP-005 inv3 release notes. LF-0403 cerrado. WU-RP-050 cerrado.
- BLOCKERS: ninguno técnico. Política RP-5 Gate: SAST + Dependabot pendientes; dogfooding ≥2 repos estructuralmente imposible; divulgación UAT-RP-005 inv3 pendiente.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5. **Prerrequisito irreducible:** UAT-RP-019/020/021 ejecutables en HEAD + UAT-RP-022 reproducibilidad + UAT-RP-018 matriz COVERED + M3 SIGPIPE caracterizado.
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 4fb79b01.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 25818c10 (run 35855686796 al cierre WU-RP-049 R1). HEAD local actual = 4fb79b01 (5 commits ahead of remote, push pendiente). Verificar el SHA que toque antes de actuar.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 4fb79b01 + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-040 R5 (SAST + Dependabot + Kover-all + triage mutantes) — próximo WU per ROADMAP.
