# ADR-0102 — PathAnchor explícito y seguridad destructiva por ownership

**Status:** Accepted  
**Date:** 2026-10-01

## Context

Jenkins distingue de facto entre:

- workspace allocation (`node/ws`);
- current directory (`dir`);
- Steps relativos al current directory;
- operaciones que conceptualmente actúan sobre el workspace completo.

PipelineK tiene Steps que consumen diferentes roots por caminos distintos. Además, para proteger `cleanWs/deleteDir` se ha necesitado inferir si un path parece un checkout buscando marcadores VCS.

## Decision 1 — PathAnchor

Cada familia workspace-aware debe tener un anchor explícito:

```kotlin
sealed interface PathAnchor {
    data object CurrentDirectory : PathAnchor
    data object WorkspaceRoot : PathAnchor
}
```

No se añade `ControlRoot` aquí. Los stores internos usan sus propios ports.

## Decision 2 — Resolution + authorization

La ruta se procesa en dos fases:

```text
resolve(anchor, raw path) -> ResolvedWorkspacePath

then

authorize(resolved, workspace.root, operation policy)
```

El resolver léxico es puro. Symlink/realpath checks están en el boundary de filesystem.

## Decision 3 — ownership is authority

La seguridad de limpieza se decide con el tipo de lease:

```text
WorkspaceLease.Attached -> root owned by user, protected
WorkspaceLease.Managed  -> root owned by PipelineK, lifecycle may clean it
```

`ProjectCheckoutDetector` puede sobrevivir temporalmente como compatibility aid/diagnóstico, pero deja de ser la autoridad primaria.

## Decision 4 — destructive policy

En `Attached`:

- root-wide `deleteDir` => reject;
- root-wide `cleanWs` => reject;
- subdirectory delete inside `dir` => allowed after confinement;
- future explicit override => named/destructive policy, not boolean magic.

En `Managed`:

- lifecycle cleanup allowed;
- existing scratch behavior remains available.

## Jenkins deviation

PipelineK prioriza confinement del workspace para filesystem Steps. Un path absoluto externo que Jenkins pudiera leer en el agente no se acepta implícitamente. Acceso host arbitrario deberá ser una capability explícita.

Esto es una desviación documentada de seguridad, no drift accidental.

## Differential-required Steps

`archiveArtifacts`, `publishHTML` y cualquier plugin cuya documentación diga sólo "workspace" deben caracterizarse con `dir(...)` antes de congelar su anchor definitivo.

## Fitness

- todos los Steps migrados declaran/reciben anchor de forma rastreable;
- ningún adapter redefine root en función de cwd;
- ningún Step usa marcador VCS para inferir ownership;
- no existe path de usuario resuelto contra `controlRoot`.
