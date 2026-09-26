# Technical Debt Backlog — Active Items

**Owner:** orchestrator-direct (pattern preautorizado)
**Last updated:** 2026-09-26 (D-001 RESUELTO + D-005 UAT-019/020/021→COVERED)
**Source of truth:** este archivo + tickets en GitHub Issues (cuando aplique)

## D-001 — PosixFilePermissions constants duplication (P3)

**Detected:** 2026-09-23, durante auditoría de calidad pre-WU-RP-051.
**Severity:** P3 (cosmetic, no funcional, con dimensión de seguridad)
**Scope:** 4 archivos, 29 sitios duplicados.
**Status:** RESUELTO en commits `da0b39d5` + `3e64e372` (T0.E follow-on).

### Resolución

1. `da0b39d5` añade `CredentialFilePermissions` en
   `:pipeline-domain:credentials` con `OWNER_READ_WRITE` (= `rw-------`)
   y `OWNER_READ_WRITE_EXECUTE` (= `rwx------`). 4 tests de postura en
   `CredentialFilePermissionsTest` (4/4 PASS).
2. `3e64e372` reemplaza los 29 inline literals en los cuatro módulos
   adaptador:
   - `:pipeline-step-sdk:scm-git` (15 sitios en `GitCredentialsApplier.kt`)
   - `:pipeline-credentials-local` (3 sitios en `CredentialsStorePosix.kt`)
   - `:pipeline-credentials-multipart` (9 sitios en `CredentialMaterializer.kt`,
     más eliminación del companion object que duplicaba las constantes)
   - `:pipeline-artefacts-local` (2 sitios en `LocalArtifactStore.kt`)

3. Verificado en 5 módulos con sus test suites completas:
   `pipeline-step-sdk:scm-git` 47/47, `pipeline-credentials-local`
   56/56, `pipeline-credentials-multipart` 30/30, `pipeline-artefacts-local`
   32/32, `pipeline-domain` 563/563 = 728/728 PASS, 0 failures, 0 errors.

4. LocalArtifactStoreTest.kt conserva `PosixFilePermissions.fromString("rwx------")`
   y `fromString("rw-------")` como VALORES ESPERADOS (no como inputs a
   la fábrica). Es deliberado: el test verifica que el código de
   producción aplica la forma octal canónica, no que coincida consigo
   mismo. Mantener la forma literal protege contra un refactor futuro
   que cambie accidentalmente los bits de la constante.

### Contexto original

`PosixFilePermissions.fromString("rwx------")` (owner-read-write-execute)
y `fromString("rw-------")` (owner-read-write) están inline en varios
sitios de producción. `CredentialMaterializer.kt` ya tiene constantes
privadas `OWNER_READ_WRITE` y `OWNER_READ_WRITE_EXECUTE` que NO se
reutilizan fuera de su archivo.

### Sitios identificados

```text
v2/pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/GitCredentialsApplier.kt:
  line 69:  Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"))
  line 128: Files.setPosixFilePermissions(credentialHelperScript, PosixFilePermissions.fromString("rwx------"))
  line 253: Files.setPosixFilePermissions(credentialHelperScript, PosixFilePermissions.fromString("rwx------"))  (duplicate of 128)
  line 266: Files.setPosixFilePermissions(askpassScript, PosixFilePermissions.fromString("rwx------"))
  line 272: Files.setPosixFilePermissions(sshWrapperScript, PosixFilePermissions.fromString("rwx------"))

v2/pipeline-credentials-local/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/local/CredentialsStorePosix.kt:
  line 24:  PosixFilePermissions.fromString("rwx------")

v2/pipeline-credentials-multipart/src/main/kotlin/dev/rubentxu/pipeline/v2/credentials/multipart/CredentialMaterializer.kt:
  line 271: private val OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------")
  line 272: private val OWNER_READ_WRITE_EXECUTE = PosixFilePermissions.fromString("rwx------")
  + 6 use sites of these constants (lines 100, 105, 120, 149, 162, 188, 200, 211, 214)

v2/pipeline-artefacts-local/src/main/kotlin/dev/rubentxu/pipeline/v2/artefacts/local/LocalArtifactStore.kt:
  line 82: PosixFilePermissions.fromString("rwx------")
```

### Propuesta

Crear `PipelineFilePermissions` (object) en `:pipeline-domain` con:

```kotlin
object PipelineFilePermissions {
    val OWNER_READ_WRITE: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rw-------")
    val OWNER_READ_WRITE_EXECUTE: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwx------")
}
```

Reemplazar inline literals en los 4 sitios. Borrar constantes
privadas duplicadas en `CredentialMaterializer`.

### Por qué NO se hizo en WU-RP-050/051

- WU-RP-050 estaba scoped a consolidación de `LinkedSecretRef`
  resolution (2 sites auditados por WU-RP-049 R1).
- WU-RP-051 (push + CI) no toca código de producción.
- Regla AGENTS.md §"No fix pre-existing defects dentro de slice scope
  sin ADR/RECETA" — la deuda es legítima pero pertenece a su propia
  WU con scope explícito.

### Esfuerzo estimado

1-2 commits, 1 hora de trabajo. No requiere nuevos tests (los
existentes cubren el comportamiento funcional). Riesgo de regresión:
bajo (sólo se cambia una constante por otra idéntica).

### WU sugerida

**WU-RP-051-bis** (o nuevo WU-RP-052 si la numeración debe respetar
orden del roadmap):
- Tarea: refactor puramente cosmético, sin cambio de comportamiento.
- Tests: re-correr scm-git, credentials-multipart, credentials-local,
  artefacts-local — todos los affected modules.
- UAT: ninguno directamente afectado (UATs usan archivos reales).
- Cierre: cierre ceremonial con verificación de los 4 módulos.

## D-002 — Rp022ThroughputProbe cold-JIT flake (P2)

**Detected:** 2026-09-23, KNOWN_FLAKE arrastrado desde WU-RP-046 R2.
**Severity:** P2 (CI-infra flake, ya documentado)
**Scope:** `:pipeline-credentials-api:test --tests Rp022ThroughputProbe`

### Contexto

Test de throughput mide 50 MiB procesados en streaming-redactor contra
floor de 20 MB/s. En CI cold-JIT (sin daemon warm) el primer warmup
iteration no es suficiente y el segundo run puede caer bajo el floor.

### Observaciones

- Local (warm daemon): 4.835s = 20.7 MB/s (PASS).
- CI cold-JIT: 4780ms = 10.96 MB/s (FAIL).
- Diagnosticado en WU-RP-046 R2 (commit 1d6b3c2f historia).
- Re-dispatch de CI suele resolverlo (JIT warming between runs).

### Acción sugerida

Aumentar `repeat(1)` warmup a `repeat(3)` con un setUp explícito del
registry + measurement aislado. Cambio de 1 línea. Riesgo: bajo.

### Por qué NO se hizo en WU-RP-051

WU-RP-051 era push + CI; el flake ya era conocido y la regla 7
prohíbe fix pre-existing dentro de scope de slice WU-RP-050 sin ADR.
El re-dispatch de CI ya verificó que el flake es auto-resolutivo
en el SHA actual (`bd52fa1b`), por lo que el fix no es urgente.

## D-003 — Init script Just install network dependency (P3)

**Detected:** 2026-09-23, observ HTTP 403 transitorio de just.systems.
**Severity:** P3 (CI-infra)
**Scope:** `.github/workflows/lpr0-ci.yml` job `application-shard`

### Estado

RESUELTO en `bd52fa1b` — añadido retry (3 intentos, backoff 5/10/15s)
+ fallback a apt-get install just.

## D-004 — sbom job missing gradle cache (P3)

**Detected:** 2026-09-23 (run 35864098784, HTTP 403 de Maven Central).
**Severity:** P3 (CI-infra, no rompía pero introducía flake cuando
Maven Central rechazaba cold-downloads).
**Scope:** `.github/workflows/lpr0-ci.yml` job `sbom (cyclonedx)`

### Estado

RESUELTO en `63220a5c` — añadido el cache step de `~/.gradle/caches`
y `~/.gradle/wrapper` con key namespace `lpr0-sbom-` (independiente
del cache de application-shards para pre-warming por el sbom job
mismo en la primera ejecución).

## Notas operativas

- Prioridad para WU futuras: D-002 (P2, flake documentado). D-001
  (P3) RESUELTO en `da0b39d5`+`3e64e372`.
- D-003 (P3) RESUELTO en `bd52fa1b`. D-004 (P3) RESUELTO en `63220a5c`.
- Cada nueva WU debe auditar código duplicado antes de empezar
  (regla 3 del operador).
- Mantener este backlog sincronizado con cada WU cerrada para
  evitar re-discovery de la misma deuda en sesiones futuras.

## D-005 — UAT-RP-019/020/021 elevación REFERENCED→COVERED (2026-09-26, follow-on T0.E)

**Detected:** 2026-09-26, en bloque autónomo tras T0.E closure.
**Severity:** P3 (mejora de precisión del certifier; sin blocker).
**Scope:** `docs/v2/07-uat/{T0E_CLOSURE_RECEIPT,WU_RP_046_R2_SLICE_RECEIPT}.md`.

### Contexto

WU-RP-046 R1 (`c2de6bca`, 2026-09-23) añadió tests ejecutables opt-in
para UAT-RP-019 (Gradle real), UAT-RP-020 (Maven real) y UAT-RP-021
(Node real). Documentados en la tabla del receipt como 'COVERED
opt-in', pero SIN marcador `UAT-EVIDENCE`. La tabla es narrativa
multi-UAT por fila → freeform parser los clasificaba como REFERENCED
(multi_noref). R2 los dejó como REFERENCED por scope recertificación.

### Resolución

Commit `f7f09ab5` (T0E-EVID-01 follow-on):
1. Re-ejecuta los 3 opt-in tests con `UAT_RP_0NN_RUN=1` en una sola
   invocación Gradle para preservar los 3 XMLs juntos.
2. `6/6 PASS` en 49.554s (WURp019 24.379s, WURp020 14.885s,
   WURp021 10.290s). SHA-256 de los 3 XMLs capturado.
3. `WU_RP_046_R2_SLICE_RECEIPT.md` gana sección 8b con procedimiento
   reproducible + digests XML + 3 marcadores `UAT-EVIDENCE` (single-UAT).
4. `T0E_CLOSURE_RECEIPT.md` gana 3 mirrors adicionales de esos mismos
   marcadores en su sección 'Machine-readable status mirror'. Esencial
   porque el closure receipt es el DAG-maximal del árbol de receipts.
5. Neutraliza la narrativa multi-UAT de la sección E1.2
   (`UAT-RP-019 COVERED, UAT-RP-020 COVERED, UAT-RP-024 PARTIAL` →
   `[ST-A], [ST-A], [ST-B]`) para que el parser freeform no siga
   leakando estados parciales sobre 019/020/024.

### Delta del certifier (commit `f7f09ab5` → commit `1b27ec04`)

| UAT | Antes | Después | Cómo |
|---|---|---|---|
| UAT-RP-019 | REFERENCED | **COVERED** | nuevo marker en T0E_CLOSURE_RECEIPT |
| UAT-RP-020 | REFERENCED | **COVERED** | nuevo marker en T0E_CLOSURE_RECEIPT |
| UAT-RP-021 | COVERED | COVERED | marker existente en WU_RP_046_R2 + nuevo mirror |
| UAT-RP-024 | REFERENCED | **KNOWN_LIMITATION** | multi-UAT leak eliminado; el receipt lo declara explícitamente KNOWN_LIMITATION |

Conteo: COVERED=12 (antes 10), KNOWN_LIMITATION=2 (antes 1),
REFERENCED=10 (antes 13), NOT_APPLICABLE=3 (sin cambio). Total 27.

UAT-RP-024 NO ES REGRESIÓN: el receipt ya decía KNOWN_LIMITATION
("dogfooding parcial 1-repo. ≥2 repos estructuralmente imposible").
El multi-UAT leak enmascaraba la clasificación correcta; la
neutralización lo expuso. KNOWN_LIMITATION sigue siendo non-blocking
(R4 sigue PASS).

### Sin código de producción tocado

Sólo docs/receipts. Cero tests añadidos (los 6 ya existían desde R1).
Cero cambios en production.

## D-007 — `gen-current-uat-status.py` false-COVERED / hyphen-FAIL classifier (P2)

**Detected:** 2026-09-26 (T0.E re-verify, corrected from initial False-Green
classification). Evidence: `docs/v2/08-production-readiness/TRAIN_0_T0E_RECEIPT.md`.

**Symptom:**

- The regex `\bFAIL\b` (case-insensitive) inside `STATUS_PATTERNS` of
  `scripts/gen-current-uat-status.py` matches hyphen-words such as
  `fail-closed`, `fail-over`, `fail-fast`. Any UAT description text
  containing those phrases gets classified as `FAIL_PROVEN`.
- Conversely, a transient `COVERED (RP-x)` narrative annotation
  anywhere in the matched corpus flips classification to COVERED for
  as long as the annotation exists, even without dedicated cert-receipt
  evidence.

**Concrete impact observed:**

- At C (committed): UAT-RP-013 = COVERED (false positive caused by
  archived `COVERED (RP-1)` narrative).
- After regen at I (post-T0.D): UAT-RP-013 = FAIL_PROVEN (true
  classification, but admission-check R4 interpreted as new blocking
  state). Same drift for UAT-RP-001..004, UAT-RP-022 etc.

**Repair sketch:**

- Tighten `STATUS_PATTERNS`: `\bFAIL_PROVEN\b` only (no fallback
  `\bFAIL\b`); require explicit marker.
- For COVERED: require either a `certified_at_sha` annotation in the
  matched line OR a dedicated cert-receipt path on disk.
- Add unit tests in `scripts/test_gen_current_uat_status.py`
  (hyphen-FAIL, transient-COVERED, clean baseline).
- After fix: explicit reclassification of UAT-RP-013 via fe-de-erratas
  in CURRENT_UAT_STATUS.md (operator decision).

**Blocks TRAIN-0 T0.E close.** Does NOT block product / RP-5 substance
but blocks certifier state accuracy. Defer to TRAIN-1 if operator
opts for option (2) in T0.E receipt.

---

## T0E-EVID-01 — UAT evidence reconstruction + applicability (TRAIN-0 T0.E long block)

**Note (2026-09-26):** this front was opened as `D-008` in
`TRAIN_0_T0E_CORRECTIVE_RECEIPT.md` (this session's previous slice).
Renamed to `T0E-EVID-01` to avoid collision with a historical LFC
`D-008` (credential provider can be absent / injection skipped).
Operator brief 2026-09-26T13:22Z: "No reutilices `D-008` para este gap."

**Detected:** 2026-09-26 (T0.E corrective + verification slice,
post-D-007). Evidence: `docs/v2/08-production-readiness/
TRAIN_0_T0E_CORRECTIVE_RECEIPT.md`, `.../PRDY_006R2_VERIFICATION_RECEIPT.md`.

**Symptom:**

- D-007's deterministic evidence selection (Git provenance, normative
  matrix excluded) correctly classifies UAT-RP-013 as `NOT_RUN`
  because no `docs/v2/07-uat/*.md` (excluding the matrix) carries a
  receipt for UAT-RP-013.
- Pre-D-007, UAT-RP-013 was falsely `COVERED` (from the archived
  "COVERED (RP-1)" narrative in the matrix's evidence cell). D-007
  closes that false-positive loophole.
- The implementation referenced by the matrix's evidence cell
  (`StrictFingerprintDivergenceDetector + coordinator tests`) DOES
  exist and DOES run, but no receipt in `docs/v2/07-uat/` records that.

**Impact:**

- Admission R4 fails at the new D-007 SHA with `UAT-RP-013=NOT_RUN`.
- D-007 is correct (fail-closed); the gap is a missing receipt, not
  a bug in classification.

**Repair (re-scoped by operator brief 2026-09-26T13:22Z):**

This front is no longer "just write a UAT-RP-013 evidence receipt". It
covers a full certifier/evidence closure inside TRAIN-0 T0.E:

1. **Certifier correctness:** SHA-max is not causal recency; per-line
   status leaks across UATs in the same Markdown row. Fix
   `scripts/gen-current-uat-status.py` to use DAG-maximal commits and
   to scope each status to its UAT. Introduce optional marker
   `UAT-EVIDENCE | UAT-RP-XXX | STATUS | candidate=<sha>` for
   machine-readable receipts; keep compatibility with old free-form
   rows.
2. **R4 diagnostic:** surface the full blocking list (count + complete
   list), not just the first 5.
3. **Evidence reconstruction:** for UAT-RP-002/004/010/013, locate
   the real test/oracle in the repo, run it against the candidate,
   capture argv/SHA/test counts/failures/errors, and emit a receipt
   that proves the status. PARTIAL if partial; NOT_RUN if not
   executable. Never fabricate COVERED from documentation.
4. **Disposition of special cases:**
   - UAT-RP-005: causal supersedence by ADR-0095/0096 (PARTIAL /
     KNOWN_LIMITATION) should replace older FAIL_PROVEN without
     artificial exceptions.
   - UAT-RP-025/026/027: applicability is separate from status.
     LOCAL profile: 025 only if SDKMAN_READY declared; 026 only
     REMOTE; 027 only Jenkins adapter. Not-applicable UATs must not
     block LOCAL.
5. **Honest regeneration:** regenerate CURRENT_UAT_STATUS without
   manual row edits; explain each delta vs the prior commit.
6. **Gate proof:** affected certifier unit tests + UAT tests + admission
   on candidate + 3 consecutive runs + 2 fresh clones + deterministic
   output + SDDK recovery without `.agent/*`. NO full Gradle re-run.

**Status:** FIXED at commit `1893e104` (T0.E Certifier & Evidence
Closure long block, WorkItem `501c88ee-…`). The front reached its
first 6/6 admission at `80d487f5`, but additional certifier issues
surfaced in subsequent receipt iterations: UAT-RP-005 marker
propagation through the closure receipt, neutralising freeform-parser
status tokens in narrative tables, removing the duplicate
KNOWN_LIMITATION entry from the rendered summary, and stabilising
the receipt's HEAD pointer pattern. All those post-`80d487f5`
fixes are part of the same T0E-EVID-01 long block; the front is
fully closed at `1893e104`. Closes inside TRAIN-0; not deferred to
TRAIN-1.

Evidence: `docs/v2/07-uat/T0E_EVID_01_RECEIPT.md` (machine-readable
UAT-EVIDENCE markers for UAT-RP-002/004/005/010/013/015) +
`docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` (full receipt).
6/6 admission rules PASS, 93/93 hermetic tests PASS (27 certifier +
43 admission + 23 other), 3 consecutive runs + 2 fresh clones
identical.

**Blocks:** ~~TRAIN-0 T0.E closure and the TRAIN-0 → main merge.~~
RESOLVED at `1893e104`.

**Status (certifier architecture):** FIXED at commit `09db2d76`
(D-007 corrective slice, T0.E corrective). Evidence: `docs/v2/
08-production-readiness/TRAIN_0_T0E_CORRECTIVE_RECEIPT.md`. Architecture
as operator-prescribed:
- Normative matrix excluded.
- Explicit statuses only.
- Git-provenance selection (latest SHA ancestor of candidate).
- CONFLICT state added; admission blocks it.
- 23 unit tests (11 D-007 + 12 regression/characterisation); 88 tests
  total in `scripts/test_*.py` all PASS.
- Two fresh clones reproduce byte-equal CURRENT_UAT_STATUS.md and
  identical admission decisions.

D-007 fix surfaces a separate gap: missing evidence for UAT-RP-002 /
004 / 010 / 013 plus residual certifier defects (max-by-SHA is not
causal recency; per-line status leaks across UATs). Tracked as
**T0E-EVID-01** (above, same WorkItem). T0E-EVID-01 is OPEN and
handled inside TRAIN-0 T0.E; it is NOT deferred to TRAIN-1.
