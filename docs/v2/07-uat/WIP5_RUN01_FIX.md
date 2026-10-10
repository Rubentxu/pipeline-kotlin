# WIP-5 — §1.3 RUN-01 corregido: escritura atómica de RunIdDirectory.record

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** (creado en este WIP; id asignado)
**Veredicto:** defecto **reproducido y corregido**. Sin cambio de formato durable. Sin necesidad de migración.

## Defecto reproducido

`RunIdDirectory.record` (línea 52-55 de la versión original) usaba:

```kotlin
Files.createDirectories(root)
Files.writeString(root.resolve(fileNameFor(definitionId)), runId.value)
```

Esto es una secuencia `truncate-then-write` no atómica. Si el proceso muere entre las dos operaciones,
el archivo queda parcial (vacío o truncado). El KDoc del archivo (líneas 27-33) afirmaba que la
escritura es atómica, pero la implementación no lo era.

## Fix aplicado

```kotlin
fun record(definitionId: DefinitionId, runId: RunId) {
    Files.createDirectories(root)
    val target = root.resolve(fileNameFor(definitionId))
    val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.writeString(temp, runId.value)
    try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (t: Throwable) {
        Files.deleteIfExists(temp)
        throw t
    }
}
```

- Escritura a `*.tmp` (hermano del target, mismo directorio)
- `Files.move` con `ATOMIC_MOVE` + `REPLACE_EXISTING`: atómico a nivel de sistema de archivos.
- En caso de fallo del move, se elimina el temp para no dejar residual; la causa se re-lanza.
- No cambia el formato durable: el archivo final contiene el mismo contenido que antes, solo
  que ahora aparece como una unidad atómica.
- No requiere migración: cualquier consumidor que lea el archivo sigue leyendo el mismo formato.

## Test adversarial añadido

`RUN-01 concurrent record calls leave the file in a coherent state - no partial writes()`:

- 16 hilos, cada uno llama a `record()` 50 veces con runIds distintos.
- El contenido final debe ser uno de los runIds escritos (coherencia).
- El contenido en disco debe coincidir exactamente con `lastRunId()` (no truncación).

## Validación

Comando: `cd v2 && ./gradlew :pipeline-application:test --tests "*RunIdDirectory*" --rerun-tasks --console=plain --no-daemon -i`

Log: `/tmp/wip5-run01.log`. Resultado: **BUILD SUCCESSFUL**, 8 tests / 0 failures / 0 errors.

| Test | Resultado |
|---|---|
| `lastRunId with no record fails closed with an actionable message` | PASSED |
| `directory is created lazily on first record` | PASSED |
| `blank recorded content fails closed` | PASSED |
| `records for different definitions are isolated` | PASSED |
| **`RUN-01 concurrent record calls leave the file in a coherent state`** | **PASSED** |
| `re-recording the same definition replaces the previous run id` | PASSED |
| `definition id with path-unsafe characters is rejected` | PASSED |
| `record then lastRunId returns the recorded run id` | PASSED |

## Consecuencias

- RUN-01 está **cerrado**. Sin formato nuevo, sin deuda de migración.
- El contrato de la clase (KDoc) ahora se cumple: "written atomically by one process at a time".
- El test de concurrencia actúa como testigo del invariante: tras N escrituras concurrentes, el
  archivo siempre contiene uno de los runIds escritos, nunca contenido parcial o mixto.

## Próximo paso

WIP-6: §1.3 PATH-01 — `WorkspaceResolver.resolveArchiveDir` interpola el RunId como segmento
de ruta. Verificar escape por traversal y symlink desde todos los llamantes.