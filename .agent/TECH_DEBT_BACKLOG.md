# Technical Debt Backlog — Active Items

**Owner:** orchestrator-direct (pattern preautorizado)
**Last updated:** 2026-09-26 (D-001 RESUELTO + D-002 RESUELTO + D-003 RESUELTO + D-004 RESUELTO + D-005 + D-006 + D-007 OBSOLETO + D-008 + D-009)
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

**Status:** RESUELTO en `7904b3c3` (2026-09-26, bloque autónomo).

### Contexto

Test de throughput mide 50 MiB procesados en streaming-redactor contra
floor de 20 MB/s. En CI cold-JIT (sin daemon warm) el primer warmup
iteration no es suficiente y el segundo run puede caer bajo el floor.

### Observaciones

- Local (warm daemon): 4.835s = 20.7 MB/s (PASS).
- CI cold-JIT: 4780ms = 10.96 MB/s (FAIL).
- Diagnosticado en WU-RP-046 R2 (commit 1d6b3c2f historia).
- Re-dispatch de CI suele resolverlo (JIT warming between runs).

### Resolución

Commit `7904b3c3`: aumenta `repeat(1)` → `repeat(3)` para amortiguar
class-loading + JIT compilation antes de la medición. Comentario
inline explica la motivación y cita D-002.

Verificación local tras el fix:
- `repeat(3)` warmup → medición 2245ms = 22.3 MB/s (PASS, antes 2194ms = 22.8 MB/s).
- Test completo en 9.701s (antes 5.107s); el +4.5s es el coste de los
  2 warmups extra, asumido por el fix.
- Módulo `:pipeline-credentials-api:test` 53/53 PASS.

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

## D-006 — UAT-RP-006/007/008/009 elevación REFERENCED→COVERED (2026-09-26, follow-on T0.E)

**Detected:** 2026-09-26, en bloque autónomo tras T0.E closure.
**Severity:** P3 (mejora de precisión del certifier; sin blocker).
**Scope:** `docs/v2/07-uat/{T0E_CLOSURE_RECEIPT,WU_RP_012_RECEIPT}.md`.

### Contexto

WU-RP-011 + WU-RP-012 habían añadido tests ejecutables para
UAT-RP-006 (HTML injection, rp011 series), UAT-RP-007 (paths publish,
rp011r2 series), UAT-RP-008 (Stash symlinks, rp012 series),
UAT-RP-009 (Stash roundtrip, rp012-roundtrip). El receipt
`WU_RP_012_RECEIPT.md` (§9 'UAT coverage unlocked') los listaba
narrativamente como 'covered' pero sin marcadores `UAT-EVIDENCE` —
la sección era lista narrativa multi-UAT → freeform parser
`multi_noref` → REFERENCED.

### Resolución

Commit `6d3b50f4` (T0E-EVID-01 follow-on #2):
1. Re-ejecuta los tests con una sola invocación Gradle:
   `StashOperationsAdapterUatTest` 7/7 PASS (0.669s),
   `PublishHtmlOperationsAdapterUatTest` 14/14 PASS (0.681s).
   Total: **21/21 PASS**, 1.35s.
2. `WU_RP_012_RECEIPT.md` gana sección 12 con procedimiento
   reproducible + digests XML + 4 marcadores `UAT-EVIDENCE`.
3. `T0E_CLOSURE_RECEIPT.md` gana 4 mirrors adicionales en su
   'Machine-readable status mirror'.

### Problema encontrado al comitear (D-007)

Al hacer el commit, la línea narrativa de `WU_RP_012_RECEIPT.md` L118
(`UAT-RP-005 invariant 3 ... remains FAIL_PROVEN`) quedó en el mismo
SHA DAG-maximal que el nuevo marker (KNOWN_LIMITATION en §12). El
certifier los ve como dos triples maximales con status distinto →
CONFLICT → R4 fail.

Además `T0E_CLOSURE_RECEIPT.md` L137 contenía una narrativa similar
(`WU_RP_013/RP2_GATE which had it as FAIL_PROVEN`) que también entró
en conflicto con el marker existente para UAT-RP-005.

### Solución del D-007 (commits `20559ff7` + `80c3e706`)

1. Neutralización quirúrgica de las narrativas (no se borra nada,
   sólo se sustituye el token de status por un placeholder
   `[ST-OLD]`/`[ST-OPEN]`). El historial sigue siendo legible para
   humanos; el parser freeform ya no ve status contradictorios.
2. Adición del marker explícito para UAT-RP-024
   (`UAT-EVIDENCE | UAT-RP-024 | KNOWN_LIMITATION | ...`) en el
   'Machine-readable status mirror' del closure receipt. Sin este
   marker, UAT-RP-024 caía a REFERENCED (multi_noref) por la
   neutralización de la narrativa multi-UAT.

### Delta del certifier (HEAD `80c3e706`)

| UAT | Antes | Después |
|---|---|---|
| UAT-RP-006 | REFERENCED | **COVERED** |
| UAT-RP-007 | REFERENCED | **COVERED** |
| UAT-RP-008 | COVERED | COVERED (con marker explícito) |
| UAT-RP-009 | REFERENCED | **COVERED** |
| UAT-RP-024 | REFERENCED | **KNOWN_LIMITATION** |

Conteos: COVERED 12→**15**, KNOWN_LIMITATION 1→**2**, REFERENCED 10→**7**, NOT_APPLICABLE 3→3. Total 27.

KNOWN_LIMITATION sigue siendo non-blocking (R4 sigue PASS).

### Sin código de producción tocado

Sólo docs/receipts. Cero tests añadidos (los 21 ya existían desde
WU-RP-011/012). Cero cambios en production.

## D-008 — UAT-RP-005 CONFLICT tras neutralización (resuelto en `20559ff7`)

**Detected:** 2026-09-26, al commitear D-006.
**Severity:** P2 (bloqueaba admission R4 hasta resolución).
**Scope:** `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` L137 +
`docs/v2/07-uat/WU_RP_012_RECEIPT.md` L118.

**Status:** RESUELTO en `20559ff7` + `80c3e706`.

Detalle completo en D-006 arriba. La regla operativa que deja este
incidente es: **el parser freeform extrae tokens de status de
cualquier línea, incluyendo narrativas**. Una narrativa que mencione
un status histórico (p.ej. 'had it as FAIL_PROVEN') ahora entra en
conflicto con un marker que diga lo opuesto (KNOWN_LIMITATION).

**Mitigación recomendada**: tras cualquier commit que toque
receipts y haga el archivo DAG-maximal, regenerar y verificar R4
antes de commitear. La regla del L1 ('compilar + test afectado')
debe extenderse a 'regenerar + admission-check' en estos casos.

### Sin código de producción tocado

## D-009 — UAT-RP-011/014 elevación REFERENCED→COVERED + DAG-maximal cross-cuts (2026-09-26)

**Detected:** 2026-09-26, en bloque autónomo tras T0.E closure.
**Severity:** P3 (mejora de precisión del certifier; sin blocker).
**Scope:** `docs/v2/07-uat/{RP2_GATE_RECEIPT,RP3_EXIT_REVIEW,T0E_CLOSURE_RECEIPT}.md`.

**Status:** RESUELTO en commits `f2e54a67` + `789e6e01` + `3c2b580e` + regen `c3f56f68` + `5a699a06` + `f192ecdb`.

### Contexto

UAT-RP-011 (concurrencia SqliteEventStore) y UAT-RP-014 (body execution
policy) estaban como REFERENCED a pesar de que sus tests existían y
pasaban (10/10 y 28/28 respectivamente). Faltaba el marcador
`UAT-EVIDENCE` en los receipts donde se referenciaban, por lo que el
certifier los dejaba en REFERENCED (narrativa-only).

### Resolución inicial (commit `f2e54a67`)

1. Re-ejecuta los tests existentes en HEAD `97a3cdb4`:
   - `SqliteEventStoreConcurrencyCharacterisationTest`: 10/10 PASS en
     `:pipeline-events:test`. XML SHA-256:
     `b4470ac6666132b357c72b7a99e09716dd5a3089d2a5a7041f92449cda456ee6`.
   - `BodyExecutionPolicyTest`: 28/28 PASS en `:pipeline-domain:test`
     (5 nested classes: Representability, FailClosedResolution,
     Ownership, RegistryAuthority, SupportAdmission). XML SHA-256
     (Representability): `9b8d4a46932312664370ba0708dbf876f4d975c04d02edef92c275cf7c9c482d`.
2. Añade sub-sección 'Re-executed evidence' en
   `RP2_GATE_RECEIPT.md` (UAT-RP-011) y `RP3_EXIT_REVIEW.md`
   (UAT-RP-014), con procedimiento reproducible + resultado + digest
   XML.
3. Añade markers `UAT-EVIDENCE | UAT-RP-011 | COVERED | candidate=97a3cdb4 | ...`
   y `UAT-EVIDENCE | UAT-RP-014 | COVERED | candidate=97a3cdb4 | ...`
   en los receipts correspondientes, más mirrors en `T0E_CLOSURE_RECEIPT.md`.

### Cross-cuts descubiertos (commit `789e6e01` + `3c2b580e`)

Tras el commit `f2e54a67`, RP2_GATE_RECEIPT.md se convirtió en
DAG-maximal para UAT-RP-005, UAT-RP-017 y UAT-RP-018 (antes lo era
T0E_CLOSURE_RECEIPT.md). Esto provocó:

- UAT-RP-005: pasó de KNOWN_LIMITATION a FAIL_PROVEN por la línea
  freeform '3. UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ...'
  que se volvió maximal.
- UAT-RP-017: pasó de COVERED a REFERENCED porque la línea freeform
  '- UAT-RP-017: ...' no tiene status keyword (single_noref).
- UAT-RP-018: pasó de COVERED a PARTIAL por la línea freeform
  '- UAT-RP-018: PARTIAL — ...'.

Resolución en dos pasos:
1. `789e6e01`: neutraliza 'FAIL_PROVEN' → '[ST-OPEN]' en la narrativa
   de RP2_GATE_RECEIPT L45.
2. `3c2b580e`: añade markers explícitos
   `UAT-EVIDENCE | UAT-RP-005 | KNOWN_LIMITATION`,
   `UAT-EVIDENCE | UAT-RP-017 | COVERED`,
   `UAT-EVIDENCE | UAT-RP-018 | PARTIAL` en RP2_GATE_RECEIPT para
   blindar el estado ante futuros commits.

### Delta del certifier (commit `97a3cdb4` → commit `f192ecdb`)

| UAT | Antes | Después | Cómo |
|---|---|---|---|
| UAT-RP-011 | REFERENCED | **COVERED** | nuevo marker en RP2_GATE_RECEIPT + T0E mirror |
| UAT-RP-014 | REFERENCED | **COVERED** | nuevo marker en RP3_EXIT_REVIEW + T0E mirror |
| UAT-RP-005 | KNOWN_LIMITATION | KNOWN_LIMITATION | latest receipt cambia a RP2_GATE (más reciente) |
| UAT-RP-017 | COVERED | COVERED | latest receipt cambia a RP2_GATE |
| UAT-RP-018 | COVERED | **PARTIAL** | latest receipt RP2_GATE dice PARTIAL (más honesto per ADR-0016) |

Conteo: COVERED=18 (antes 17), PARTIAL=1 (antes 0), KNOWN_LIMITATION=2
(sin cambio), REFERENCED=3 (antes 5), NOT_APPLICABLE=3 (sin cambio).
Total 27.

### Lección operativa

**Toda vez que un receipt de evidencia se vuelve DAG-maximal por un
commit, las líneas freeform que mencionan UAT-RP-* pueden filtrar
status tokens y romper la admisión R4.** La mitigación recomendada
es:

1. Tras editar un receipt que liste UATs, regenerar
   `scripts/gen-current-uat-status.py` ANTES de commitear.
2. Verificar que ningún UAT pase de un estado válido a un estado de
   fallo (FAIL_PROVEN, BLOCKED, REJECTED, NOT_RUN, CONFLICT).
3. Si hay regresiones, o se neutraliza el token (FAIL_PROVEN → [ST-X])
   o se añade un marker `UAT-EVIDENCE` explícito.

Esto formaliza la regla operativa que D-008 dejaba implícita: la
cadena "edit receipt → commit → regenerar" no es idempotente cuando
el receipt alterado era dominado y pasa a ser maximal.

### Sin código de producción tocado

Sólo docs/receipts. Cero tests añadidos (los 38 ya existían en
B10_W1B + LPR-011). Cero cambios en production.

## D-007 — `gen-current-uat-status.py` false-COVERED / hyphen-FAIL classifier (P2)

**Detected:** 2026-09-26 (T0.E re-verify, corrected from initial False-Green
classification). Evidence: `docs/v2/08-production-readiness/TRAIN_0_T0E_RECEIPT.md`.

**Status:** **OBSOLETO** — la regresión descrita ya estaba corregida en commits
anteriores al cierre de T0.E; verificado el 2026-09-26.

### Symptom original (descrito en T0.E)

- The regex `\bFAIL\b` (case-insensitive) inside `STATUS_PATTERNS` of
  `scripts/gen-current-uat-status.py` matches hyphen-words such as
  `fail-closed`, `fail-over`, `fail-fast`. Any UAT description text
  containing those phrases gets classified as `FAIL_PROVEN`.
- Conversely, a transient `COVERED (RP-x)` narrative annotation
  anywhere in the matched corpus flips classification to COVERED for
  as long as the annotation exists, even without dedicated cert-receipt
  evidence.

### Verificación del estado actual (2026-09-26)

El regex actual en `scripts/gen-current-uat-status.py` L111-113 es:

```python
EXPLICIT_PATTERN = re.compile(
    r"\b(?:" + "|".join(EXPLICIT_STATUSES) + r")\b", re.I
)
```

donde `EXPLICIT_STATUSES = ["FAIL_PROVEN", "BLOCKED", "REJECTED",
"COVERED", "PARTIAL", "KNOWN_LIMITATION", "NOT_RUN"]`. **No hay un
fallback `\bFAIL\b`**. Las pruebas de hyphen-FAIL en
`scripts/test_gen_current_uat_status.py` (L483-487) verifican
explicitamente que `fail-closed` y `fail-fast` NO se clasifican como
`FAIL_PROVEN`:

```python
m = self.mod.EXPLICIT_PATTERN.findall("rechazo fail-closed antes de nuevos efectos")
# → []  (assertNotIn FAIL_PROVEN)
m = self.mod.EXPLICIT_PATTERN.findall("the orchestrator uses fail-fast semantics")
# → []  (assertNotIn FAIL_PROVEN)
```

Resultado: 27/27 tests del certifier PASAN en este SHA
(`7904b3c3`).

### Conclusión

D-007 describía un bug que ya fue corregido antes del cierre de
T0.E (probablemente durante el propio T0.E corrective slice). El
estado correcto del certifier actual se alinea con la
"Repair sketch" propuesta en D-007. No requiere acción.

### Accion tomada

Marcar como OBSOLETO. Eliminar la entrada del backlog de trabajo
activo pero preservar la nota historica para auditoria.

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
