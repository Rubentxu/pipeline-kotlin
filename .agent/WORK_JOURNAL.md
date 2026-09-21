# WORK_JOURNAL — diario de continuidad (append-only)

**Finalidad:** conservar una secuencia breve de decisiones, trabajo realmente realizado y el siguiente punto de entrada. NO sustituye a Git, tickets, XML, recibos ni .agent/TESTING-STATE.md. Anotar UTC y SHA; nuevas entradas al FINAL, sin cambiar las anteriores. Los receipts de releases permanecen inmutables.

## 2026-09-21 — RP-BOOT-001 — Orden documental y nuevo contrato de certificación

- Baseline observada: main a554fd5544f74f580bbd531c9b394cff1e073621; a esta fecha el workflow LPR-0 del SHA falló antes de compilar por no encontrar ./gradlew en raíz. Versión v0.39.0 publicada el 2026-09-19, no equivale al estado posterior de main.
- Hecho en esta unidad documental: archivar las cinco propuestas empaquetadas y la cronología/snapshot LPR antiguos sin tocar sus blobs; adoptar ROADMAP.md como única secuencia; establecer ACTIVE_DOCUMENTS, CERTIFICATION_PROTOCOL, PRODUCTION_READY_UAT_MATRIX y SESSION_POINTER; añadir protocolo de recuperación a AGENTS.md.
- Decisión: gates RP-0 y RP-1 preceden nuevos Steps; cada certificado se ancla al SHA/artefacto; conservar histórico de M3/ML/EM/EVT y recibos previos. No confundir REGISTERED con CERTIFIED.
- Código/CI/tests de aplicación editados: NINGUNO. Pruebas ejecutadas durante esta unidad: NINGUNA. Estado: DOCUMENTACIÓN PUBLICADA si commit existe; BUILD/CI/PRODUCT-GATE siguen NO_VERIFICADOS en el nuevo SHA.
- Riesgos iniciales: H01 CI; H02 suite publishHTML; H03/H04 index.html/inyección; H05/H06 symlinks; H07 coordinator; H08/H09 serialización/concurrencia; H10 drift.
- Próxima unidad WU-RP-000: inspeccionar Git/CI actuales, corregir wrapper/workflows, conseguir gates de CI reales. NO continuar WU-LPR-091 antes de RP-0/RP-1.
- Registro del commit documental: identificar mediante git log -1 -- docs/v2/05-roadmap/ROADMAP.md; no escribir SHA anticipado. Al iniciar la próxima sesión, agregar una entrada breve de comprobación de cabeza de rama.

## Plantilla para próximas entradas (copiar al final, no marcar PASS sin resultado)

### YYYY-MM-DDTHH:MM:SSZ — WU-RP-NNN — estado

- Base SHA / HEAD SHA / branch:
- Intención, contrato y UAT:
- Decisión/ADR; rutas modificadas:
- Tests realmente ejecutados: comando, exit, XML (tests/failures/errors/skipped), artefacto SHA; CI URL:
- PASS / FAIL / BLOCKED / NOT_RUN y causa; evidencia histórica todavía válida/caducada:
- Bloqueos y riesgo residual:
- Puntero actualizado: NEXT_WU y primer comando reproducible:

### 2026-09-21T11:58Z — WU-RP-000 — CI path repair (PASS local; remote CI verification pending push)

- Base SHA / HEAD SHA / branch: base = 8b5f41bfc9239a72de01a063e433875912357ae8 (origin/main @ audit baseline); HEAD = 5aa318029337dd5fbbf3fe54a3233b91a2a8bda4 (local, NOT_YET_PUSHED); branch = main.
- Intención: ROADMAP.md §2 WU-RP-000 — fix CI workflows so the SHA actually executes. Caracterizar la falla del run 35584931177 (compile job FAIL exit 127; domain/arch-fitness/application-focused SKIPPED) y corregir wrapper path + typo + build.yml vacío. NO_GO respetado: cero código de aplicación, cero Steps nuevos, cero releases. Receipt: docs/v2/07-uat/WU_RP_000_RECEIPT.md.
- Decisión/ADR; rutas modificadas: 3 archivos, 7 líneas modificadas + 1 archivo (0 bytes) eliminado. No se requieren ADRs nuevos — el cambio es corrección de paths, sin tocar contratos.
  - .github/workflows/lpr0-ci.yml (5 sitios): `./gradlew -p v2 ...` → `cd v2 && ./gradlew ...`
  - .github/workflows/lpr0-ci.yml (1 sitio): `uploads/upload-artifact@v4` → `actions/upload-artifact@v4` (typo que hubiera roto architecture-fitness si corriera).
  - .github/workflows/v2-baseline.yml (1 sitio): `./gradlew -p v2 check` → `cd v2 && ./gradlew check`.
  - .github/workflows/build.yml: eliminado (0 bytes, sin valor; LPR-0 CI lo cubre).
  - Causa raíz: `.gitignore:12` ignora `gradlew` raíz; sólo `v2/gradlew` está tracked. Por eso CI falla con "No such file or directory" exit 127.
- Tests realmente ejecutados (todos locales; remote GH Actions pendiente del push):
  - `cd v2 && ./gradlew compileKotlin --no-daemon --quiet` → exit 0. NO XML (no es test).
  - `cd v2 && ./gradlew :pipeline-domain:test --no-daemon --quiet` → exit 0. XML aggregate v2/pipeline-domain/build/test-results/test/TEST-*.xml: tests=554 failures=0 errors=0 skipped=0.
  - `cd v2 && ./gradlew :pipeline-events:test --no-daemon --quiet` → exit 0. XML aggregate v2/pipeline-events/build/test-results/test/TEST-*.xml: tests=178 failures=0 errors=0 skipped=0.
  - `cd v2 && ./gradlew :pipeline-architecture-tests:test --no-daemon --quiet` → exit 1. XML aggregate v2/pipeline-architecture-tests/build/test-results/test/TEST-*.xml: tests=309 failures=2 errors=0 skipped=0.
    - Failure 1: `FArchL7DomainEventExhaustivityTest.domain_event_sealed_hierarchy_has_48_variants` — drift 48→51; LPR-090 phase-a (8dd59eba) añadió HtmlReport{Failed,Published,Skipped} sin bumpear el contador. PRE-EXISTING en 8b5f41bf. WU-RP-002.
    - Failure 2: `Lfc0V1QuarantineFitnessTest.UAT catalogue lists all four governance contracts` — apunta a `docs/pipeline-kotlin-local-foundation-consolidation/.../UAT_CATALOG.md` archivado en 8b5f41bf. PRE-EXISTING en 8b5f41bf. WU-RP-002.
  - yaml syntax: `python3 -c "import yaml; yaml.safe_load(...)"` para lpr0-ci.yml y v2-baseline.yml → OK.
  - Remote CI: pendiente. Acción: push 5aa31802 y verificar `gh run list --workflow=lpr0-ci.yml --limit 3` para confirmar compile PASS + jobs NO skipped.
- PASS / FAIL / BLOCKED / NOT_RUN: PASS local scope (workflow YAML syntax + local L0/L1 GREEN). REMOTE_GATE: NOT_RUN (push pendiente). Pre-existing KNOWN_FAILURES (arch-fitness 2) documentadas y deferidas a WU-RP-002.
- Bloqueos y riesgo residual:
  - GH Actions run de 5aa31802 no ejecutado todavía. Hasta verlo verde NO se puede afirmar PRODUCT-GATE verde en este SHA (per CERTIFICATION_PROTOCOL §4 y SESSION_POINTER NO_GO).
  - Pre-existing drift en arch-fitness (48 vs 51; archived path) → WU-RP-002 (inventory + path reconciliation).
  - SDKMAN channel state: independiente; no en scope WU-RP-000.
  - v0.39.0 release certification permanece en su propio SHA; HEAD=5aa31802 NOT_YET_RECERTIFIED hasta T3/T4/T5 verde.
- Puntero actualizado: NEXT_WU = WU-RP-001 (mapear checks obligatorios + protección de main + verificar GH Actions run verde de 5aa31802). Primer comando reproducible:
  ```bash
  cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin
  git checkout main && git push origin main  # push 5aa31802
  gh run list --workflow=lpr0-ci.yml --limit 3
  gh run view <new-run-id> --json jobs
  ```
