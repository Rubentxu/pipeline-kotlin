# ADR-PK1-OUTPUT-FRAMING — Framing de la salida del CLI

**Estado:** PROPUESTO (no aprobado; no cambia comportamiento por defecto)
**Ciclo:** `p-733fb505b5a6bd2d/release-0-56-consolidation`
**WorkItem:** `93158a45-2997-49ff-9a85-7906c56344c6`
**Alcance:** D2 de PK-RELEASE-CONSOLIDATION
**Predecesor:** ADR-PK1-IDENTITY (fija stdout como canal de `pipelinek.launch/1`)

## Por qué hace falta

`--format json` promete un único documento JSON por stdout. El proceso cumple otra cosa: el
recibo de lanzamiento se escribe **antes** y por el mismo canal, así que stdout contiene dos
documentos JSON no delimitados. Ningún consumidor puede parsear stdout como un documento, y
`--format json` no significa lo que dice.

El caso `--format json` es el peor porque **no emite líneas de progreso**: stdout empieza
exactamente en el `{` del recibo. En texto, el progreso `[PipelineK]` actúa como separador
visual, que es por lo que el defecto lleva tiempo oculto.

## Decisión ya tomada, que este ADR no reabre

`pipelinek.launch/1` **no** se mueve globalmente a stderr. Está publicado y ADR-PK1-IDENTITY
fija stdout como su canal. Este ADR no contradice esa decisión: la resuelve sin romperla.

## La incompatibilidad, enunciada

| Requisito | Fuente | Estado |
|---|---|---|
| `--format json` produce un documento JSON único | CLI publicado | **No cumplido**: hay dos documentos |
| `pipelinek.launch/1` en stdout | ADR-PK1-IDENTITY, publicado | Cumplido |
| Un consumidor puede parsear stdout sin heurística | Necesario | **No cumplido** |

Los tres no pueden cumplirse a la vez en el protocolo actual. Por eso hace falta versión nueva,
no un arreglo in situ.

## Diseño propuesto: protocolo opt-in versionado

El protocolo actual **no cambia**. Se añade uno nuevo, activado por bandera explícita:

- `--output-protocol=<id>` con negociación: el consumidor pide un protocolo y declara la
  versión que entiende; si no hay intersección, **fallo explícito**, nunca degradación
  silenciosa.
- Declare su **transporte** por canal: qué documento va a stdout, cuál a stderr, y cuál es
  texto humano. Declarado, no deducido.
- Un **documento por stream**. Donde hoy hay dos documentos en un stream, el nuevo los separa.
- El **recibo sigue siendo `pipelinek.launch/1`**, sin cambiar de forma. Lo que cambia es el
  canal en el nuevo protocolo.
- El protocolo antiguo queda disponible hasta que sus consumidores migren. La retirada es una
  decisión posterior, no parte de este ADR.

## Decoder de salida de proceso

H1.3 pide centralizar un decoder en el testkit que **distinga** lo que hoy se mezcla por
posición:

1. `LaunchReceipt` tipado.
2. Documento de eventos.
3. Salida textual humana.
4. Diagnóstico.
5. Error de protocolo.

Un decoder **por estructura**, no por posición. Ya existe precedente: `arrayBoundsOf` en
`JsonEventLog` localiza el array por forma y no por índice, y con eso 40 fallos se resolvieron.
Este decoder aplica la misma lección a stdout completo.

## Prohibición explícita

Los tests reparados **no** pueden consistir en descartar la primera línea y declarar PASS. Un
test que pasa porque ignoró el documento que le importaba es un test que pasa por omisión: es
precisamente la clase de defecto que dejó a rc4 en `NO_GO 0/13`.

## Casos que la matriz debe cubrir

- Parser anterior contra modo de compatibilidad.
- Parser nuevo contra modo nuevo.
- Recibo ausente.
- Recibo duplicado.
- Recibo con correlación contradictoria.
- JSON inválido.
- Dos ejecuciones concurrentes.
- Fallos antes y después del lanzamiento.

## Lo que este ADR NO hace

- No mueve `pipelinek.launch/1` a stderr.
- No cambia el comportamiento por defecto.
- No retira el protocolo actual.
- No declara compatible retrocompatibilidad no medida.
