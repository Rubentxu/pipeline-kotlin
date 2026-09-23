# WU-RP-049 LF-0403 — PLAN: cerrar el TODO passphrase/password LinkedSecretRef en CredentialProjection

**Fecha:** 2026-09-23T12:45Z. **Estado:** PLANNED (aprobado por operador en modo AUTO; AGENTS.md §3 + §4 + §5).
**SHA base:** c2de6bcab48e1dbba231d3054c413184c7a79ab8 (HEAD main). **Próximo ADR libre:** ADR-0097.

**Tipo de slice:** A-min (cambio bounded, una sola capacidad nueva, port hexagonal, test-first).

**Motivación (qué dice el operador):** el operador advirtió que **"el número de recuentos de cierres documentales no equivale a que las condiciones de aceptación estén verificadas. Esto tambien alcanza a la deuda tecnica generada a lo largo de los ciclos de implementacion."**

**Hallazgo de auditoría (2026-09-23):** dos `// TODO LF-0403 follow-up:` en `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialProjection.kt:189,233` con un placeholder vacío (`SecretHandle.masked("")`) en lugar del contenido real del credential referenciado por `LinkedSecretRef`. **Defecto funcional reproducible:** si un usuario define una SSH key con passphrase vía `passphraseVariable`, el env se inyecta con string vacío y la SSH falla por passphrase incorrecta.

**Causa raíz (preliminar, antes del test RED):**

1. `CredentialProjection.kt` NO depende de `pipeline-credentials-api` (hexagonalismo correcto).
2. La API `SecretStore.getAsSecretHandle(id)` ya existe y se usa en `GitCredentialsApplier.kt:277,282` (mismo módulo scm-git, distinto módulo).
3. El patrón arquitectónico de la casa es **port domain** que abstrae la dependencia externa: ya existe `CredentialMaterializationDomain` (en `v2/pipeline-domain/.../CredentialMaterialization.kt`) con su implementación en `pipeline-credentials-multipart`. **El fix correcto es crear un port gemelo `CredentialLinkedSecretResolver` (o ampliar el existente) y consumirlo vía DI en `DefaultCredentialProjector`.**

**Lo que NO se hace en este slice:**

- NO se reabren otros TODO abiertos (sólo LF-0403, los 2 sitios identificados).
- NO se introduce nueva abstracción para materialización de secretos (ya existe el patrón `CredentialMaterializationDomain`).
- NO se cambia contrato público certificado (la firma de `project(spec, credential, runId)` permanece; sólo se añade un parámetro opcional al constructor del projector y a su `fun interface`).
- NO se modifica el contrato de `CredentialBindingSpec`.
- NO se tocan Steps core (`core.sh`, `core.echo`), no se toca framework OS-level, no se integra el paquete overlay, no se reabre UAT-RP-005 inv3.

## Forma del slice

### 1. ADR-0097 — Port `CredentialLinkedSecretResolver` (1 commit)

**Cambios (todos en `:pipeline-domain`):**

- Crear `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialLinkedSecretResolver.kt` con `fun interface CredentialLinkedSecretResolver { fun resolve(ref: LinkedSecretRef): SecretHandle }`.
- Resultado tipado: `SecretHandle.masked(bytes)` igual que el resto del flujo. **Errores tipados**: si el credential referenciado no existe, lanzar `LinkedSecretReferenceNotFoundException` (ya existe en `pipeline-credentials-api/SecretStore.kt:164`); si el tipo no es `SecretText`, lanzar `LinkedSecretReferenceTypeMismatchException` (ya existe, mismo archivo línea 172).
- Documentar el contrato en KDoc: `LinkedSecretRef → bytes`, narrowing respecto a `SecretStore.getAsSecretHandle`, mismo shape que `CredentialMaterializationDomain`.

**Justificación del cambio:** hexagonalismo. El dominio no debe importar `pipeline-credentials-api` directamente; el patrón gemelo ya existe (`CredentialMaterializationDomain` ↔ `MaterializationDomainImpl`).

### 2. Test RED — LF-0403 defect reproduction (1 commit)

**Cambios (todos en tests):**

- Nuevo archivo `v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/CredentialProjectionLf0403Test.kt`.
- Test `passphrase env var contains the canary value when ssh key has LinkedSecretRef passphrase`:
  - Inyecta `InMemoryCredentialLinkedSecretResolver` con un credential `SecretText` que contiene `canary` como passphrase.
  - Construye `SshPrivateKey(username, privateKeyBytes, passphraseRef=LinkedSecretRef(canaryId))`.
  - Construye `SshUserPrivateKeyBindingSpec(credentialsId, keyFileVariable="SSH_KEY_FILE", passphraseVariable="SSH_PASSPHRASE", usernameVariable="SSH_USER")`.
  - Ejecuta `DefaultCredentialProjector(materializer, resolver).project(spec, sshCred, runId)`.
  - **Assert**: el `bindings["SSH_PASSPHRASE"]` cuando se materializa via `SecretHandle.materialize()` retorna el `canary` (no string vacío).
- Test `password env var contains the canary value when certificate has LinkedSecretRef password`:
  - Igual para `Certificate(keystore, passwordRef=LinkedSecretRef(canaryId), alias)`.
  - **Assert**: el `bindings["KEYSTORE_PASSWORD"]` materializado contiene el `canary`.

**Ambos tests deben fallar en RED** porque la implementación actual inyecta `SecretHandle.masked("")`.

### 3. Implementación mínima + GREEN (1 commit)

**Cambios (todos en `:pipeline-domain`):**

- `DefaultCredentialProjector` añade `private val linkedSecretResolver: CredentialLinkedSecretResolver` como segundo parámetro del constructor (con valor por defecto `ThrowingCredentialLinkedSecretResolver` para preservar compatibilidad con tests existentes que no necesitan LinkedSecretRef).
- En línea 189 (`passphraseVariable?.let`): reemplazar `env[varName] = SecretHandle.masked("")` por `env[varName] = linkedSecretResolver.resolve(spec.credential.passphraseRef).copy()` o equivalente (mirar `GitCredentialsApplier.resolveSecret` para patrón `use { it.copyOf() }`).
- En línea 233 (`passwordVariable?.let`): igual para `Certificate.passwordRef`.
- KDoc actualizado: el binding shape sigue siendo total (mismas variables, mismas posiciones); el contenido pasa de placeholder vacío a credential real. Esto **NO cambia contrato público** (no se añade/quita variable; sólo cambia el contenido de un env var ya declarado).

### 4. Wiring (1 commit)

**Cambios (todos en `:pipeline-credentials-executor`):**

- `WithCredentialsExecutor` (que es donde se construye `DefaultCredentialProjector` actualmente, ver `v2/pipeline-credentials-executor/.../WithCredentialsExecutor.kt:10`) debe inyectar el port nuevo `CredentialLinkedSecretResolver`. La implementación del port reside en `pipeline-credentials-executor` o `pipeline-credentials-api` y delega a `SecretStore.getAsSecretHandle(id)`.
- **Cero código duplicado**: el método `resolveSecret` de `GitCredentialsApplier.kt:276` es exactamente la misma operación. **Decisión arquitectónica:** si el patrón empieza a aparecer en ≥3 sitios, refactorizar a un helper compartido `LinkedSecretResolver` en `:pipeline-credentials-api`. **Por ahora** con 2 sitios + 1 nuevo = 3, **vale la pena** extraerlo desde el inicio. Lo planteo como parte del ADR-0097.

### 5. Tests de integración (1 commit)

- Test `WithCredentialsExecutorIntegrationTest` (o extensión del existente) verifica que con un binding SSH real + passphrase real, el env se inyecta con el contenido real.
- Re-correr `UatLocal008SshPrivateKeyRoundGateTest` (que ya está verde) para verificar **no-regresión** (los tests existentes verifican "no leak del canary", ahora deben seguir verificando no-leak + que el canary SÍ aparece en el env de SSH cuando se inyecta).
- Re-correr la suite de `pipeline-application` completa (L4) — debe quedar 0 failures.

### 6. RECEIPT (1 commit)

- `docs/v2/07-uat/WU_RP_049_LF0403_SLICE_RECEIPT.md` con:
  - SHA antes/después.
  - Tests RED → GREEN con diffs.
  - Cero regresiones (cuenta de tests pre-existentes vs post-WU).
  - Listado de TODO restantes en código de producción (si quedan otros que no son LF-0403).

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Regresión en tests SSH pre-existentes | L4 `:pipeline-application:test` rerun completo antes de cerrar el slice |
| Código duplicado entre GitCredentialsApplier y DefaultCredentialProjector | Extraer helper compartido en `:pipeline-credentials-api` desde el inicio (decisión del ADR-0097) |
| Cambio en semántica de `passphraseVariable` (de placeholder a contenido real) | El binding shape es idéntico (mismas variables); sólo cambia el contenido del env. **Es la corrección del defecto, no un cambio de contrato.** |
| Nuevos sitios con TODO idéntico | grep final + búsqueda de "LF-0403" en código debe retornar 0 hits |

## Criterios de aceptación

- [ ] RED: 2 tests nuevos (passphrase + password) fallan en HEAD base por env vacío.
- [ ] GREEN: 2 tests pasan tras la implementación.
- [ ] ADR-0097 firmado, sin contradicción con ADR-0051 (credentials parity).
- [ ] Cero `// TODO LF-0403` en `git grep "LF-0403" -- '*.kt'` después del slice.
- [ ] Cero código duplicado: `resolveSecret(ref: SecretHandleRef): ByteArray` extraído a `pipeline-credentials-api` si llega a 3 sitios.
- [ ] L4 `:pipeline-application:test` rerun: 0 failures, 0 errors. Mismo conteo de tests preexistentes +/- los 2 nuevos.
- [ ] `UatLocal008SshPrivateKeyRoundGateTest` rerun verde.
- [ ] CI del SHA push: SUCCESS 10/10.
- [ ] RECEIPT firmado con SHA final, base SHA, y matriz UAT-MATRIX actualizada (LF-0403 sale de KNOWN_LIMITATIONS).
- [ ] Sin regresión: el defecto **era** que el env estaba vacío; el fix es que ahora tiene contenido. Si algún test dependía del comportamiento vacío, se documenta y se ajusta (probable: ninguno, porque ese comportamiento es exactamente el bug).

## Estimación

- 6 commits: ADR-0097 + RED + GREEN + WIRING + INTEGRATION + RECEIPT.
- ~200-300 líneas de código (90% tests).
- Tiempo esperado: una sesión de trabajo enfocada (1-2 horas de clock con verificaciones incrementales).

## Slip-guard

- NO Step core nuevo.
- NO release.
- NO framework OS-level (RP-7+).
- NO overlay package.
- NO cambio de contrato público certificado (sólo corrección de un defecto ya documentado).
- NO bypass: cada paso se verifica con tests antes de avanzar al siguiente.
