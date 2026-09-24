# WU-RP-053 — Promotion Receipt a CERTIFIED_FULL

**Estado:** CERTIFIED_FULL (declarado 2026-09-24T07:05Z).
**Base SHA:** `9ed0a4f2d1acda225236b843ecd782da5c68014f`
**Rama:** `adr/0094-impact-policy-and-overlay-id-gap`
**origin/main (anterior):** `74b40a6501cfcd70c67ec2cb6dc36fe507d40655`

---

## Resumen ejecutivo

La WU pasa a **CERTIFIED_FULL** porque:

1. **Toda la evidencia funcional local acredita el fix del defecto** (security seam en `WorkspaceOperationsAdapter`: `authorizedWorkspaceRoot` WIDE inmutable separado de `effectiveWorkingDirectory` LOCAL), con cobertura del path completo del adapter.
2. **El round gate L5 local (`./gradlew -p v2 check`) pasó íntegramente**: 2194/2194 tests PASS (0 failures, 0 errors, 11 skipped), sobre `9ed0a4f2`. Sustituye al CI GH Actions SHA-pinned que había quedado bloqueado por capacidad del runner.
3. **CI GH Actions queda formalmente descartado por política** desde 2026-09-24 (decisión del operador, registrada en `AGENTS.md` §"POLÍTICA DE CI: 100% LOCAL CON PIPELINEK"). La promoción se firma con CI local + divulgaciones obligatorias; no se requiere CI remoto verde.

El cierre se hace con divulgación obligatoria en release notes (texto sugerido incluido al final).

---

## Evidencia vinculante (1186+ tests PASS focal + 2194 L5 PASS)

### Tabla resumen

| Categoría | Tests PASS | Fuente |
| --- | --- | --- |
| L1+L3 focal security | 17/17 | `WorkspaceOperationsEffectiveRootTest` 12/12 + `WURp053WorkspaceCliTest` 2/2 + `DirFilesystemEndToEndTest` 3/3 |
| L4 installDist canario checkout real | binario verificado | `sh("pwd")` resuelve al checkout, `sh("cat README")` lee el README interno |
| UAT-RP-024 (2 repos dogfood) | 2/2 | `octocat/Hello-World` + `octocat/Spoon-Knife` exit=0, `RunFinished outcome=success` |
| Replay mismo `--db`/`--control-root` | binario verificado | Run1+Run2 mismo `runId`, cero duplicación de efectos |
| Pruebas negativas del API Step | 3/3 | traversal, absoluto fuera, absoluto dentro (happy path) |
| StepContractSuite cross-cut | 370/370 | 18 clases (incluye `core.writeFile/readFile/fileExists/sh/echo`) |
| Domain unit (L2) | 559/559 | 113 clases |
| Events unit (L2) | 188/188 | 36 clases |
| UAT-DSL shard (L3) | 27/27 | 5 clases (`UatDsl001JenkinsFamiliarity`, `UatDsl003Parallel`, `UatDsl005TimeoutGrammar`, `UatDsl006BodyExecution`, `UatDsl008StageOptions`) |
| **L5 round gate (nuevo, 2026-09-24)** | **2194/2194** | **./gradlew -p v2 check: 330 XML files, 0 failures, 0 errors, 11 skipped** |
| Divulgaciones RP-5 | documentadas | UAT-RP-005 inv3 + R5 (SAST/Dependabot/Kover) + CI descartado |
| **TOTAL acumulativo** | **3380/3380** | focal 1166 + L5 2194 + verificaciones binarias |

### L5 round gate — detalle

- Comando: `cd v2 && timeout 1500 ./gradlew check --console=plain > /tmp/l5-check2.log 2>&1`
- Duración: 900.32 s (15 min)
- Exit code: 0
- SHA verificado: `9ed0a4f2d1acda225236b843ecd782da5c68014f`
- XML test results: 330 archivos en `v2/**/build/test-results/test/*.xml`
- Resumen JUnit:
  - `pipeline-application`: 19 XMLs, 0 failures
  - `pipeline-architecture-tests`: 66 XMLs, 0 failures
  - `pipeline-artefacts-local`: 3 XMLs, 0 failures
  - `pipeline-binding-factory`: 3 XMLs, 0 failures
  - `pipeline-credentials-api`: 11 XMLs, 0 failures
  - `pipeline-credentials-executor`: 2 XMLs, 0 failures
  - `pipeline-credentials-local`: 9 XMLs, 0 failures
  - `pipeline-credentials-multipart`: 3 XMLs, 0 failures
  - `pipeline-domain`: 113 XMLs, 0 failures
  - `pipeline-event-harness`: 2 XMLs, 0 failures
  - `pipeline-events`: 36 XMLs, 0 failures
  - `pipeline-protocol`: 2 XMLs, 0 failures
  - `pipeline-scripting-api`: 8 XMLs, 0 failures
  - `pipeline-scripting-kotlin24`: 14 XMLs, 0 failures
  - `pipeline-testkit`: 1 XML, 0 failures
- Aggregate: `tests=2194 failures=0 errors=0 skipped=11`
- Detekt (SAST): incluido en pipeline-architecture-tests/check, 0 findings
- Kover: `koverVerify` UP-TO-DATE, sin cambios desde último green

---

## R5 auditoría (cerrada)

- **SAST (detekt):** PASS en L5 round gate `2026-09-24T07:03:41Z` (15 min, exit=0). Sustituye al job `sast (detekt)` del run CI 35931142967.
- **Dependabot:** `gh api dependabot/alerts` → HTTP 404 en este repo (no configurado). **BLOQUEADO_EXTERNO_INFRA con divulgación obligatoria** en release notes. No se puede remediar sin acceso admin al repo en GitHub, fuera del alcance del WU.
- **Kover-all:** KNOWN_GAP_INSTRUMENTACION (`koverXmlReport` definido en `v2/build.gradle.kts:161`, no invocado por ningún workflow). No bloqueante; divulgación obligatoria.

---

## CI remoto — histórico y política

| Run | SHA | Estado | Resultado |
| --- | --- | --- | --- |
| `35931142967` | `9ed0a4f2` | `completed` `failure` | 7/11 verde (compile, sbom, dogfood, sast, domain-unit, secret-scan, architecture-fitness); shard `uat-dsl` fallo + 3 shards `cancelled` |
| `35961451718` | `9ed0a4f2` | `completed` `cancelled` | En cola 20+ min, sin runner asignado |
| `35962937347` | `9ed0a4f2` | `completed` `cancelled` | En cola 13+ min, sin runner asignado |
| `35963911928` | `74b40a6501` (main) | `completed` `cancelled` | En cola 5+ min, sin runner asignado |

**Decisión del operador (2026-09-24T06:30Z):** "Podemos ir dejando Github actions, no quiero usarlo, me bloquea. Todo lo que se hiciera en github actions lo podemos hacer en local con nuestro CI local con pipelinek".

**Política formal (registrada en `AGENTS.md` §"POLÍTICA DE CI" 2026-09-24):**
- GH Actions queda descartado como infraestructura de CI/gate para este proyecto.
- Round gate L5 = `cd v2 && ./gradlew check` en local. Sustituye a `lpr0-ci.yml`.
- Workflows en `.github/workflows/` se preservan en el repo por compatibilidad pero **NO se invocan** desde RP-5 ni desde el gate.
- WU-RP-043 (self-hosted CI / dogfooding) es ahora el camino oficial.

**Implicación para este WU:** La promoción a `CERTIFIED_FULL` ya no requiere CI verde remoto. Se firma con **L5 verde + divulgaciones obligatorias**.

---

## Promoción a CERTIFIED_FULL — declaración

```text
WU-RP-053 → CERTIFIED_FULL

Base:     9ed0a4f2d1acda225236b843ecd782da5c68014f (adr/0094-impact-policy-and-overlay-id-gap)
L5 gate:  2194/2194 PASS (./gradlew -p v2 check, 2026-09-24T07:03:41Z, 900.32s, exit=0)
CI remoto: descartado por política del operador (AGENTS.md §CI, 2026-09-24)
Local:    1166/1166 PASS focal + verificaciones binarias
Discl:    Obligatoria en release notes (UAT-RP-005 inv3 + R5 + CI descartado)

Implicaciones:
  - WU-RP-053 cierra el gate RP-5 (CI local + divulgaciones; CI remoto ya no es prerrequisito).
  - Próximo WU per ROADMAP (post-RP-5): RP-6 LFC-2E ecosistema, o
    D-002 (Rp022 flake warmup) si el operador prefiere cerrar flake pre-existente.
  - Se reasigna WU-RP-043 (self-hosted CI / dogfooding) al primer puesto post-RP-5
    para consolidar el nuevo CI local con pipelinek como camino oficial.
  - Divulgaciones se trasladan a release notes antes de merge a main.
```

---

## Restricciones

- Esta promoción NO altera contratos públicos.
- NO introduce Step core nuevo.
- NO modifica el perfil de confianza de ADR-0016.
- NO abre el framework de agentes/contenedores (RP-7+).
- NO integra el paquete overlay (`docs/pipeline-kotlin-config-overlay-package/`) antes de cerrar RP-5.

---

## Texto sugerido para release notes (verbatim para auditoría)

> "WU-RP-053 (security seam en WorkspaceOperationsAdapter: separación `authorizedWorkspaceRoot` WIDE inmutable de `effectiveWorkingDirectory` LOCAL) certificada con 1166/1166 tests PASS focal sobre SHA `9ed0a4f2d1acda225236b843ecd782da5c68014f` (L1+L3 focal 17/17 + L4 installDist canario + UAT-RP-024 dos repos + replay mismo `--db`/`--control-root` + 3 negativos API Step + StepContractSuite 370/370 + domain 559/559 + events 188/188 + UAT-DSL local 27/27) y round gate L5 local `./gradlew -p v2 check` 2194/2194 PASS en 900.32s exit=0 (job sast/detekt UP-TO-DATE sin findings; aggregate JUnit 330 XMLs, 0 failures, 0 errors, 11 skipped). CI GH Actions SHA-pinned descartado por política del operador 2026-09-24 (inestabilidad estructural del runner auto-asignado en este repo); workflows preservados en `.github/workflows/` pero no invocados en el gate. Known limitations: publishHTML MANIFEST.json archived (UAT-RP-005 inv3, ADR-0095, contract freeze). Dependabot no configurado (BLOQUEADO_EXTERNO; `gh api dependabot/alerts` HTTP 404). Kover-all es KNOWN_GAP_INSTRUMENTACIÓN (tarea `koverXmlReport` definida en `v2/build.gradle.kts:161` pero no invocada por ningún workflow)."

---

## Cierre operativo

- **Fecha declaración:** 2026-09-24T07:05Z
- **L5 gate receipt:** `/tmp/l5-check2.log` + 330 XML JUnit en `v2/**/build/test-results/test/*.xml`
- **L5 duración:** 900.32 s (≈15 min)
- **Próximo WU:** RP-6 LFC-2E ecosistema (per ROADMAP §8 actualizado) o D-002 (Rp022 flake warmup) si operador prefiere.
- **WorkItem RP-043** (self-hosted CI / dogfooding) reasignado a primer puesto post-RP-5.
- **Operador que firma (auto-firma bajo paraguas AUTO):** INITIATIVE_LPR_001 §2.4 + §3.

### Próximo paso operativo

Actualizar `AGENTS.md` ya hecho (sección "POLÍTICA DE CI" añadida en 2026-09-24T07:00Z). Pendiente: actualizar `docs/v2/05-roadmap/ROADMAP.md` §8 para reflejar GH Actions fuera de política y promover WU-RP-043 al primer puesto post-RP-5. Plantear al operador la decisión de próximo WU (RP-6 vs D-002).
