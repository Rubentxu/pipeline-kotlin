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
