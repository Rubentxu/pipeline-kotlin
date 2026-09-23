# ADR-0098 — Adapter compartido `SecretStoreLinkedSecretResolver` para consolidar resolución de `LinkedSecretRef`

**Estado:** ACCEPTED (firmado 2026-09-23, en slice WU-RP-050 consolidación LF-0403).

**Autor:** pipeline-kotlin (Rubentxu).
**SHA base:** 25818c10a0ba155185e206284dd64a7b4ea65b2d (post WU-RP-049 R1 cierre).
**Aplica a:** V2 desde este SHA. Cierra la "acción de seguimiento" ADR-0097 §Consecuencias que WU-RP-049 R1 RECEIPT documentó.

## Contexto

WU-RP-049 R1 introdujo el port domain `CredentialLinkedSecretResolver` (en `:pipeline-domain/.../CredentialLinkedSecretResolver.kt`) y un adapter SPI `SpiCredentialLinkedSecretResolver` (en `:pipeline-credentials-executor`) que delega a `CredentialProvider.resolve`. Una auditoría honesta posterior descubrió que la lógica de resolver `LinkedSecretRef` → bytes estaba duplicada en **3 sitios independientes**:

1. `v2/pipeline-step-sdk/scm-git/.../GitCredentialsApplier.kt:276-279` — método privado `resolveSecret(ref: SecretHandleRef)` que llama directamente a `secretStore?.getAsSecretHandle(ref.id)?.use { it.copyOf() }`.
2. `v2/pipeline-step-sdk/scm-git/.../GitCredentialsApplier.kt:281-288` — método privado `resolveAndEncode(usernameRef, passwordRef)` que llama directamente a `secretStore.getAsSecretHandle(...)` dos veces (username + password).
3. `v2/pipeline-credentials-executor/.../SpiCredentialLinkedSecretResolver.kt:37` — `override fun resolve(ref: LinkedSecretRef) = provider.resolve(ref.credentialsId)`. Indirectamente delega a `SecretStore.getAsSecretHandle` (vía `LocalCredentialProvider.resolve`).

Esto es exactamente la "deuda técnica generada a lo largo de los ciclos de implementación" que el operador mandó auditar. Si en el futuro se añaden: caching, métricas, observabilidad, validación de tipo, hay que duplicarlo en 3 sitios. Hexagonalismo y port-driven architecture se rompen en este punto.

## Decisión

Crear **un único adapter compartido** `SecretStoreLinkedSecretResolver(secretStore: SecretStore)` en `:pipeline-credentials-api` (módulo donde reside `SecretStore`). Este adapter implementa el port domain `CredentialLinkedSecretResolver` delegando a `SecretStore.getAsSecretHandle`.

**Importante — corrección factual**: `CredentialProvider` NO extiende `SecretStore` (son dos SPIs paralelos; `LocalCredentialProvider` compone un `SecretStore` por tenencia, no por herencia). Por lo tanto, **NO se puede pasar `provider` directamente al adapter compartido**. La consolidación se centra en los **2 sitios de `GitCredentialsApplier`** que sí tienen un `SecretStore` directo, no en `SpiCredentialLinkedSecretResolver`.

Las **3 implementaciones se reducen a 2** (1 compartido + 1 legítimo):

- **`GitCredentialsApplier.resolveSecret`** (en `:pipeline-step-sdk/scm-git`): refactor para construir `SecretStoreLinkedSecretResolver(secretStore)` en el `init { ... }` y delegar. La firma de retorno cambia de `ByteArray` a `SecretHandle` (consistente con el port domain). Los call sites internos que hacen `.copyOf()` siguen funcionando sin cambios.
- **`GitCredentialsApplier.resolveAndEncode`**: refactor análogo usando el adapter.
- **`SpiCredentialLinkedSecretResolver`** (en `:pipeline-credentials-executor`): **se mantiene sin cambios**. Sigue delegando a `provider.resolve(ref.credentialsId)`. Esto es legítimo porque opera sobre el port SPI `CredentialProvider`, no sobre `SecretStore`. La indirección provider→store es correcta: el provider puede implementar caché, métricas u otra lógica que NO está en el `SecretStore` raw. El adapter compartido NO aplica aquí.

Resultado: `secretStore.getAsSecretHandle` directo aparece **sólo en 3 sitios legítimos**:
- La definición del SPI en `:pipeline-credentials-api/.../SecretStore.kt:72` (la firma).
- La implementación del SPI en `:pipeline-credentials-local/.../LocalCredentialProvider.kt:40` (que es `SecretStore.getAsSecretHandle` por composición — es la implementación legítima).
- La nueva implementación en `SecretStoreLinkedSecretResolver` (el adapter compartido que es el ÚNICO consumidor directo fuera de `LocalCredentialProvider`).

Forma concreta:

```kotlin
// v2/pipeline-credentials-api/.../SecretStoreLinkedSecretResolver.kt
package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef

/**
 * WU-RP-050 (ADR-0098) — Production adapter of [CredentialLinkedSecretResolver]
 * for any caller that already holds a [SecretStore]. Centralises the
 * `store.getAsSecretHandle(ref.id)` operation so the rest of the codebase
 * does NOT call [SecretStore.getAsSecretHandle] directly.
 *
 * Lives in `:pipeline-credentials-api` because that's the module that owns
 * [SecretStore]; both `:pipeline-credentials-executor` (via
 * [dev.rubentxu.pipeline.v2.credentials.executor.SpiCredentialLinkedSecretResolver])
 * and `:pipeline-step-sdk/scm-git` (via
 * [dev.rubentxu.pipeline.v2.sdk.scm.git.GitCredentialsApplier]) consume this
 * adapter instead of duplicating the pattern.
 *
 * Errors from [SecretStore.getAsSecretHandle] (typed
 * [LinkedSecretReferenceNotFoundException] /
 * [LinkedSecretReferenceTypeMismatchException]) propagate as-is — fail-closed,
 * no swallowing, no substitution.
 */
class SecretStoreLinkedSecretResolver(
    private val secretStore: SecretStore,
) : CredentialLinkedSecretResolver {

    override fun resolve(ref: LinkedSecretRef): SecretHandle =
        secretStore.getAsSecretHandle(ref.credentialsId)
}
```

## Alternativas consideradas

### A. Inyectar `CredentialLinkedSecretResolver` en el constructor de `GitCredentialsApplier` (en lugar de `SecretStore`)
**Rechazada**: cambia la firma pública del constructor (`secretStore: SecretStore? = null` → `linkedSecretResolver: CredentialLinkedSecretResolver? = null`). Riesgo de regresión en composición root. El adapter compartido evita romper el contrato público.

### B. Hacer que `CredentialProvider` extienda `CredentialLinkedSecretResolver` o `SecretStore`
**Rechazada**: hexagonalismo se rompe. `:pipeline-credentials-api` no debe conocer detalles de domain ports de `:pipeline-domain`. Además, `CredentialProvider` y `SecretStore` son SPIs paralelos por diseño (provider tiene `resolve` + `resolveToCredential` + `close`; store tiene `get` + `getAsSecretHandle` + `close`). Cambiar la jerarquía de herencia rompe el modelo.

### C. Mover el adapter a `:pipeline-credentials-executor` y que `GitCredentialsApplier` dependa de ese módulo
**Rechazada**: `:pipeline-step-sdk/scm-git` ya depende de `:pipeline-credentials-api` (donde reside `SecretStore`). Añadir dependencia transitiva a `:pipeline-credentials-executor` infla el grafo innecesariamente.

### D. Mantener la duplicación y documentar como KNOWN_LIMITATION
**Rechazada**: el operador mandó auditar la deuda técnica generada; este es exactamente el patrón que NO queremos consolidar en 3 sitios.

### E. (Considerada y descartada) Refactorizar también `SpiCredentialLinkedSecretResolver` para usar el adapter compartido
**Rechazada por corrección factual**: `CredentialProvider` NO extiende `SecretStore`. Pasar `provider` como `SecretStore` requeriría un cast inseguro o cambiar el contrato del SPI. La indirección actual `SpiCredentialLinkedSecretResolver → provider.resolve → store.getAsSecretHandle` es legítima porque el provider puede tener lógica adicional (caché, métricas) que NO queremos perder.

## Consecuencias

**Positivas**:
- Cierra la acción de seguimiento ADR-0097 §Consecuencias.
- Elimina 2 sitios duplicados (los de `GitCredentialsApplier`) → 1 adapter compartido.
- `GitCredentialsApplier.resolveSecret` ahora retorna `SecretHandle` (consistente con el port domain) en lugar de `ByteArray` (variación ad-hoc).
- Cualquier mejora futura (caching, métricas, observabilidad, validación de tipo) sobre `SecretStore` se aplica en un solo sitio para los call sites que tienen acceso directo al store.

**Negativas / riesgos**:
- `GitCredentialsApplier.resolveSecret` cambia de retornar `ByteArray` a `SecretHandle`. **Mitigado**: los call sites internos (líneas 260-289 que usan `.copyOf()`) siguen funcionando porque `SecretHandle.copyOf()` / `SecretHandle.materialize()` están disponibles. 8/8 tests `GitCredentialsApplierTest` pre-existentes deben seguir verdes.
- `SpiCredentialLinkedSecretResolver` sigue siendo necesario (no se consolida). **Aceptado**: el provider puede tener lógica adicional (caché, métricas) que NO está en el `SecretStore` raw. La indirección provider→store es legítima.

**Acciones de seguimiento (fuera del scope de este ADR)**:
- Limpiar `CredentialScope` (legacy/dead code en `:pipeline-credentials-api/CredentialScope.kt`) — NO se usa en producción.
- Habilitar UatLocal008 por defecto (SKIP si `V2_SSH_OK` no es "true") — cobertura LF-0403 SSH end-to-end.

## Compatibilidad

**Sin cambios**:
- `CredentialLinkedSecretResolver` port (firmas).
- `SecretStore.getAsSecretHandle` SPI (firma y semántica).
- `LinkedSecretRef` data class.
- `SecretHandle` API.
- `CredentialProvider` SPI.
- `GitCredentialsApplier(tempDir, credentials, secretStore: SecretStore? = null)` constructor (firma pública).
- `SpiCredentialLinkedSecretResolver(provider: CredentialProvider)` constructor (firma pública).
- Composición root en `Main.kt`.
- `CredentialBindingSpec`, `Credential`, `DefaultCredentialProjector` (post-WU-RP-049 R1 sin cambios).

**Cambios internos (no rompen contrato público)**:
- `GitCredentialsApplier.resolveSecret(ref: SecretHandleRef): ByteArray` → `resolveSecret(ref: SecretHandleRef): SecretHandle` (método privado, no es contrato público).
- `GitCredentialsApplier.resolveAndEncode` interno: usa el adapter compartido.
- `SpiCredentialLinkedSecretResolver`: **sin cambios** (sigue delegando a `provider.resolve`).

## Referencias

- ADR-0097 (`docs/v2/04-adrs/ADR-0097-credential-linked-secret-resolver-port.md`) — port domain original; §Consecuencias documenta esta acción de seguimiento.
- WU-RP-049 R1 RECEIPT (`docs/v2/07-uat/WU_RP_049_R1_SLICE_RECEIPT.md`) — cierre LF-0403; lista explícitamente la duplicación con `GitCredentialsApplier.resolveSecret` como "acción de seguimiento, fuera del scope".
- WU-RP-050 PLAN (`docs/v2/07-uat/WU_RP_050_CONSOLIDATION_PLAN.md`) — plan operativo del slice (6 commits, criterios de aceptación).
- ROADMAP V2 §7 — RP-5 Gate; este ADR no contradice ningún criterio existente.

## Decisión final

**ACCEPTED.** Firmado por el operador en modo AUTO según `AGENTS.md §3 + §4 + §5`.
