# ADR-PK1-OUTPUT-FRAMING — opt-in versioned output protocol

**Estado:** ACEPTADO (2026-10-12, tren 0.57)
**Ciclo:** pk-release-0-57-consolidation
**WorkItem:** pk-0-57-rc1
**Alcance:** D2 de PK-RELEASE-CONSOLIDATION, promovido a 0.57
**Predecesor:** ADR-PK1-IDENTITY (fija stdout como canal de `pipelinek.launch/1`)

## Problema

`--format json` promete "un único documento JSON por stdout", pero el
proceso escribe el `LaunchReceipt` **antes** y por el **mismo canal**,
así que stdout contiene **dos documentos JSON no delimitados**.
Ningún consumidor puede parsear stdout como un único documento. La
solución "join por índice de línea" es frágil: el cliente que inserta
un filtro entre líneas rompe la sincronía.

## Decisión

1. **`pipelinek.launch/1` no se mueve.** El recibo conserva su schema,
   su posición y su canal por defecto. Esto preserva ADR-PK1-IDENTITY
   sin sobrescritura retroactiva.
2. Se introduce un **protocolo opt-in versionado** bajo la bandera
   `--output-protocol=<id>` con negotiation explícita: el cliente
   declara los protocolos que acepta; el servidor falla con error
   tipado si no hay intersección. **Nunca** degradación silenciosa.
3. El identificador del nuevo protocolo es **`pipelinek.output/2`**,
   alineado con el patrón `pipelinek.*/N`. Bump sobre el canal, no
   sobre el recibo: el `/1` del recibo se preserva por diseño.
4. Default sin cambios: en ausencia de `--output-protocol`, el
   comportamiento actual (recibo stdout línea 1, eventos stdout
   línea 2) se mantiene.
5. El protocolo antiguo queda disponible. La retirada es una decisión
   posterior y se hará en un tren posterior con su propio ADR.

## Forma del protocolo `pipelinek.output/2`

- **stdout línea 1**: `LaunchReceipt` (`pipelinek.launch/1`), idéntico
  al protocolo actual.
- **stderr línea 1**: array JSON de eventos (`[{...}, {...}]`),
  prefijo `{"events":[...]"` o array plano — el decoder lo acepta
  por **estructura** (`arrayBoundsOf`), no por posición.
- **Ni stdout ni stderr** llevan nada más en formato JSON. Texto
  humano (logging, diagnostics) sigue yendo al stream de logs ya
  existente; el ADR no lo toca.

## Reglas de negotiation

- El cliente pasa `--output-protocol=<id>` con el id que prefiere
  (puede repetirse la bandera para una lista).
- Si la intersección con los protocolos del servidor es vacía, el
  proceso **falla con exit code `InvalidArgument` (2)** y un envelope
  `pipelinek.cli/1` cuyo `message` enumere los ids disponibles.
- El servidor **nunca** degrada silenciosamente: nunca acepta un id
  desconocido devolviendo el comportamiento default; siempre falla
  cerrado. Esto es la prohibición explícita del ADR-PK1-IDENTITY
  contra "skip first line and PASS".

## Decoder tipado

El decoder `ProcessOutputDecoder` (en `src/test/.../support/`) y el
testkit `CliContractTestKit` se extienden con:

- Un enum `Protocol { Published, Unknown, SeparatedV2 }`. La
  transición `Published → SeparatedV2` se materializa aquí; la
  lista puede crecer en futuros protocolos.
- `decode(stdout, stderr, expectedProtocol)` despacha por protocolo.
- `parseReceiptFor(protocol, rawLine)` delega a `parseReceipt`
  existente para `Published`; en `SeparatedV2` el recibo sigue
  siendo `pipelinek.launch/1` por lo que **no requiere parser
  paralelo**; lo único que cambia es el canal de los eventos.

## Tests obligatorios (matriz 8)

1. Parser anterior contra modo de compatibilidad (recibo `/1` + eventos
   en mismo stdout, comportamiento preservado).
2. Parser nuevo contra modo nuevo (canal split, recibo `/1` en canal
   declarado).
3. Recibo ausente en `SeparatedV2` → `MissingReceipt` finding.
4. Recibo duplicado → contract violation.
5. Recibo con correlación contradictoria → `ContradictoryCorrelation`.
6. JSON inválido → `InvalidJson` finding (no excepción silenciosa).
7. Dos ejecuciones concurrentes con `--output-protocol=separated-v2`
   no se interfieren.
8. Fallos antes y después del lanzamiento: el recibo y los eventos
   mantienen contrato independiente del momento del fallo.

Prohibido: tests que consistan en descartar la primera línea y
declarar PASS. El decoder debe distinguir por **estructura**, no por
posición; la matriz de arriba verifica esa propiedad.

## Precedente

`JsonEventLog.arrayBoundsOf` localiza el array de eventos por
estructura, no por índice. La misma lección se aplica al output
completo: el decoder mira la forma, no la posición.

## Lo que este ADR NO hace

- No mueve `pipelinek.launch/1` a stderr.
- No cambia el comportamiento por defecto.
- No retira el protocolo actual.
- No declara retrocompatibilidad no medida.
- No introduce un quinto schema top-level para el wrapper (se
  mantiene el array plano por forma, decodificable con
  `arrayBoundsOf`).

## Riesgos aceptados

- **Adopción**: el protocolo nuevo es opt-in. Consumidores que ya
  parsean stdout con la regla "línea 1 = recibo, línea 2 = array"
  siguen funcionando sin cambios. Consumidores que quieran
  cleanliness deben añadir `--output-protocol=separated-v2`.
- **Doble superficie**: la negociación introduce dos formas de
  emitir el mismo output. La matriz 8 mide que ambas son
  contractualmente equivalentes; la retirada futura es un ADR aparte.

## Métricas de cierre

- `./gradlew check` con `--output-protocol` ausente: comportamiento
  idéntico al tren 0.56 (todos los tests verdes, mismo output).
- `./gradlew :pipeline-application:test --tests
  '*support.*' --rerun-tasks` con la matriz 8 verde.
- `pipeline --output-protocol=separated-v2-bogus run ...` → exit 2
  con envelope `pipelinek.cli/1` cuyo `message` lista los ids.
