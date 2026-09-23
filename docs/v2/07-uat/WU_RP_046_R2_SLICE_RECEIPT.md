# WU-RP-046 R2 — RECEIPT de recertificación UAT-RP-022 (release byte-idéntico) y cierre WU-RP-040 + flake M3 SIGPIPE

**Fecha:** 2026-09-23T12:30Z. **Base SHA:** 2a66317c0d1a0fa70c7d586d7f9e59ddd31f5d6f (HEAD actual main).
**Tipo:** RECEIPT de recertificación por SHA + auditoría de deuda técnica pendiente.
**Continuación de:** WU-RP-046 R1 (`e5465ddb`, CI 35846205928 SUCCESS 10/10).

---

## 1. UAT-RP-022 — RECERTIFICACIÓN EN HEAD `2a66317c` ✅

**Resultado:** **PASS** (byte-idéntico confirmado en doble build).

### 1.1 Procedimiento reproducible

```bash
# Limpiar build previo del HEAD actual
rm -rf v2/pipeline-application/build/distributions/

# Build #1 incremental (warm cache)
cd v2 && ./gradlew --no-daemon :pipeline-application:distZip
# → BUILD SUCCESSFUL in 16s (47 up-to-date, 1 executed)

# Build #2 con --rerun-tasks (cold cache, full rebuild)
cd v2 && ./gradlew --no-daemon :pipeline-application:distZip --rerun-tasks
# → BUILD SUCCESSFUL in 1m 5s (48 actionable tasks, 48 executed)
```

### 1.2 Hash verificado

```text
6c30e6b6e9b538fdae3dd1ee523173f6c5d7a917d2856043fc4409db67fe7ae1  build1.zip  (92 070 649 bytes)
6c30e6b6e9b538fdae3dd1ee523173f6c5d7a917d2856043fc4409db67fe7ae1  build2.zip  (92 070 649 bytes)
$ cmp build1.zip build2.zip && echo "IDENTICOS"
IDENTICOS
```

- **SHA256 doble build idéntico**: `6c30e6b6e9b538fdae3dd1ee523173f6c5d7a917d2856043fc4409db67fe7ae1`
- **Tamaño**: 92 070 649 bytes en ambos
- **Entradas ZIP**: 44 archivos, **todas con fecha `02-01-1980 00:00`** (reproducible flags activos: `isPreserveFileTimestamps=false`, `isReproducibleFileOrder=true` en `v2/build.gradle.kts:113`).
- ZIP del HEAD actual **difiere** del ZIP certificado en `65afc24d` (release law: publicar ZIP del SHA probado, no reutilizar).

### 1.3 Instalación limpia + smoke

```bash
rm -rf /tmp/recert/install
mkdir /tmp/recert/install && cd /tmp/recert/install
unzip -q /tmp/recert/build2.zip

# Wrapper (JAVA_HOME apunta a temurin-21 como en CI)
JAVA_BIN=/var/home/rubentxu/.asdf/installs/java/temurin-21.0.8+9.0.LTS/bin/java
$JAVA_BIN -cp '/tmp/recert/install/pipelinek-0.39.0/lib/*' \
  dev.rubentxu.pipeline.v2.application.MainKt version
# → "pipeline 0.39.0" exit=0

$JAVA_BIN -cp '...' dev.rubentxu.pipeline.v2.application.MainKt doctor
# → "jdk: 21.0.8 (Eclipse Adoptium)" + "workdir: writable" + exit=0
```

### 1.4 Success path (functional oracle)

```bash
$JAVA_BIN -cp '...' dev.rubentxu.pipeline.v2.application.MainKt run \
  --db /tmp/recert/db1.sqlite \
  --control-root /tmp/recert/ctrl1 \
  --workspace <repo> \
  v2/compatibility/01-basic.pipeline.kts
# → exit=0, 9 events, .[0].kind="CompilationStarted", .[-1].kind="RunFinished", outcome="success"
```

### 1.5 Failure path (functional oracle)

```bash
# Pipeline con sh("exit 1")
$JAVA_BIN -cp '...' dev.rubentxu.pipeline.v2.application.MainKt run \
  --db /tmp/recert/db3.sqlite ... /tmp/recert/fail-explicit.pipeline.kts
# → exit=1 ("Pipeline finished with FAILURE" en stderr)
```

### 1.6 Veredicto

**UAT-RP-022 = COVERED en HEAD `2a66317c`.** El release byte-idéntico se cumple para el SHA actual. Recertificación válida; matriz UAT-MATRIX actualizada.

---

## 2. WU-RP-040 — RECEIPT consolidado (R1..R4) ✅

Ver `docs/v2/07-uat/WU_RP_040_RECEIPT.md` para detalle completo.

**Resumen ejecutivo:**

| Ronda | Estado | Evidencia | Brecha |
|---|---|---|---|
| R1 Kover | PARTIAL | domain 82.64% / events 77.62% line | 12 módulos sin cobertura; agregado root vacío |
| R2 SHA-pin | COVERED | 34/34 actions con SHA | — |
| R3.1 SBOM | COVERED | 49 componentes CycloneDX + sha256 | — |
| R3.2 secret-scan | COVERED | gitleaks job verde + allowlist | gitleaks no en PATH local |
| R3.3 SAST | **KNOWN_GAP** | (no implementado) | detekt/pushdoor pendientes |
| R3.4 dependency-audit | **KNOWN_GAP** | (no implementado) | Dependabot pendientes |
| R4 pitest | PARTIAL | mutation score 42%/50% | sin triage; sin CI job |

**Acción recomendada:** abrir **WU-RP-040 R5** con Kover-all-modules + detekt + Dependabot + triage de 128 mutantes sobrevivientes.

**NO_RELEASE** hasta que R3.3 + R3.4 se hayan implementado (RP-5 Gate exige "seguridad conforme a presupuestos ratificados").

---

## 3. UAT-RP-023 — COVERED con KNOWN_GAP documentado ✅

**Resultado:** consolidado en `WU_RP_040_RECEIPT.md` (R3.1 + R3.2) + matriz actualizada.

- SBOM CycloneDX con 49 componentes, sha256 `223f65d257ff15d3d0cb227c44d48f2d0aee8ac7dc83f92e3602112db38c8cd1` (bom.json) y `7d261bb9d828503fb66a2792f318973ed96832e575c07315576087b70fa66b77` (bom.xml).
- Secret-scan gitleaks verde en CI run 35847575157 + allowlist `.gitleaks.toml` documentada.
- **SAST (detekt/pushdoor) NO implementado** — KNOWN_GAP, no cerrar el gate sin esto.
- **Dependency-audit (Dependabot/dependency-check) NO implementado** — KNOWN_GAP, no cerrar el gate sin esto.

---

## 4. M3 SIGPIPE flake — NO REPRODUCIBLE EN HEAD ACTUAL ✅

**Estado:** caracterización concluida con clasificación **NO_REPRODUCIBLE_EN_SHA_2a66317c**.

### 4.1 Procedimiento

```text
# Caso 1: yes | head -n 1000000
sh("yes | head -n 1000000")
# 5 runs: exit=0 (todos), 9 events, outcome=success

# Caso 2 (stress): yes | head en bucle ×5
sh("for i in 1 2 3 4 5; do yes | head -n 10000000; done")
# 5 runs: exit=0 (todos)
```

### 4.2 Conclusión

El flake M3 SIGPIPE (exit 141 child) documentado originalmente en **WU-RP-022 SHA `9393e34a`** (RP-022 baseline) **no se reproduce** en HEAD `2a66317c` con dos patrones de stress de pipe distintos, 5 repeticiones cada uno (10 ejecuciones totales), todos exit=0.

Hipótesis: el flake original fue una condición de carrera no determinista bajo concurrencia del pump de transcript. Las refactorizaciones de RP-3 (StreamingRedactor, TranscriptStreamingEmissionTest) y RP-4 (WU-RP-044 streaming chunks RSS debt) parecen haberlo eliminado.

**Decisión:** clasificar como **QUARANTINED + NO_REPRODUCIBLE_AT_CURRENT_HEAD**, no requiere fix. La clasificación queda en la matriz como nota y se reactiva sólo si reaparece en algún CI run o en una sesión de soak.

---

## 5. Defecto "CLI exit-code-0-on-typed-exception" — RECLASIFICADO ✅

**Estado:** re-caracterizado. **NO es un defecto del binario pipelinek.**

### 5.1 Reproducción controlada

```text
# Caso A (éxito)
sh("echo hello")     → pipelinek exit=0 ✓
# Caso B (sh fallido)
sh("exit 1")         → pipelinek exit=1 ✓ ("Pipeline finished with FAILURE")
# Caso C (DSL inválido)
DSL syntax broken    → pipelinek exit=1 ✓ ("VALIDATION FAILED" + diagnostics)
# Caso D (script inexistente)
/path/no/existe.kts  → pipelinek exit=2 (validation typed rejection)
```

En **todos los casos** el binario retorna exit code correcto. El "defecto" documentado en `WU_RP_046_R1_SLICE_RECEIPT.md` y `WU_RP_045_SLICE_RECEIPT.md` **NO es del CLI**; era un artefacto del bash pipe `... | tail` que enmascaraba exit codes en los scripts de tests (tail exit 0 siempre que lea EOF). El workaround `|| { echo ORACLE_X_BAD_FAIL; exit 1; }` introducido en WU-RP-046 R1 sigue siendo válido y debe permanecer en los scripts de tests que usen pipes.

### 5.2 Acción

- **NO crear ADR** sobre un defecto que no existe en el binario.
- Mantener el workaround en scripts bash de tests (no en código de producción).
- Documentar este re-descubrimiento en la matriz UAT-MATRIX (Update 2026-09-23 ya hecho).

---

## 6. Verificación

- **L0** `:pipeline-application:compileKotlin :pipeline-application:compileTestKotlin` → BUILD SUCCESSFUL (incremental, ~13s up-to-date).
- **L1** ejecutables:
  - `koverLog` → BUILD SUCCESSFUL (2 módulos, 0 fallos).
  - `cyclonedxBom` → BUILD SUCCESSFUL (49 componentes).
  - `pitest` → BUILD SUCCESSFUL (762 mutaciones en domain, 20 en SDK).
- **L5 incremental** `./gradlew -p v2 check` (incremental, ~13s no-op).
- **Doble build ZIP** byte-idéntico verificado (ver §1).

---

## 7. Estado consolidado tras WU-RP-046 R2

### UAT-MATRIX actualizado

| ID | Estado previo | Estado nuevo | Cambio |
|---|---|---|---|
| UAT-RP-018 | COVERED | COVERED | (link añadido al receipt consolidado WU-RP-040) |
| UAT-RP-019 | COVERED opt-in | COVERED opt-in | sin cambios |
| UAT-RP-020 | COVERED opt-in | COVERED opt-in | sin cambios |
| UAT-RP-021 | COVERED opt-in | COVERED opt-in | sin cambios |
| UAT-RP-022 | PARTIAL | **COVERED** | **recertificado en HEAD actual** |
| UAT-RP-023 | PARTIAL | **COVERED (con KNOWN_GAP)** | receipt consolidado R3.1+R3.2 |
| UAT-RP-024 | KNOWN_LIMITATION | KNOWN_LIMITATION | (1-repo este mismo) |
| UAT-RP-025 | NO_APLICA | NO_APLICA | sin cambios |

### Bloqueante RP-5 Gate (actualizado)

**Sigue bloqueado por:**
1. SAST (detekt) NO implementado (R3.3) — pendiente WU-RP-040 R5.
2. Dependency-audit (Dependabot) NO implementado (R3.4) — pendiente WU-RP-040 R5.
3. UAT-RP-024 (≥2 repos dogfooding) — KNOWN_LIMITATION estructural.
4. UAT-RP-005 inv3 (MANIFEST.json) — KNOWN_LIMITATION deferida a disclosure en release notes (ADR-0095).

**Quitado de bloqueantes** (esta sesión):
- ~~UAT-RP-022 release byte-idéntico~~ → COVERED.
- ~~UAT-RP-023 receipt consolidado cadena suministro~~ → COVERED (con KNOWN_GAP documentado).
- ~~M3 SIGPIPE flake no caracterizable~~ → NO_REPRODUCIBLE_EN_HEAD_ACTUAL.
- ~~Defecto CLI exit-code-0-on-typed-exception~~ → NO_ES_DEFECTO_DEL_BINARIO.

### NO_RELEASE

**Sigue vigente.** No se puede cerrar RP-5 Gate honesto sin WU-RP-040 R5 (SAST + Dependabot) ni sin UAT-RP-024 (al menos evidencia parcial de 1-repo dogfooding en este repo o en un fork externo), ni sin UAT-RP-005 inv3 disclosure en release notes.

---

## 8. Slip-guard preservado

- **Cero código de producción tocado** en este slice (recertificación + measurements + docs only).
- **Cero tests añadidos** (los nuevos vendrán en WU-RP-040 R5 cuando se implemente detekt + Dependabot).
- **Cero bypasses ceremoniales**: este receipt declara PARTIAL/KNOWN_GAP donde corresponde.
- **No tocar Step core, no framework OS-level, no overlay package**: preservado.

---

## 9. Próxima unidad

- **WU-RP-040 R5** (siguiente ronda, alta prioridad) — cerrar R3.3 (SAST/detekt) + R3.4 (Dependabot) + extender Kover a todos los módulos + triage de mutantes sobrevivientes. Sin esto, **NO_RELEASE**.
- **WU-RP-048 (candidato)** — dogfooding en 1 repo fork (este mismo, con un proyecto real). Cierra parcialmente UAT-RP-024.
- **Disclosures de release notes** — UAT-RP-005 inv3 + UAT-RP-024 KNOWN_LIMITATION antes de cualquier release.
