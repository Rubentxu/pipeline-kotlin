# PK-NEXT / R0 — PK-1 Recovery (v0.56.0-rc6)

## Estado
- **Baseline:** `origin/main = 05ce62361b4f75e86fa1cec1b26a0ac821ac0a21`
  (incluye el fix del renderer rc5 + fix de 0.53.0 test).
- **Última candidata publicada:** `v0.56.0-rc5` (NO_CERTIFIED).
- **Target:** `v0.56.0-rc6`.

## Diagnóstico de partida (debe quedar resuelto en este bloque)

1. **ZIP binario en el árbol Git** (92.7 MB en
   `pk-rc-render-001/docs/v2/07-uat/evidence/v0.56.0-rc5/`). El ZIP no
   debe commitearse; debe vivir como release asset en GitHub o en
   `inputs/dogfood/<version>/` del harness, referenciado por digest.
2. **Tag-divergente en rc5:** el tag `v0.56.0-rc5` está en
   `2dfe7396` (rc5 release branch tip), pero el handoff afirma
   `sourceCommit: 05ce6236` (main merge). El `release-receipt.md` de rc5
   es internamente contradictorio.
3. **Commits de documentación `faf4d04d` y `2dfe7396` no están en main.**
   El handoff vive solo en la rama rc5, fuera de main.
4. **`DistributionMandatoryPluginsTest` con SKIP accidental.** El build
   configura `PIPELINEK_DIST_ZIP` como `systemProperty` (JVM `-D`), pero
   el test lee `System.getenv()`. Resultado: el test SKIPpea en lugar de
   FAIL cuando no se ejecuta desde el gate correcto.
5. **48 archivos `Uat*Test.kt` con fallos no investigados.** Clasificados
   como "preexistentes" sin matriz de causa reproducible.
6. **Lanzado del `RunCorrelated` antes de `RunStarted` rompe el contrato
   de stdout de `--format json` (la línea `{"schema":"pipelinek.launch/1",...}`
   antecede al JSON array). Decenas de tests assertando
   `stdout.startsWith("[")` fallan.
7. **`sanitize()` parcial.** Solo aplicado a `RunCorrelated`; otros campos
   externos siguen interpolando sin protección.

## R0.1 — Reparar la integridad de las releases

- [ ] Crear rama `release/pk-next-r0-v0.56.0-rc6` desde `origin/main`
  (hecho).
- [ ] Recuperar selectivamente la documentación útil de rc5, corrigiendo
  los datos: recibo de release con source SHA, tag SHA, ZIP SHA
  distintos y reconciliados. NO incorporar el ZIP binario al repositorio.
- [ ] El tag rc6 debe señalar un commit ancestro de `origin/main` (no una
  rama huérfana).
- [ ] Los artefactos grandes referenciados por digest fuera de Git
  (release asset de GitHub o `inputs/dogfood/` del harness).
- [ ] Tests negativos que detecten:
  - [ ] tag divergente
  - [ ] SHA de procedencia falso
  - [ ] referencia remota inexistente
  - [ ] asset cuyo digest no coincide

## R0.2 — Un único camino de publicación obligatorio

- [ ] Investigar qué scripts/comandos publicaron rc3, rc4, rc5.
- [ ] Diseñar `publisher` con dos fases:
  - **Pre-admisión:** árbol limpio, compile, arch tests, application
    tests, domain tests, `check`, detekt/ABI, distZip, SBOM,
    `candidateAdmission`, plugins oficiales instalados, UAT funcionales
    obligatorias.
  - **Publicación:** lee el recibo PASS, verifica ZIP digest, comprueba
    tag libre y ancestría, publica, re-descarga, verifica SHA remoto,
    publica recibo de publicación.
- [ ] Bloquear ruta alternativa: `gh release create` no puede publicar
  sin admisión documentada.
- [ ] Si no hay enforcement remoto (branch protection / runner
  restriction), registrar `REMOTE_ENFORCEMENT_NOT_CONFIGURED`.
- [ ] `DistributionMandatoryPluginsTest`:
  - [ ] Configurar `PIPELINEK_DIST_ZIP` como `environment()` (no
    `systemProperty`) en el test de `pipeline-release`.
  - [ ] Derivar host version del manifest, no de literal `0.56.0`.
  - [ ] Rechazar identidades de plugin duplicadas.
  - [ ] Verificar cuatro manifiestos oficiales válidos, no cuatro
    nombres distintos.
  - [ ] Mutaciones: retirar un plugin, duplicar identidad, corromper
    manifest, desalinear apiRange. Todas deben bloquear admisión.

## R0.3 — Contrato de stdout y LaunchReceipt

Estado: **channel split + testkit landed** (commits 8ece5258, b4f9c756,
9a1b5836, e4eaadb1).

- [x] ADR + contrato versionado:
  - [x] stdout en formato humano: contrato y semántica
  - [x] stdout en `--format json`: contrato y semántica
  - [x] LaunchReceipt: transporte, schema, campos, inmutabilidad
  - [x] DomainEvents tipados: canal durable vs canal stdout
  - [x] Compatibilidad declarada con PK-1 y Fabric
- [x] Prohibido: tratar LaunchReceipt como sustituto del EventStore
  durable. El recibo NO es un EventStore.
- [x] Framing estructurado: discriminador `schema` o separación
  explícita de canales. TEXT view: receipt en stderr; JSON view:
  receipt en stdout línea 1, array en stdout línea 2.
- [x] Testkit reutilizable de procesos CLI (CliContractTestKit):
  - [x] stdout/stderr separados
  - [x] exit code + protocolo
  - [x] decodificación por contrato
  - [x] fail ante malformación (ContractViolation tipada)
  - [x] NO `catch { emptyList() }` para ocultar fallos de decodificación
  - [x] LaunchReceipt y RunCorrelated durable con la misma correlación
  - [x] detecta ausencia/duplicación/contradicción
- [x] Actualizar tests de aplicación DESPUÉS de fijar la semántica, no
  antes. ~30 family-A tests reescritos en :pipeline-application:test
  para consumir el testkit.

Pendiente R0.3:
- [ ] Verificar que los 48 Uat*Test.kt con fallos se cierran tras
  las reescrituras. Family A (framing/LaunchReceipt) cerrada; las
  otras familias (B-F) las trata R0.4.

## R0.4 — Investigar y cerrar la suite de aplicación

Estado: **matriz parcial producida**; suite incompleta (daemon
muerto antes de terminar todos los tests). R0.3 closed + 6
stragglers de R0.3 cerrados; remaining fallos son producto/DSL
reales que requieren investigación adicional.

**Fix de producto de R0.4 (causa raíz de ~30 fallos):**
- Los example plugins (`example-block-plugin`,
  `example-directive-plugin`, `example-uppercase-plugin`) shipped
  con `apiRange = [0.47.0, 0.53.0)` mientras que los plugins
  oficiales ya están en `[0.47.0, 0.57.0)`. Los examples
  rechazaban cargar contra el runtime 0.56.0 con
  `IncompatibleApiRange`, exit 2. Bumped a `[0.47.0, 0.57.0)`.
  Esto es corrección de version drift, no cambio funcional: el
  Step body no cambió; solo el rango declarado.
- `DirectivePluginContractSuiteTest`: el SHA hardcoded
  (`d35e9fdf...`) correspondía a los bytes ANTIGUOS. Bumped a
  `f45ab769...` con comentario explícito ("range correction, not
  behaviour change"). Una re-certificación no es necesaria
  porque el cambio es de versión, no de comportamiento.

**R0.3 contract stragglers cerrados en R0.4:**
- `RunDefaultIsHumanEndToEndTest.json`: el assertion
  `startsWith("[{")` era contra el contrato viejo. Re-escrito
  a través del testkit.
- `S0SemanticWitnessMatrixTest.rawRun`: el helper devolvía stdout
  raw; el caller comparaba dos strings. Después de R0.3 la línea
  1 es el receipt; la comparación no medía lo que debía. Decodifica
  por testkit y serializa la lista de eventos para `stripRunIdentity`.
- `PureBuilderProbe`: el guard `startsWith([)/endsWith(])` significaba
  0 eventos después de R0.3. Decodifica por testkit.

**Matriz R0.4 (suite incompleta; 57 unique fallidos en último
run):**

| Familia | Clase | Fallos | Naturaleza |
|---|---|---:|---|
| A: framing | (closed by R0.3) | 0 | testkit + channel split |
| A: residual | `DirectivePluginContractSuiteTest` | 1 | certified SHA bump (product fix) |
| A: residual | `FArchS0CPureBuilderConsumptionTest` | 2 | (debería estar cerrado; ver run pK-next-r0.4-full3) |
| A: residual | `FArchS3PureBuilderPurityWitnessTest` | 2 | (debería estar cerrado; mismo motivo) |
| A: residual | `S0SemanticWitnessMatrixTest W-script` | 1 | (otro W-script test, no el de replay) |
| A: residual | `RunIdDirectoryTest RUN-01` | 1 | product issue (no contract) |
| B: feature-path | `UatDsl003ParallelTest` | 8 | parallel branch event order (product) |
| B: feature-path | `UatDsl005TimeoutGrammarTest` | 2 | timeout+retry timeline (product) |
| B: feature-path | `UatEvt001ReplayTest` | 1 | replay timeline (product) |
| B: feature-path | `UatEvt002MultiStepReplayTest` | 1 | multi-step events (product) |
| C: DSL | `UatLocal010SmokeE2ESandboxTest` | 3 | smoke fixtures (product/DSL) |
| C: DSL | `UatStep001ShExecutionTest` | 2 | sh step (product) |
| C: DSL | `UatStep001ShFailureStepFinishedCountTest` | 4 | sh failure event count (product) |
| C: DSL | `UatStep003ErrorAbortTest` | 1 | error abort DSL (product) |
| C: DSL | `UatLocal005RegressionGateTest` | 1 | regression smoke (product) |
| D: workspace | `UatLocal008CredentialsTest` | 16 | credential binding lifecycle (product) |
| D: workspace | `UatLocal009TopStepsTest` | 10 | env vars, file ops (product) |

**Pendiente R0.4 (siguiente sesión / iteración):**
- Investigar causa reproducible de cada cluster B/C/D, corregir
  desde autoridad real, ejecutar suite completa para validar.
- Suite actualmente killed by external daemon crash; el run
  posterior confirmó 57 fallos únicos pero suite no terminó.
- El conteo de 48 del brief del usuario era una estimación
  parcial. La matriz real es más grande.

**NO hechos (sin clasificar como preexistente):**
- FArch: post-fix `PureBuilderProbe`, los FArch tests siguen
  rojos en el log; puede ser que el compile no captó el fix o
  hay un issue de producto. Re-correr para confirmar.
- El resto de los clusters B/C/D requieren investigación de
  producto, no de contract.

## R0.5 — Cerrar los 13 escenarios de certificación

- [ ] Recuperar el catálogo canónico de los 13 (no "primeros 13
  fixtures" — son un catálogo externo, posiblemente distinto).
- [ ] Cada uno con identidad única, evidencia individual, oráculo
  propio.
- [ ] 10-smoke-e2e: reproducir segundo checkout, decidir semántica
  (destino vacío / reutilización explícita / idempotente). Si es
  fixture, arreglar el fixture, no desactivar el rechazo destructivo.
  Conservar negativo que demuestre rechazo destructivo no autorizado.
- [ ] 11-workflow-control: contrastar `deleteDir()` con ADR-0102. Si el
  test usa un workspace attached, adaptarlo a workspace gestionado por
  PipelineK. Conservar adversarial que rechace borrar raíz ajena.
- [ ] NO desactivar mecanismos de seguridad para pasar.
- [ ] 13/13 escenarios válidamente evaluados (PASS o FAIL esperado por
  oráculo) localmente. exit code 0 no es el criterio.

## R0.6 — Seguridad de presentación

- [ ] Política por tipo de dato:
  - [ ] Identificadores: una línea, sin controles
  - [ ] Texto multilinea: preservar sin suplantar registros
  - [ ] URLs/paths/errores: escapar controles y terminales
  - [ ] Sensibles: NO imprimir
  - [ ] `echo` literal: respetar contrato sin reinterpretar
- [ ] Implementación revisable de neutralización (no parche por evento).
- [ ] Aplicar a TODAS las interpolaciones en
  `ConsoleFamilyLines`, no solo a `RunCorrelated`.
- [ ] UAT/AAT con CR/LF, CSI, OSC, DCS, secuencias incompletas,
  Unicode de control, mensajes compuestos.
- [ ] NO atribuir a `sanitize()` garantía universal mientras haya
  bypasses.

## R0.7 — Gate final y publicación rc6

- [ ] Full `check` con tests efectivamente ejecutados.
- [ ] `releaseGate` completo, sin SKIP.
- [ ] Mandatory plugins ejecutados.
- [ ] Compilación + testkit consumer externo.
- [ ] UAT instalada completa.
- [ ] Negativos de renderer y protocolo.
- [ ] ABI, SBOM, reproducibilidad.
- [ ] ZIP publicado con digest verificado, fuera del árbol Git.
- [ ] Re-descarga + SHA verificado.
- [ ] Entrega al harness independiente.

## Criterios de aceptación

- 0 failures y 0 errores en suite obligatoria de aplicación
- Tests arquitectónicos verdes
- `DistributionMandatoryPluginsTest` ejecutado, no omitido
- 13/13 escenarios funcionales aprobados por oráculo
- Recibo y commit de release sin contradicciones
- Tag remoto alcanzable desde main
- ZIP publicado con digest verificado (fuera de Git)
- Ninguna ruta autorizada de publicación puede eludir la admisión

Si cualquier condición falla, NO publicar rc6. Registrar BLOCKED con
reproducción y seguir corrigiendo dentro de R0.

## Reporte final

**HECHO:** SHA base, SHA final, commits, tests ejecutados, UAT/AAT,
ZIP SHA, SDK SHA, tag y release remotos, estado del certifier, estado
del pair gate.

**BLOCKED / NOT_MEASURED:** tests no ejecutados, fallos del gate,
divergencias, deuda, decisiones pendientes, acciones correctivas.

Distinguir IMPLEMENTED, INTEGRATED, CANDIDATE_PUBLISHED, CERTIFIED,
PAIR_CERTIFIED.
