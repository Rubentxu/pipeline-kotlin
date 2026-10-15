# ADR-PK1 — Identidad de ejecución y correlación durable

**Status:** ACEPTADA (2026-10-12, PK-1)
**Hito:** PK-1 — Contrato SDK consumible e identidad de ejecución
**Release objetivo:** v0.56.0-rc1
**Decisor:** PK release maintainer

## Contexto

PipelineK conserva la autoridad sobre `RunId` (el identificador que el
runtime asigna a cada ejecución y que aparece en cada evento, output y
recibo del run). Pero hasta `v0.55.0-rc1` no existía un contrato
explícito que permita a un invocador externo (Fabric, un script de CI, un
operador humano ejecutando el CLI) correlacionar de forma estable su
propia identidad con la ejecución de PK.

Tres síntomas del gap:

1. **Sin correlación externa estable.** El `RunId` es generado por PK
   (UUIDv4) y el invocador no tiene forma de pre-asignarlo ni de
   proyectar su propia identidad sobre el run. Si el invocador guarda
   "yo invoqué el pipeline A", no puede mapearlo a un `RunId` sin
   parsear el evento `RunStarted` o leer la tabla `events`.
2. **Sin receipt de lanzamiento tipado.** El CLI imprime en stderr
   "Pipeline finished with SUCCESS" o "Pipeline finished with FAILURE",
   pero no hay un envelope tipado y machine-verifiable que un consumidor
   pueda parsear de forma estable para obtener `runId`,
   `correlationId`, `ownerId`, `startedAtMs`.
3. **Códigos de salida dispersos.** Los exit codes del CLI están
   hard-coded en `Main.kt` (0, 1, 2, 3, 4) sin una taxonomía cerrada
   que un consumer pueda mapear de forma estable a acciones
   (reintentar, abortar, degradar, escalar).

## Decisión

Se introduce el **contrato de identidad de ejecución** sobre tres
componentes ortogonales:

### 1. `CorrelationId` (estable, externo, opcional)

- Value class `dev.rubentxu.pipeline.v2.domain.CorrelationId(val value: String)`.
- Forma: el invocador puede pasar `--correlation-id <uuid>` a `pipeline
  run` / `validate` / etc.; si no, PK genera un UUIDv4.
- Garantía: la misma `CorrelationId` aparece en:
  - El `LaunchReceipt` stdout.
  - Un nuevo evento tipado `RunCorrelated(runId, correlationId, ownerId,
    startedAtMs)` en la `event-plane`.
  - El archivo durable `last-run.json` en `controlDirRoot/last-run/`.

- **Posición en el timeline (ratificada 2026-10-15).** `RunCorrelated` es el
  PRIMER evento del run: precede a `CompilationStarted`, que precede a
  `RunStarted`. La redacción anterior —«antes del primer `RunStarted`»— era
  cierta pero infra-determinada: dejaba sin declarar la posición respecto a la
  compilación, y tres UATs rellenaron ese hueco afirmando un orden que ningún
  documento había prometido nunca.

  La razón de ese orden no es el orden en sí. `RunCorrelated` se emite cuando la
  ejecución ya tiene identidad durable —tras tomar el lease, y junto al registro
  `last-run.json` del que leen tanto `--resume` como la arbitraje de propiedad—. Si
  se emitiera después de compilar, un script que no compila dejaría **ningún**
  registro de correlación: la invocación desaparecería del directorio de control
  sin explicación y sin nada que reanudar. El orden es lo que hace observable un
  fallo temprano.

  Registro de la decisión: `docs/v2/07-uat/EVENT_TIMELINE_CONTRACT_DRIFT_ESCALATION.md`.
- Concurrencia: dos invocaciones concurrentes con la misma
  `CorrelationId` **no** se fusionan; cada una obtiene su propio
  `RunId`. La `CorrelationId` no es un identificador de run, es un token
  de correlación que agrupa runs a ojos del invocador.

### 2. `LaunchReceipt` (typed, machine-verifiable, una línea JSON)

- Data class `dev.rubentxu.pipeline.v2.application.LaunchReceipt(
    schema: String = "pipelinek.launch/1",
    runId: RunId,
    correlationId: CorrelationId,
    ownerId: RunOwnerId,
    startedAtMs: Long,
    controlDir: Path?,
    status: LaunchStatus,
  )` donde `LaunchStatus = Launched | Refused(reason: String)`.
- Emitido en stdout como **una sola línea JSON** después de que el
  estado durable está comprometido (lease adquirido, journal abierto).
  Garantía: si el consumer lee la línea, la correlación está
  establecida.
- Schema versionado: `pipelinek.launch/1`. Cambios incompatibles
  bump a `/2`.

### 3. `CliExit` (taxonomía cerrada, machine-verifiable)

- Sealed interface `dev.rubentxu.pipeline.v2.application.CliExit`
  con casos:
  - `Success(0)` — pipeline ejecutó sin failure outcome.
  - `PipelineFailed(1)` — pipeline ejecutó y produjo `RunOutcome.Failure`.
  - `InvalidArgument(2)` — argumento no entendido, valor faltante, conflicto.
  - `IncompatibleVersion(3)` — version resource ausente o consumer SDK
    version mismatch.
  - `RuntimeNotAvailable(4)` — control root inaccesible, db no escribible,
    journal no creable, lease store roto.
  - `UnknownExecution(5)` — `runId` no encontrado en durable store, lease
    retenido por otro proceso.
  - `ReadFailure(6)` — error de I/O leyendo del durable store.
  - `IntegrityViolation(7)` — credentials store tampering, journal
    corrupto, hash de digest mismatch.
- Cada `CliError` (sellado existente en `CliParser.kt`) se mapea a un
  `CliExit` por una función pura `fun CliError.toCliExit(): CliExit`.
- La función `main` se refactoriza para acumular el `CliExit` y
  emitirlo en stderr como JSON antes de `System.exit(code)`.
- Schema versionado: `pipelinek.cli/1`.

### 4. `pipeline capabilities` (subcomando machine-verifiable)

- Subcomando `pipeline capabilities` emite en stdout la lista de
  capabilities del SDK con su estado (PUBLICADA / EXPERIMENTAL),
  first-published, y la SHA-256 del build actual.
- Schema: `pipelinek.capabilities/1`.
- Sin argumentos, sin side-effects, exit 0.

### 5. `pipeline sdk-info` (subcomando machine-verifiable)

- Subcomando `pipeline sdk-info` emite las 5 coordenadas SDK + SHA-256
  de cada JAR + la versión del BOM.
- Schema: `pipelinek.sdk/1`.
- Sin argumentos, sin side-effects, exit 0.

## Consecuencias

### Positivas

- Un consumer externo (Fabric, CI) puede correlacionar su identidad con
  el run sin parsear logs ni consultar `events` directamente. Cumple
  PK-1: "No exigir a Fabric consultas directas a la tabla `events`".
- Los exit codes son estables y documentados; un consumer puede
  decidir reintento vs. abort sin ambigüedad.
- El `LaunchReceipt` es la primera superficie tipada de "recibí tu
  invocación"; los `CLI exit envelopes` son la primera superficie
  tipada de "tu invocación falló por X".
- `pipeline capabilities` + `pipeline sdk-info` son machine-verifiable
  surfaces que un orquestador puede usar para negociar versiones antes
  de invocar.

### Negativas

- El `Main.kt` crece ~150 líneas. La función `main` actual ya es 1100+
  líneas; se justifica extraer un `MainEntrypoint` que delegue en
  sub-handlers.
- `LaunchReceipt` y los CLI envelopes añaden ruido a stdout/stderr.
  Consumidores que parsen stdout del run deben usar `--format json` o
  filtrar por la línea que empieza con `{"schema":"pipelinek."`.
- El evento `RunCorrelated` añade una fila a la `event-plane` por cada
  run. Aceptable: es una fila pequeña y el costo es despreciable
  comparado con `RunStarted`.

## Alternativas consideradas

### A. `RunId` externo pre-asignado (rechazado)

El invocador pasaría `--run-id <uuid>` y PK lo aceptaría. **Rechazado**
porque rompe la autoridad de PK sobre la identidad: dos invocaciones
con el mismo `--run-id` colisionarían en el `last-run.json` y el lease
store. La `CorrelationId` evita el problema: el `RunId` sigue siendo
interno, y la correlación es ortogonal.

### B. Receipt en archivo durable, no en stdout (rechazado)

El consumer leería `controlDirRoot/last-run/receipt.json` post-hoc.
**Rechazado** porque obliga al consumer a conocer la ruta del control
dir (que es privada del runtime). El stdout line es universal y
mantiene el control dir como detalle interno.

### C. Exit codes como `Int` libre (rechazado)

Seguir con `System.exit(2)` directo, documentado en un README.
**Rechazado** porque un consumer que vea un exit code no documentado no
tiene forma de saber si debe reintentar o abortar. La taxonomía cerrada
con nombre es la única forma de hacer el contrato estable.

## Compatibilidad N/N-1

- `v0.56.0-rc1` introduce los nuevos tipos y subcomandos. Es un MINOR
  bump (0.55.0 → 0.56.0).
- Consumidores que usen `v0.55.0-rc1` sin invocar `pipeline capabilities`
  o `--correlation-id` siguen funcionando: los nuevos campos son
  opcionales (default = PK genera).
- Consumidores que parsen `System.exit(2)` siguen viendo exit 2 para
  `InvalidArgument`; la taxonomía solo añade nombres y rutas JSON
  alternativas, no cambia los códigos.
- El `LaunchReceipt` se emite **después** del `Pipeline finished with ...`
  existente, por lo que consumidores que lean `stderr` no se rompen.

## Referencias

- `dev.rubentxu.pipeline.v2.domain.RunId` (existente, value class)
- `dev.rubentxu.pipeline.v2.domain.RunOwnerId` (existente, lease)
- `dev.rubentxu.pipeline.v2.application.CliError` (sellado, 60+ casos)
- `dev.rubentxu.pipeline.v2.application.Main.kt` (1100+ líneas, entrypoint)
- M2 §3 (lease y ownership)
- M3 §4 (durable run selection)
- CERTIFICATION_PROTOCOL T2/T3 (CLI test gates)
