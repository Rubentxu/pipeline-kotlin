# Modelo de credenciales como capacidades

## 1. Cambio conceptual

Modelo legado:

```text
credential id → resolve bytes → env/file → process
```

Modelo objetivo:

```text
CredentialRef
    + Operation
    + Resource/Audience
    + MinimumPosture
        │
        ▼
CredentialPlanner
        │
        ▼
CredentialAccessPlan
```

El planner elige **cómo usar** una identidad, no necesariamente cómo extraer un secreto.

## 2. Tipos propuestos

```kotlin
@JvmInline
value class CredentialRef(val value: String)

@JvmInline
value class CredentialProfileRef(val value: String)

data class CredentialUseRequest(
    val credential: CredentialRef,
    val operation: CredentialOperation,
    val resource: CredentialResource?,
    val audience: Set<Authority>,
    val minimumPosture: IntegrationPosture,
    val tool: ToolIdentity?,
)

enum class IntegrationPosture {
    STRONG_SECRETLESS,
    SHORT_LIVED_EXPOSURE,
    ISOLATED_PROCESS_EXPOSURE,
    RAW_PROCESS_EXPOSURE,
    UNSUPPORTED,
}
```

## 3. Access plans

```kotlin
sealed interface CredentialAccessPlan {
    val posture: IntegrationPosture

    data class SignerSession(...) : CredentialAccessPlan
    data class ProtocolProxy(...) : CredentialAccessPlan
    data class ServiceProxy(...) : CredentialAccessPlan
    data class CredentialHelper(...) : CredentialAccessPlan
    data class ShortLivedLease(...) : CredentialAccessPlan
    data class ConfigOverlay(...) : CredentialAccessPlan
    data class IsolatedExec(...) : CredentialAccessPlan
    data class LegacyEnvironment(...) : CredentialAccessPlan
    data class LegacyFile(...) : CredentialAccessPlan
}
```

## 4. Orden de preferencia

No es una jerarquía absoluta; depende de tool/provider/policy. Como default:

```text
SIGNER / PROXY / HELPER
        >
SHORT_LIVED
        >
CONFIG_OVERLAY
        >
ISOLATED_EXEC
        >
RAW_ENV / RAW_FILE
```

`ConfigOverlay` puede seguir siendo `RAW_PROCESS_EXPOSURE` si el proceso destino puede leer la credencial. El hecho de que el fichero sea 0600 y efímero reduce persistencia, no convierte la operación en strong-secretless.

## 5. Lease mínimo por Step

`withCredentials` nuevo no materializa secretos al entrar.

```text
enter credential scope
      │
      └─ refs + policy únicamente

Step A starts
      ├─ authorize
      ├─ prepare use
      ├─ execute
      └─ revoke/cleanup

sleep / unrelated step
      └─ no live lease

Step B starts
      └─ new minimal lease
```

## 6. Provider contract

El contrato nuevo preferido no debe obligar a devolver bytes:

```kotlin
interface CredentialProvider {
    suspend fun prepareUse(
        request: CredentialUseRequest,
        context: CredentialExecutionContext,
    ): PreparedCredentialUse
}
```

`PreparedCredentialUse` puede contener un session handle, signer, proxy endpoint, short-lived token o capacidad para crear una projection.

La API actual `resolve(): SecretHandle` se conserva como adapter de compatibilidad para providers/materializaciones que realmente lo necesitan; no es el centro de la nueva arquitectura.

## 7. Step credential requirements

Un Step debe poder declarar necesidades semánticas sin conocer el provider:

```kotlin
CredentialRequirement(
    operation = CredentialOperation("git.fetch"),
    resource = GitRemoteResource(input.url),
    audience = setOf(authorityOf(input.url)),
)
```

Para requisitos dependientes del input:

```kotlin
interface CredentialRequirementResolver<I> {
    fun resolve(input: I): Set<CredentialRequirement>
}
```

## 8. Fail-closed

El planner falla antes del efecto cuando:

- no hay credential scope compatible;
- audience no coincide;
- provider no soporta la operación;
- la única posture disponible es inferior a la mínima;
- no se puede verificar la identidad del tool cuando policy la exige;
- projection no puede confinarse/limpiarse según contrato.

## 9. ASV

ASV se integra como `PreparedCredentialUse`/session backend. No se adapta de forma general a `SecretHandle` porque eso violaría su invariante agent-facing.

Ejemplos deseados:

```text
Git SSH        → signer/SSH_AUTH_SOCK
GitHub HTTP    → broker/proxy/session
OAuth2         → short-lived substitute
compat tool    → isolated exec (posture explícita)
```

