# ROADMAP V2 — Production Ready verificable y evolución coherente

**Estado:** AUTORIDAD ACTIVA DE SECUENCIACIÓN para V2 desde 2026-09-21.
**Baseline de código auditado:** main @ a554fd5544f74f580bbd531c9b394cff1e073621 (2026-09-21).
**Estado de esta entrega:** SOLO DOCUMENTACIÓN; NO se ha recompilado ni recertificado HEAD.
**Estado de producto publicado:** v0.39.0, GitHub Release de 2026-09-19; su certificación NO se transmite a commits posteriores.
**Puntero operativo:** .agent/SESSION_POINTER.md. **Pruebas vinculantes:** ../07-uat/CERTIFICATION_PROTOCOL.md y ../07-uat/PRODUCTION_READY_UAT_MATRIX.md.

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

## 10. RP-8 — Control plane, ejecución remota y protocolo

**Dependencias:** RP-5 producto local estable, RP-7 contratos/persistencia/identidad, ADR de threat model y versionado. Recuperar M4 E5-02..10 como INPUT histórico, NO como código listo para integrar sin revalidar. Vertical: worker aislado → handshake/protobuf versionado → leases/fencing → ACK/replay/event ordering → reconexión → cancelación → multi-worker → resiliencia. Requerir mTLS/autorización, compatibilidad N/N-1, backpressure, límites, pruebas kill/network partition/duplicate y observabilidad. Seleccionar backend de transporte por spike, no por preferencia heredada. Remote storage/protocol/API incompatibles requieren autorización explícita.

## 11. RP-9 — Adaptadores Jenkins/Kubernetes y plataforma distribuida

**Dependencias:** RP-8. Adaptador Jenkins como consumidor de eventos LIVE y proyección de FlowNodes/stages; identidad de run, causalidad, reconexión, replay sin efectos duplicados, autorización de credenciales. Kubernetes/OpenShift workers aislados y provisionamiento reproducible; pruebas en clusters reales y compatibilidad de versiones. Mantener núcleo Kotlin local independiente de Jenkins, Kubernetes y almacenamiento remoto. No confundir la UAT de un adaptador con certificación de todo el control plane.

## 12. Mecánica de ejecución y actualización

- Cada WU sigue: caracterización RED real o baseline → especificación/ADR cuando proceda → parche mínimo → pruebas T0 contrato, T1 módulo, T2 fitness/corpus, T3 instalada, T4 durable/fallos, T5 release/soak cuando el impacto lo exige → recibo y commit.
- Mantener .agent/SESSION_POINTER.md como ÚNICO puntero de reanudación, .agent/WORK_JOURNAL.md append-only y .agent/TESTING-STATE.md para comandos/evidencia reutilizable. Si el puntero contradice Git/CI, se marca STALE y se corrige antes de trabajar.
- Cada cierre registra fecha UTC, base SHA, HEAD SHA, WU, decisiones, paths, test argv/exit/XML, hashes de artefactos, errores abiertos, evidencia caducada, próximo primer comando y motivo. La validación histórica no se reescribe.
- Los porcentajes se publican sólo para cohortes cerradas con denominador verificable (p. ej. WUs 2/6); si una WU está TBD, el avance global es NO_CALCULABLE.
- La secuencia puede evolucionar por descubrimiento respaldado por un ADR/recibo, preservando trazabilidad y gates. Ni un TODO ni un comentario de código prevalecen sobre una prueba ejecutada.
