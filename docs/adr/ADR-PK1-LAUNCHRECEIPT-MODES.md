**Estado:** ACEPTADO (2026-10-12, tren 0.57)
**Ciclo:** pk-release-0-57-consolidation
**WorkItem:** pk-0-57-rc1
**Alcance:** complementar ADR-PK1-IDENTITY con la semántica de modos
**Predecesor:** ADR-PK1-IDENTITY

# ADR-PK1-LAUNCHRECEIPT-MODES — LaunchReceipt en modo durable y no durable

## Problema

ADR-PK1-IDENTITY fija `pipelinek.launch/1` como el schema del
recibo de lanzamiento y define su contenido. La KDoc del tipo
`LaunchReceipt` en `LaunchReceipt.kt` documenta que una
respuesta `Launched` confirma un **estado durable**: journal
abierto, lease adquirido, autoridad exclusiva de escritura.

Sin embargo, la ruta de ejecución en memoria de `Main.kt`
también emite `LaunchReceipt.launched()` usando un propietario
sintético basado en el PID, sin adquirir el lease durable del
flujo SQLite. Ambos modos lanzan el pipeline correctamente,
pero la semántica del mismo literal `Launched` diverge entre
modos: durable y no-durable.

Un consumidor que reciba `Launched` puede inferir durabilidad
incorrectamente si el modo en uso es no-durable.

## Decisión

El literal `Launched` en `pipelinek.launch/1` representa una
**garantía de lanzamiento** (el binario aceptó el run, emitió
el recibo, y comenzó la coordinación), NO una **garantía de
durabilidad** (journal SQLite abierto, lease FileChannel
adquirido, autoridad exclusiva de escritura persistente).

Los dos modos difieren en la **capacidad de recuperación**
del run ante un crash del proceso:

- **Modo durable** (`--db <path>`): journal SQLite y lease
  FileChannel se persisten. Un crash del proceso permite a un
  proceso posterior `--resume` encontrar el run por
  `runId` y continuar desde el último estado durable.
- **Modo no durable** (in-memory): no hay persistencia. Un
  crash del proceso pierde el run. `--resume` no tiene
  adónde recuperar.

## Mecanismo de distinción

`ADR-PK1-IDENTITY` mantiene la forma `Launched` intacta. La
distinción se hace mediante el **canal del recibo**: el modo
durable emite el recibo por la ruta
`emitCorrelationAndReceipt` (Main.kt:386/705) que ya adquiere
el lease. El modo no durable emite por la misma ruta, pero
con un propietario sintético basado en PID y sin journal
persistente.

Esto **no** es una diferencia observable a través del schema
`pipelinek.launch/1` — el consumidor que necesite la
distinción debe usar el `--db <path>` o el flag equivalente
como proxy. La política durable/no-durable es ortogonal al
contrato público del recibo.

## Migración del consumidor

Un consumidor que necesite inferir durabilidad debe:

1. Comprobar si el proceso se invocó con `--db <path>`. Si
   sí, el recibo es de un modo durable. Si no, es no durable.
2. Si el consumidor controla la invocación, puede usar
   `pipeline capabilities` (que emite `pipelinek.capabilities/1`
   e incluye las capacidades de `M2` para distinguir modos).
3. Para consumidores externos como Fabric, el contrato actual
   no obliga a distinguir: el `OutcomesReport` que el consumer
   de Fabric lee del `OutputReadPort` ya documenta, en la
   cabecera, el `OutputStreamId` y el `recoveryGate`. Si el
   `recoveryGate` es `null`, el stream es de un modo no
   durable (in-memory) y la recuperación no es posible.

## Compatibilidad

`pipelinek.launch/1` no cambia de forma. Consumidores que
actualmente parsean el recibo siguen funcionando. La
distinción durable/no-durable se obtiene por la vía
descrita arriba, no por el schema del recibo.

## Lo que este ADR NO hace

- No añade un campo `mode` al schema `pipelinek.launch/1`.
  Eso sería una incompatibilidad para los consumidores
  existentes, que el principio de compat del ADR-PK1-IDENTITY
  protege.
- No introduce negotiation para distinguir modos. La
  decisión se hace por la invocación del binario, no por
  un protocolo de salida.
- No modifica el comportamiento de la ruta in-memory. Esa
  ruta seguirá siendo "rápida, sin journal" — es la
  optimización legítima para casos donde la durabilidad
  no es requerida (CI runs, tests, one-off scripts).

## Métricas de cierre

- La KDoc de `LaunchReceipt.launched()` se actualiza para
  declarar explícitamente que la garantía de
  `Launched` no implica durabilidad.
- Un test en `M1DCrossJvmFollowTest` o equivalente verifica
  que el modo in-memory NO escribe un journal SQLite.
- Un test en `LaunchReceiptTest` verifica que el
  `LaunchReceipt` emitido por el modo in-memory tiene el
  mismo schema y la misma forma que el emitido por el modo
  durable, salvo por el `runId` (que es generado por el
  owner sintético en el modo no durable).

Estas métricas se miden en el tren 0.58.
