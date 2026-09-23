# WU-RP-050 PLAN — Consolidar `LinkedSecretRef` resolution (cerrar duplicación LF-0403)

**Estado:** DRAFT (firmado para ejecución en modo AUTO, sha base 25818c10).

**Autor:** pipeline-kotlin (Rubentxu).
**SHA base:** 25818c10a0ba155185e206284dd64a7b4ea65b2d (post WU-RP-049 R1 cierre).
**Aplica a:** V2 desde este SHA. Cierra la "acción de seguimiento" que WU-RP-049 R1 RECEIPT documentó: `GitCredentialsApplier.resolveSecret` duplica lógica del nuevo port `CredentialLinkedSecretResolver`.

## Contexto (auditoría honesta)

Tras cerrar WU-RP-049 R1 (LF-0403), una auditoría honesta del repositorio en busca de regresiones y código duplicado descubrió:

**Duplicación activa**:
- `v2/pipeline-step-sdk/scm-git/.../GitCredentialsApplier.kt:276-279`: `resolveSecret(ref)` privado que llama a `secretStore?.getAsSecretHandle(ref.id)?.use { it.copyOf() }`. Produce `ByteArray` (diferente tipo de retorno que el port nuevo).
- `v2/pipeline-step-sdk/scm-git/.../GitCredentialsApplier.kt:281-288`: `resolveAndEncode` que llama a `secretStore?.getAsSecretHandle(...)` dos veces (username + password).
- `v2/pipeline-credentials-executor/.../SpiCredentialLinkedSecretResolver.kt:37`: `provider.resolve(ref.credentialsId)` que internamente delega a `SecretStore.getAsSecretHandle` (vía `LocalCredentialProvider.resolve`).

Son **3 sitios** que resuelven `LinkedSecretRef` → bytes, con pequeñas variaciones de tipo de retorno y manejo de errores. Si en el futuro se añaden: caching, métricas, observabilidad, validación de tipo, hay que duplicarlo en 3 sitios. Esto es exactamente la "deuda técnica generada a lo largo de los ciclos de implementación" que el operador mandó auditar.

**Verificaciones**:
- 0 TODOs/FIXMEs en producción (verificado: `grep -rn 'TODO\|FIXME' v2/ --include='*.kt' | grep -v /test/` → 0 hits).
- 0 regresiones silenciosas en XML recientes (verificado: failures=0 errors=0 en módulos ejecutados).
- LF-0403 sólo aparece en KDoc/comentarios históricos — no es deuda abierta.
- `CredentialScope` (en `:pipeline-credentials-api`) es legacy/dead code — NO se usa en producción; limpieza de código muerto queda fuera del scope de este slice.

## Decisión

Crear **un único adapter compartido** `SecretStoreLinkedSecretResolver(secretStore)` en `:pipeline-credentials-api` (módulo donde reside `SecretStore`). Este adapter implementa el port domain `CredentialLinkedSecretResolver` delegando a `SecretStore.getAsSecretHandle`.

Las **3 implementaciones actuales** se reducen a **1**:
- `SpiCredentialLinkedSecretResolver` (en `:pipeline-credentials-executor`) → refactor para delegar a `SecretStoreLinkedSecretResolver(provider)` en lugar de `provider.resolve(ref.credentialsId)`. Esto elimina la doble indirección (provider.resolve → store.getAsSecretHandle) → ahora va directo (resolver → store.getAsSecretHandle).
- `GitCredentialsApplier.resolveSecret` (en `:pipeline-step-sdk/scm-git`) → refactor para construir el `SecretStoreLinkedSecretResolver` en el `init` y delegar. Firma de retorno cambia de `ByteArray` a `SecretHandle` (consistente con el port).
- `GitCredentialsApplier.resolveAndEncode` → también usa el adapter (en lugar de `secretStore.getAsSecretHandle` directo).

**Compatibilidad de contrato público**:
- `GitCredentialsApplier(secretStore: SecretStore? = null)` firma sin cambios (sólo cambia el cuerpo).
- `SpiCredentialLinkedSecretResolver(provider: CredentialProvider)` firma sin cambios.
- `CredentialLinkedSecretResolver.resolve(ref: LinkedSecretRef): SecretHandle` firma sin cambios.
- `SecretStore.getAsSecretHandle` firma sin cambios.
- Tests públicos: 8/8 `GitCredentialsApplierTest` siguen pasando (comportamiento idéntico).

## Forma concreta

```kotlin
// v2/pipeline-credentials-api/.../SecretStoreLinkedSecretResolver.kt
package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef

/**
 * WU-RP-050 — Production adapter of [CredentialLinkedSecretResolver] for any
 * caller that already holds a [SecretStore]. Centralises the
 * `store.getAsSecretHandle(ref.id)` operation so the rest of the codebase
 * does NOT call [SecretStore.getAsSecretHandle] directly.
 *
 * Lives in `:pipeline-credentials-api` because that's the module that owns
 * [SecretStore]; both `:pipeline-credentials-executor` (via
 * SpiCredentialLinkedSecretResolver) and `:pipeline-step-sdk/scm-git` (via
 * GitCredentialsApplier) consume this adapter instead of duplicating the
 * pattern.
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

## Slice: A-min bounded, 6 commits

1. **ADR-0098** (docs): firma del refactor; explica la decisión, hexagonalismo, sin cambios de contrato público.
2. **feat(credentials-api)**: añadir `SecretStoreLinkedSecretResolver` en `:pipeline-credentials-api`.
3. **test(domain,adr-0098)**: RED tests que pin el contrato del adapter compartido.
4. **fix(credentials-executor,adr-0098)**: GREEN — `SpiCredentialLinkedSecretResolver` delega al nuevo adapter.
5. **fix(scm-git,adr-0098)**: GREEN — `GitCredentialsApplier` consume el adapter.
6. **docs(uat-rp-050-r1)**: RECEIPT consolidado.

## Criterios de aceptación

| # | Criterio | Verificación |
| - | --- | --- |
| 1 | `SecretStoreLinkedSecretResolver` en `:pipeline-credentials-api` | nuevo archivo + compila |
| 2 | `SpiCredentialLinkedSecretResolver` delega al adapter compartido (no más `provider.resolve(ref.credentialsId)` directo) | refactor + tests existentes pasan |
| 3 | `GitCredentialsApplier.resolveSecret` consume el adapter (no más `secretStore.getAsSecretHandle` directo) | refactor + 8/8 tests `GitCredentialsApplierTest` PASS |
| 4 | `GitCredentialsApplier.resolveAndEncode` consume el adapter | idem |
| 5 | `secretStore.getAsSecretHandle` directo sólo en 2 sitios: la definición del SPI y `LocalCredentialProvider.resolve` (legítimo, es la implementación del SPI) | grep |
| 6 | Sin regresiones: 18/18 tests projector PASS + 8/8 tests `GitCredentialsApplierTest` PASS + suite `pipeline-credentials-api` PASS + suite `pipeline-credentials-executor` PASS | L1/L2 tests |
| 7 | Round gate `check` incremental: 0 nuevos fallos (los 3 KNOWN_FLAKE pre-existentes pueden seguir apareciendo) | L5 round gate |
| 8 | Contrato público sin cambios | diff: ningún `public`/`open` cambia |

## Riesgos y mitigaciones

- **`SecretStore` retorna `SecretHandle` que `use { it.copyOf() }` extrae bytes** (en GitCredentialsApplier). El refactor cambia `resolveSecret` de retornar `ByteArray` a retornar `SecretHandle` (consistente con el port). Los call sites internos que hacen `.copyOf()` siguen funcionando. Tests confirman.
- **Hexagonalismo**: `:pipeline-credentials-api` ya depende de `:pipeline-domain`, así que puede importar `CredentialLinkedSecretResolver`. No añade nuevas dependencias.
- **Compatibilidad tests pre-existentes**: `GitCredentialsApplierTest` tests con `secretStore` mockeado. El refactor no cambia la interfaz del constructor. Riesgo bajo.

## Acciones de seguimiento (fuera de scope)

- **Limpiar `CredentialScope`** (legacy/dead code en `:pipeline-credentials-api/CredentialScope.kt`): NO se usa en producción. Queda para un slice de "limpieza de código muerto" cuando se aborde el siguiente ciclo.
- **Cobertura UAT LF-0403**: `UatLocal008SshPrivateKeyRoundGateTest` está SKIP por defecto (`V2_SSH_OK=true`). Una vez que `GitCredentialsApplier` use el adapter, los 2 tests SSH deberían pasar con `V2_SSH_OK=true`. Mejora de cobertura, fuera del scope de este slice.
- **3 KNOWN_FLAKE pre-existentes** (Rp022 throughput cold JIT, UatDsl005 T21 retry timing, UatCompat001 corpus smoke timeout): agrupar en `FLAKE-ROUND-GATE-CLEANUP` cuando se aborde el siguiente ciclo de estabilización CI.

## Compatibilidad

Sin cambios:
- `CredentialLinkedSecretResolver` port (firmas).
- `SecretStore.getAsSecretHandle` SPI.
- `LinkedSecretRef` data class.
- `SecretHandle` API.
- `CredentialProvider` SPI.
- `GitCredentialsApplier` constructor (firma).
- `SpiCredentialLinkedSecretResolver` constructor (firma).
- Composición root en `Main.kt`.
- `CredentialBindingSpec`, `Credential`, `DefaultCredentialProjector` (post-WU-RP-049 R1 sin cambios).

## Decisión final

**APPROVED para ejecución en modo AUTO** (operator preautoriza gates según AGENTS.md §3+§4+§5).
