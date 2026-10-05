# E1 — La página de eventos la decide la autoridad de secuencia

> Recibo de `532e272a`, que introduce `EventStore.readSlice` / `EventSlice` (ADR-0100). Cubre un
> defecto de **cohesión del read-side** del Event Plane y el defecto de **orden de lectura** que
> se encontró al auditarlo y que no era el mismo. La autoridad de lo afirmado aquí es el árbol y
> la evidencia ejecutada sobre este SHA; los recibos anteriores prueban sus propios SHA.

## 1. El defecto que se corrigió

El read-side del Event Plane tenía cuatro entradas y ninguna era la autoridad:

| read-side | devuelve | cursor | límite | consumidores de producción |
|---|---|---|---|---|
| `EventStore.eventsFor` | `DomainEvent` (semántica) | no | no | muchos — es el `EventSink` que recorre el producto |
| `EventHistory.history` | envelope (identidad) | no | no | **ninguno** |
| `EventTail.readAfter` | envelope (identidad) | sí | sí | **ninguno** |
| `EventViewProjection` | líneas de texto | no | no | **ninguno** |

La única lectura paginada decidía el corte **en un lector**. `EventHistoryReader.readAfter` leía
`eventsFor` completo, proyectaba **todos** los eventos a envelope, descartaba los que pasaban del
límite y obtenía `hasMore` mirando uno de más. Seis reglas —cursor, `sequence > after`, orden,
límite, cursor de continuación y `hasMore`— escritas en un componente que no es la autoridad de
secuencia y al que no se puede preguntar cuál es el orden.

Y la lectura que sí trae semántica no pagina. Un consumidor que quisiera recorrer un run
incrementalmente, en orden store-assigned, con cursor y límite, y ver qué ocurrió, tenía dos
salidas: releer el run entero —con historiales de GiB— o tragarse identidad sin semántica.

## 2. La prueba de que el hueco era real, escrita antes de cerrarlo

`examples/fabric-contract-consumer/.../PublishedReadSurfaceTest` caracterizó el hueco **ejecutándolo**
en lugar de describirlo: el test escribía entero el corte que un consumidor tenía que reimplementar
—`sequence > after`, orden, el peek que prueba `hasMore`, y el cursor de reanudación— y el otro
mostraba que la lectura paginada que el contrato sí ofrecía respondía con posición, kind, tiempo e
identidad, y con ninguna forma de expresar el outcome aunque el evento underlying lo llevara.

Se conservó después de cerrar el hueco, y ahora afirma lo contrario: que un consumidor camina el
run en páginas tipadas contra el artefacto publicado sin escribir una línea de corte. La
caracterización no se borró al resolver el problema, porque una capacidad que se afirma una vez y
ya no se vuelve a mirar es una capacidad que nada protege.

## 3. Qué se decidió, y por qué no es otro nombre

`EventStore` —que ya es la autoridad de secuencia por EVT-0 §2— expone la lectura paginada que le
faltaba. Aditivo: no cambia `EventPage`, no cambia `PipelineEventEnvelope` y no cambia ninguna firma
publicada. El ABI publicado crece en 20 líneas y todas son `EventSlice` y `readSlice`.

Tres propiedades que hacen que no sea un `readAfter` renombrado:

1. **El default ES la regla.** La implementación por defecto decide las seis reglas una sola vez
   sobre `eventsFor`. Los dos stores reales la sobrescriben por optimización, y la ley de paridad
   es lo que convierte "equivalente" en algo con significado. La referencia es el default heredado
   y no la versión SQL: si la dirección fuera al revés, la implementación más rápida pasaría a ser
   la especificación sin que nadie lo hubiera decidido.
2. **El lector proyecta, no corta.** Eso elimina la segunda implementación de la regla y, de paso,
   el hecho de que construir una página de tres construyera envelopes para el run entero.
3. **`nextCursor` es no-null, y es decisión.** Un cursor `null` ya significa "empezar por el
   principio", así que una continuación nullable no distinguiría el final de un run de su comienzo.
   `hasMore` es el campo que dice si hay más.

Alternativas descartadas, con su razón, en el ADR. La corta: dar elemento tipado a `EventPage`
habría dejado `PipelineEventEnvelope` con un solo consumidor, a un paso de ser un segundo modelo de
eventos; y un puerto nuevo habría sido añadir superficie sin cambiar la que ya tenía la culpa.

## 4. El defecto de orden, que no era el mismo y era más grave

`SqliteEventStore.eventsFor` leía `ORDER BY rowid ASC` —la posición física de la fila— mientras el
`KDoc` de `EventCursor` y el comentario de `EventHistoryReader` prometían orden por secuencia.

`appendAssigned` estampa la secuencia en el hilo del producto con un contador atómico y encola
unas instrucciones después. Entre el `incrementAndGet` y el `put`, un segundo hilo puede tomar la
siguiente secuencia y encolar antes: la fila con la secuencia **más alta** recibe el `rowid`
**menor**, y los dos órdenes se separan.

La consecuencia no es un orden equivocado, es **pérdida silenciosa**. Un lector que reanuda desde
`lastSequence = 6` pide `sequence > 6`; la fila que lleva la 5 sigue sin leerse, y ningún cursor
posterior la devolverá porque un cursor solo avanza.

**Cómo se probó sin ganar carreras contra el scheduler.** Se escribieron filas con el store de
verdad y se reescribió su `rowid`, de modo que el orden físico y el asignado discrepan. Ese estado
de fichero es exactamente el que deja un escritor concurrente. RED: `expected [1, 2, 3] but was
[3, 2, 1]`.

**La primera versión de la prueba falló por la razón equivocada** y se descartó: `eventsFor` hace
`SELECT payload` y decodifica la secuencia del JSON, así que reescribir la **columna** `sequence`
no cambia nada que un observador vea, y la prueba pasaba contra un lector demostrablemente
desordenado. Un RED por la razón equivocada no es un RED.

## 5. Mutaciones, todas con atribución

| # | Mutación | Qué tumba | Restaurada |
|---|---|---|---|
| M-orden | `ORDER BY rowid` en `eventsFor` | exactamente la fila del orden; la de no-vacuidad sigue verde | por hash `a382f512` |
| M-corte | `sequence >= ?` en `readSlice` de SQL | la fila de paridad del SQL, y **sólo** esa | por hash `18f807c9` |
| M-peek | quitar el `+1` del `LIMIT`, que haría `hasMore` siempre falso | la fila de paridad del SQL, y **sólo** esa | por hash `18f807c9` |
| M-rowid-slice | `ORDER BY rowid` en `readSlice` de SQL | **nada** | por hash |

La última se registra como ineficaz a propósito, porque no lo es y conviene que se sepa. La ley
de paridad compara implementaciones **sobre el mismo estado**, y en un estado donde `rowid` y
secuencia coinciden no hay divergencia que ver. El orden correcto lo cubre
`SqliteEventOrderAuthorityTest`, que fuerza la divergencia. Anotar el límite de una ley es parte de
afirmarla: una ley que parece cubrir de más es peor que una que cubre menos.

## 6. Evidencia ejecutada

```text
:pipeline-events         105 tests   0F  0E  0S
:pipeline-events-store   192 tests   0F  0E  0S   (+5 leyes de paridad, +2 del orden)
consumer externo          14 tests   0F  0E  0S   (12 previos + 2 de superficie de lectura)
:pipeline-architecture-tests 456 tests  0F  0E 10S
```

Los logs quedan en `/var/home/rubentxu/.local/state/pipelinek-gates/`: `order-red3.log` (el RED por
la razón correcta), `order-green.log`, `order-mut.log`, `d2-mut2.log`, `d2-mut3.log`,
`verify-consumer2.log`.

## 7. Lo que este recibo NO demuestra

**Que un consumidor externo lo use.** Lo que está probado es que puede: el consumer compila y
camina el run contra el artefacto publicado. Que `worker/pipelinek-runtime-adapter` de Fabric lo
use es trabajo de BLOCK 3, y el tripwire de Fabric sigue en `UNPUBLISHED` a propósito. Pasará a
`PUBLISHED` cuando el adapter real compile y pase contra el artefacto, no antes.

**Que el hueco de `causation`/`correlation` esté cerrado.** No lo está, y no se mezcló aquí.
`EnvelopeProjector` los rellena con `null` siempre porque **`DomainEvent` no lleva contexto
causal** — no es un defecto de serialización, sino un hueco de origen. La identidad cross-system
que el envelope promete está a medias por el lado del evento, no del sobre. Eso es S5.

**Que la paridad sobreviva a un store nuevo.** La ley compara los stores que existen hoy contra el
default. Un cuarto store entra cubierto por el default, y pasa a estar sujeto a la ley en cuanto lo
sobrescriba: no hay un mecanismo que le exija sobrescribir, ni debe haberlo.
