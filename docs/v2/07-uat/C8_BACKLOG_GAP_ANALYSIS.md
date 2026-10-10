# Análisis: el gap del backlog `bl-bl-01M4GNTCY00003891BPQN5KGW0` es incorrecto

- **Fecha**: 2026-10-09 19:06Z
- **Base**: `main` @ `57774c9250a0da146a4d9eeb71648e85f48c2553`
- **Independiente del gate**: sí. No requiere issue #12.
- **Resultado**: **NO se cambió código.** La premisa del ítem de backlog no se sostiene contra la
  referencia Jenkins y contra el propio guard de dominio.

## Qué afirmaba el ítem

> *"a caller reading the `StepSpec.DeleteDir` signature still sees a root-deleting default and
> cannot tell that `Attached` refuses it; the type does not make the illegal combination
> unrepresentable (AGENTS.md rule 5/8). CANDIDATE FIX: remove the default (make path explicit) or
> narrow it to a non-root default."*

## Verificación 1 — ¿es el default un defecto? No: **es la semántica Jenkins**

AGENTS.md STEP SEMANTICS regla 1 exige que *"step names, parameters, semantics, and outcomes MUST
match Jenkins behavior"*, y nombra `deleteDir` explícitamente.

Referencia primaria (jenkins.io, *Pipeline: Basic Steps*, sección `deleteDir`, consultada
2026-10-09):

> **deleteDir: Recursively delete the current directory from the workspace**
>
> "Recursively deletes the current directory and its contents. Symbolic links and junctions will
> not be followed but will be removed. **To delete a specific directory of a workspace wrap the
> deleteDir step in a dir step.**"

Dos consecuencias que invierten la premisa del ítem:

1. **En Jenkins `deleteDir` no tiene ningún parámetro.** No hay `path` que narrow ni default que
   quitar. El ámbito lo establece `dir(...)`, exactamente igual que aquí.
2. **Por tanto `path: String = "."` no es un default destructivo añadido: es el caso base de
   Jenkins.** Un pipeline Jenkins que escribe `deleteDir()` espera borrar el directorio actual.

Eliminar el default —la primera opción del ítem— rompería `deleteDir()` para todo usuario Jenkins
que migra, violando la regla 1.

El ítem quiere narrow el default a algo "no-root". Pero **la raíz es el único valor que Jenkins
define**, y el valor no-raíz se expresa en Jenkins envolviendo en `dir`, que ya está implementado.

## Verificación 2 — ¿el guard deja pasar el caso destructivo? No, y se evalúa antes del efecto

`DeleteDirOperationsAdapter.kt`:

```kotlin
118:  WorkspacePathResolver.authorizeRootDestruction(...)   // decide
123:  is DestructiveAuthorization.Permitted -> RootDestruction.ScratchOwned
124:  is DestructiveAuthorization.Refused  -> RootDestruction.UserOwned
129:  val execResult = executor.execute(...)                 // efecto
```

Y el guard (`WorkspacePathResolver.kt:109-120`):

```kotlin
fun authorizeRootDestruction(lease: WorkspaceLease, operation: String): DestructiveAuthorization =
    when (lease) {
        is WorkspaceLease.Attached -> DestructiveAuthorization.Refused(
            WorkspacePathError.ProtectedWorkspaceRoot(operation, lease.root),
        )
        is WorkspaceLease.Managed -> DestructiveAuthorization.Permitted
    }
```

El default `"."` **nunca es destructivo en la superficie pública**: en un workspace `Attached`
—el checkout del usuario vía `--workspace`— falla cerrado en la línea 118, once líneas **antes**
del efecto. Y `RootDestruction` ya modela las tres situaciones
(`ScratchOwned` / `UserOwned` / `DecidedElsewhere`) como tipo cerrado, con `DecidedElsewhere`
documentando que la ausencia de decisión preserva.

## Veredicto

La regla 5/8 se queixa de un `Boolean`/`Any?` mutable o de un default que produzca estados
ilegales. Aquí el estado es un enum cerrado y la ilegalidad —borrar un workspace del usuario—
**no es representable como decisión permitida**: el guard la rechaza por tipo de lease, no por
heurística de ruta.

**El gap real es más pequeño y distinto:** el parámetro `path` es un **superconjunto** de Jenkins
(`deleteDir("subdir")` no existe en Jenkins; allí se usa `dir("subdir") { deleteDir() }`). Es una
extensión, no un defecto de seguridad, y el ítem de backlog la trata como si lo fuera.

## Qué NO se hace y por qué

- **No se quita el default.** Es semántica Jenkins verificada. Eliminarlo rompe compatibilidad y
  contradice AGENTS.md regla 1.
- **No se narrow el default.** Lo mismo: el valor no-raíz pertenece a `dir`, no a un default.
- **No se reescribe `StepSpec.DeleteDir` ni `CoreDeleteDirStep`.** El guard ya está en el sitio
  correcto y antes del efecto; tocarlo sería riesgo sin beneficio demostrado.

## Recomendación

El ítem de backlog debe **cerrarse como `resolved` con evidencia**, o reescribirse para registrar
únicamente que `deleteDir(path)` es una extensión sobre Jenkins y que su documentación debe decir
que `dir(...)` es la forma Jenkins-nativa de acotar el ámbito. La segunda opción es documentación,
no cambio de contrato.

**Esto es una corrección a una premisa del backlog, no una implementación.** Es exactamente el
trabajo que la auditoría del backlog debía producir: no todo ítem vivo es trabajo ejecutable, y
algunos describen un defecto que el código ya no tiene.

## Nota de método

Este ítem se re-verificó contra la referencia primaria en lugar de aceptar su resumen. El ítem
afirmaba `MITIGATION VERIFIED IN PRODUCTION PATH` — correcto — pero su `REMAINING GAP` no sobrevive
al contraste con la semántica Jenkins. Una mitigación verificada no implica un gap pendiente.
