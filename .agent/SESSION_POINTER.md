# SESSION_POINTER — ÚNICO puntero de reanudación

**Actualizado:** 2026-09-23T12:45Z. **Tipo de cambio de esta sesión:** **Auditoría de deuda técnica abierta — LF-0403 detectado como defecto funcional no documentado**. WU-RP-046 R2 cerrada en c2de6bca (recertificación UAT-RP-022 byte-idéntico + WU-RP-040 RECEIPT consolidado + flake M3 SIGPIPE + CLI defect reclasificación). Sesión actual descubrió dos TODO LF-0403 en `CredentialProjection.kt:189,233` con `SecretHandle.masked("")` (placeholder vacío) en lugar del contenido real del credential `LinkedSecretRef`. Defecto reproducible: SSH con passphrase se inyecta vacía → SSH falla por passphrase incorrecta. PLAN redactado en `docs/v2/07-uat/WU_RP_049_LF0403_PLAN.md` (ADR-0097 + RED + GREEN + WIRING + INTEGRATION + RECEIPT, 6 commits). HEAD = c2de6bca (LOCAL + REMOTE sincronizados, CI 35849856485 SUCCESS 10/10). **NO_GO estricto**: NO_RELEASE, no tocar Step core, no Step framework OS-level, no overlay package.

**Código auditado:** main @ c2de6bca. WU head = c2de6bca.

**Documento de prioridad:** docs/v2/05-roadmap/ROADMAP.md.
**Certificación:** docs/v2/07-uat/CERTIFICATION_PROTOCOL.md.
**Matriz UAT:** docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md.
**Diario:** .agent/WORK_JOURNAL.md.

## Estado operativo

- ACTIVE_PHASE: **RP-5 GATE — preparación honesta, pendiente WU-RP-040 R5 + LF-0403 + UAT-RP-024 + UAT-RP-005 inv3 disclosure**. WU-RP-046 R2 cerrada en c2de6bca (recertificación 022 + WU-RP-040 RECEIPT + flake M3 SIGPIPE + CLI defect reclasificación). Sesión actual descubrió deuda técnica abierta LF-0403 (TODO en CredentialProjection.kt:189,233 con placeholder vacío en lugar de LinkedSecretRef resuelto); PLAN WU-RP-049 redactado con slice A-min (ADR-0097 + RED + GREEN + WIRING + INTEGRATION + RECEIPT). Cierre de RP-5 sigue bloqueado por: (a) WU-RP-040 R5: SAST/detekt + Dependabot + Kover-all-modules + triage de 128 mutantes sobrevivientes, (b) LF-0403: defecto funcional SSH passphrase/password sin resolver, (c) UAT-RP-024 (≥2 repos dogfooding), (d) divulgación UAT-RP-005 inv3 en release notes.
- LAST_CLOSED_WU: **WU-RP-046 R2** (c2de6bca, CI 35849856485 SUCCESS 10/10): UAT-RP-022 byte-idéntico recertificado (ZIP sha256 `6c30e6b6e9b538fdae3dd1ee523173f6c5d7a917d2856043fc4409db67fe7ae1`); WU-RP-040 RECEIPT consolidado publicado; M3 SIGPIPE flake NO REPRODUCIBLE; CLI defect reclasificado. L0/L1 ejecutables (koverLog, cyclonedxBom, pitest, distZip×2) todos BUILD SUCCESSFUL. Slip-guard: cero código de producción tocado, cero tests añadidos.
- KNOWN_LIMITATIONS adicional detectada esta sesión (no añadida aún a matriz para no maquillar; pendiente tras WU-RP-049 cierre):
  - **LF-0403 SSH/cert passphrase-password LinkedSecretRef (defecto funcional, no documentado):** `CredentialProjection.kt:189,233` inyecta `SecretHandle.masked("")` (placeholder) en lugar del contenido real del credential referenciado por `LinkedSecretRef`. Si el usuario define SSH key con passphrase o Certificate con password, el env se inyecta con string vacío y el handshake falla por credencial incorrecta. **Sin ADR formal, sin RECEIPT, sin issue GitHub.** Plan en `docs/v2/07-uat/WU_RP_049_LF0403_PLAN.md` para cerrar en 6 commits (ADR-0097 + RED + GREEN + WIRING + INTEGRATION + RECEIPT). Tests pre-existentes (`UatLocal008SshPrivateKeyRoundGateTest.CR-RD-021`) verifican **no-leak** del canary (que pasa verde porque el canary nunca llega al env); NO verifican que el canary SÍ aparezca cuando corresponde — el defecto está enmascarado por el diseño de los tests.
  - Histórico WU-RP-044 (f7ee7e8f): M5 RSS debt cerrada por streaming end-to-end (maxRss ~10GB → ~1,4GB con -Xmx1g en soak 1GiB, lossless 1073741824 chars). Sub-corrección: `.gitleaks.toml` allowlist (12 fixtures intencionales pre-existentes, 0 hits introducidos por WU). L1 4/4 + L2 21/21 (Lpr011/11r2 + streaming) + L3 events 188/188 + L4 application 1735/0 + sdk-runtime 190/0 + L5 incremental check BUILD SUCCESSFUL.
  - Histórico RP-043 (74617ff8): dogfood CI (N1 bootstrap, N2 pipelinek ejecuta .pipeline.kts del mismo SHA, N3 verificación externa).
  - Histórico RP-042 (e23c575d): S1 reproducibilidad bit-a-bit distZip, S2 credentials CLI regression + ADR-0096.
  - Histórico RP-041 (a8068165): S1 paridad cancelación deadline, S2/S3 threat model + RunnerTrustProfile ADR-0016.
  - Histórico RP-040 (R1-R4): Kover, SHA-pinning, gitleaks+SBOM, pitest mutation.
- KNOWN LIMITATIONS (vigentes, sin suavizar — actualizadas tras WU-RP-046 R2):
  - **UAT-RP-005 invariant 3 (MANIFEST.json archivado):** FAIL_PROVEN, ADR-0095, deferida a RP-5 con divulgación obligatoria en release notes (NO se reabre).
  - **UAT-RP-019/020/021 (Gradle/Maven/Node real):** COVERED en opt-in (`UAT_RP_019_RUN=1`, etc.); 6/6 PASS verificados en HEAD 87d7f2ef (CI run 35846205928). Por defecto los tests SKIP en CI por su coste.
  - **UAT-RP-022 (Release byte-idéntico):** **COVERED** en HEAD 2a66317c (recertificado en WU-RP-046 R2, ZIP sha256 `6c30e6b6e9b538fdae3dd1ee523173f6c5d7a917d2856043fc4409db67fe7ae1` doble build idéntico).
  - **UAT-RP-023 (Cadena suministro):** COVERED en parte (R3.1 SBOM + R3.2 secret-scan). SAST/detekt (R3.3) y Dependabot (R3.4) son KNOWN_GAP en WU-RP-040 RECEIPT consolidado.
  - **UAT-RP-024 (Dogfooding en dos repos):** KNOWN_LIMITATION. Imposible de cumplir en sesión autónoma (no hay 2 repos ajenos); el roadmap exige uso en ≥2 repos.
  - **M3 SIGPIPE flake:** **NO REPRODUCIBLE en HEAD 2a66317c** (10 ejecuciones todas exit=0). Clasificado como QUARANTINED + NO_REPRODUCIBLE_AT_CURRENT_HEAD. Reactivar sólo si reaparece en CI/soak.
  - **"Defecto CLI exit-code-0-on-typed-exception":** **RECLASIFICADO** — NO es un defecto del binario pipelinek (éste retorna exit=0/1/2 correctamente según outcome). Era artefacto del bash pipe `... | tail` en scripts de tests. Workaround `|| { echo ORACLE_X_BAD_FAIL; exit 1; }` introducido en WU-RP-046 R1 sigue siendo válido en scripts bash de tests que usen pipes; NO requiere fix en código de producción.
  - **UAT-RP-018 PARTIAL→COVERED en matriz (actualizado en WU-RP-046 R1).** LOCAL certificada a 12 caps; `os` sigue fail-closed con ADR-0016 M5/M9 (RP-7+ scope, no se construye aquí).
  - **Paquete externo** `docs/pipeline-kotlin-config-overlay-package/`: depositado pero NO integrado (colisión de identificadores con ADRs/WUs vigentes). Decisión: incorporar como propuesta tras RP-5 con identificadores libres.
  - **WU-RP-040 R1 Kover PARTIAL:** sólo domain (82.64%) + events (77.62%); 12 módulos sin cobertura medida.
  - **WU-RP-040 R3.3/R3.4 KNOWN_GAP:** SAST/detekt y Dependabot/dependency-check NO implementados — bloqueante RP-5 Gate honesto.
  - **WU-RP-040 R4 pitest PARTIAL:** mutation score 42% domain / 50% SDK; 128 mutantes sobrevivientes sin triage.
- NEXT_WU: **WU-RP-049 LF-0403** — cerrar TODO passphrase/password LinkedSecretRef en CredentialProjection.kt. 6 commits según plan (ADR-0097 + RED + GREEN + WIRING + INTEGRATION + RECEIPT). Sigue **WU-RP-040 R5** (SAST/detekt + Dependabot + Kover-all-modules + triage). Sigue **WU-RP-048** (candidato, dogfooding 1-repo fork para UAT-RP-024). **NO_RELEASE** hasta: (a) LF-0403 verde, (b) WU-RP-040 R5 verde, (c) UAT-RP-024 evidencia parcial de 1-repo dogfooding, (d) divulgación UAT-RP-005 inv3 en release notes.
- BLOCKERS: ninguno técnico. Bloqueante de política RP-5 Gate: SAST + Dependabot pendientes; LF-0403 defecto funcional abierto; dogfooding ≥2 repos estructuralmente imposible.
- NO_GO: Step core nuevo o release; no editar recibos históricos; no cambiar contrato público sin ADR; **NO empezar el framework de agentes/contenedores (eso es RP-7+); NO integrar el paquete overlay antes de cerrar RP-5**. Próximo ADR libre: ADR-0097.
- RELEASE_REFERENCE: v0.39.0; HEAD posterior NOT_YET_RECERTIFIED until RP-5. **Prerrequisito irreducible:** UAT-RP-019/020/021 ejecutables en HEAD + reproducibilidad UAT-RP-022 + UAT-RP-018 matriz actualizada a COVERED + M3 SIGPIPE caracterizado (resuelto o quarantined honesto).
- OPERATIONAL NOTE: sub-agent swarm pool no funcional; orchestrator-direct con evidencia verificable (patrón preautorizado).

## Inicio de la siguiente sesión (solo lectura antes de tocar código)

1. `git status --short && git rev-parse HEAD && git log -1` — no asumir HEAD = 87d7f2ef.
2. Leer ROADMAP (§6 RP-4, §7 RP-5), CERTIFICATION_PROTOCOL, UAT_MATRIX, este puntero y la última entrada de WORK_JOURNAL.
3. CI verificado para 87d7f2ef (run 35841650754 al cierre); si HEAD avanzó, verificar el nuevo SHA.
4. Gradle SIEMPRE desde v2: `cd v2 && ./gradlew <tasks>`.
5. Primer comando sugerido: `git status --short && git log -1` — debe mostrar árbol limpio en 87d7f2ef + `docs/pipeline-kotlin-config-overlay-package/` como untracked; verificar que nada más cambió y proseguir con WU-RP-046 (1) matriz UAT-MATRIX baseline update, (2) UAT-RP-019/020/021 Gradle/Maven/Node real (3) caracterización M3 SIGPIPE (4) recertificación UAT-RP-022 (5) emisión CERTIFICATIONS.md con KNOWN_LIMITATION explícito para UAT-RP-024.
