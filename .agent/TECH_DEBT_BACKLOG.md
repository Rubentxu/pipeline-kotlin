# Technical Debt Backlog — Active Items

**Owner:** orchestrator-direct (pattern preautorizado)
**Last updated:** 2026-09-23
**Source of truth:** este archivo + tickets en GitHub Issues (cuando aplique)

## D-001 — PosixFilePermissions constants duplication (P3)

**Detected:** 2026-09-23, durante auditoría de calidad pre-WU-RP-051.
**Severity:** P3 (cosmetic, no funcional)
**Scope:** 4 archivos, 7+ sitios duplicados.

### Contexto

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

- Prioridad para WU futuras: D-002 (P2, flake documentado) > D-001
  (P3, cosmético) > backlog general.
- Cada nueva WU debe auditar código duplicado antes de empezar
  (regla 3 del operador).
- Mantener este backlog sincronizado con cada WU cerrada para
  evitar re-discovery de la misma deuda en sesiones futuras.

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

## D-008 — UAT-RP-013 evidence receipt missing (P3)

**Detected:** 2026-09-26 (T0.E corrective, post-D-007). Evidence:
`docs/v2/08-production-readiness/TRAIN_0_T0E_CORRECTIVE_RECEIPT.md`.

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

**Repair:**

- Add `docs/v2/07-uat/UAT_RP_013_EVIDENCE.md` (or similar) with one
  paragraph that:
  - cites `StrictFingerprintDivergenceDetector` and the relevant test
    classes (`StrictFingerprintDivergenceDetectorTest`,
    `CanonicalDurableRunCoordinatorDivergenceTest` etc.);
  - includes the explicit status token `COVERED` on a line that
    also mentions `UAT-RP-013`;
  - is committed in Git so D-007's `git_last_commit_for` returns a
    SHA.
- Re-run admission; expect R4 to clear UAT-RP-013.
- Belt-and-braces: also write small evidence receipts for UAT-RP-002
  (workflow), UAT-RP-004 (DSL compile), UAT-RP-010 (event JSON
  roundtrip) so the integration candidate C passes R4 without
  exceptions.

**Trigger:** first PRDY or WU in TRAIN-1 / RP-5 closure that adds
ceremony for evidence receipts. Until then the gap is documented and
does not block TRAIN-0 closure (D-006 already deferred these to RP-6).

**Blocks:** nothing in TRAIN-0 (corrective done in this slice). Blocks
TRAIN-1 from claiming R4 PASS without exceptions at the integration
candidate boundary.

**Status:** FIXED at commit `09db2d76` (D-007 corrective slice,
T0.E corrective). Evidence: `docs/v2/08-production-readiness/TRAIN_0_T0E_CORRECTIVE_RECEIPT.md`.
Architecture as operator-prescribed:
- Normative matrix excluded.
- Explicit statuses only.
- Git-provenance selection (latest SHA ancestor of candidate).
- CONFLICT state added; admission blocks it.
- 23 unit tests (11 D-007 + 12 regression/characterisation); 88 tests
  total in `scripts/test_*.py` all PASS.
- Two fresh clones reproduce byte-equal CURRENT_UAT_STATUS.md and
  identical admission decisions.

D-007 fix surfaces a separate gap: UAT-RP-013 has no evidence
receipt. Tracked as D-008 (above). The fix is a small evidence
receipt, not a generator change.
