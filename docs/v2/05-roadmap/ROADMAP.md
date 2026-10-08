# ROADMAP V2 — Production Ready verificable y evolución coherente

**Estado:** AUTORIDAD ACTIVA DE SECUENCIACIÓN para V2 desde 2026-09-21.
**Baseline de código auditado:** main @ a554fd5544f74f580bbd531c9b394cff1e073621 (2026-09-21).
**Estado de esta entrega:** SOLO DOCUMENTACIÓN; NO se ha recompilado ni recertificado HEAD.
**Estado de producto publicado:** v0.39.0, GitHub Release de 2026-09-19; su certificación NO se transmite a commits posteriores.

**Actualización de hechos 2026-10-08 (observada, no inferida). Supersede las tres líneas anteriores como referencia de estado; se conservan por trazabilidad.**

```text
origin/main observado      b66bf7c796db28dabf3e13df9784844e7a59aeda
rama de trabajo            s6-plugin-sdk @ acd12111 (33 commits por delante de main, 0 por detrás)
producto publicado         v0.47.0 (GitHub Release Latest, 2026-10-06); zip sha256:2fa2d272…d3301c
superficie de CI           inexistente (.github/workflows/ no existe; solo Dependabot activo)
checks requeridos en main  ninguno (required_status_checks 404, rulesets [])
ciclos SDDK                42 OPEN, 4 BLOCKED, 82 CLOSED, 1 RELEASE_PENDING
```

Hallazgos de esa reconciliación, con evidencia y dueño, en `../07-uat/B0_RECONCILIATION_RECEIPT.md`.
Dos son P0 y **no** están corregidos: la release estable `v0.47.0` se cortó desde `3ec99a4c`, que no
está en `main` y vive en la PR #99 abierta (contradice ADR-0099), y `main` no tiene hoy ningún check
requerido ni superficie que pueda producirlo (G10 sin mecanismo).
**Autoridad operativa (TRAIN-0 cutover 2026-09-26):** SDDK + Git + ADRs + evidencia externa. `.agent/SESSION_POINTER.md` queda como proyección humana opcional / histórico (no autoridad). **Pruebas vinculantes:** ../07-uat/CERTIFICATION_PROTOCOL.md y ../07-uat/PRODUCTION_READY_UAT_MATRIX.md.

## 0. Autoridad, límites y significado de DONE

1. Respetar ADRs aceptados, especificaciones normativas, contratos públicos y garantías ya publicadas. Este plan unifica PRIORIDADES; no revoca decisiones técnicas aceptadas ni reescribe recibos.
2. El roadmap antiguo y la propuesta LPR de 2026-09-18 se preservan en docs/historico/; LOCAL_FOUNDATION_CONSOLIDATION, LFC2_STEP_ECOSYSTEM_EXPANSION y los ADRs vigentes son referencias técnicas, NO colas paralelas que puedan saltarse este orden.
3. LPR-001/auto-run puede gestionar WUs sucesivas, pero NO permite saltar gates, ocultar bloqueos, reetiquetar fallos ni eludir las excepciones de autorización de INITIATIVE_LPR_001 §2.4. Cambios de semántica pública, protocolo remoto, publicación inmutable o receipts históricos exigen autorización según esa iniciativa.
4. Fuentes de verdad separadas: (a) Git/registry/contratos/código, (b) CI e informes ejecutados en el SHA exacto, (c) recibos históricos, (d) estado operativo derivado. Nunca obtener el porcentaje global sumando tests, commits y WUs heterogéneas.
5. Todo hito produce una capacidad vertical verificable, exit criteria, UAT de distribución instalada, registro de fallos y decisión GO/STOP. No existe certificado implícito por disponer de código, PR cerrado, tag, test omitido o un recibo antiguo.
6. Prioridad obligatoria: corregir la línea de verificación y los defectos de integridad/seguridad antes de iniciar expansión funcional. No añadir un nuevo Step core mientras RP-0/RP-1 no cierren con pruebas actuales. No refactorizar en masa ni modificar decisiones aceptadas para hacer pasar tests.
7. Plataforma objetivo inicial: uso local seguro en un entorno de CI de confianza. Ejecutar pipelines ajenos en aislamiento fuerte requiere un gate separado de OS/container, no equivale al sandbox best-effort ni a un ClassLoader.

## 1. Línea temporal y precedencias

| Período | Estado factual a preservar | Consecuencia para la planificación |
|---|---|---|
| Hasta 2026-09-18 | M3/ML, EM y EVT tienen sus recibos, pero algunas líneas anteriores están supersedidas como secuencia. | Usar su código, ADRs y UAT como fundamento; NO reiniciar milestones históricos. |
| 2026-09-18 | ADR-0082 prioriza LPR; el plan anterior LFC-2E se difiere como expansión global. | LPR gobierna la línea local de producto; compatibilidad y contratos anteriores siguen vinculantes. |
| 2026-09-19 | v0.39.0 publicada y documentada sobre su commit certificado; canal SDKMAN pendiente según examen posterior. | Separar garantía del artefacto publicado de main y de canales pendientes. |
| 2026-09-20 a 21 | Iniciativa LPR-001, Step burn-down, stash/unstash y publishHTML incorporados a main; WU-090 Phase D en a554fd55. | Conservar los recibos; NO asumir que el estado de main pasa el gate global. |
| 2026-09-21: auditoría | CI de a554fd55 falla antes de compilar por ruta incorrecta de wrapper. Hay hallazgos reproducibles por inspección sobre informes, enlaces y evidencia contractual. | Abrir primero RP-0 y RP-1 y generar NUEVA evidencia de HEAD. |

**Orden obligatorio:** RP-0 → RP-1 → RP-2 → RP-3 → RP-4 → RP-5 (Gate local) → RP-6 (ecosistema) → RP-7 (local-first ampliado) → RP-8 (controller/worker remoto) → RP-9 (Jenkins y capacidades distribuidas). Los spikes sin cambios públicos pueden adelantarse; su implementación NO puede adelantar el gate del que depende.

## 2. RP-0 — Restaurar CI y verdad del repositorio (P0; primera WU)

**Objetivo:** un checkout limpio del SHA auditado ejecuta realmente todas las validaciones requeridas.
**WU-RP-000:** corregir .github/workflows/lpr0-ci.yml y v2-baseline.yml: el Gradle wrapper está en v2/gradlew, no en la raíz. Usar, por ejemplo, trabajo en directorio v2 y ./gradlew; reparar uploads/upload-artifact@v4; eliminar o sustituir build.yml vacío. Asegurar un job explícito para corpus completo en gate de release, separado del carril rápido si su coste lo requiere.
**WU-RP-001:** mapear checks obligatorios y protección de main; recoger resultados reales (no skipped) de compile, domain, events, architecture, application, compatibility, CLI instalada, release; comprobar GitHub Actions del SHA. Si faltan permisos para configurar protección, registrarlo como BLOQUEADO_EXTERNO sin afirmar protección activa.
**WU-RP-002:** regenerar inventario desde CoreStepRegistryFactory, plugins, suites y receipts. Resolver 16 frente a 20 Steps en registros, duplicidad de estados, WU-090 Phase D ya incluida y Tier B 094 TBD. El archivo generado incluye SHA/código fuente, fecha, comando de generación, alcance y distinción REGISTERED / CERTIFIED_AT_SHA / EXPERIMENTAL / REJECTED.
**Salida RP-0:** CI real verde y no omitido para el SHA candidato; workflow de release tiene validación de artefacto y corpus; estado operativo sin falsos pendientes; evidencia en receipt RP-0. Si main requiere una corrección para estar verde, se cierra sobre el nuevo commit, no mediante evidencia de a554fd55.

## 3. RP-1 — Integridad, seguridad y verdad de certificación (P0; sin nuevos Steps)

**WU-RP-010:** publishHTML: no sobrescribir index.html aportado por el usuario; el índice generado no debe colisionar; hash del contenido FINAL archivado, integridad de manifest y replay verificada. Respetar API/semántica publicada: si una corrección alterase un contrato público certificado, documentar el cambio y activar la autorización correspondiente.
**WU-RP-011:** escapar nombres de archivo de modo contextual en HTML/href; confinar reportDir por ruta real y no seguir symlinks; pruebas de traversal, symlinks intermedios y directos, Unicode y archivos maliciosos.
**WU-RP-012:** stash/unstash: no seguir symlinks fuera del workspace, validar árbol destino/entrada y cierres bajo error/interrupción. Revisar exposición de secretos y permisos. Comparar resultado frente a los recibos WU-089/090 sin reescribirlos.
**WU-RP-013:** reconciliar StepContractSuite de publishHTML con sus afirmaciones G7. Añadir pruebas de handler positivo/negativo, missing capability, replay/restart, contenido de eventos y distribución instalada. Regenerar XML/certificación vinculados al SHA. Revisar familias vecinas si comparten el mismo bug de serialización.
**Salida RP-1:** los escenarios UAT-SEC/ART incluidos en la matriz son verdes, sin escapes de ámbito ni pérdida de contenido; una certificación revisada demuestra cada dimensión obligatoria. No basta que el canario devuelva exit 0.

**Estado RP-1 al 2026-09-22 (HEAD `f4aa20dc`):**
- WU-RP-101 CLOSED (e95b3d41) — test determinism.
- WU-RP-010 r1 CLOSED (4b93a1eb) — 4 tests E2E (UAT-RP-005 inv 1, 2, 4).
- **WU-RP-010 r2 DEFERIDA → KNOWN_LIMITATION** (ADR-0095). UAT-RP-005 inv 3 (MANIFEST.json archivado) se reevaluará en WU-RP-042 antes de release.
- WU-RP-013 CLOSED (57a26d19) — StepContractSuite 11→23.
- WU-RP-011 CLOSED (ae6b334e + d3e9b9b6) — UAT-RP-006 + UAT-RP-007.
- WU-RP-012 CLOSED (b3f74e93) — UAT-RP-008 + UAT-RP-009.
- RP-1 cerrado con 1 KNOWN_LIMITATION documentada. **RP-2 arranca con WU-RP-020** (caracterización SqliteEventStore, test-side puro).

## 4. RP-2 — Baseline de arquitectura, determinismo y observación

**WU-RP-020:** caracterizar SqliteEventStore bajo concurrencia: sequence asignada frente a orden de inserción/lectura, flush/close con productores activos, reinicio, gap, error de writer, replay y arrays anidados. No modificar el contrato de secuencia hasta reproducir o descartar el riesgo; usar test determinista y criterios observables.
**WU-RP-021:** catalogar rutas de ejecución soportadas (declarative/scripted, in-memory/durable, restart, branch, cancellation), sin StepKey privilegiado. Pruebas golden de IR, eventos, journal, fingerprint, outcome y recovery. Registrar excepciones/legacy reales antes de eliminarlas.
**WU-RP-022:** medir baseline reproducible de startup, compilación/cache, salida de 200 MiB y soak de ≥1 GiB, consumidor lento y CPU/RSS; definir SLO y presupuesto numérico DESPUÉS de medir y aprobar baseline. Separar eventos semánticos, transcript, valores y journal; comprobar redacción de secretos en todos los modos.
**Salida RP-2:** UAT-OBS/PERF/REC verde sobre el mismo SHA y entorno documentado; métricas comparables y decisión explícita respecto a límites. Si una métrica no se ha medido, estado UNKNOWN y gate abierto.

## 5. RP-3 — Fortalecer arquitectura, DSL y ejecución durable

**WU-RP-030:** establecer fitness de dependencias hexagonales, cohesión, ownership de I/O, no globals/Any? en fronteras, no when(stepKey), contrato de capacidades, compatibilidad y connascence entre modelo-evento/codecs/sequence. Caracterizar consumidores de APIs antes del cambio.
**WU-RP-031:** separar por pequeñas extracciones StructuralPreparation → DurableResolution → TypedInputDecode → StepExecutor; coordinator solo lifecycle/run-stage, sin reescritura masiva. Cada extracción pasa golden journal/event/replay y UAT kill/resume antes de retirar su predecesor.
**WU-RP-032:** cerrar semánticas del DSL soportado: declaración vs ejecución, valores runtime tipados, block Steps/cancelación, contextos, failures, validación compile-negative/positive, plugin externo sin tocar motor. Rechazar explícitamente superficie no soportada; no publicar un fallback ficticio.
**Salida RP-3:** un Step externo con y sin cuerpo accede al mismo registro genérico (si forma parte del SDK soportado), con admission/replay/typed errors comprobados y cero nueva ramificación concreta en el coordinator. Los recorridos soportados tienen paridad demostrada.

### WU-RP-034 — Workspace & Execution Location Semantic Remediation (post-exit corrective WU)

**Trigger:** el dogfooding posterior a RP-3 demostró que `pipelinek run` sin
`--workspace` ejecuta proyectos locales en un scratch temporal donde no existen
los paths del checkout. La caracterización WU-RP-053R también mostró
coexistencia de WORKSPACE_ROOT/CURRENT_DIRECTORY y consumidores con anchors
distintos.

**Objetivo:** establecer una única semántica tipada para workspace root, current
working directory y control root; hacer local-first el default del CLI sin
perder el modo scratch Jenkins-like.

**Decisiones (ADR-0100/0101/0102, ACCEPTED 2026-10-01):**
- `WorkspaceLease` distingue Attached(user-owned) de Managed(PipelineK-owned).
- `ExecutionLocation = workspace + cwd`; `cwd` es no-null y comienza en root.
- `dir(...)` deriva sólo cwd; no redefine workspace root.
- `controlRoot` nunca resuelve rutas del usuario.
- no-flag `pipelinek run` => attach invocation directory.
- `--workspace <path>` => attach explícito (compatible).
- `--isolated` => scratch gestionado (comportamiento histórico explícito).
- `--workspace` y `--isolated` son mutuamente excluyentes.
- filesystem Steps resuelven mediante PathAnchor + ExecutionLocation.
- workspaces Attached protegen su root contra `deleteDir/cleanWs` por defecto.
- Ownership es estado tipado; ninguna heurística VCS es autoridad.

**Secuencia obligatoria:**
A characterization-only → B ADTs puros → C runtime capability bridge →
D core cwd Steps → E consumers externos/utilities → F root/differential Steps →
G destructive safety → H CLI default flip → I cleanup/certificación.
**G precede obligatoriamente a H.**

**Exit:** distribución instalada demuestra Gradle/Maven/Node + self-hosting sin
`--workspace`, `--workspace .` conserva compatibilidad, `--isolated` conserva
scratch, nested `dir` es coherente entre sh/pwd/files/stash/plugins, attached root
no puede destruirse por default, fresh/replay/concurrency/architecture/full gate
verdes en el mismo SHA.

**Gate:** esta WU reabre únicamente la semántica de workspace necesaria para
certificación futura; no invalida RP3_EXIT_REVIEW en su SHA. No certificar una
nueva candidata local-first hasta cerrar WU-RP-034.

**Estado (2026-10-01):** secuencia **A→I ejecutada**. Caracterización congelada en
`WorkspaceExecutionLocationCharacterizationTest` (4/4). Un intento previo de
activar el default local-first (`8e838e6d`) fue **revertido** (`aeae1e4c`)
porque dejaba el checkout del usuario alcanzable con la protección destructiva
dependiendo aún de la heurística VCS. Ver
[`P0_SHELL_WORKING_DIRECTORY_RECEIPT.md`](../07-uat/P0_SHELL_WORKING_DIRECTORY_RECEIPT.md).

**RP034-I (certificación) — hallazgo de seguridad y cierre del slice.**
Con el default local-first ya activo, `deleteDir()` **borró el proyecto del
llamante**. La causa era una pérdida de hecho en tránsito, no un fallo de tipo:
`WorkspaceIntent` exponía sólo la base (`Path?`), incapaz de expresar ownership,
así que el bridge re-derivaba `WorkspaceLease.Managed` para **toda** ejecución.
El guard ADR-0102 estaba vivo en el tipo y muerto en el cableado — el gate de
RP034-G no estaba satisfecho en producción aunque su matriz y sus unit tests
pasaran. Corregido en `9d2e999a` con un `RuntimeWorkspaceTransport` de ownership
no-nulable, y verificado contra la distribución instalada en los tres modos
(adjunta → rechazo con exit 1; aislada → wipe permitido; subruta → exit 0). La
corrección del registro histórico está en
[`RP034_G_OWNERSHIP_DESTRUCTIVE_SAFETY.md`](../07-uat/RP034_G_OWNERSHIP_DESTRUCTIVE_SAFETY.md).

**Exit criteria pendientes:** el gate de "fresh/replay/concurrency/architecture/
full gate verdes en el MISMO SHA" requiere un `./gradlew check` completo y verde
sobre el SHA candidato. Hasta ese punto RP-034 **no** se declara cerrado y no se
libera candidata local-first.

## 6. RP-4 — Calidad transversal y distribución reproducible

**WU-RP-040:** configurar cobertura por módulo y umbrales fundamentados por riesgo (branch/line) sobre partes críticas, mutación selectiva de codecs/políticas; registrar exclusiones y @Disabled clasificados, nunca contar tests omitidos como PASS. Crear informes SAST/dependency audit/secret scan/SBOM y fijación de acciones por SHA según política de suministro.
**WU-RP-041:** probar aislamiento del runner local, filesystem, proceso, límites de CPU/memoria/tiempo, egress y secretos; declarar claramente modelo de amenaza y diferenciar ejecución confiable de multi-tenant. Cualquier capacidad que aún sea best-effort queda fuera del perfil de ejecución no confiable.
**WU-RP-042:** construir distZip reproducible a partir de commit inmutable, manifiesto y hashes; instalar de cero; ejecutar Gradle/Maven/Node reales, fallos de compilación, rollback/restart, uso de credenciales, CLI validate/run/inspect y corpus. Publicar sólo el ZIP EXACTO probado. Congelar API/schema para el candidato; certificación caduca si cambian los bytes.
**WU-RP-043 (self-hosted CI / dogfooding progresivo):** convertir el CI en
tres niveles. N1 bootstrap independiente: checkout + JDK + build pipelinek +
canary de arranque, válido aunque el DSL/registro/ejecutor estén rotos. N2
dogfooding: el pipelinek del mismo SHA ejecuta un `.pipeline.kts` del repos
itorio con las pruebas de integración/acotadas (per-shard), dejando los
`--tests` actuales como fallback. N3 verificación externa: comprobar resultado
e informes desde fuera del motor. Criterios de salida: (a) pipelinek construido
desde checkout limpio; (b) un `.pipeline.kts` real del mismo SHA ejecutado;
(c) salida/artefactos verificados fuera del motor; (d) fallo intencional deja
CI en rojo; (e) un error de DSL no impide diagnósticos del bootstrap. GitHub
Actions queda como lanzador y publicador de checks; pipelinek define y ejecuta
la lógica de CI. El motor de selección por impacto (ADR-0094) será la fuente
común de la política de testing para local, Actions y pipelinek.
**Salida RP-4:** todos los checks de release vinculados a commit, artefacto, SO/JDK y logs. No declarar cobertura/seguridad/rendimiento verificados sin resultados fechados.

## 7. RP-5 — Gate Local Production Ready de main / candidata

Todos simultáneos en la MISMA candidata: RP-0..4 cerrados; checks obligatorios verdes; UAT obligatorias verdes; cero bugs críticos/altos abiertos dentro del perfil; cero tests obligatorios @Disabled contados; instalación limpia + reproducibilidad + hashes; seguridad y rendimiento conforme a presupuestos ratificados; compatibilidad verificada; manual rápido y runbook; uso repetido en al menos dos repositorios de naturaleza distinta, incluida una actualización y un fallo intencionado recuperable. El estado histórico v0.39.0 se mantiene como PUBLICADA_CERTIFICADA_EN_SU_SHA y no se sobreescribe.
**SDKMAN:** canal separado; sólo marcar SDKMAN_READY tras publicación real, instalación limpia, UAT y promoción verificadas. Un bloqueo externo no autoriza a afirmar que el canal está verde; una release ZIP local puede tener un estado distinto.
**GO:** release certificada por SHA/artefacto. **STOP:** preservar evidencias y reabrir la WU causante; no disminuir la batería para forzar verde.

## 8. RP-6 — Ecosistema LFC-2E por demanda, con un patrón de Steps

Tras RP-5, reconciliar Tier A y B existentes sin reimplementar capacidades certificadas. Cola provisional heredada: WU-091 core.lock → WU-092 core.input → WU-093 core.httpRequest → WU-094 por concretar mediante inventario y decisión de producto; Tier C (readTOML/writeTOML, tar/untar) sólo si necesidad real y clasificación vigente lo requiere. Mantener distinción entre 20 REGISTERED y CERTIFIED por SHA; junit.results es plugin externo, no un nuevo core universal. Cada Step: contrato tipado, registro sin bypass, capability + policy, semántica/replay/cancelación, compilación positiva/negativa, fitness, UAT instalada, Jenkins-diff donde aplique y recibo verificable. No sumar features a core por comodidad: vendor/toolchains/contenedores van a plugins independientes y versionados.

**Estado de RP-6: CLOSED en RP6-CLOSEOUT (2026-10-03).** La cola se ejecutó completa y sus tres
elementos llegaron a `CERTIFIED_AT_SHA`:

| WU | Step | Forma de entrega | Recibo |
|---|---|---|---|
| RP6-A / WU-091 | `core.lock` | core BlockStep, backend de fichero POSIX | `WU091_LOCK_RELEASE_RECEIPT.md` |
| RP6-B / WU-092 | `core.input` | core Step | `WU092_INPUT_RELEASE_RECEIPT.md` |
| RP6-C / WU-093 | `http.request` | **OFFICIAL_PLUGIN**, NO core Step | `WU093_HTTP_IMPLEMENTATION_RECEIPT.md` |

**WU-093 se deliveró como plugin, no como `core.httpRequest`.** La cola de arriba nombra la forma
superada y se conserva como registro de lo que se decidió; la vigente está en
`docs/v2/07-uat/WU093_HTTP_DELIVERY_RECONCILIATION.md` y en
`docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md` (`pipeline-plugin-http`: typed
`httpRequest`). La clasificación no fue una preferencia de implementación: la política de
ecosistema ya situaba HTTP como concern de protocolo/vendor antes de que RP6-C empezara.

**WU-094 NO se abre.** Fue una *propuesta* (`markdown-toolkit-plugin`), no un requisito, y RP-6 no
convierte un TBD en criterio de salida. Pasa a un train posterior si aparece demanda real. Lo
mismo para Tier C: `NOT STARTED by decision`, no por falta de tiempo. Inventario regenerado:
`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`.

**PRODUCT-GATE sigue `BLOCKED_EXTERNAL`** mientras no exista superficie de CI (ver la política de
verificación en `AGENTS.md`). El STEP-CERT de los tres Steps no lo vuelve verde.

**WU-094 (proposed): plugin externo `markdown-toolkit-plugin` — multi-step library para procesar Markdown en pipelines.**
- **Motivación**: pipelines de libros (book-builder skill) y de docs-as-code necesitan renderizar Markdown a HTML, generar TOC y validar headings dentro del pipeline. Hoy esto se hace con `sh("markdownlint ...")` / `sh("md-to-pdf ...")` invocando binarios externos, lo que mezcla el transcript de consola con la salida tipada y depende de tooling presente en la imagen CI. Un plugin externo dedicado permite tipar la salida (`{ htmlPath, byteCount, sha256 }`), separar el canal tipado del transcript (mismo principio que `core.sh`), y declarar `ReplayPolicy.NEVER` cuando el render escribe a disco.
- **Forma**: nueva carpeta `examples/markdown-toolkit-plugin` siguiendo **exactamente** el patrón de `examples/example-uppercase-plugin` (mismo `build.gradle.kts`, mismo `StepDefinitionContributor` SPI, mismo `StepDefinitionContributor`/`StepCodec`/`StepContract` flujo, mismo burn-down G0..G8 hasta CERTIFIED). NO se toca `pipeline-application`, NO se añade StepKey en `CoreStepRegistryFactory`, NO se modifica coordinator.
- **Pasos incluidos (3 steps, single concern = markdown processing)**:
  1. `markdown.render(input: String, outputPath: String, flavor: RenderFlavor)` → typed `MarkdownRenderResult(htmlPath: String, byteCount: Long, sha256: String, headings: List<Heading>)`. Flavor enum: COMMONMARK | GFM. Usa `org.commonmark:commonmark` (BSD-2-Clause) como adapter inicial; puerta port para swap a flexmark si surge necesidad.
  2. `markdown.headings(input: String, minLevel: Int = 1, maxLevel: Int = 6)` → typed `List<Heading>(level: Int, text: String, line: Int)`. Parseo puro, sin I/O.
  3. `markdown.toc(input: String, minLevel: Int = 1, maxLevel: Int = 6, bullets: BulletStyle)` → typed `MarkdownTocResult(toc: String, headings: List<Heading>)`. BulletStyle enum: DASH | ASTERISK.
- **Modelo de datos (ADT, sellado)** — el siguiente bloque es Kotlin, no parte de la lista numerada anterior:

    ```kotlin
    sealed interface MarkdownNode {
        data class Heading(val level: Int, val text: String, val line: Int) : MarkdownNode
        data class Paragraph(val text: String, val line: Int) : MarkdownNode
        data class CodeBlock(val language: String?, val content: String) : MarkdownNode
        // ...extensible sin tocar el handler
    }
    ```
- **Capability**: declarar `MARKDOWN_OPERATIONS_CAPABILITY` en cada `StepContract.requiredCapabilities`. El adapter (no el handler) tiene acceso al port; el handler no toca fs ni red directamente.
- **Replay policies**: `NEVER` en `markdown.render` (cada invocación debe escribir; sin reutilización — análogo a `core.echo`); `ALWAYS` en `markdown.headings` y `markdown.toc` (puro, sin side-effects, idempotente).
- **Output channels**: typed `O` via `outputCodec.encode`; durable HTML se escribe a disco por el handler con un único `O_TRUNC` open; el transcript opcional emite un preview corto (primeras 5 líneas del HTML) por canal independiente — mismo principio de `core.sh` (typed ≠ console).
- **Neutral naming**: nada de "Jenkins markdown", "GitHub markdown", etc. en runtime. Adapter específico sí puede mencionarlo (p.ej. `GitHubFlavoredMarkdownAdapter`), pero los StepKeys son `markdown.render`, `markdown.headings`, `markdown.toc`.
- **DSL facade**: `StageScope.markdownRender(text, path)` / `.markdownHeadings(text)` / `.markdownToc(text)`. Cada una baja solo a `registryStep(...)`, igual que `uppercase(text)`.
- **Reference research (AGENTS.md §reference-implementation-research)**:
  - commonmark-java (`org.commonmark:commonmark:0.21.0`, BSD-2-Clause) — parser/renderer canónico, mismo formato que GitHub. Adoptado como adapter inicial.
  - flexmark-java (`com.vladsch.flexmark:flexmark-all:0.64.0`, BSD-2-Clause) — alternativo con tablas, footnotes, strikethrough. Considerado para Fase 2 si surge necesidad real.
  - markdownlint-cli (`npm`, MIT) — referencia para validar reglas de estilo; NO se adopta (sería acoplarse a npm); se documenta la diferencia para el usuario en el DSL doc.
- **Tests (StepContractSuite adaptado al plugin pattern)**:
  - identity, contract completeness, input codec, output codec, canonical envelope, registry resolution, capability admission, fresh / replay / divergence, typed rejection, missing capability, observability, real DSL, real external JAR, instalado-distribución execution, absence/isolation, zero-production-change.
  - UAT instalada con al menos 1 caso por step: `markdown.render` con un Markdown pequeño → HTML generado y validado por sha256 contra baseline; `markdown.headings` con un doc de 3 niveles → typed list correcta; `markdown.toc` con el mismo doc → string TOC contiene los 3 headings en orden.
- **NO_GO mientras RP-0/RP-1 abiertos**: esta WU solo se planifica y entra al backlog; la implementación arranca únicamente tras WU-RP-005 cerrar RP-0 y tras decisión explícita de abrir RP-6.
- **Estado**: PLANNED (NO STARTED). Plan-budget: 1 WU = bloque de 4-6 commits (plugin skeleton + 3 steps + tests + DSL + integración + receipt).

## 9. RP-7 — Local-first ampliado, sin dependencia prematura del control-plane

Plugins de reportes/testing/artifacts/toolchains/SCM/HTTP y coordinación local priorizados por dogfooding. Sandbox opcional OS/container y límites verificables, secreto/egress, almacenamiento local robusto, migraciones de schema, cobertura de plataforma Linux/macOS/Windows declarada por separado. Antes de extender el SDK: compatibilidad semántica/binary, identidad/digest/provenance/versión del plugin, cargas externas en instalación limpia y certificación idéntica a core. Estudiar Cedar/policy con un spike y ADR; no activar enforcement opaco sin pruebas de deny/allow/versioning.

### 9.1 Secuencia de RP-7 (integrada 2026-10-03, `RP7_INTEGRATION_DISPOSITION.md`)

Antes de §9 era Roadmap con una cola y dos paquetes completos esperando fuera de
él. Los tres son ahora una secuencia, y sólo esta sección es la autoridad.

```text
RP7-SEM-0  constitution patch: 07-AGENTS-patch.md → AGENTS.md      ← PRIMER PASO
  ↓
RP7-SEM    Convergencia semántica        docs/pipelinek-semantic-evolution/
  ├─ S3  agent / environment / options   02-directive-model.md
  ├─ S4  Scripted Runtime v2             03-scripted-runtime.md
  ├─ S5  Reactive Event Spine v2         04-events-reactivity.md
  ├─ S6  Plugin SDK v2                   05-step-plugin-sdk-v2.md
  ├─ S7  Certification Harness v2        06-certification-harness-v2.md
  └─ S8  Migración / compatibilidad      08-roadmap.md, 11-release-cut.md
  ↓
RP7-ASX    Agent-first / secretless      docs/proposals/pipelinek-agent-secretless/
  ASX-0 baseline · ASX-1 capability kernel · ASX-2 scopes · ASX-3 providers
  ASX-4 tool projections · ASX-5 inline CLI · ASX-6 MCP/skills · ASX-7 certificación
  ↓
RP7-LOCAL  sandbox, resource limits, policy/Cedar, storage, provenance  ← SIN PAQUETE
```

**S0–S2 de `pipelinek-semantic-evolution` están YA SATISFECHOS o PARCIALES y no se
reejecutan** — la tabla de reconciliación está en
`docs/v2/07-uat/RP7_INTEGRATION_DISPOSITION.md` §1. Reejecutarlos destruiría trabajo ya
certificado.

`ASX` va después de `SEM` y no en paralelo: **ASX-0 congela `core.sh`,
`withCredentials`, fingerprints, replay, events y payload antes de tocar secretos**, y
ese freeze no significa nada sobre una superficie semántica todavía inestable.

`http.request` es el reference plugin de S6 y el primer caso real de S7: ya probó el
seam sin rama de compilador y ya recoge la disciplina de certificación que S7 debe
industrializar.

**`RP7-LOCAL` no tiene paquete canónico.** La descripción de §9 es su única autoridad
y no basta para implementar. Abrirlo es decisión abierta, registrada como tal.
`RP8` (§10) y `RP9` (§11) conservan su autoridad actual y no se amplían aquí.

## 10. RP-8 — Control plane, ejecución remota y protocolo

### 10.1 Disposición vigente (BLOCK 2, 2026-10-04): el control plane NO es de `pipeline-kotlin`

**RP-8 deja de reclamar el control plane, los workers, el transporte remoto y el
protocolo distribuido.** Esas responsabilidades están delegadas en `pipelinek-fabric`, y
mantenerlas aquí sería una segunda autoridad sobre las mismas leyes, no una posición
de trabajo.

Lo que `pipeline-kotlin` conserva de RP-8 es exactamente lo que ya existe y ya tiene
dueño dentro del core: el spine durable de una ejecución, la identidad de run y step, el
journal, el replay, el recovery y el Output Plane. Eso no es "parte del control plane";
es el runtime que el control plane ejecuta. La frontera no es el features, es quién
posee la autoridad.

**Por qué el reparto y no una separación por capas.** Un control plane vive de
observar y dirigir: decide qué run arranca, qué run se cancela, cómo se presenta un
stage y qué hace un operador cuando algo se cuelga. Eso requiere autoridad sobre
ciclos de vida, sobre observadores y sobre identidades de run que son de Fabric. Si
`pipeline-kotlin` lo reclamara, habría dos autoridades decidiendo si un run está
terminal, y la ley de "una condición, una representación" deja de cumplirse por
separación de paquetes en lugar de por diseño.

**Lo que este repo debe entregar para que Fabric pueda cumplir RP-8**, y es la
secuencia que BLOCK 2, BLOCK 5 y BLOCK 6 ejecutan:

1. Un contrato publicado que un consumidor pueda resolver sin dependencia de fuente
   (BLOCK 2: `pipeline-domain`, `pipeline-events`, `pipeline-output`,
   `pipeline-scripting-api`).
2. Un spine de eventos con semántica, causation/correlation y contrato de
   replay/lectura estables (BLOCK 5 / S5).
3. Un SDK de plugins que aporte Step, Directive, Event y capability sin exigir
   cambios en el core (BLOCK 6 / S6).

**Lo que este repo NO debe construir**, aunque el texto histórico de §10.2 lo
pidiera leerse como suyo: handshake/protobuf versionado, leases/fencing de workers,
backpressure de transporte, mTLS, multi-worker, orquestación de contenedores,
provisionamiento de infraestructura, y cualquier backend de transporte distribuido.
Si alguno de esos hace falta dentro de `pipeline-kotlin`, es un defecto de diseño y
no una tarea pendiente.

### 10.2 Texto histórico (conservado, sin autoridad operativa)

Se conserva literal por trazabilidad. Fue la disposición vigente hasta el cierre de
BLOCK 2 y describía un control plane dentro de `pipeline-kotlin` que ya no se va a
construir aquí.

> **Dependencias:** RP-5 producto local estable, RP-7 contratos/persistencia/identidad,
> ADR de threat model y versionado. Recuperar M4 E5-02..10 como INPUT histórico, NO
> como código listo para integrar sin revalidar. Vertical: worker aislado →
> handshake/protobuf versionado → leases/fencing → ACK/replay/event ordering →
> reconexión → cancelación → multi-worker → resiliencia. Requerir mTLS/autorización,
> compatibilidad N/N-1, backpressure, límites, pruebas kill/network
> partition/duplicate y observabilidad. Seleccionar backend de transporte por spike,
> no por preferencia heredada. Remote storage/protocol/API incompatibles requieren
> autorización explícita.

## 11. RP-9 — Adaptadores Jenkins/Kubernetes y plataforma distribuida

### 11.1 Disposición vigente (BLOCK 2, 2026-10-04): Jenkins y Kubernetes son de `pipelinek-fabric`

**RP-9 deja de reclamar el adaptador Jenkins, los workers Kubernetes/OpenShift y la
plataforma distribuida.** Viven en `pipelinek-fabric`, junto con el control plane de
§10.1, y comparten con él la misma frontera de autoridad.

`pipeline-kotlin` no tendrá código de Jenkins, ni step/plugin de Jenkins, ni
operaciones de clúster. No es una omisión pendiente: es la posición correcta, porque un
núcleo de ejecución que conoce a su orquestador deja de poder ejecutar donde ese
orquestador no está, y la executabilidad local es una propiedad que RP-9 histórico ya
exigía y que la disposición vigente conserva ("mantener núcleo Kotlin local
independiente de Jenkins, Kubernetes y almacenamiento remoto").

**El contrato que RP-9 necesita de este repo** es de lectura y de eventos, no de
integración: `RunOutcome` para el estado del build, `PipelineEventEnvelope` con
causation/correlation para la proyección de FlowNodes y stages, y `OutputReadPort` con
su cursor para la consola. Eso es exactamente lo que BLOCK 2 publica y lo que
`examples/fabric-contract-consumer` ejercita desde fuera. Las leyes de RP-9 que sí
pertenecen a Fabric y se implementarán en BLOCK 3 y BLOCK 4: identidad de run,
causalidad, reconexión, replay sin efectos duplicados, autorización de credenciales,
at-least-once con identidad de reacción idempotente, y la distinción entre UAT de un
adaptador y certificación de todo el control plane.

**Consecuencia de gobernanza:** ninguna WU de `pipeline-kotlin` abre trabajo de
Jenkins, Kubernetes o control plane. Si aparece una, se clasifica como trabajo de
`pipelinek-fabric` o se rechaza.

### 11.2 Texto histórico (conservado, sin autoridad operativa)

> **Dependencias:** RP-8. Adaptador Jenkins como consumidor de eventos LIVE y
> proyección de FlowNodes/stages; identidad de run, causalidad, reconexión, replay
> sin efectos duplicados, autorización de credenciales. Kubernetes/OpenShift workers
> aislados y provisionamiento reproducible; pruebas en clusters reales y
> compatibilidad de versiones. Mantener núcleo Kotlin local independiente de Jenkins,
> Kubernetes y almacenamiento remoto. No confundir la UAT de un adaptador con
> certificación de todo el control plane.

## 12. Mecánica de ejecución y actualización

- Cada WU sigue: caracterización RED real o baseline → especificación/ADR cuando proceda → parche mínimo → pruebas T0 contrato, T1 módulo, T2 fitness/corpus, T3 instalada, T4 durable/fallos, T5 release/soak cuando el impacto lo exige → recibo y commit.
- La unidad de entrega es el **TRAIN** (un ciclo SDDK con sus WUs internas). El estado operativo se recupera vía SDDK (`sddk status --cycle <active-cycle>`, `sddk project resolve`, `sddk cycle next`). `.agent/SESSION_POINTER.md`, `.agent/WORK_JOURNAL.md`, `.agent/TESTING-STATE.md` y `.agent/TECH_DEBT_BACKLOG.md` son **proyección humana opcional / histórico** y NO autoridad. Si contradicen SDDK/Git/CI, la resolución es a favor del estado real y se marca el fichero como obsoleto para esa sesión.
- Cada cierre registra fecha UTC, base SHA, HEAD SHA, TRAIN/WU, decisiones, paths, test argv/exit/XML, hashes de artefactos, errores abiertos, evidencia caducada, próximo primer comando y motivo. La validación histórica no se reescribe.
- Los porcentajes se publican sólo para cohortes cerradas con denominador verificable (p. ej. WUs 2/6); si una WU está TBD, el avance global es NO_CALCULABLE.
- La secuencia puede evolucionar por descubrimiento respaldado por un ADR/recibo, preservando trazabilidad y gates. Ni un TODO ni un comentario de código prevalecen sobre una prueba ejecutada.

## 13. Plan por bloques B0..B7 — integración 2026-10-08

**Qué es esto.** La secuencia única de trabajo pasa a expresarse en bloques de entrega de valor
`B0..B7`. **No es un segundo roadmap**: cada bloque es una agrupación con exit criteria sobre las
secciones RP-x ya existentes, y **no revoca, ni sustituye, ni reordena** ninguna decisión técnica
aceptada. Si un bloque y un ADR aceptado discrepan, el ADR gana y el bloque se corrige.

El bloque activo de esta sección (B0) está registrado como WorkItem `f8fc07e6` del ciclo
`rp7-sem-s6-plugin-sdk`; los bloques siguientes se abren como WorkItem al iniciarse, no antes. La
ejecución opera por la unidad de entrega del §12 (TRAIN / ciclo SDDK + WU internas), y el orden
`B0 -> B1 -> ...` no autoriza a adelantar el gate del que depende cada bloque.

### 13.1 Trazabilidad bloque → RP existente

```text
B0  verdad del repositorio, procedencia y admisión      RP-0, RP-1, ADR-0105 (G10)
B1  hardening de runtime y convergencia semántica       RP-3, RP-2, RP-034, AUD-02..AUD-08
B2  S6 plugin SDK v2 y plataforma extensible           RP-6, 05-step-plugin-sdk-v2
B3  S7 certification harness v2                        RP-4, RP-5, CERTIFICATION_PROTOCOL
B4  S8 compatibilidad, migración y release 0.49.x      RP-4, RP-5, RP-6
B5  agent-first y secretless (ASX-0..ASX-6)             RP-7
B6  hardening local y seguridad de producción           RP-7, RP-4, RP-5
B7  depuración final y retirada de V1                   MIGRATION_PLAN, IMPLEMENTATION_BACKLOG
```

### 13.2 Orden y dependencias

```text
B0 -> B1 -> B2 -> B3 -> B4 -> B5 -> B6 -> B7
```

Ningún bloque abre trabajo de otro. Los spikes sin cambio público pueden adelantarse; su
implementación no puede adelantar el gate del que depende. `RP-8` y `RP-9` **no pertenecen a este
repositorio** (§10.1, §11.1): son de `pipelinek-fabric`.

### 13.3 Exit criteria por bloque

| Bloque | Exit criteria | Estado observado 2026-10-08 |
|---|---|---|
| B0 | Procedencia reproducible (mismo digest para entradas idénticas en dos checkouts, un path con espacios no rompe) + autoridad de admisión inequívoca + dependencias externas registradas como bloqueo verificable | **PARCIAL**: procedencia corregida y probada (digest idéntico al base en worktree, exclusiones exactas, builds reales); autoridad resuelta por ADR-0105; hallazgos abiertos: B0-F1 (release fuera de `main`, PR #99) y B0-F2 (main sin checks requeridos), ambos requieren autorización para tocar el remoto |
| B1 | El perfil de ejecución soportado cumple sus contratos; sin defectos críticos/altos del bloque sin disposición explícita | **CERRADO con gate verde** (`check` 24m41s, 5195 tests, 0 fallos, 140 skipped declarados): AUD-02(c)(d) y su fuga de tmpdir, AUD-04, AUD-05, AUD-07 y el keying de AUD-08 cerrados con mutación; AUD-06 parcial con dueño y fila guarda; **AUD-03 CERRADO por B1.2** (`../07-uat/B1_2_WAITUNTIL_TERMINAL_ADT_RECEIPT.md`) — el terminal de `core.waitUntil` es el ADT `WaitUntilCompletion` que el motor durable ya proyectaba, el token de wire pasa a ser proyección derivada y el decoder **rechaza por nombre** lo que no puede nombrar en vez de coercionar a un timeout inexistente; allowlist de la ley ahora **vacía** y baseline de emisiones 3 → 2, con 2 mutaciones muertas. **B1.3 primera rebanada CERRADA** (`../07-uat/B1_3_CONTROL_JOURNAL_UNKNOWN_STATUS_RECEIPT.md`) — las filas de control de `retry` y `waitUntil` rechazaban un estado persistido desconocido fuera de su propio vocabulario (`Enum.valueOf` escapando como `IllegalArgumentException` en vez de `…DivergenceException`), lo que además perdía el fichero en el diagnóstico: un status desconocido **debe** rechazarse porque defaultear a `PENDING` re-ejecutaría un intento con efecto hijo ya ocurrido y a `SUCCEEDED` se tragaría un fallo; helper único `operationStatusOrThrow` con el rechazo como función, 2 mutaciones muertas (crudo y default). Gate: `check` 27m28s, **794 clases / 5225 tests**, 0 fallos, 140 skipped declarados. Las otras tres categorías de B1.3 se midieron y **no** se tocaron (83 `Instant.now()` en 32 ficheros con sustitución masiva prohibida; `StepOutcome.valueOf` inexistente; los `valueOf` de **codecs de salida** registrados como P2 con dueño y exit criterion). El resto es deuda P2 declarada en `../07-uat/B1_BLOCK_EXIT_RECEIPT.md` |
| B2 | La plataforma de plugins funciona desde fuera del monorepo y está certificada al nivel Step/SDK aplicable | **CERRADO en su sustancia, con dos residuos nombrados** (`../07-uat/B2_BLOCK_EXIT_RECEIPT.md`): **BOM** `:pipeline-sdk-bom` publicado (fuera de `publishedContractModules`, porque no tiene ABI que guardar) y **ejecución externa probada** — un consumidor independiente resuelve por `platform(...)` con coordenadas sin versión y EJECUTA la distribución instalada con `--plugin-jar`, afirmando el payload propio del handler. La lista B2.2 quedó **verificada fila por fila** (`../07-uat/B2_CHECKLIST_VERIFICATION_RECEIPT.md`): `apiRange`, plugins incompatibles, cargas duplicadas y errores de admisión ya estaban implementados y probados — el rechazo por rango prueba que el código del plugin NUNCA corrió, con control de no-vacuidad — y se citan en lugar de reimplementarse. Suites contractuales por plugin medidas (14 externo + 113 utilities + 20 scm-git, 0 fallos). Residuos: el arnés que convierta esas suites en veredictos con testigos exigidos por mutación (B2.4 → B3) y la disposición del puerto `BranchInvoker` |
| B3 | Testigos semánticos ejecutables, certificación externa coherente, veredictos que fallan ante mutaciones relevantes | **PARCIAL: precondiciones medidas** (`../07-uat/B3_PRECONDITION_STATE_RECEIPT.md`). El mecanismo de testigo ya existe y **discrimina**: `S0SemanticWitnessMatrixTest` (17) + `S3EnvironmentSemanticWitnessTest` (7), verdes con XML fresco. Kill/restart/recovery y seguridad destructiva tienen dueño y están verdes (15 tests: UatLocal001/002, S54, `DestructiveSafetyOwnershipTest`). **Sin dueño y declarado**: el vocabulario de veredicto (B3.7) no existe como tipo — es diseño con frontera decidida frente a ADR-0105, y no se creó aquí para no generar una segunda autoridad de admisión; extender el índice de testigos a los constructos restantes (el manifiesto tiene 50+3 y el índice nombra ~15); barrido de relojes (B3.5, B3a cubrió las dos sustituibles); escenarios S7 de plugin externo |
| B4 | Contratos clasificados y verificados, candidata `0.49.x` entregada al harness | **PARCIAL, sólo queda el handoff externo**. **B4.1/B4.3 verificados**: los 4 módulos publicados están clasificados EXPERIMENTAL con taxonomía cerrada, fitness que falla el build si falta una entrada y la regla de que una excepción no puede invocar madurez más laxa (16 verdes); el inventario de superficie está verificado **por reflexión contra el código** (11 verdes) y **se corrige contra el código**. **B4.2 CERRADO** (`../07-uat/B4_2_CONSUMER_COMPATIBILITY_CHARACTERIZATION.md`): la premisa de que hacía falta producir consumidores era **falsa** — los dumps `.api` versionados SON la superficie por revisión y se pueden diffear. Cinco ejes separados; el inventario medido por clase (2 clases y 12 miembros ausentes en contratos publicados) coincide **9/9** con `published-contract-exceptions.json`, medido antes de leerlo; eje durable verificado **negativo con mecanismo** (huella = `wireToken`, versiones idénticas, decisión no serializable); `pipeline-output` con delta cero; y los 6 tipos perdidos por `pipeline-step-sdk/api` **no son ruptura de consumidor** (módulo sólo-BCV, 0 `maven-publish`, verificado). **B4.4 CERRADO** (`../07-uat/B4_4_PLUGIN_AUTHOR_GUIDE_RECEIPT.md`): guía de autores junto al plugin de referencia, derivada **ejecutando** el ejemplo (par de aislamiento EXIT 0 / EXIT 1 con salida real, 380 tests 0 fallos), con 3 defectos de documentación corregidos — incluido que `StepContractSuite` **no existe como clase** y que `examples/README.md` documentaba una ruta de binario inexistente. Hueco de §4 **CERRADO**: `agentWithCapabilities` promovido a **STABLE** (`../07-uat/B4_AGENT_CAPABILITIES_PROMOTION_RECEIPT.md`) con testigo en ambas direcciones cruzando el coordinador de producción y mutación que aísla la positiva. Queda: B4.5 (candidata; autoridad del harness), `BLOCKED_EXTERNAL` mientras el harness no publique check-runs ni commit statuses |
| B5 | Agente y herramientas ejecutan con perfiles y secretos sin vía alternativa de ejecución | **OPEN** |
| B6 | Garantías anunciadas del perfil soportado verificadas por pruebas externas | **OPEN** |
| B7 | V1 retirada sin regresiones; V2 es la única ruta productiva | **OPEN**, último bloque |

### 13.4 Condiciones de STOP vigentes (no negociables)

No se declara un bloque terminado si: existe un defecto crítico/alto sin resolver dentro del alcance;
una UAT obligatoria está omitida; la semántica se conserva por stub o mock; un test verde no se
ejecutó realmente; se cambió un contrato público sin autorización; un efecto corre por ruta no
canónica; se perdió información durable; se debilitó una protección de credenciales; se cambió el
artefacto tras certificarlo; o falta la prueba externa exigida para el nivel anunciado. Un bloqueo
externo legítimo se documenta y se conserva: **nunca se convierte en PASS**.

### 13.5 Prohibiciones de frontera que este plan hereda

```text
- No implementar Jenkins, Kubernetes, control plane ni workers remotos aquí (son de pipelinek-fabric).
- No publicar ni promocionar releases desde este repositorio: la autoridad es el harness externo.
- No reintroducir GitHub Actions para cerrar G10 (ADR-0105 D3).
- No mantener tests que dependan del binario instalado o del corpus externo: eso vive en el harness.
- No crear estado operativo nuevo dentro del repositorio.
- No eliminar V1 antes de demostrar equivalencia o sustitución de sus capacidades necesarias.
```

### 13.6 GATE de integración local y su alcance

```bash
cd v2 && ./gradlew check --rerun-tasks   # presupuesto derivado según AGENTS.md rule 4
```

Es necesario para el cierre local cuando aplique y **no sustituye** la certificación externa ni
demuestra por sí solo el PRODUCT-GATE. Cada gate registra SHA y tree SHA, comando exacto, exit code,
tests/fallos/errores/skipped, tareas ejecutadas y `UP-TO-DATE`, detekt y API check, cobertura cuando
aplique, artefacto y SHA-256, UAT, mutaciones probadas, restricciones de plataforma, deuda abierta y
resultado `PASS`/`FAIL`/`BLOCKED`/`NOT_RUN`.
