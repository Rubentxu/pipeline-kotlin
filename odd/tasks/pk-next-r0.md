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

- [ ] ADR + contrato versionado:
  - [ ] stdout en formato humano: contrato y semántica
  - [ ] stdout en `--format json`: contrato y semántica
  - [ ] LaunchReceipt: transporte, schema, campos, inmutabilidad
  - [ ] DomainEvents tipados: canal durable vs canal stdout
  - [ ] Compatibilidad declarada con PK-1 y Fabric
- [ ] Prohibido: tratar LaunchReceipt como sustituto del EventStore
  durable.
- [ ] Framing estructurado: discriminador `schema` o separación
  explícita de canales.
- [ ] Testkit reutilizable de procesos CLI:
  - [ ] stdout/stderr separados
  - [ ] exit code + protocolo
  - [ ] decodificación por contrato
  - [ ] fail ante malformación
  - [ ] NO `catch { emptyList() }` para ocultar fallos de decodificación
  - [ ] LaunchReceipt y RunCorrelated durable con la misma correlación
  - [ ] detecta ausencia/duplicación/contradicción
- [ ] Actualizar tests de aplicación DESPUÉS de fijar la semántica, no
  antes.

## R0.4 — Investigar y cerrar la suite de aplicación

- [ ] Ejecutar `:pipeline-application:test --rerun-tasks` y producir
  matriz:
  - clase, test, causa reproducible, primer commit divergente (si
    determinable), categoría (producto/fixture/protocolo/entorno/test),
    corrección, evidencia verde.
- [ ] NO clasificar como "preexistente" sin causa demostrada.
- [ ] Agrupar por familia de causa raíz:
  - [ ] A: framing y LaunchReceipt (R0.3)
  - [ ] B: feature-path y orden de eventos
  - [ ] C: DSL y semántica de bloques
  - [ ] D: workspace, ownership, operaciones destructivas
  - [ ] E: recursos, tiempos, locks, inputs
  - [ ] F: test harness, aislamiento, lifecycle de procesos
- [ ] Corregir desde la autoridad real. No relajar aserciones, no
  transformar FAIL en SKIP.

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
