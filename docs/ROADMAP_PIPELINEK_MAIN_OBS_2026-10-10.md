# PipelineK · Plan integrado main + OBS-PC

**Corte de información:** 10 de octubre de 2026. **Destino:** instrucciones para agentes que trabajan en `Rubentxu/pipeline-kotlin` y dependencias externas SDDK/Fabric. **Estado del documento:** plan propuesto; NO constituye un receipt de ejecución ni altera el roadmap normativo.

## 0. Autoridad, información confirmada y límites

Fuentes de autoridad: código Git en SHA, `AGENTS.md`, ADR aceptados, `docs/v2/05-roadmap/ROADMAP.md`, `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`, `docs/v2/08-production-readiness/PRODUCTION_READY_GATE.md`, y veredictos reales. Los receipts históricos informan, pero sus resultados no se trasladan a otro SHA. El roadmap OBS es subordinado al roadmap principal; no se crea un segundo roadmap normativo.

**GitHub verificado al redactar:**

| Referencia | Estado |
|---|---|
| `origin/main` | `57774c9250a0da146a4d9eeb71648e85f48c2553` |
| `origin/par/cli-observation` | `37df79612779d278fb0a3bcaa41eef41e051b732` |
| Comparación `main...OBS` | Divergidas: OBS 71 ahead, 113 behind, 169 archivos en comparación |
| `origin/integrate/main-obs` | No existe en remoto |
| GitHub release estable más reciente | `v0.47.0` |
| Última prerelease pública | `v0.48.0-rc1` (commit de S6 `6e8e86bd`); 0.48.0 NO certificada estable |
| SDDK issue #12 | Abierta: `evaluate-gate` registra receipt y `transition` devuelve `ENGINE_MISSING_GATE_RECEIPT` |

**Datos del agente, no certificados independientemente contra el árbol local:** `integrate/main-obs` está en `c924af8cc6bbe0c7cc48fca4cadc2e3ebe300d7e`, con 1.515 ejecuciones de tests in-VM verdes acumuladas, 12 skips y un recibo local `docs/v2/07-uat/BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (sin push). Falta full `check --rerun-tasks` y UAT/AAT cross-process del mismo candidato; el agente observó saturación y cuelgues por host compartido. Es incorrecto inferir **un solo gate completo verde al SHA `c924...`** de la suma de ejecuciones parciales sobre varios commits. La salida de Detekt en `main` se detuvo por tres finales de línea ausentes y no ejecutó tests posteriores, según el agente; está pendiente corrección y SHA nuevo.

**Otros hechos de diseño a reconciliar:**

- En `main`, `ADR-0105-admission-check-publication-authority.md` significa *G10, harness externo*; en el OBS remoto, `ADR-0105-frame-index-ownership.md` significa *FileLock del índice*. Hay colisión de ID. El informe del agente sobre el **worktree local** menciona una `ADR-0106` en el recibo: **verificar si ya resolvió la colisión; no renumerar dos veces**. Mantener el documento histórico y una tabla de equivalencia, más prueba de unicidad de ADR en el árbol integrado.
- El OBS remoto conserva en `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` un destino `s6-plugin-sdk` obsoleto. El destino autoritativo es `main`, según ADR-0099. S6 está completamente en `main` por ascendencia Git.
- La aplicación `RedactingEventSink` / `ConsolePrintingEventSink` merece test canario *del binario instalado* en todos los recorridos: el orden de construcción del remoto OBS permite que `stream.accept(event)` procese el objeto original antes de llamar a `delegate.append(event)`. Los tests in-VM de redacción del informe no prueban por sí solos la superficie pública completa.
- El origen de los bloqueos de full check por alta carga **no está aislado concluyentemente**: existe evidencia de contención, no demostración universal de ausencia de defectos. Es imprescindible aislar runners, capturar thread dumps y timeouts y pasar UAT entre procesos; `nice` o umbral load <5 por sí solos no validan la corrección.

## 1. Reglas invariables de integración y release

1. **Una sola autoridad Git:** `main`. Las ramas de evolución y worktrees salen del `origin/main` actualizado; no fusionar ciegamente 169 archivos ni sustituir una rama completa por otra. Las resoluciones semánticas deben mantener ambas líneas de funcionalidad. Preservar historial publicado, PR explícita, sin squash ni force push. `git merge-base`, `git range-diff`, ABI, UAT.
2. **Cada bloque funcional termina en un artefacto remoto real si está admitido:** PR fusionada en `origin/main`, tag inmutable alcanzable desde ese main, ZIP + manifiesto + SBOM + checksums, GitHub **prerelease**, recibo con URL, SHA, digest y resultados. No etiquetar `DONE` si falta el release. Un bloqueo legítimo produce `BLOCKED` y conserva la rama/receipts; no inventar un release para cumplir el calendario.
3. **Distinguir candidato y estable.** La construcción/integración/prerelease pertenecen al repositorio; la certificación externa y promoción GA exigen harness `pipelinek-release-harness`, G10 check obligatorio, SHA y digest coincidentes, y autorización. Ausencia = `PRODUCT_GATE_STOP`; jamás publicar release estable ni Maven GA por inferencia local. No reintroducir GitHub Actions.
4. **Mismo SHA para el gate obligatorio:** resultado integral `cd v2 && ./gradlew check --rerun-tasks`, Detekt/API/ABI, tests runtime y UAT/AAT aplicables, `installDist`, reproducción y auditables en el SHA/tag candidato. Los parciales sirven para desarrollo, no sustituyen gate. Tests omitidos: `SKIPPED` con causa, no `PASS`.
5. **Seguridad/semántica:** redacción ANTES de persistir y ANTES de presentar, también `stdout` humano; event store, output store y Step typed values son autoridades distintas; no inventar orden total event/output; lectura pasiva sin recovery destructivo; cursores estables; memoria y backpressure acotados; fallos tipados y fail closed.
6. **SDDK:** cada bloque necesita WorkItem válido y transición real. Corregir `Rubentxu/software-development-decision-kernel#12` en el kernel, no en PipelineK. No `--no-verify`, no manipular ledger a mano, no dar por válida una transición que falla. Los WorkItems activos de ciclos CLOSED se reconcilian por operaciones SDDK auditable, no borrando historia.
7. **Higiene:** conservar staging/untracked de la sesión de `main` y el worktree OBS. Usar nuevo worktree limpio para gates/release; residuos operativos SDDK fuera del repositorio; registrar receipts normativos en `docs/v2/07-uat`, sin duplicar estado temporal.
8. **Versionado propuesto, no inventado:** las etiquetas `v0.48.0-rc2`, `v0.49.0-rc1..rc6`, `v0.49.0` son marcadores de planificación. Resolver la versión final conforme a SemVer, autoridad Gradle y tags existentes antes de crearla; no modificar ni reapuntar tags existentes.

## 2. Secuencia de bloques largos con entrega y release

### K0 — Reparar la autoridad SDDK (dependencia externa; antes de cualquier release PipelineK)

**Dueño:** agente del repositorio SDDK. **Salida:** corrección remota + release propio de SDDK, verificación con CLI instalada desde un proceso limpio; NO release de PipelineK.

- Reproducir issue #12 con mismo gate/ciclo/`plan_hash`; `evaluate-gate` escribe `gate_receipts`, y `transition` debe reconocer la fuente autoritativa; verificar proyección/ledger y persistencia tras reinicio. Diagnóstico causal: no confundir tabla persistida con evento autoritativo.
- Pruebas negativas de gate equivocado, recibo obsoleto, digest distinto, ciclo cerrado, concurrencia, caídas y replay idempotente. Resolver por separado el selector que ve WorkItems activos en ciclo CLOSED.
- Cerrar issue solo con pruebas reales, changelog y versión/kernel remoto. Los agentes PipelineK pueden seguir codificando y probando en worktrees mientras tanto, pero no falsificar admission.

**Gate:** `evaluate-gate → transition` PASS en misma identidad, con receipt persistido y proceso reiniciado, hooks reales sobre PipelineK. **Si falla, todos los releases sujetos a SDDK continúan bloqueados.**

**Referencia:** https://github.com/Rubentxu/software-development-decision-kernel/issues/12

### M1 — Cerrar el tren 0.48.0: SDK S6 + hardening C8

**Dueño:** agente `main`. **Valor:** base de SDK externa certificable, seguridad de workspace y distribución coherentes; independizar OBS.

- No repetir `check` en `57774c92` sin cambios: ya falló. Reconstruir los **tres archivos exactos** del XML/log, añadir solo newline final desde worktree nuevo, `:pipeline-domain:detekt --rerun-tasks` y revisión diff; corregir otros defectos solo si pruebas los reprodujeron.
- RP-034 es cierre histórico en `c06331af`; NO reabrir todo. Revalidar sus propiedades afectadas en candidato (Attached vs Managed, no-flag local-first, deleteDir/cleanWs, Gradle/Maven/Node/self-host). C8 `C8InstalledDistributionCanaryTest` con controles positivo/negativo. Ejecutar consumidor externo de plugin S6: Step, Block Step, Directive, Event, capability, composición SDK/BOM, ABI y admission.
- Gate total SHA exacto `./gradlew check --rerun-tasks`, installed canaries, distZip reproducible dos veces, manifest/SBOM/checksums, `STEP_PLUGIN_CERTIFICATION` y `CERTIFICATION_PROTOCOL`; revisar skips, SAST/SCA si aplica y ownership de G10.
- WorkItem/ciclo release SDDK válido; PR contra main, merge sin squash; tag sugerido `v0.48.0-rc2`, publicar GitHub prerelease y verificar descargas/digest. Solicitar veredicto externo del harness y G10 para **promover los mismos bytes a v0.48.0** cuando realmente corresponda.

**Release obligatorio:** `v0.48.0-rc2` PRERELEASE desde `main` cuando todos los gates locales/SDDK sean verdes. **Estable `v0.48.0` solo si harness y G10.** No incorporar OBS en M1.

**Referencias:**
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/05-roadmap/ROADMAP.md (RP-034, §13 B0–B4)
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/07-uat/RP034_ID_CERTIFICATION_AND_SECURITY_CORRECTION.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/07-uat/RP034_I_INSTALLED_MULTITOOLCHAIN_UAT.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/07-uat/CERTIFICATION_PROTOCOL.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0099-main-authority-release-candidates.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0105-admission-check-publication-authority.md

### O1 — Validar fusión OBS y publicar primer corte acotado (Bloque E / OBS-R1)

**Dueño:** agente OBS en `integrate/main-obs` local. **Valor:** OBS integrado en la línea autoritativa con contratos de observación y recuperación pasiva verificables; no declarar capacidad no certificada (OBS-2 B).

- Partir de `c924af8c` como hipótesis local, verificar SHA, cambios/no-committed, siete SHAs y recibo. Revalidar ambas bases `main` y OBS. Resolver colisión ADR-0105 **si el trabajo local no la resolvió ya como ADR-0106**, corregir destino a `main`, fijar tabla de provenance/decisiones y asegurar que `MainEventsCli --typed`, `ShExecution.ingestTranscriptIntoOutputPlane` y output sink tienen un solo camino durable.
- Elegir tests diferenciales que distinguen integración real vs auto-merge: `--typed`, `--format json`, códigos 0/1/2, `UNKNOWN` flags realmente inexistentes, ausencia de duplicación de output, `run` sin DB y con DB. Prueba canario de secretos mostrando consola instalada (print humano), JSONL, persistencia; errores de rendering no pueden impedir append durable; `returnStdout` sin duplicación o filtrado accidental.
- No heredar 1.515 tests sobre varios SHAs como certificación. Ejecutar los cinco fixtures de E1, suites focalizadas, los cross-process pendientes (ordinal, leases, kill JVM, recovery, follow, installed CLI, SSH/permissions si aplica), ENCODER-2/3 y FULL `check --rerun-tasks` sobre el SHA integrado definitivo.
- Remediar el runner: ventana de host realmente aislada (no solo `nice`); `--max-workers=1` y límite de JVM/forks; `timeout` por test; dumps de stack/thread y XML fresco; preferir runner dedicado si el host sigue saturado. Un test bloqueado es `BLOCKED`, no `PASS`.
- UAT instalada de lector pasivo y recuperación autorizada; sellado correcto, run y step sin pérdidas, no modificación por observadores. Migración/compatibilidad ABI y chequeo ADR duplicadas. Aislar funcionalidades OBS inacabadas del perfil anunciado, sin afirmar continuidad de hijo tras muerte JVM hasta O2.
- PR de fusión sobre `main` actualizado, preservando commits y resoluciones (no cherry-pick ciego, no squash); merge, tag y prerelease inmutable sugerida `v0.49.0-rc1` (o versión `-obs.1` si el tren lo decide explícitamente). Verificar ZIP, SBOM, checksums, `CandidateId`. 

**Release obligatorio:** primera prerelease OBS **solo si** gate total, UAT cross-process y SDDK admiten. **No publicar c924af8c hoy**: le falta full gate/cross-process. Si no se satisface, O1 permanece `BLOCKED` y sus cambios NO se declaran aterrizados ni released.

**Referencias:**
- Informe local no publicado `docs/v2/07-uat/BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` (agente, `c924af8c`; validar localmente)
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md (OBS-1, leyes A1–A10, STOP)
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-OBS-002-read-recovery-ownership.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-0105-frame-index-ownership.md (ID conflictivo en remoto; revisar ADR-0106 local)
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md

### O2 — OBS-R2: agente de ingesta productivo y supervivencia de stdout/stderr

**Valor:** el proceso hijo produce salida recuperable después de morir la JVM propietaria sin spool de secretos en claro. **Salida:** siguiente prerelease integrada en `main` (`v0.49.0-rc2`, sujeto a versionado real).

- Basarse en ADR-OBS-003 **aceptada**; no reabrir arquitectura salvo contraindicación ejecutable. Mover prototipo `ObsPc2IngestAgent` de test a implementación de un agente por run, propiedad de descriptores `stdout`/`stderr` con FIFO **real**, redacción en memoria, confirmación antes de exposición pública, control de EOF/`Sealed`, redriver del lifecycle, orphan reconciliation tras restart. No crear un segundo runtime.
- Ownership y estados ADT del agente, PID/lease, preparación antes del hijo, `Ready` real, terminación/cancelación explícita; diferenciar matar ejecutor de matar hijo; `trap '' PIPE` y compatibilidad de `yes|head`, `pipefail`, utilidades sensibles a EPIPE caracterizadas antes de anunciar paridad.
- UAT-R2-01..08 y AAT-R2-01..02 del contrato local; ensayo real `kill -9` de JVM → **50/50** líneas post-muerte, bytes de salida aumentan, run conserva semántica, sin secretos crudos en toda la raíz de almacenamiento, ningún nuevo efecto de Step en resume. Mutación FIFO→regular file y matanza del agente al matar JVM deben volver ROJO.
- `installDist` incluye realmente el agente (no depender del test classpath); ABI plugin SDK comprobada antes de cambiar contrato S6.

**Gate/release:** full suite + cross-process + reproducibilidad + release prerelease desde main; sin 50/50 y controles de seguridad, **STOP**.

**Referencias:**
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-OBS-003-child-output-descriptor-ownership.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/07-uat/OBS2_LEVEL_B_INGEST_AGENT_PROTOTYPE_RECEIPT.md
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md (OBS-2, `OBS-PC-208`)

### O3 — OBS-R3: índice paginado, costes y backpressure acotado

**Valor:** histórico grande consultable sin O(n) por página ni O(n²) agregado de append, memoria acotada y ejecución sin estrangulación por observadores lentos. **Salida:** prerelease desde main (`v0.49.0-rc3`).

- Baseline antes de fijar umbrales: frames/pages de 10^3, 10^5, 10^6; tamaño 200 MiB y 1 GiB según el perfil; throughput, CPU, heap, IO, latencia p50/p95, `force`/fsync. No inventar números antes de medir.
- `SegmentFrameIndex.framesOfRun` usa hoy lectura de todo el archivo antes de `filter/take`; `append` revisa todo el histórico por `sealTornTail`. Migrar a seek/checkpoints durables o índice equivalente **dentro del mismo Output Plane** y reanudable, sin introducir SQLite como segunda autoridad solo para evadir seek. Separar append normal de reparación post-crash, preservando lock cross-process y ordinales no duplicados.
- Batch/backpressure adaptativo sujeto a contrato y medición, no una nueva cola con pérdida silenciosa; secuencias monotónicas, corrupción/torn tails y restart fail-closed.
- UAT/AAT: paginación cerca del fin de histórico masivo, coste sublineal medido, memoria acotada, dos JVM con escritores/lectores, SIGKILL y reconstrucción, slow consumer y sin duplicación. No fijar umbrales por gusto: presupuesto ratificado desde baseline en receipt.

**Gate/release:** rendimiento con datos + full check + 200 MiB/1 GiB + prerelease desde main o `BLOCKED` explícito.

**Referencias:**
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md (OBS-3 PC-04/05)
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/07-uat/UAT_OBSERVABILITY_PERFORMANCE.md
- ADR índice de escritor: revisar numeración normalizada ADR-0106 local

### O4 — OBS-R4: CLI humana y agent-first, filtros y streaming veraces

**Valor:** `pipelinek observe`, `events`, `console` y `run` sirven a terminal/automatismos sin falsificar stream ni bloquear pipeline. **Salida:** prerelease desde main (`v0.49.0-rc4`).

- Reutilizar código ya presente (`MainObserveCli`, `MainConsoleCli`, `ObservationView`, query/format; 65 tests in-VM de contrato reportados). No reimplementar superficies que ya funcionan. Validar que `--view` y `--format` son ortogonales, AND entre dimensiones, OR dentro de repetidas, `--follow` cursor resume exactamente, `--range` contra `--after-cursor` se rechaza tipado; stdout JSONL puro y error solo stderr.
- Corregir frames UTF-8 partidos con decodificación stateful en text view y representación sin pérdida de bytes en machine wire; JSON no debe materializar una historia ilimitada. `full` debe rechazarse si no hay protocolo de intercalación honesto entre event sequence y output offsets (no orden total inventado).
- UAT instalada observando run largo: segunda JVM reconnect, `kill -STOP`/`CONT`, tail bounded, lector JSONL lento, truncación/corrupción reportada, dos canales, `--typed` de main y compatibilidad legado.

**Gate/release:** UAT-R4-02, AAT-R4-01/02 y full suite en el candidato; publicar prerelease real desde main.

**Referencias:**
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md (status PROPOSED: ratificar lo que se exponga como contrato GA)
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-0088-cli-observation-contract.md (status PROPOSED)
- https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md (OBS-4 PC-06/07/08)

### O5 — OBS-R5: consola por Run/Stage/Step, contexto y diagnóstico

**Valor:** experiencia real de consola y diagnóstico de fallos comparable a Jenkins sin mezclar eventos y bytes; consumo agent-first eficiente. **Salida:** prerelease desde main (`v0.49.0-rc5`).

- Usar el mismo read-side/output plane; enlazar contexto stage/step/run sin conservar transcript duplicado ni inferir resultado semántico desde logs. Proyección de error con tail acotado, status/failure tipado, continuidad, terminal/sealed/observer terminated distintos.
- Comandos `inspect --failed --context --log-tail --fields --format json` solo cuando implementados y probados; evitar declarar switches meramente documentados como capacidad. CLI textual y JSONL consistentes, redacción antes de presentar.
- UAT: pipeline de etapas paralelas, step concreto, reintento, fallo intencionado, log largo truncado con indicación explícita, reabrir desde segunda JVM, no duplicar contenido tras resume; mutaciones discriminantes de asociaciones stage/step.

**Gate/release:** instalado real + suite + prerelease desde main; no un alias superficial que combine archivos enteros.

**Referencias:** CLI_OBSERVABILITY_SPEC §6 y §7, OBS_PROGRESSIVE_CONSOLE_ROADMAP OBS-5, ADR-M1-output-authority.

### O6 — OBS-R6: consumidor Fabric/Jenkins verificado, sin introducir Jenkins en core

**Valor:** el contrato CLI/SDK/output ofrece un consumidor externo real que reutiliza Jenkins Stage View y consola con cursores, sin duplicar la autoridad. **Salida:** release prerelease del adaptador en `pipelinek-fabric` **y** prerelease de PipelineK (`v0.49.0-rc6`) solo si realmente ha cambiado su contrato/código.

- Contrato externo consumer-friendly: staged reads, cursors, event projection y output sealed/refusal; proteger ABI/compat y semántica del SDK. Fabric prueba reattach de **dos observadores sobre la misma run**, matar el inspector sin cancelar la run, reconectar y respetar Stage/Step. No hacer de Jenkins un runtime alternativo.
- La implementación Jenkins, Controller y adaptación a Stage View viven en `pipelinek-fabric` (repositorio separado), con sus propias pruebas/release. Core PipelineK ofrece solo APIs/contratos neutros.
- Compatibilidad CLI/SDK/Fabric comprobada sobre exactamente los mismos bytes y cursores; pruebas de backpressure, test UAT proceso externo y seguridades. Si no hay cambio PipelineK, registrar release del consumidor sin inventar bump de motor; para cierre de bloque cruzado exige release real en el proyecto que cambió.

**Gate/release:** candidate instalado + integración de ambos repositorios + releases trazables; mismo CandidateId si es la misma distribución, sin recompilar tras firmar.

**Referencias:** OBS_PROGRESSIVE_CONSOLE_ROADMAP OBS-6, CLI_OBSERVABILITY_SPEC, ROADMAP §13.5 (RP-8/9 pertenecen a Fabric).

### O7 — OBS-R7: certificado de producto, 0.49 estable e integración final

**Valor:** una distribución OBS completa con garantías A/B, lectura segura y escalable, CLI/SDK y Fabric demostrados; entrenamiento de release cerrado. **Salida:** `v0.49.0` estable **solo tras** harness real y G10.

- Revalidar EN LA MISMA candidata AAT/R1–R6, Step SDK, ABI, formatos, seguridad, kill/recovery, 200 MiB/1 GiB, JVM crash, con benchmarks y dos repositorios reales.
- Verificar ZIP reproducible e inmutable, SBOM, SHA256, provenance, instalar desde release descargada, run/failure/resume y consumidor independiente. Publicar candidate prerelease final si existen fixes posteriores.
- Harness externo produce veredicto atado al digest y SHA; check G10 publicado y requerido en protección Git; solo entonces promover exactos bytes a estable. Si G10 continúa ausente: `BLOCKED_EXTERNAL`, conservar última prerelease; **no declarar GA**.
- Actualizar roadmap único y ledger de deuda con estado resultante, no reescribir receipts históricos.

**Referencias:** `CERTIFICATION_PROTOCOL.md`, `PRODUCTION_READY_GATE.md`, ADR-0099, ADR-0105 de main, OBS_PROGRESSIVE_CONSOLE_ROADMAP OBS-7.

### M2 — Evolutivos posteriores `main` B5–B7, tras OBS-R7

**Siguiente línea:** agent-first/secretless B5 → hardening de producción B6 → retirada documentada de V1 B7, conforme al **orden único** de `ROADMAP.md` §13. No adelantar eliminación V1 hasta equivalencia probada. Agrupar cada uno en release propio (p. ej. `v0.50.0-rc1`, siguiente tren al aprobar versión), integración PR main, nueva suite y certificación; no mezclar con 0.49 antes de cumplir sus gates. RP-8/9 (control plane y Jenkins) fuera de este repositorio.

## 3. Paralelismo permitido y secuencia de promociones

```text
                         ┌── K0 · SDDK #12 → release SDDK ──────────┐
main 0.48:  freeze ───────┤                                         ├─ M1 → 0.48 RC → GA si G10/harness
                         └─ gate local M1 (sin OBS) ────────────────┘
                                                                     │
OBS local:  O1 cross-process/adr/merge preparado ────────────────────┼─→ O1 PR main + 0.49 RC1
                                                                     │
OBS después de O1:                                                   └─→ O2 RC2 → O3 RC3 → O4 RC4
                                                                              → O5 RC5 → O6 RC6 → O7 0.49 GA
main ulterior:                                                                  → B5 → B6 → B7
```

M1 y preparativos locales O1 pueden ejecutarse en paralelo; **las operaciones sobre `origin/main`, tags y versiones se serializan**. O2 puede investigar/preparar pruebas sin publicar mientras O1 está bloqueado, pero **no se puede cerrar O2 por release antes de resolver O1**, ni arrastrar varios bloques ocultos bajo un único cambio gigante.

**No elegir ahora** la opción del agente «§0.3/§8 autorizado → push/merge/tag sobre c924af8c» porque el full gate y UAT cross-process están incompletos. Tampoco dar por cierre de Bloque E la suma 1.515 verde. La elección adecuada es **opción 4**: desbloquear entorno y gobierno, certificar E, y solo después autorizar/efectuar la publicación conforme a gates. Se autoriza preparación técnica y correcciones locales autónomas, **no una publicación no admitida**.

## 4. Plantilla universal de release y receipt (aplicable a cada bloque)

Cada agente entrega por bloque:

```yaml
block_id: O1
base_main_sha: <full_sha>
source_branch_sha: <full_sha>
merged_main_sha: <full_sha_or_BLOCKED>
source_tree_sha: <full_sha>
sddk_cycle_work_item: <cycle>/<work_item>
commit_history_preserved: true_or_BLOCKED
functional_scope: <profile_verified>
checks:
  - command: "cd v2 && ./gradlew check --rerun-tasks"
    exit_code: <0_or_number_or_NOT_RUN>
    xml: <path>
    tests: <number>
    failures: <number>
    errors: <number>
    skipped: <number_and_classification>
  - installed_binary_uat: <argv/exit/logs>
  - xproc_kill_resume: <UAT IDs/results>
  - security_redaction: <canary/negative tests>
  - abi_and_reproducibility: <tests>
artifact_zip_sha256: <64_hex_or_NOT_BUILT>
manifest_s_bom_checksums: <paths_or_NOT_BUILT>
tag: <immutable_tag_or_NOT_PUBLISHED>
release_url: <real_url_or_NOT_PUBLISHED>
external_harness_verdict: <PASS_FAIL_NOT_RUN_BLOCKED>
g10_check: <PASS_FAIL_NOT_RUN_BLOCKED>
decision: <CANDIDATE_PUBLISHED|CERTIFIED|STABLE_PROMOTED|BLOCKED|FAIL>
blockers: <specific evidence>
```

Un `BLOCKED` no se convierte en `PASS` porque el agente complete un TODO, produzca documentación o porque el host esté cargado. La salida es auditable y recuperable; si un test no se ejecutó, se conserva `NOT_RUN`.

## 5. Instrucción inicial a los agentes

**Agente `main`:** usar M1. Leer primero ADR-0099, ADR-0105 G10, roadmap B0–B4, RP-034 y receipts C8; recuperar SDDK tras K0. En worktree limpio aplicar corrección de newline de Detekt (sin tocar el árbol staging), ejecutar gate focal y luego full gate SHA definitivo, UAT instalada y SDK consumidor externo; integrar y publicar RC2 **solo con gates válidos**. No comenzar funcionalidades ni integrar OBS.

**Agente OBS:** usar O1. No perder ni reescribir `c924af8c`; verificar localmente receipts y colisión ADR; aislar la batería cross-process en un entorno no saturado y demostrar test real (no especular sobre wedging). Cerrar canario de redacción de consola, compat main+OBS y ABI. Completar E2 y ENCODER-2/3, después PR sin squash hacia `main`, con tag/prerelease. Una vez O1 liberado, ejecutar O2→O7 en el orden autorizado; cada bloque requiere release remoto real o permanece blocked. Evitar parada innecesaria por requests de aprobación para cambios de código dentro del alcance; las acciones remotas solo se realizan cuando sus gates y permisos lo permiten.

**Agente SDDK:** usar K0 y la issue #12; resolver flujo de receipt/transición + colisión WorkItems del ciclo cerrado en el kernel y publicar su corrección sin manipular PipelineK.

## 6. Índice normativo de referencia

- [Roadmap principal: `ROADMAP.md`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/05-roadmap/ROADMAP.md) (§13 B0–B7; RP-034; release authority).
- [OBS-PC roadmap vigente en rama OBS](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md) (estado antiguo a reconciliar, OBS1–OBS7, A1–A10).
- [CLI Observability specification](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md) (**PROPOSED** al corte).
- [ADR-OBS-002 passive read/recovery](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-OBS-002-read-recovery-ownership.md).
- [ADR-OBS-003 child output agent](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-OBS-003-child-output-descriptor-ownership.md).
- [ADR-0088 CLI observation](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-0088-cli-observation-contract.md) (**PROPOSED** en remoto).
- [ADR-0099 release authority](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0099-main-authority-release-candidates.md) (**ACCEPTED**).
- [ADR-0105 G10/admission](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0105-admission-check-publication-authority.md) (**ACCEPTED**, autoridad de main).
- [ADR-0105 OBS frame ownership](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/04-adrs/ADR-0105-frame-index-ownership.md) (**ID colisiona**; comprobar ADR-0106 local).
- [Certification protocol](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/07-uat/CERTIFICATION_PROTOCOL.md).
- [Production ready gate](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/08-production-readiness/PRODUCTION_READY_GATE.md).
- [RP034-I installed multichain](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/07-uat/RP034_I_INSTALLED_MULTITOOLCHAIN_UAT.md).
- [OBS2 ingest prototype receipt](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/07-uat/OBS2_LEVEL_B_INGEST_AGENT_PROTOTYPE_RECEIPT.md).
- [OBS performance UAT](https://github.com/Rubentxu/pipeline-kotlin/blob/par/cli-observation/docs/v2/07-uat/UAT_OBSERVABILITY_PERFORMANCE.md).
- [SDDK external gate defect #12](https://github.com/Rubentxu/software-development-decision-kernel/issues/12).

**Nota de trazabilidad:** este plan está escrito desde las referencias remotas verificables y el informe aportado por el usuario. No se ha ejecutado Gradle ni inspeccionado el worktree local del agente desde esta conversación. Toda afirmación sobre `c924af8c` y sus receipts es reportada por el agente y debe auditarse en su entorno antes de certificarla.
