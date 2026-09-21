# Especificación propuesta — ITO: Kotlin CLI, Git, YAML, runners, UAT y Step

**Estado: PROPOSED/NOT_IMPLEMENTED.** ADR-0094, plan INTELLIGENT_TESTING_ROADMAP.md y matriz INTELLIGENT_TEST_ORCHESTRATOR_UAT.md. No reemplaza AGENTS.md, STEP_PLUGIN_CERTIFICATION.md, PIPELINE_TEST_HARNESS.md ni CERTIFICATION_PROTOCOL.md.

## 1. Separación y contratos
Kernel Kotlin JVM independente de pipelinek: ChangeSource, PolicySource, ImpactResolver, TestCatalog, PlanBuilder, EvidencePolicy. API pura plan(ChangeSet, Policy, Catalog, History) → TestPlan; tipos explícitos para PlanReady, NoMatchingTests, UnknownImpact, UnsupportedSelector, InvalidConfig. testing-execution aporta RunnerAdapter, ProcessSupervisor, ResultParser, UatOracle y EvidenceSink; testing-cli conecta Git/YAML/OS; testing-pipelinek-plugin depende del kernel mediante Step SDK público, nunca al revés. Mantener pocos módulos físicos, directorio/build autónomo fuera de v2/settings.gradle.kts. La CLI debe arrancar incluso cuando v2 no compile.

Plan tipado y auditable: ID/revision de snapshot, argv vectorizado, cwd, env allowlist, selector original y selector traducido, ID test/UAT, reason y path de contrato/dependencia que justificó la inclusión, HF requerida, timeout/costEstimate provenance, obligatoriedad, exclusiones explícitas y UNKNOWN. No usar un comando como sustituto del análisis de impacto. Salida legible + JSON machine-readable para CLI/agentes.

## 2. Git: snapshots y alcance
En dev unir diff desde WU base o merge-base de PR: commits, index, worktree, untracked, rename/delete, lockfiles, manifest, YAML de testing, inputs de código generado y cambios de build/CI. HEAD no identifica por sí solo un worktree sucio. Para tests reusables, fingerprint de contenido de inputs relevantes; registrar snapshot antes/después, invalida corrida si el árbol cambia concurrentemente. Base SHA definido explícitamente; diferencias respecto al último commit NO capturan toda la WU. Gate integración/release sobre checkout limpio e inmutable y SHA + tree + artifact sha256 exactos. Repos cloned/worktrees aislados por identidad estable sin guardar credenciales de remote URL.

Mapeo ownership de paths a componentes, contratos semánticos y reverse consumers. Changeset de Step acotado → suite directa y canario instalado si verifica comportamiento de Step; cambio de events/DSL/coordinator/build ⇒ cierre transversal + UAT/fitness afectadas. Archivo nuevo sin owner/relación, cambio de configuración de testing o relación no conocida ⇒ ensanchar conservadoramente; nunca plan vacío verde. Historial p50/p95 y fallos sirve para **priorizar**, no para decidir que un test exigido deja de serlo.

## 3. YAML v1 y adaptadores multilenguaje
Único fichero opcional testing.yaml versionado por proyecto: apiVersion, project, git, runners, components, contracts, profiles, gates, uat, state. Validar schema con reject de unknown fields en zonas de seguridad/gates, duplicate IDs, ciclos extends, referencias inexistentes y rutas fuera de scope; YAML safe parser con límites de tamaño/aliases, sin instanciación arbitraria de tipos. Una reducción del conjunto de UAT obligatorias requiere revisión explícita de política, nunca ocurre por merge implícito de perfiles.

Runner v1: gradle (configura wrapper/cwd/task y selectores reales) y command (argv explícito por perfil); v2 adaptadores Maven/JUnit, pytest, Jest/Vitest, Cargo, Go **solo conforme a demandas reales**. Cada adaptador declara capacidades de selección module/class/method/file/tag/none, expansión mínima cuando no soporta el selector, directorio y env, timeout y parser de resultados (JUnit XML, JSON o protocolo estructurado). No introducir shell -c ni evaluación libre de plantillas en configuración; scripts custom versionados son código confiable sujeto a revisión. Exigir número real de tests >0 cuando la intención es probar; XML fresco si obligatorio; no interpretar '--tests !patrón' como exclusión Gradle sin prueba de IDs ejecutados.

Propuesta ilustrativa, no implementación:
~~~yaml
apiVersion: testing.pipelinek.dev/v1
kind: TestingPlan
project: {name: pipeline-kotlin, root: .}
git: {baseRef: origin/main, include: [committed, staged, unstaged, untracked]}
state: {mode: local}
runners:
  gradle: {type: gradle, executable: ./gradlew, workingDirectory: v2}
  custom: {type: command, workingDirectory: tools/example}
components:
  events:
    paths: ["v2/pipeline-events/src/**"]
    runner: gradle
    testTargets: [":pipeline-events:test"]
    contracts: [event-model]
contracts:
  event-model:
    consumers: [application]
    tests: ["*JsonEventLogTest", "*Replay*Test"]
profiles:
  dev: {selection: changed, include: [direct, affected-contracts]}
  verify: {selection: affected-closure, include: [direct, consumers, relevant-uat]}
  integration: {selection: complete, requireFreshEvidence: true}
  release: {extends: integration, selection: complete, requireFreshEvidence: true}
~~~
Todos los campos/semánticas son PROPUESTOS. Tests/paths y contratos concretos deben validarse contra repositorio actual al implementarlos.

## 4. Estado operativo y ausencia de morralla
**Solo testing.yaml, fixtures y código versionados en Git; ningún estado/cache/resultado generado por ITO en el directorio de proyecto.** Linux: XDG_STATE_HOME/pipelinek-testing, XDG_CACHE_HOME/pipelinek-testing y XDG_RUNTIME_DIR (si seguro) o tempdir OS. macOS/Windows: ubicaciones convencionales. Parámetros --state-dir/--cache-dir/--artifacts-dir externos al repo para CI; mode ephemeral no persiste, local con retención/GC, ci con temporal + export de artefactos declarados. Backend MVP JSON/manifest por run, escritura atómica y bloqueo breve entre procesos o detección de conflicto; no introducir SQLite/servicio obligatorio. Hash de inputs, logs redacted y ACL restringidas; no grabar secretos en logs, manifests, URLs remotas ni cachés.

La herramienta garantiza ausencia de **sus propios** outputs en proyecto; Gradle/Maven/pytest pueden escribir build/ o .pytest_cache por cuenta propia. Para UAT que requieran repo intacto, ejecutar runner en copia/worktree temporal **fuera** del proyecto y redirigir HOME/TMP/build-cache/report a ubicaciones seguras; comprobar git status/snapshot antes y después. No presentar un worktree como contenedor de seguridad para código no confiable.

Evidence store guarda test ID, config/test/code/toolchain/dependency/environment fingerprints, resultado, duración, ubicación/digest de XML/log, estado SELECTED/EXECUTED/REUSED/SKIPPED/NOT_RUN. Reutilizar local GREEN solo en dev con fingerprints completos, inputs externos controlados y política explícita; un cache-hit NO se cuenta como "ejecutado ahora". Integration/release ejecutan fresh gate conforme contrato, no heredan verde por parecido con HEAD antiguo.

## 5. Ejecución eficiente y segura
Comandos previstos: testing plan --profile dev --base <WU_BASE>, testing dev, testing verify, testing gate --profile integration|release, testing explain <id>, testing doctor. El CLI no arranca pipelinek. Una iteración: plan/change → tests mínimos → feedback; cierre WU: closure de consumidores + UAT relevantes; frontera integración/release: conjunto obligatorio completo sin omisiones. Agrupar invocaciones por runner y preparar artefactos instalados solo para HF2+; separar con medición los tests puros de los que dependen de installDist. Cache/daemon Gradle warm en local; CI cache claveada por lockfiles/Gradle/JDK, sin sacrificar reproducibilidad. No ejecutar bare :pipeline-application:test por defecto ante un fichero pequeño si existe selección fina defendible.

ProcessSupervisor usa ProcessBuilder(argv), cwd explícito, stdout/stderr drenados **concurrentemente antes y durante wait**, límites de captura/backpressure, timeout monotónico, cancelación y kill/cleanup de descendientes, diagnóstico thread-dump/PID/último test activo cuando sea posible. Prohibido waitFor() seguido de readText() en pipes que pueden llenarse. Separar ASSERTION_FAIL, NONZERO_EXIT, TIMED_OUT, CANCELLED, INFRA_ERROR, NO_TESTS, MISSING_REPORT, PROCESS_LEAK y STALE; no reintentar ciegamente ni deshabilitar tests para forzar verde. Agregador de shards requiere IDs esperados cubiertos exactamente una vez, sin missing/cancelled.

## 6. UAT first-class y resultados
UAT schema: ID estable, perfil obligatorio/opcional, componentes/contratos, fidelidad HF0..HF6, precondiciones/capacidades, setup, runner + argv, oráculo, timeout/recursos, cleanup ALWAYS, evidence references. Máquina sencilla setup → execute → assert → cleanup, sin reinventar DSL de pipelines; para asserts complejas, verificador externo versionado. Oráculos nativos: exit, fichero presente/ausente y hash de bytes finales, JSON field/value, evento/order/outcome, stdout/stderr redacted, counter/marker de ausencia de efecto y before/after/restart. Un exit 0 sin oráculo suficiente NO certifica escenario. Una UAT real puede usar JUnit/pytest/scripts existentes: ITO registra IDs/resultado, no sustituye sus assertions.

Distinciones: UAT del motor en repos Git temporales y dos lenguajes; UAT del plugin real instalada (HF2+, C01..C19/G0..G8/Event Harness); UAT de consumidor siguen perteneciendo a cada producto (UAT-RP-001..027 intacta). HF se usa para escoger el entorno fiel, selección por impacto para escoger **qué** escenario ejecutar. Resultado VERIFIED_SCOPED no es CERTIFIED. T0..T5 y STEP-CERT/PRODUCT-GATE/CHANNEL-GATE mantienen su significado vigente.

## 7. SDK externo y semántica durable
En RP-7, plugin testing.verify(config, profile, baseRef) devuelve TestRunResult tipado y eventos; façade DSL vía registryStep/StepDefinitionContributor; manifest, identidad/digest, capability admission antes de cualquier proceso, codecs, output typed, cancelación, Event Harness y ejecución real de plugin independiente sin per-Step cambio en core. Efectos del runner y cache no pueden disfrazarse de puro/siempre memoizable; diseñar y ensayar replay, intentos/reanudación, no duplicación oculta y coherencia de resultados para el Step antes de certificarlo.

## 8. Adopción y medición
Primero shadow mode: comparar selecciones de tres cambios representativos (Step, event codec, DSL) con matriz de tests y full gate, registrar false negatives/misses y tiempos cold/warm/primer fallo. No activar ITO en required checks hasta certificarlo y aprobar migración; mantener CI actual íntegro durante la comparación. Sin números objetivos de performance hasta medir baseline reproducible. Referencias operativas: INTELLIGENT_TESTING_ROADMAP.md e INTELLIGENT_TEST_ORCHESTRATOR_UAT.md.