# ADR-0097 — Port `CredentialLinkedSecretResolver` para resolver `LinkedSecretRef` desde el dominio

**Estado:** ACCEPTED (firmado 2026-09-23, en slice WU-RP-049 LF-0403).

**Autor:** pipeline-kotlin (Rubentxu).
**SHA base:** 050004c00fdc31f6c0ea259785de6b8ad66a7319 (post WU-RP-046 R2 + WU-RP-049 PLAN).
**Aplica a:** V2 desde este SHA. Sustituye la implementación placeholder de `passphraseVariable` / `passwordVariable` en `CredentialProjection.kt`.

## Contexto

`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialProjection.kt` contiene dos `// TODO LF-0403 follow-up:` (líneas 189 y 233) con un placeholder `SecretHandle.masked("")` (string vacío) en lugar del contenido real del credential referenciado por `LinkedSecretRef`. Si un usuario define una SSH key con passphrase vía `passphraseVariable` o un `Certificate` con `passwordRef`, el env se inyecta con string vacío y el handshake falla por credencial incorrecta. Defecto funcional reproducible. Tests pre-existentes (`UatLocal008SshPrivateKeyRoundGateTest.CR-RD-021`) sólo verifican **no-leak** del canary — pasan verdes porque el canary nunca llega al env, **NO** verifican que el canary SÍ aparezca cuando corresponde.

La API `SecretStore.getAsSecretHandle(id)` ya existe en `:pipeline-credentials-api/SecretStore.kt:72` y se usa en `:pipeline-step-sdk/scm-git/.../GitCredentialsApplier.kt:277,282`. Pero `:pipeline-domain` NO puede depender directamente de `:pipeline-credentials-api` sin romper el hexagonalismo del proyecto (la inversión de dependencias es justamente lo que mantiene `pipeline-domain` como capa pura sin I/O).

## Decisión

Introducir un **port domain** `CredentialLinkedSecretResolver` (análogo al gemelo `CredentialMaterializationDomain` que ya existe en `:pipeline-domain/.../CredentialMaterialization.kt`) que abstrae la operación "dado un `LinkedSecretRef`, devolver los bytes del credential referenciado". La **implementación** del port reside en `:pipeline-credentials-executor` (o `:pipeline-credentials-api`) y delega a `SecretStore.getAsSecretHandle(id)`. Se inyecta en `DefaultCredentialProjector` por constructor (DI).

Forma concreta:

```kotlin
// :pipeline-domain/.../CredentialLinkedSecretResolver.kt
package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.SecretHandle

/**
 * LF-0403 — Domain port for resolving a LinkedSecretRef to its referenced SecretText bytes.
 *
 * Lives in :pipeline-domain so DefaultCredentialProjector can compose against it
 * without dragging in :pipeline-credentials-api. Same pattern as
 * CredentialMaterializationDomain (the SPI in :pipeline-credentials-api/spi/CredentialMaterialization
 * extends the domain port by subtyping; here, the impl lives in :pipeline-credentials-executor
 * and delegates to SecretStore.getAsSecretHandle).
 *
 * Contract (single method):
 *  - resolve(ref): turn a LinkedSecretRef into a SecretHandle carrying the
 *    referenced SecretText bytes. The default impl in :pipeline-credentials-executor
 *    queries SecretStore.getAsSecretHandle(ref.id).
 *
 * Errors are typed (no string exceptions):
 *  - LinkedSecretReferenceNotFoundException if credential id absent
 *  - LinkedSecretReferenceTypeMismatchException if referenced credential is not SecretText
 *
 * ThrowingCredentialLinkedSecretResolver is the safe default for tests/call sites
 * that do not exercise LinkedSecretRef: it surfaces accidental unconfigured
 * dependencies as a clear error instead of silently injecting empty bytes.
 */
fun interface CredentialLinkedSecretResolver {
    fun resolve(ref: LinkedSecretRef): SecretHandle
}

object ThrowingCredentialLinkedSecretResolver : CredentialLinkedSecretResolver {
    override fun resolve(ref: LinkedSecretRef): SecretHandle =
        throw IllegalStateException(
            "CredentialLinkedSecretResolver not configured; ref=${ref.credentialsId.value}"
        )
}
```

**Cambios asociados en `:pipeline-domain/.../CredentialProjection.kt`:**

1. `DefaultCredentialProjector` añade segundo parámetro de constructor: `private val linkedSecretResolver: CredentialLinkedSecretResolver` con valor por defecto `ThrowingCredentialLinkedSecretResolver` (preserva compatibilidad con tests existentes que no configuran resolver).
2. Línea 189 (`spec.passphraseVariable?.let`): reemplazar `env[varName] = SecretHandle.masked("")` por `env[varName] = linkedSecretResolver.resolve(ssh.passphraseRef).copy()` (ver patrón en `GitCredentialsApplier.resolveSecret:276`).
3. Línea 233 (`spec.passwordVariable?.let`): análogo para `Certificate.passwordRef`.
4. KDoc del binding actualizado: el binding shape permanece idéntico (mismas variables, mismas posiciones); el contenido pasa de placeholder vacío al credential real.

**Wiring en `:pipeline-credentials-executor/.../WithCredentialsExecutor.kt`:**

- `DefaultCredentialProjector` se construye con la implementación real del port (que delega a `SecretStore.getAsSecretHandle`).
- La implementación del port reside en `:pipeline-credentials-executor` (o `:pipeline-credentials-api`); delega a `SecretStore.getAsSecretHandle(ref.id).use { it.copyOf() }`.

**Decisión sobre código duplicado:** `GitCredentialsApplier.resolveSecret` (línea 276) ya hace exactamente esta operación. Con la introducción de este port, son 2 sitios + 1 nuevo = 3. **Decisión:** extraer helper compartido `LinkedSecretResolver` en `:pipeline-credentials-api` (no en este slice, queda como seguimiento; ver "Consecuencias" abajo).

## Alternativas consideradas

### A. Inyectar `SecretStore` directamente en `DefaultCredentialProjector`
**Rechazada:** rompe hexagonalismo (`:pipeline-domain` no debe importar de `:pipeline-credentials-api`).

### B. Hacer la resolución en `WithCredentialsExecutor` antes de llamar al projector
**Rechazada:** desplaza la lógica de "qué variable se inyecta" fuera del projector; el binding shape deja de ser total dentro del dominio.

### C. Mantener el placeholder y documentar como KNOWN_LIMITATION
**Rechazada:** defecto funcional real; el operador instruyó cerrar la deuda técnica abierta, no documentarla para siempre.

## Consecuencias

**Positivas:**
- Cierra LF-0403 sin romper el hexagonalismo del proyecto.
- Reutiliza el patrón ya validado (`CredentialMaterializationDomain` gemelo) — coherencia arquitectónica.
- Errores tipados: si el credential referenciado no existe o no es `SecretText`, el projector lanza `LinkedSecretReferenceNotFoundException` / `LinkedSecretReferenceTypeMismatchException` (ya existen en `:pipeline-credentials-api/SecretStore.kt`), no falla silenciosamente.
- Tests futuros pueden inyectar un `InMemoryCredentialLinkedSecretResolver` sin tocar `:pipeline-credentials-api`.

**Negativas / riesgos:**
- Añade un parámetro al constructor de `DefaultCredentialProjector`. Mitigado: valor por defecto `ThrowingCredentialLinkedSecretResolver` preserva compatibilidad con tests pre-existentes (verificados en L4 `:pipeline-application:test`).
- Posible código duplicado con `GitCredentialsApplier.resolveSecret` (línea 276) si la implementación concreta del port se hace en cada módulo por separado. Mitigado: la implementación vive en un solo sitio (`:pipeline-credentials-executor`); `GitCredentialsApplier` puede consumirla también en un slice de seguimiento (ver "Acciones de seguimiento").

**Acciones de seguimiento (fuera del scope de este ADR):**
- Extraer `LinkedSecretResolver` (impl que delega a `SecretStore.getAsSecretHandle`) a helper compartido en `:pipeline-credentials-api` cuando un segundo consumidor (`GitCredentialsApplier`) lo necesite. Esto evita la duplicación del patrón `secretStore?.getAsSecretHandle(ref.id)?.use { it.copyOf() }` que ahora vive en 2 sitios (GitCredentialsApplier + LinkedSecretResolver.impl).

## Compatibilidad

- `CredentialProjector.project(spec, credential, runId)` — firma inalterada. Sólo se añade un parámetro opcional al constructor de `DefaultCredentialProjector` (con valor por defecto).
- `CredentialBindingSpec` — sin cambios.
- `Credential` (sealed) — sin cambios.
- `SecretHandle` — sin cambios.
- `LinkedSecretRef` — sin cambios.
- `SecretStore.getAsSecretHandle` — sin cambios.

**No rompe contrato público certificado.** El cambio es interno al wiring interno de `DefaultCredentialProjector`. El binding shape externo (qué env vars se emiten) es idéntico; sólo cambia el contenido de los vars que ya estaban declarados (de `""` al contenido real del credential).

## Referencias

- ADR-0051 (`docs/v2/04-adrs/ADR-0051-credentials-parity.md`) — credentials parity V1→V2; define `LinkedSecretRef` como reemplazo tipado de V1 `passphraseSecretId`/`passwordSecretId`.
- `CredentialMaterializationDomain` (`:pipeline-domain/.../CredentialMaterialization.kt:27`) — patrón gemelo de port domain ya implementado y certificado.
- `WU-RP-049_LF0403_PLAN.md` (`docs/v2/07-uat/`) — plan operativo del slice (6 commits, criterios de aceptación).
- ROADMAP V2 §5 (`docs/v2/05-roadmap/ROADMAP.md:43-54`) — RP-1 cerrado, no hay colisión con este ADR.

## Decisión final

**ACCEPTED.** Firmado por el operador en modo AUTO según `AGENTS.md §3 + §4 + §5`. La autorización cubre la iniciativa completa (no requiere re-aprobación para cada paso del slice). El slice se ejecuta según `WU_RP_049_LF0403_PLAN.md`.
