# WIP-6 — §1.3 PATH-01 corregido: contención en resolveArchiveDir

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**Veredicto:** defecto **reproducido y corregido**. Sin cambio de formato durable. Sin necesidad de migración.

## Defecto reproducido

`WorkspaceResolver.resolveArchiveDir(runId, stageName)` interpolaba el `runId` directamente como
segmento de ruta (línea 76 de la versión original):

```kotlin
return controlDirRoot.resolve("artefacts").resolve(runId).resolve(safeName)
```

Esto permite que un runId como `"../../etc/passwd"` escape del controlador via traversal.

## Test adversarial añadido

`PATH-01 resolveArchiveDir refuses runId that traverses outside the controller root`:
- `runId = "../../etc/passwd"` → debe lanzar `IllegalArgumentException`
- `runId = "../escaped"` → debe lanzar `IllegalArgumentException`
- `runId = "run/with/slashes"` → debe lanzar `IllegalArgumentException`
- `runId = "run with spaces"` → debe lanzar `IllegalArgumentException`

Resultado **antes del fix**: test fallido (`Expected IllegalArgumentException to be thrown, but nothing was thrown`).
Resultado **después del fix**: test pasa.

`PATH-01 resolveArchiveDir returns a contained path for a path-safe runId`: para un runId
path-safe (`run-001`), el path resuelto debe estar bajo `<controlDirRoot>/artefacts/` y
ser igual a `<controlDirRoot>/artefacts/run-001/<stageName>`.

## Fix aplicado

```kotlin
fun resolveArchiveDir(runId: String, stageName: String): Path {
    require(runId.matches(RUN_ID_SEGMENT)) {
        "resolveArchiveDir: runId '$runId' is not a path-safe segment"
    }
    val safeName = stageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val artefactsRoot = controlDirRoot.resolve("artefacts").toAbsolutePath().normalize()
    val resolved = artefactsRoot.resolve(runId).resolve(safeName).normalize()
    require(resolved.startsWith(artefactsRoot)) {
        "resolveArchiveDir: resolved path '$resolved' is not contained under '$artefactsRoot'"
    }
    return resolved
}
```

Tres capas de defensa:

1. **Validación del runId** como segmento path-safe (mismo charset que `RunIdDirectory.fileNameFor`).
2. **Normalización** del path para colapsar `..` y `.` antes del check.
3. **Containment** con `startsWith` sobre el `artefactsRoot` normalizado.

El charset se mantiene consistente con `RunIdDirectory.SAFE_FILENAME = [A-Za-z0-9._-]+` para que
las dos clases no puedan discrepar sobre qué es un runId seguro.

Limitación documentada: la defensa es **léxica** (string-based). No detecta un symlink en
`<controlDirRoot>/artefacts/<runId>/` que apunte fuera — esa defensa debe ocurrir en el caller
que escribe (mediante `LinkOption.NOFOLLOW_LINKS` o `Files.createDirectories` + chequeo).

## Validación

Comando: `cd v2 && ./gradlew :pipeline-application:test --tests "*WorkspaceResolver*" --rerun-tasks --console=plain --no-daemon -i`

Log: `/tmp/wip6-path01-fix.log`. Resultado: **BUILD SUCCESSFUL in 2m 23s**, 17 tests / 0 failures / 0 errors.

| Test | Resultado |
|---|---|
| 15 tests preexistentes | PASSED |
| **`PATH-01 resolveArchiveDir refuses runId that traverses outside the controller root`** | **PASSED** |
| **`PATH-01 resolveArchiveDir returns a contained path for a path-safe runId`** | **PASSED** |

## Consecuencias

- PATH-01 está **cerrado**. Sin formato nuevo, sin deuda de migración.
- RunIds inválidos ahora fallan cerrados con un mensaje actionable.
- RunIds válidos siguen resolviendo al mismo path que antes; ningún cambio para callers que
  ya pasan runIds path-safe (la convención del repo).

## Próximo paso

WIP-7: §1.3 COV-01 — direccionamiento Kover por `project.path`.