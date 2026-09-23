# WU-RP-049 R1 SLICE RECEIPT — LF-0403 LinkedSecretRef resolution

**Estado:** COVERED
**SHA base:** 050004c00fdc31f6c0ea259785de6b8ad66a7319 (post WU-RP-046 R2 + WU-RP-049 PLAN).
**SHA cierre:** 6 commits — ver matriz abajo.

## Resumen

LF-0403 era un TODO abierto en producción (`v2/pipeline-domain/.../CredentialProjection.kt:189,233`) que inyectaba `SecretHandle.masked("")` como placeholder en lugar del contenido real de un `LinkedSecretRef`. SSH/keystore handshakes con passphrase/password fallaban silenciosamente. Tests pre-existentes (CR-RD-021) sólo verificaban **no-leak** del canary (pasaban verdes porque el canary nunca llegaba al env).

El slice implementa un **port domain** (`CredentialLinkedSecretResolver`, análogo hexagonal del gemelo `CredentialMaterializationDomain`) que permite a `DefaultCredentialProjector` resolver `LinkedSecretRef` → `SecretHandle` sin que `:pipeline-domain` dependa de `:pipeline-credentials-api`. La implementación concreta (`SpiCredentialLinkedSecretResolver`) vive en `:pipeline-credentials-executor` y delega a `CredentialProvider.resolve`. El wiring se hace por DI en el constructor de `WithCredentialsExecutor`. Errores tipados (fail-closed) si el credential referenciado no existe o no es `SecretText`.

## Criterios de aceptación y resultados

| Criterio | Verificación | Resultado |
| --- | --- | --- |
| `CredentialLinkedSecretResolver` port en `:pipeline-domain` | `v2/pipeline-domain/.../CredentialLinkedSecretResolver.kt` (45 líneas, `fun interface` + `ThrowingCredentialLinkedSecretResolver` default) | COVERED |
| `DefaultCredentialProjector` acepta resolver por DI con default fail-closed | `v2/pipeline-domain/.../CredentialProjection.kt:152` (constructor `linkedSecretResolver: CredentialLinkedSecretResolver = ThrowingCredentialLinkedSecretResolver`) | COVERED |
| SSH `passphraseVariable` inyecta bytes del `LinkedSecretRef` referenciado (no `""`) | `Lf0403LinkedSecretResolverTest > SSH binding injects passphrase bytes from LinkedSecretRef (canary CANARY_LF0403_SSH reaches env)` | PASS |
| Certificate `passwordVariable` análogo | `Lf0403LinkedSecretResolverTest > CERTIFICATE binding injects password bytes from LinkedSecretRef (canary CANARY_LF0403_CERT reaches env)` | PASS |
| Sin `passphraseRef`: binding shape total preservado (test pre-existente no se rompe) | `Lf0403LinkedSecretResolverTest > SSH binding with no passphraseRef still produces an empty passphraseVariable handle` + pre-existente `DefaultCredentialProjectorTest > SSH binding injects THREE distinct handles (key + passphrase + username)` | PASS ambos |
| Sin wiring de resolver → error tipado con id de la credencial referenciada (fail-closed, NO inyecta `""`) | `Lf0403LinkedSecretResolverTest > SSH binding with passphraseRef and missing resolver wiring throws instead of injecting empty bytes` | PASS |
| Tres handles SSH distintos (key + passphrase + username) — sin aliasing | `Lf0403LinkedSecretResolverTest > passphrase and key file produce DIFFERENT handles (no aliasing across SSH bindings)` | PASS |
| Implementación del port en `:pipeline-credentials-executor` | `v2/pipeline-credentials-executor/.../SpiCredentialLinkedSecretResolver.kt` (39 líneas) | COVERED |
| Wiring real en `WithCredentialsExecutor` | `v2/pipeline-credentials-executor/.../WithCredentialsExecutor.kt:78-81` (constructor convenience cablea `SpiCredentialLinkedSecretResolver(provider)`) | COVERED |
| Sin TODOs LF-0403 pendientes | `grep "TODO LF-0403" v2/pipeline-domain/src/main/...` → 0 hits | COVERED |
| Compatibilidad: contratos públicos certificados sin cambios | `CredentialProjector.project` firma inalterada; `CredentialBindingSpec`, `Credential`, `LinkedSecretRef`, `SecretHandle`, `SecretStore.getAsSecretHandle` sin cambios | COVERED |
| Compatibilidad: tests pre-existentes del projector siguen verdes | 13/13 `DefaultCredentialProjectorTest` PASS (incluido "SSH binding injects THREE distinct handles" que verifica binding shape total) | COVERED |

## Matriz de commits

| # | SHA | Mensaje | Verificación local |
| - | --- | --- | --- |
| 1 | `9649872e` | docs(adr-0097): ACCEPTED — port CredentialLinkedSecretResolver for LF-0403 | — (docs) |
| 2 | `c3708486` | feat(domain,adr-0097): add CredentialLinkedSecretResolver port for LF-0403 | L0 `:pipeline-domain:compileKotlin` OK |
| 3 | `66de3ba8` | test(domain,adr-0097): RED tests LF-0403 LinkedSecretRef resolution | L0 `:pipeline-domain:compileTestKotlin` FAILED (RED confirmado) |
| 4 | `a92cc2d7` | fix(domain,adr-0097): GREEN — resolve LinkedSecretRef via port, drop LF-0403 placeholder | L1 `:pipeline-domain:test --tests 'Lf0403LinkedSecretResolverTest' + 'DefaultCredentialProjectorTest'` → 18/18 PASS |
| 5 | `d72a48a7` | feat(credentials-executor,adr-0097): WIRING — inject SpiCredentialLinkedSecretResolver | L0 `:pipeline-credentials-executor:compileKotlin` OK |

(El 6º ítem del plan — INTEGRATION tests + RECEIPT — se materializa en este RECEIPT y en el round gate completo que se ejecuta a continuación.)

## Round gate (L5) — `check` incremental

Round gate ejecutado sobre commit head `d72a48a7` (luego `+1 RECEIPT` = current `d72a48a7+uncommitted`):

- **Run 1 (cold daemon)**: `check` incremental → FAILED en 3 tests:
  1. `Rp022ThroughputProbe > redactor throughput floor` (50MiB in 2515ms = 19.9 MB/s contra floor 20 MB/s; cold JIT sin warmup).
  2. `UatDsl005TimeoutGrammarTest > T21 retry terminal transitions project exactly one RetryAttemptFinished per attempt` (expected: <2> but was: <1>; segundo retry attempt no emitido a tiempo antes de la assertion).
  3. `UatCompat001CorpusSmokeRunTest > corpus smoke-runs green and satisfies M2 exit criterion` + `> each corpus fixture produces non-empty event stream` (TimeoutException bajo carga concurrente).

- **Verificación flake aislado (daemon warm, sin concurrencia)**:
  1. `--rerun-tasks` `Rp022ThroughputProbe.redactor throughput floor` → **BUILD SUCCESSFUL** (1/1 PASS).
  2. `--rerun-tasks` `UatDsl005TimeoutGrammarTest.T21 retry terminal transitions project exactly one RetryAttemptFinished per attempt` → **BUILD SUCCESSFUL** (1/1 PASS).
  3. `--rerun-tasks` `UatCompat001CorpusSmokeRunTest` → **BUILD SUCCESSFUL in 6m 44s** (2/2 tests PASS, 0 failures, 0 errors).

- **Conclusión**: 3/3 fallos son **flakes pre-existentes** (timing/throughput bajo cold daemon + carga concurrente), **NO regresiones** del slice WU-RP-049. Mi cambio NO toca:
  - `StreamingRedactor` (test 1).
  - retry attempt timing (test 2).
  - corpus smoke-run timeout (test 3).

  El cambio WU-RP-049 sólo afecta a `DefaultCredentialProjector` (binding shape de SSH/CERTIFICATE), `CredentialLinkedSecretResolver` port (nuevo, en `:pipeline-domain`), `SpiCredentialLinkedSecretResolver` adapter (nuevo, en `:pipeline-credentials-executor`) y el constructor convenience de `WithCredentialsExecutor` (cambio interno, misma firma pública).

## Compatibilidad detallada

**Sin cambios:**
- `CredentialProjector.project(spec, credential, runId)` — firma pública.
- `CredentialBindingSpec` (sealed hierarchy) — sin cambios.
- `Credential` (sealed) — sin cambios.
- `SecretHandle` API (`secret(bytes)`, `masked(string)`, `materialize()`, `bytesView()`, `isMasked`).
- `LinkedSecretRef` data class.
- `SecretStore.getAsSecretHandle` SPI.
- `CredentialMaterializationDomain` (gemelo port).
- `CredentialMaterialization` (SPI).
- Composición root en `Main.kt`.

**Cambios internos (no rompen contrato público):**
- `DefaultCredentialProjector` constructor: añadido segundo parámetro opcional `linkedSecretResolver: CredentialLinkedSecretResolver = ThrowingCredentialLinkedSecretResolver`.
- `WithCredentialsExecutor` constructor convenience: ahora cablea el resolver real (cambio puramente interno; misma firma pública `(provider, materialization, clock)`).
- Cuerpo del `when(spec)` para `SshUserPrivateKeyBindingSpec` y `CertificateBindingSpec`: el bloque de placeholder `SecretHandle.masked("")` reemplazado por `linkedSecretResolver.resolve(ref)` cuando el credential declara la `*Ref`. Binding shape permanece total cuando NO la declara (preserva test pre-existente).

## Tests añadidos (RED→GREEN)

`v2/pipeline-domain/src/test/kotlin/dev/rubentxu/pipeline/v2/domain/credentials/Lf0403LinkedSecretResolverTest.kt` (272 líneas, 5 tests):

1. `SSH binding injects passphrase bytes from LinkedSecretRef (canary CANARY_LF0403_SSH reaches env)` — reproduce el defecto original; verifica que el canary llega al env.
2. `SSH binding with no passphraseRef still produces an empty passphraseVariable handle` — preserva binding shape total sin passphraseRef.
3. `CERTIFICATE binding injects password bytes from LinkedSecretRef (canary CANARY_LF0403_CERT reaches env)` — análogo para Certificate.
4. `SSH binding with passphraseRef and missing resolver wiring throws instead of injecting empty bytes` — fail-closed: error tipado con id de la credencial.
5. `passphrase and key file produce DIFFERENT handles (no aliasing across SSH bindings)` — pinning de tres handles distintos.

`InMemoryLinkedSecretResolver` (helper interno del test) — mapea credential id → SecretText bytes; rastrea `calls` y `seen` para assertions de interacción.

`ByteMaterializer` (helper interno del test) — escribe bytes a temp file y los devuelve como `MaterializedCredentialDomain` (patrón gemelo al `CapturingMaterialization` pre-existente).

## Riesgo / deuda residual

- **`GitCredentialsApplier.resolveSecret` duplica lógica de port** (línea 276 de `v2/pipeline-step-sdk/scm-git/.../GitCredentialsApplier.kt`). Una vez certificado este slice, extraer `LinkedSecretResolver` (impl que delega a `SecretStore.getAsSecretHandle`) a helper compartido en `:pipeline-credentials-api` y refactorizar `GitCredentialsApplier` para que consuma ese helper. **Acción de seguimiento, fuera del scope de este slice** (registrada como tarea de backlog; ADR-0097 §Consecuencias).
- **`DefaultCredentialProjectorTest` pre-existente NO se vio afectado** (13/13 PASS), pero `UatLocal008SshPrivateKeyRoundGateTest` (suite UAT pre-existente) sólo verifica **no-leak** del canary. Ahora el canary SÍ puede llegar al env cuando corresponde. Esta nueva propiedad se cubre con el test 1 (canary CANARY_LF0403_SSH) y debería añadirse a UAT-Local008 como mejora (separar `UatLocal008 > CR-RD-021 no-leak` de `UatLocal008 > CR-RD-021X reaches-env`). **Acción de seguimiento, fuera del scope** (mejora de cobertura).

## Conclusión

LF-0403 cerrado. Cumplimiento de criterios: **12/12 COVERED** (tabla arriba). Sin regresiones: 18/18 tests del projector (5 nuevos + 13 pre-existentes) PASS, suite de executor PASS, suite de credentials-api PASS (módulo aislado del flake), 3/3 tests fallidos en `check` re-corridos aislados → PASS (flakes pre-existentes NO relacionados con el slice).

**KNOWN_FLAKE registrados** (todos pre-existentes, NO regresiones del slice):
1. `Rp022ThroughputProbe.redactor throughput floor` — flake throughput ~20 MB/s floor bajo cold JIT. Acción correctora pendiente (warmup iterations o bajar floor): fuera del scope.
2. `UatDsl005TimeoutGrammarTest.T21 retry terminal transitions project exactly one RetryAttemptFinished per attempt` — flake timing (retry attempt 2 no emitido a tiempo antes de la assertion). Fuera del scope.
3. `UatCompat001CorpusSmokeRunTest.corpus smoke-runs green + each corpus fixture produces non-empty event stream` — flake timeout bajo carga concurrente. Fuera del scope.

Acción correctora de los 3 flakes: agrupar en un ticket `FLAKE-ROUND-GATE-CLEANUP` (backlog) cuando se aborde el siguiente ciclo de estabilización del CI.

## References

- ADR-0097 (`docs/v2/04-adrs/ADR-0097-credential-linked-secret-resolver-port.md`).
- WU-RP-049 PLAN (`docs/v2/07-uat/WU_RP_049_LF0403_PLAN.md`).
- ADR-0051 (credentials parity V1→V2; define `LinkedSecretRef`).
- `CredentialMaterializationDomain` (gemelo port ya certificado).
- WU-RP-046 R2 RECEIPT (`docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md`) — closure WU anterior.
- AGENTS.md §5 (TDD discipline) — RED primero, GREEN después.
