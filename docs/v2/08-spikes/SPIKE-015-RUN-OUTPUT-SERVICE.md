# SPIKE-015 — Run Output Service: salida durable, segura y observable

- **Estado:** propuesta de spike; no autoriza implementación.
- **Fecha:** 2026-09-02
- **Ámbito propuesto:** ML-R11 / L-11, después de cerrar ML-R10. No se incorpora a
  ML-R10 porque éste es una extracción de credenciales con cero cambio de
  comportamiento.
- **Decisión que se busca:** aceptar o rechazar el contrato de salida como puerto
  transversal y su primer adapter local. No crear aún un servicio remoto.

## Resumen ejecutivo

Sí conviene tratar la salida de procesos como una capacidad transversal, asíncrona
y durable. Pero **no** como un microservicio independiente ahora: el roadmap sigue
en ejecución local y el event log no puede contener cuerpos de log ilimitados. La
unidad correcta es un puerto `RunOutputStore` con un adapter local segmentado; más
adelante el gateway y Jenkins lo consumirán. Separarlo por red hoy añade una nueva
consistencia, autenticación y operación sin resolver ninguna necesidad del ML.

El contrato debe conservar `stdout`, `stderr` y `system` como canales distintos;
redactar antes de persistir o publicar; proveer replay paginado por cursor opaco y
suscripción caliente reanudable; y renderizar ANSI solamente en el borde. El
resultado de `sh(returnStdout=true)` permanece como resultado explícito del step,
no se vuelve a publicar automáticamente mediante la API de logs.

## Evidencia y límites V2

La arquitectura ya ordena que los logs grandes sean `LogChunk`/stream storage con
offsets y referencias, no eventos completos, y exige redacción antes de abandonar
el worker con defensa adicional en gateway/controller
([OBSERVABILITY §Logs](../02-architecture/OBSERVABILITY.md)). `EVENT_MODEL` prohíbe
secretos y cuerpos de log no acotados, y distingue evento de dominio, métrica y
stream humano ([§4 y §8](../03-specifications/EVENT_MODEL.md)). El protocolo prevé
cola acotada, prioridad control > estado > logs y spill a storage
([WORKER_PROTOCOL §9](../03-specifications/WORKER_PROTOCOL.md)).

La implementación actual ya tiene la base durable, no la sustituyamos: 
`DurableShellExecutor` escribe `jenkins-log.txt`; para `returnStdout` guarda stdout
en `output.txt` y deja stderr en el log. ADR-0046 establece el patrón equivalente a
durable-task: `script.sh`, resultado atómico, log a fichero, heartbeat y cookie,
sin pipear la salida del proceso al JVM
([ADR-0046](../04-adrs/ADR-0046-local-ecosystem-first-reprioritization.md)).

La redacción actual tampoco debe duplicarse con otra semántica. ADR-0049 ya exige
`SecretHandle` en el límite `ProcessBuilder` y un `RedactingEventSink` para texto
libre antes de persistir ([D1](../04-adrs/ADR-0049-credentials-local.md)). El
servicio propuesto extiende esa garantía a la salida de proceso: el dato sin
redactar no puede llegar al fichero de segmentos, buffer, suscriptor, exportador ni
proyección.

## Qué reutilizar del código anterior y qué no

| Evidencia V1 | Decisión V2 |
|---|---|
| `core/.../logger/ProcessLogger.kt` drena stdout y stderr concurrentemente en `Dispatchers.IO`. | Reusar el principio de **dos drenadores independientes** si se usa pipe; no mezclar canales ni bloquear uno esperando el otro. |
| `DefaultLoggerManager` y `BatchingConsoleConsumer` tienen cola MPSC, consumidores aislados, batching y métricas de drops. | Reusar colas acotadas, aislamiento y telemetría de pérdida. Rechazar su fallback a consola para eventos críticos: podría eludir la redacción. |
| `ConsoleLogFormatter` formatea ANSI por nivel. | Es formato de logger, no parser seguro de ANSI de proceso; no usar para construir HTML ni como modelo canónico. |
| `core/.../steps/Shell.kt` mezcla stdout/stderr en `returnStdout`, imprime salida cruda y construye `sh -c`. | **RETIRE / no depender**. Rompe separación de canales, puede filtrar secretos y contradice el contrato durable de ADR-0046. |

## Lecciones externas verificadas

1. Jenkins durable-task persiste una ubicación de lectura y lee sólo el intervalo
   nuevo; durable-task-step combina watch/push, fallback a polling y termina con
   fallo explícito si la vigilancia no confirma la salida. Es una buena semántica
   de reconexión, aunque su lectura puede reservar un bloque grande: V2 debe usar
   chunks acotados. [FileMonitoringTask](https://github.com/jenkinsci/durable-task-plugin/blob/8dcdaa8d6d3fc9a7f39bd3d2014291866a67f82a/src/main/java/org/jenkinsci/plugins/durabletask/FileMonitoringTask.java#L265), [DurableTaskStep](https://github.com/jenkinsci/workflow-durable-task-step-plugin/blob/46cb22ff3686c9e427aab1096bd255489b003140/src/main/java/org/jenkinsci/plugins/workflow/steps/durable_task/DurableTaskStep.java#L235)
2. Jenkins `LargeText` usa posición progresiva; el issue de CloudWatch documenta
   por qué offsets de bytes no escalan para backends remotos. La API V2 debe usar
   cursor opaco, aunque el adapter local lo traduzca internamente a offset.
   [LargeText](https://github.com/jenkinsci/stapler/blob/master/core/src/main/java/org/kohsuke/stapler/framework/io/LargeText.java), [análisis CloudWatch](https://github.com/jenkinsci/pipeline-cloudwatch-logs-plugin/issues/155)
3. Jenkins aplica `ConsoleLogFilter` antes del log; credentials-binding registra
   variantes de secretos, prioriza las más largas y enmascara en stream. Es el
   antecedente directo para redacción antes de almacenamiento y fan-out.
   [ConsoleLogFilter](https://github.com/jenkinsci/jenkins/blob/982bc91d866ed90aa135b87a2cb4ac1e68c2412e/core/src/main/java/hudson/console/ConsoleLogFilter.java#L41), [SecretPatterns](https://github.com/jenkinsci/credentials-binding-plugin/blob/387426d83ddba2beae6da7b2bedfe683b30b8d39/src/main/java/org/jenkinsci/plugins/credentialsbinding/masking/SecretPatterns.java#L42), [BindingStep wiring](https://github.com/jenkinsci/credentials-binding-plugin/blob/387426d83ddba2beae6da7b2bedfe683b30b8d39/src/main/java/org/jenkinsci/plugins/credentialsbinding/impl/BindingStep.java#L139)
4. AnsiColor trata ANSI como stream con estado y genera anotaciones de render; no
   es correcto parsear cada chunk aislado ni confiar códigos para emitir HTML.
   [AnsiHtmlOutputStream](https://github.com/jenkinsci/ansicolor-plugin/blob/03d235fee02d157b8bcc876b407e450ca17dff89/src/main/java/hudson/plugins/ansicolor/AnsiHtmlOutputStream.java#L38)
5. Buildkite combina terminal rendering ANSI, límites de output, filtrado y
   conservación del original como artefacto; confirma que UI y retención no pueden
   ser ilimitadas. V2 no conservará original sin redactar: su equivalente seguro
   es un artefacto ya redactado y sujeto a autorización.
   [Buildkite log output](https://buildkite.com/docs/pipelines/configure/managing-log-output)
6. Para el logger del sistema, el modelo estable de OpenTelemetry aporta
   `Timestamp`, `ObservedTimestamp`, severidad, cuerpo, recurso, scope, atributos
   y trace context. [OTel Logs Data Model](https://opentelemetry.io/docs/specs/otel/logs/data-model/)

La redacción reduce exposiciones accidentales, no convierte un job no confiable en
confiable: Jenkins y GitHub advierten que secretos transformados o un pipeline con
capacidad maliciosa pueden escapar del masking. Ante exposición hay que rotar el
secreto y borrar el log accesible; además se deben limitar permisos de credenciales.
([Jenkins](https://www.jenkins.io/blog/2019/02/21/credentials-masking/),
[GitHub](https://docs.github.com/en/actions/reference/security/secure-use),
[OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)).

## Diseño propuesto

```text
proceso durable / system logger
          │ bytes o LogRecord
          ▼
  OutputIngestor (orden, límite, redacción)
          │              ├─ métricas: drop/truncation/lag
          ▼
 RunOutputStore ──► segmentos locales ya redactados
          │
          ├────────► read(cursor, filtros, límite)
          └────────► subscribe(afterCursor) [at-least-once]
                                  │
                         renderizador ANSI seguro / gateway / Jenkins
```

### Contrato y semántica

`OutputRecord` lleva `runId`, `operationId`/stage/step/attempt opcionales,
`stream` (`STDOUT`, `STDERR`, `SYSTEM`), `sequence` por stream, `observedAt`,
payload ya redactado, flags (`truncated`, `continued`, `ansiPresent`) y versión de
la política aplicada. `SYSTEM` no infiere severidad desde un proceso; transporta
el envelope OTel con `SeverityNumber`/`SeverityText`, resource, scope y trace
context. stdout/stderr son salida factual y conservan su canal.

- **Ingestión:** append asíncrono, ordenado por stream, en chunks con máximo de
  bytes y máximo de espera. Si el origen es el fichero durable, el tailer conserva
  el checkpoint tras el append atómico. Si el origen son pipes, dos lectores
  concurrentes drenan hacia una cola acotada. Nunca se bloquea el proceso por un
  viewer lento.
- **Redacción:** primero `SecretHandle`/canal tipado; después `StreamingRedactor`
  antes de todo durable o visible. Soporta literal y variantes aprobadas de cada
  secreto, longest-match-first, estado de cola entre chunks y límite estricto de
  tamaño de patrón. Regex sólo bajo configuración administrativa, compilada y con
  presupuesto para evitar ReDoS. `CR`, `LF` y controles se normalizan/escapan en
  exportación estructurada para evitar log injection. La sustitución es `****` y
  nunca informa el valor ni el patrón al consumidor.
- **Lectura:** `read(after: OpaqueCursor?, limit, filters)` devuelve registros
  ordenados, `nextCursor`, `endOfStream`, `truncated` y versión de filtro. El cursor
  firmado enlaza stream, secuencia, filtro y snapshot/retención; no expone offset
  físico. Límite máximo de página y de bytes, con filtros allowlisted por
  `stream`, step/attempt, ventana temporal y texto **sólo sobre datos ya
  redactados**. `subscribe(afterCursor)` es at-least-once; el cliente deduplica por
  `(stream, sequence)` y reanuda con cursor.
- **Observadores / puerto reactivo:** el core expone `RunOutputObserverPort`, no
  una API WebSocket/gRPC/Jenkins. Su contrato mínimo es
  `subscribe(request: OutputSubscription): Flow<OutputDelivery>` (o el adapter
  equivalente `java.util.concurrent.Flow.Publisher` si la frontera lo exige).
  `OutputSubscription` contiene `afterCursor`, filtros allowlisted y un máximo de
  elementos/bytes en vuelo; `OutputDelivery` incluye registro, cursor de
  confirmación y marcadores `Gap`, `Truncated`, `Expired` o `Completed`. El
  consumidor confirma sólo el cursor procesado. La entrega es **at-least-once**:
  al reconectar crea otra suscripción desde el último cursor confirmado y deduplica
  por `(stream, sequence)`. Si no confirma, supera el presupuesto de in-flight o
  permanece lento, el adapter corta con causa explícita; nunca acumula memoria ni
  frena el proceso. Un futuro gateway, adapter Jenkins o API propia traduce este
  puerto a su transporte y aplica autenticación/RBAC, sin que `pipeline-domain` o
  el store conozcan sockets, HTTP o controller.
- **Retención:** límite por run, por segmento y por organización; rotación por
  tamaño/edad; metadato `OutputTruncated`/`OutputExpired` sin contenido en el
  event log. Borrado/expiración invalida cursores explícitamente. El adapter local
  empieza con segmentos redactados en workspace/run-dir; una política futura puede
  exportarlos a object storage con cifrado, RBAC y auditoría de lectura.
- **ANSI:** la representación canónica es texto/bytes ya saneados, no HTML.
  `AnsiTokenizer` mantiene estado entre chunks, admite sólo SGR allowlisted y
  produce spans semánticos (`fg`, `bg`, `bold`, etc.). Renderizadores terminal,
  web y texto plano son proyecciones. Esto no cambia la semántica del decorator
  DSL `ansiColor` ya cerrado en ML-R9.

## Decisión de despliegue

| Alternativa | Ventaja | Coste / decisión |
|---|---|---|
| Microservicio remoto ahora | Escala y consulta central potenciales. | Requiere protocolo, autenticación, persistencia distribuida y fallo de red antes de M4; **rechazada para ML**. |
| Puerto + adapter local (propuesto) | Cumple durabilidad local, es testeable y puede alimentar E7-07. | Requiere definir contrato y storage segmentado; no ofrece multi-worker aún. |
| Seguir con `jenkins-log.txt` sin puerto | Cambio mínimo. | No hay stream/página, política uniforme ni futuro adapter; insuficiente. |

## Encaje obligatorio en roadmap

No implementar hasta crear **ML-R11 / L-11 — Run Output Service** como nuevo
milestone/backlog, posterior a ML-R10. Se propone que sea fuente del futuro
**E7-07 log projection/storage**, no su UI. Esto respeta la directiva de scope:
no depende de `:pipeline-steps-system:compiler-plugin` ni repara V1.

| Trazabilidad propuesta | Criterio verificable |
|---|---|
| Milestone/backlog | ML-R11/L-11, nuevo item; E7-07 declara consumir el puerto, sin adelantar el adapter Jenkins. |
| Exit criterion | Un proceso con stdout+stderr intercalados se recupera tras reinicio; `read` y el puerto de observadores no pierden ni duplican más allá del contrato at-least-once; un observador lento es desconectado con causa y puede reanudar por cursor; no queda contenido secreto en segmentos, páginas, stream, eventos ni journal. |
| Architecture gate | Event model no gana cuerpos ilimitados; `pipeline-domain` permanece framework-free; dependencia unidireccional hacia el puerto; no acceso de UI/Jenkins a paths locales. |
| Quality gate | TDD de orden por canal, límites/backpressure, cursor inválido/expirado, corte de chunk dentro de secreto, variantes, regex adversa, ANSI partido entre chunks y escape HTML/control chars. |
| Compatibility gate | `returnStdout`, `ansiColor` y las firmas DSL existentes siguen sin cambio; corpus Jenkins-familiar sin regresiones. |
| Operational gate | Métricas de bytes/records, lag de tail, cola llena, drops, truncation, redaction matches/failures, cursores expirados y suscriptores lentos; alertas y runbook de rotación/borrado. |
| UAT | Nuevo **UAT-LOCAL-014**: streaming y paginación reanudable de ambos canales, secreto partido entre chunks redacted en todas las superficies, ANSI seguro, retención/truncation observable y recuperación de proceso. Reusa y no sustituye UAT-LOCAL-001, -002, -004 y -011. |

## Plan por cortes pequeños

1. **Contrato y storage local:** puerto, modelo, segmentos, cursor, página,
   retención y tail de `jenkins-log.txt`, sin exposición de red ni cambio DSL.
2. **Seguridad y streaming:** `StreamingRedactor`, suscripción acotada y métricas;
   completar el canary de ADR-0049 contra cada superficie de salida.
3. **Proyecciones:** tokenizador ANSI y renderer seguro; CLI local. Jenkins/gateway
   sólo cuando M4/M6 alcancen sus propios gates.

No considerar green el corte 1 si guarda contenido sin redactar para “filtrarlo al
leer”. Ese diseño falla precisamente en crash, backup, artifact export y acceso
local: el secreto ya se filtró.
