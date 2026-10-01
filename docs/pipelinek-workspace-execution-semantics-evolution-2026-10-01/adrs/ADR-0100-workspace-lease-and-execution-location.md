# ADR-0100 — WorkspaceLease + ExecutionLocation como autoridad de rutas

**Status:** PROPOSED  
**Date:** 2026-10-01  
**Scope:** runtime/domain/application

## Context

PipelineK representa hoy la ejecución mediante una combinación de `workspaceBase`, `WorkspaceResolver`, `ShOptions.workspaceRoot`, `ShOptions.workingDirectory?` y capabilities como `WorkspaceIdentity`.

La implementación ya ha necesitado usar `workingDirectory ?: workspaceRoot`, y el capability bridge ha llegado a publicar el cwd efectivo bajo un campo llamado `workspaceRoot`.

Esto permite drift entre Steps y dificulta razonar sobre seguridad, `dir`, replay y futuras ejecuciones remotas.

## Decision

Introducir una autoridad explícita:

```kotlin
sealed interface WorkspaceLease { ... }

data class ExecutionLocation(
    val workspace: WorkspaceLease,
    val cwd: WorkingDirectory,
)
```

### Leyes

1. `workspace.root` es estable durante la lease.
2. `cwd` es total/no-null.
3. `cwd` comienza en `workspace.root`.
4. `dir` deriva sólo `cwd`.
5. El runtime nunca usa `controlRoot` como fallback de cwd.
6. Los Steps workspace-aware reciben `ExecutionLocation` o un port construido desde ella.
7. `WorkspaceIdentity` legado no puede volver a representar cwd bajo el nombre `workspaceRoot`.

## Alternatives considered

### A — Mantener `Path? workspaceBase` y mejorar nombres

**Rejected.** La nulabilidad seguiría codificando varias decisiones y cada adapter podría reconstruir semantics.

### B — Sólo cambiar el default CLI a `--workspace .`

**Rejected.** Arregla el síntoma inicial pero deja inconsistencia bajo `dir`, seguridad y stores.

### C — Usar únicamente `cwd` y eliminar workspace root

**Rejected.** Se pierde frontera estable para confinamiento, ownership y operaciones root-scoped.

### D — Adoptar `ExecutionLocation`

**Accepted.** Hace las invariantes explícitas y encaja con el modelo inmutable de overlays existente.

## Consequences

### Positive

- semántica uniforme;
- mejor seguridad;
- menos connascence accidental;
- base reutilizable para workers remotos;
- facilita UAT diferencial por Step;
- elimina `workingDirectory?` como sentinel.

### Negative

- migración de varios consumers/adapters;
- cambio de capability pública/interna que debe hacerse aditivamente;
- requiere caracterización para Steps cuyo anchor Jenkins es ambiguo.

## Compatibility

No se elimina `WorkspaceIdentity` en el primer corte. Se mantiene como bridge derivado del root real hasta que los consumidores sean migrados y los dumps/API gates permitan retirarlo.

## Fitness rules

- prohibir nuevos usos directos de `WorkspaceResolver` desde Step handlers;
- prohibir nuevos `workingDirectory ?: workspaceRoot` fuera del seam de compatibilidad durante migración;
- prohibir `System.getProperty("user.dir")` como fallback runtime de Step;
- prohibir `controlDirRoot.resolve(...)` para paths procedentes del DSL/Step input.
