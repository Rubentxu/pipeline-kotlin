# SPEC-01 — PipelineK: lectura viva, output/events y recuperación

**Owner:** pipeline-kotlin · **Prioridad:** P0 · **Entrega:** M1 (prevalidación en M0). **ADRs:** ADR-01, ADR-02. **Referencias:** `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` OBS-1..OBS-7, ADR-0085/0086/0088, `OutputReadPort`, `OutputFrameIndex`, `EventStore`, `OutputTailPort`, `DurableShellExecutor`.

## Resultados de producto

Un `pipelinek run` autónomo ofrece salida y hechos antes de terminar el proceso. Un observador CLI/local y el adapter de Fabric leen el mismo store y tienen cursores independientes. Tras crash puede reanudar o informar imposibilidad sin corrupción ni duplicado. No se introducen dependencias de Fabric, Jenkins, S3, Kubernetes, gRPC o ELK en el core.

## Requisitos

**PK-L1:** los bytes observables salen de `Output Plane`, incluidos fragmentos sin `\n`; stderr y stdout se atribuyen por canal, con ordinal observado. No duplicar altos volúmenes en EventStore. `returnStdout` es valor tipado no exportable implícitamente.

**PK-L2:** read side externo no puede efectuar recovery destructivo contra escritor vivo. Lectores multijvm prueban `Open`, `Sealed`, `Unavailable` y `RecoveryNotCompleted` sin transformar uno en otro.

**PK-L3:** los puertos de lectura y follow usan cursors/offsets durables con páginas acotadas. Registro de wakeup anterior a última lectura, recheck + polling de respaldo. Wakeup es hint coalescible; pérdida o doble wakeup no afecta corrección.

**PK-L4:** `OutputFrameIndex` obtiene páginas posteriores a ordinal sin escanear/decodificar todo run. Si se plantea índice nuevo, benchmark comparativo y migración sin pérdida. Lecturas de UTF-8 entre frames reconstruyen caracteres completos de forma incremental; evitar `String` por chunk en el core.

**PK-L5:** captura por pumps independientes y redactor streaming antes de commit. Redactor necesita lookahead para secretos partidos; no eliminarlo por bajar latencia. Buffer bounded; presión del disco se decide aparte, no se propaga desde renderer.

**PK-L6:** `OperationJournal` conserva autoridad de recovery; EventStore y salida no sirven para inferir replay seguro. Terminal de run no significa EOF de todos los output streams; sellado independiente.

**PK-L7:** añadir contexto de operación/stage/step a referencias o join estable sin reescribir el payload, de modo que filtrar por etapa+stderr no devuelva vacío injustificadamente. Joins explícitos, nunca `event?.stage` como aproximación de output.

## Interfaces mínimas

Reusar `OutputReadPort`, `OutputTailPort`, `OutputFrameIndex`, `EventStore`. Publicar solo read/cursor/capability necesarios para consumidor externo; no hacer pública la clase interna `SegmentOutputStore` por comodidad. Si falta un seam para notificaciones, introducir un `CommitWakeups` read-side opcional. `Flow`/canal solo transporta notificaciones, no la fuente de verdad.

## No funcionales

- Memoria del lector y pump acotada con tamaños medidos en bytes.
- Sin tareas de red, regex, renderizado, archivo ni búsqueda en hot path.
- No meter cambios masivos en el coordinador durable; aplicar adapters/handlers.
- No crear dos ficheros con la misma salida por compatibilidad.
- Persistencia local falla con resultado diagnosticable si se agota el recurso obligatorio.

## Cierre M1

UAT-PK-001..008 y AAT-01/02/03/04, artefacto SDK publicado con ABI/conformance; Fabric resuelve exactamente ese SDK e implementa primer follower live. Baseline comparar latencias y RSS. Rollback del adapter no hace ilegible el store local.
