# Roadmap ejecutable para el agente de PipelineK

**Repo:** https://github.com/Rubentxu/pipeline-kotlin  
**Corte de auditoría:** 2026-10-13, inspección estática de código y referencias GitHub (no ejecución local de Gradle).  
**Referencia actual:** `origin/main` = `05ce62361b4f75e86fa1cec1b26a0ac821ac0a21`; rama `pk-release-0-57-consolidation` = `e69c6c8f187fc340a32f1a825954424c4d5a771e`, 59 commits adelantada; última estable en GitHub `v0.56.0`, tag en `2dfe739657f6470d6ed22537da3a4c0e052040ee`. Revalidar todas estas referencias al empezar cada hito.

## Mandato y límites de propiedad

Actúa como mantenedor del producto, no como redactor de estados. Lee primero el código real y las pruebas del `HEAD` concreto. Usa `docs/` para conocer obligaciones, no para certificar hechos. PipelineK **interpreta/ejecuta pipelines, administra estado de ejecución, emite eventos y bytes observables, certifica y publica SDK/CLI/plugins**. No desarrolles Jenkins, scheduler distribuido, autenticación de flota, carrier worker, S3, gRPC de Fabric ni una segunda autoridad de control plane. Conserva puertos lectores segregados y ADTs exhaustivos; no rompas ABI sin protocolo de migración.

**Reglas innegociables de trabajo**

- Ciclo por hito: preflight Git/SDK/Gradle → diff y contratos vs specs → ADR si cambia semántica → implementación en commits atómicos Conventional Commits → tests focales y de falsación → full suite de integración/release → distribución **real** y consumidor externo → hashes/recibos verificables → push rama → PR → merge a `main` → tag anotado → GitHub Release con assets → verificación de instalación/descarga y SHA. Nada de tags locales como evidencia de publicación.
- No usar `git push --force` sobre `main` o tags ni reescribir releases anteriores. El commit de release debe corresponder a una versión producto inequívoca; verificar tag con `git rev-parse`, `git ls-remote --tags origin`, `gh release view`, `sha256sum` y build/version binaria.
- Si el gate queda rojo/omitido, **NO publicar una release de ese alcance**: subir commits y evidencias de bloqueo, conservar candidata o rama pero reportar `BLOCKED` (no `PASS`). Los tests obligatorios con `@Disabled`/`Assumptions` se cuentan como no ejecutados. La medición anterior no certifica un nuevo SHA.
- Pruebas quirúrgicas en desarrollo; gates completos al integrar/liberar. No reemplazar tests fallidos por mocks indulgentes. Falsadores sobre corrupción, duplicado, origen, drift de versión, cierre de canal y bytes reales.
- Cambios en el agente de Fabric: sólo mediante `PK_CONTRACT_HANDOFF` y solicitudes tipadas; cada repo cierra su propio PR/tag. No acoplar el motor a Fabric.

## Punto de partida: deuda auditada en código

1. `v2/pipeline-application/.../OutputProtocol.kt` acepta `output/v1` y `output/v2`, mientras `docs/adr/ADR-PK1-OUTPUT-FRAMING.md` define `pipelinek.output/2` y ejemplifica `separated-v2`; `Main.kt` negocia tomando `tokens.last()` en lugar de intersección de preferencias. Debe resolverse, no ocultarse con tests sintéticos.
2. El bundle `evidence/0-57-rc1` reconoce ZIP/SBOM hechos sobre `a95956b...`, un HEAD declarado `31619155...` y un ref de rama posterior `e69c6c8f...`; R3/R4/R5 quedaron pendientes. La certificación debe ligar **SHA de código, versión, ZIP, SDK/BOM, SBOM, plugins y resultados**.
3. `LaunchReceipt` identifica `Launched` tanto para `--db` durable como para in-memory, por decisión deliberada de `/1`; el consumidor no puede inferir recuperación a partir de ese único literal.
4. Hay puertas de admisión de plugins y metadatos de rangos oficiales ya implementados. Preservar y probar contra la distribución. Los ejemplos externos controlan su propio `apiRange`: no darles la política oficial como si fuera obligatoria.
5. `Main.kt` ya emite JSON incremental en la rama durable. Antes de proponer otra refactorización, medir RSS/heap y latencia contra streams de tamaño grande y consumidores lentos.

## PK-57A — Protocolo verificable, ejecución observable y RC inmutable

**Entrega de valor:** los consumidores pueden invocar PipelineK 0.57 sin ambigüedad en stdout/stderr, usando negociación real, errores tipados y correlación correcta; una distribución instalable pasa pruebas de contrato.  
**Release objetivo:** `v0.57.0-rc1` si no existe; si ya se publicó, usar siguiente `-rcN` y no retocar un tag. **Rama:** continuar `pk-release-0-57-consolidation` con PR a `main` sólo tras gates.

**Trabajo de implementación (una unidad larga, no doc-only):**

1. Preflight de `OutputProtocol.kt`, `Main.kt`, `ProcessOutputDecoder.kt`, `CliContractTestKit.kt`, `LaunchReceipt.kt`, `RunCorrelated` y adaptadores `RunObservationOutput`. Comparar **literal por literal** el wire id con el ADR; aclarar decisión versionada. Si se adopta alias de compatibilidad, registrar compatibilidad exacta y tests para alias viejo/nuevo; no inventar negociación ni cambiar default inadvertidamente.
2. Cambiar el negociador para respetar lista de preferencias aceptadas, prioridad y rechazo tipado cuando no hay intersección. Probar: opción repetida `[válido,inválido]` y `[inválido,válido]`, vacío, duplicados, ids desconocidos, priorización, salida stdout/stderr por cada modo. Ningún fallback silencioso.
3. Construir matriz **contra binario extraído del ZIP** para JSON, JSONL y TEXT; prueba de recibo ausente, doble o contradictorio, JSON inválido, error prelaunch y postlaunch, RunCorrelated y concurrencia. Comprobar byte-for-byte separación y contrato legacy sin `--output-protocol`. Distinguir tests de decoder sintético vs proceso real.
4. Incorporar tests del in-memory `LaunchReceipt` y la semántica de `--db`; si los criterios ADR difieren, resolver ADR primero. No exigir modo recuperable basándose en receipt exclusivamente.
5. En `v2/pipeline-release/`, ejecutar `releaseGate`: distZip, CycloneDX, `candidateAdmission`, tests de arquitectura/aplicación, 4 plugins oficiales y 3 externos. Añadir falsadores para duplicidad `pluginId`, apiRange incorrecto, vendor manifest ausente, versión divergente, `RunCorrelated` mal renderizado y contenido alterado del ZIP.

**Criterios de salida UAT/AAT:** protocolo de salida JSON/JSONL/TEXT **y bytes reales** coinciden con contrato; la CLI instalada rechaza un protocolo no soportado con exit 2 tipado; matrix 8 del ADR y pruebas adversariales pass; ningún artefacto stale; balance de PASS/FAIL/SKIP explicito. Gate local `:pipeline-release:releaseGate`, :pipeline-application:test y CLI instalada sin omitidos obligatorios.

**Docs autoritativos:** [`ADR-PK1-OUTPUT-FRAMING`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/docs/adr/ADR-PK1-OUTPUT-FRAMING.md), [`ADR-PK1-LAUNCHRECEIPT-MODES`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/docs/adr/ADR-PK1-LAUNCHRECEIPT-MODES.md), [`CLI_OBSERVABILITY_SPEC`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md), [`DISTRIBUTION_RELEASE_SPEC`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md), [`PK1-IDENTITY`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md), tests `ProcessOutputDecoderTest`, `DistributionMandatoryPluginsTest`.

**Cierre Git/release:** commits `fix(cli): ...`, `test(contract): ...`, `fix(release): ...`; push + PR, merge mediante política y tag RC **sobre el commit exacto construido/certificado**, GitHub prerelease con ZIP SDK, distZIP, SBOM, manifest y SHA256SUMS. Si el proceso de certificador es externo, RC se publica como **CANDIDATE_PUBLISHED**, no como certified. Remitir `PK_CONTRACT_HANDOFF` a Fabric y certifier.

## PK-57B — Certificación independiente y promoción de 0.57 estable

**Valor:** 0.57 deja de ser una rama funcional y se convierte en release consumible externamente con identidad y compatibilidad demostradas.  
**Release objetivo:** `v0.57.0` estable; ante cualquier corrección de código respecto a RC, primero nueva `-rcN` y nueva certificación del ZIP.

**Trabajo:**

1. Partir de ZIP de la RC, **no** de directorio de trabajo alterado; descargar de GitHub, reinstalar fuera del repo; comprobar manifest, ABI, SHA, versión del binario, BOM, módulos y plugins. Rechazar builds dependientes de Maven local no inmovilizado.
2. Completar R3 observación y recovery: `WURp023`, `UatS2R0`, smoke E2E, lease/owner, Unicode/UTF-8, channel split, persistencia y ejecución concurrente. Ejecutar **13 escenarios** del certifier independiente sobre exactamente ese `candidate_id` y reflejar `NOT_RUN`/`FAIL` como bloqueo.
3. Ejecutar smoke del consumidor publicado independiente (`examples/fabric-contract-consumer` sólo como una prueba adicional), más consumer externo Fabric contra la ABI. Verificar que el consumidor 0.56 no se rompe por cambios publicados en la línea 0.57 si se afirma soporte N-1; si rompe, negociación/versionado/refusal explícita.
4. Automatizar provenance: `source_sha`, tag, sha256 del ZIP, BOM, SDK, manifiestos y un resultado de certificación **firmado** o con autoridad de verificación documentada. No permitir autocertificación productor=certifier.
5. Normalizar estado operativo de docs: historial no se convierte en `current`; un solo punto que lee tags y recibos. No reescribir historia de releases.

**Criterio de corte:** todos los gates y 13/13 del certifier real PASS sobre los mismos bytes; Fabric entrega `FAB_CONSUMER_VERDICT` claro; PRs fusionados, `main` contiene commit de release; stable disponible en GitHub con assets resolubles y SHA idéntico. Si falta cualquiera, permanece `v0.57.0-rcN` y se reporta bloqueo.

**Refs:** [`STATUS del candidato`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/evidence/0-57-rc1/STATUS.md), [`HANDOFF-TO-CERTIFIER`](https://github.com/Rubentxu/pipeline-kotlin/blob/pk-release-0-57-consolidation/evidence/0-57-rc1/HANDOFF-TO-CERTIFIER.md), [`RELEASE_STRATEGY`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/05-roadmap/RELEASE_STRATEGY.md), [`ARTIFACTS_SUPPLY_CHAIN`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/ARTIFACTS_SUPPLY_CHAIN.md), [`FAB-SPEC-10`](https://github.com/Rubentxu/pipelinek-fabric/blob/main/docs/fabric-coordinated-evolution/specifications/FAB-SPEC-10-PAIR-RELEASE-ADMISSION.md).

## PK-58A — Lectura viva/recuperable y presupuesto de recursos

**Valor:** herramientas externas ven bytes y hechos de un pipeline **aún vivo**, se reconectan sin replay de efectos y soportan presión de salida sostenida.  
**Release:** `v0.58.0-rc1` o siguiente candidato libre; adaptar el número si la línea ha cambiado.

**Trabajo:** inventariar puertos ya disponibles antes de proponer interfaces. Reutilizar `OutputReadPort`, `OutputTailPort`, `OutputFrameIndex`, `EventTail`, codecs y `CommitWakeupPort` si existe. Introducir exclusivamente seams de lectura ausentes. Garantizar contenido parcial sin `\n`, separación stdout/stderr y ordinal, UTF-8 cortado entre frames, secretos divididos entre chunks con redacción **antes del commit**, reads multijvm no destructivos (`Open`, `Sealed`, `Unavailable`, `RecoveryNotCompleted`), wakeups como hint con recheck y polling seguro. Medir RSS/heap, FD, latencia p95 y bytes escaneados en streams pequeños y grandes; no inflar EventStore con un evento por byte. Sin thread/proceso por frame/página.

**Gates:** **UAT-PK-001..008** y **AAT-01..04** sobre binario instalado, con SIGKILL writer distinto de crash observer; ejecutar mismos tests como lector externo de Fabric. Contrato de capacidad y compatibilidad ABI publicado. Publicar `v0.58.0-rc1` con gates de producto y `PK_CONTRACT_HANDOFF` que anuncie sólo funcionalidades demostradas.

**Refs:** [`SPEC-01-PIPELINEK-LIVE-PLANES`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/specifications/SPEC-01-PIPELINEK-LIVE-PLANES.md), [`PK-SPEC-02-RUNTIME-OBSERVATION`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/specifications/PK-SPEC-02-RUNTIME-OBSERVATION.md), [`OBS_PROGRESSIVE_CONSOLE_ROADMAP`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md), [`PERFORMANCE_BUDGETS`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/PERFORMANCE_BUDGETS.md), [`PK UAT`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/acceptance/UAT.md), [`PK AAT`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/acceptance/AAT.md).

## PK-58B — Runtime introspectivo y recuperación segura como contrato publicado

**Valor:** Fabric puede preguntar a PK `Running / RecoveredTerminal / Lost / Unobservable`, decidir reattach/recover/cancel sin inferir resultado por exit code de un observador; clientes N/N-1 tienen respuesta explícita.  
**Release:** `v0.58.0` estable **sólo si** certifier independiente y Fabric aceptan `v0.58.0-rcN`; si no, próxima RC.

**Trabajo:** verificar qué tipos equivalentes ya resuelven `RuntimeIntrospectionPort`, `RuntimeControlPort`, `EventReadPort` antes de crear más; publicar los tipos mínimos **si** falta una capacidad observada por Fabric. Tabla ADT pura para `Start`, `Inspect`, `Reattach`, `Recover`, `Cancel`, `Refuse` y single-writer. No exponer `OperationJournal` interno ni reusar `EventStore` como autoridad de efectos. Reconciliar indicadores durables con `LaunchReceipt` sin modificar `/1` por sorpresa; negociar una posible `/2` en otra rama si hiciera falta. Certificar restart+reattach y salida/cancelación en run con efectos irreversibles; comprobar que el mismo attempt no ejecuta dos veces un deploy.

**Gates:** pruebas de recuperación fallida y autoritativa (no false-success, no double effects); UAT-SEC-008, UAT-PK-004/005, AAT-01/02; consumidor Fabric compila desde Maven publicado y trabaja contra CLI de Release descargada; `PAIR_CERTIFIED` requerido para reclamar interoperabilidad. PR/merge/tag stable/Release con recibos.

**Refs:** [`PK-SPEC-02`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/specifications/PK-SPEC-02-RUNTIME-OBSERVATION.md), [`RECOVERY_DURABILITY`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/RECOVERY_DURABILITY.md), [`DURABLE_KOTLIN_EXECUTION`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/DURABLE_KOTLIN_EXECUTION.md), [`PK coordinated roadmap`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/roadmap/PIPELINEK-ROADMAP.md).

## PK-59A — Retención verificable, SDK externo y seguridad del plano de datos

**Valor:** leer y retener output hasta que un consumidor confirme durabilidad, sin meter S3 ni Jenkins en PK; SDK sin ambigüedad de apiRange y telemetría que no afecta al motor.  
**Release:** `v0.59.0-rc1`, o siguiente RC disponible; estabilidad 0.59 sólo tras promoción en un bloque de certificación posterior.

**Trabajo:** habilitar `readRangeDigested`, semántica de rango/retention gap y pin/release del store **sólo si** Fabric acredita una falta del contrato actual. Asegurar retención ante caída del consumer, pin idempotente y no-prune antes de ACK durable; proteger redacción multi-chunk, split stdout/stderr, valores tipados `returnStdout`, SAN/escapes de logs al llegar a UI (presentación corresponde a Fabric). Benchmarks del reader (millones de frames y peticiones pequeñas), pruebas multi-proceso y ABI de plugins externos publicados; contribuciones oficiales con `PluginCompatibilityResource` generado y externos con rango derivado de SDK resuelto por su propia política. OTel opt-in sin poner exportador en el critical path.

**Gates:** UAT-AR-006/007 si se introduce retención, UAT-Q-001 si hay contextualización, UAT-SEC-001/002/006 y AAT-37/39/43; compatibilidad exacta del SDK publicado, suites y tests de mutación de artefactos. Entregar RC + handoff y pareja verificable con Fabric cuando necesite estas ABI.

**Refs:** [`SPEC-01`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/specifications/SPEC-01-PIPELINEK-LIVE-PLANES.md), [`PK-SPEC-03-LOCAL-PRESSURE`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/specifications/PK-SPEC-03-LOCAL-PRESSURE.md), [`SPEC-08-SECURITY-TELEMETRY`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/specifications/SPEC-08-SECURITY-TELEMETRY.md), [`STEP_PLUGIN_CERTIFICATION`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md), [`PK roadmap`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-coordinated-evolution/roadmap/PIPELINEK-ROADMAP.md).

## Secuencia para ejecutar TODOS los bloques en remoto

Usa comandos concretos adaptados a tu shell, con comprobaciones de permisos de publicación y PR. **No ejecutes este script a ciegas**; reemplaza los placeholders y respeta gates, branch protections y versiones libres.

```bash
git fetch --prune --tags origin
git status --short
git rev-parse HEAD
git log -1 --format='%H %cs %s'
# Verificar tag version, ABI y tests antes de publicar.
cd v2
./gradlew --no-daemon :pipeline-release:releaseGate
# ejecutar AAT/UAT instaladas y certifier realmente correspondientes al SHA
cd ..
git push -u origin "HEAD:refs/heads/$BRANCH"
gh pr create --base main --head "$BRANCH" --title "$PR_TITLE" --body-file "$RECEIPT"
# Sólo tras revisión + gates: merge PR conforme política (no push directo a main).
gh pr view "$PR_NUMBER" --json mergeCommit,state,mergeStateStatus # verificar estado real
git fetch origin
git switch main
git pull --ff-only origin main
# Si el release commit coincide exactamente con bytes certificados:
git tag -a "$TAG" "$RELEASE_SHA" -m "PipelineK $TAG — $HITO"
git push origin "refs/tags/$TAG"
gh release create "$TAG" "${ASSETS[@]}" --title "PipelineK $TAG" --notes-file "$RELEASE_NOTES" --prerelease
# Quitar --prerelease para stable (nunca usarlo para candidata).
gh release view "$TAG"
git ls-remote origin "refs/tags/$TAG"
```

**Obligatorio en cada cierre:** `RELEASE_RECEIPT` con `scope`, versión, rama/commit/parent, SHA Git actual, JDK/OS/Gradle, tests PASS/FAIL/SKIP, UAT/AAT y comandos+exit real, zip/sdk/sbom/checksums, ABI y wire changes, instalaciones verificadas, deuda residual, enlaces PR/merge/tag/GitHub Release, upstream consumer verdict y decisión `GO|BLOCKED`. En compatibilidad cruzada emitir `PK_CONTRACT_HANDOFF` y requerir `FAB_CONSUMER_VERDICT` / `PAIR_RECEIPT` según [`FAB-SPEC-10`](https://github.com/Rubentxu/pipelinek-fabric/blob/main/docs/fabric-coordinated-evolution/specifications/FAB-SPEC-10-PAIR-RELEASE-ADMISSION.md).

## Condiciones explícitas para NO avanzar

No promover 0.57 si el protocolo de salida no coincide con el contrato o el ZIP no corresponde a la evidencia; no declarar 0.58 runtime recuperable si recovery es inferido de stdout/silencio; no introducir features del control plane de Fabric; no liberar aunque 'tests verdes' si skips significativos. No multiplicar archivos de estado: usar el contrato verificable fuente→artefacto→comprobación como única evidencia actual.
