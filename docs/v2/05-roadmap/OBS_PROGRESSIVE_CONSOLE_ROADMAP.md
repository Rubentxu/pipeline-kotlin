# OBS-PC — Progressive Console & Durable Streaming

**Evolutivo:** OBS-PC · **Estado global:** `IMPLEMENTED_PARTIAL` / `NOT_PRODUCT_CERTIFIED`
**Línea de trabajo:** `par/cli-observation` · **Integración de destino:** `s6-plugin-sdk`, por integración controlada
**Orquestación:** SDDK · **Orquestador de estado:** `docs/v2/05-roadmap/ROADMAP.md`

Este documento describe **sólo** el evolutivo OBS-PC. No es un roadmap de producto paralelo: la autoridad
sobre prioridad, integración y calendario sigue siendo `ROADMAP.md`. OBS-PC no bloquea el calendario de
publicación del SDK, y ningún bloque OBS se admite antes de cumplir su gate.

---

## 1. Objetivo

PipelineK se convierte en una **fuente de consola durable y reanudable**: la terminal y Jenkins son
consumidores, no autoridades adicionales sobre los bytes. Bytes persistidos una sola vez, cursores
durables, atribución por canal, recuperación segura y lectura que no reconstruye el historial.

## 2. Principios vinculantes

| | Principio |
|---|---|
| **A1** | Autoridad única: los bytes de proceso pertenecen al Output Plane. |
| **A2** | Event Plane, Output Plane y valores tipados de Step no son intercambiables. |
| **A3** | **Lectura pasiva:** ningún observador modifica ni recupera destructivamente el estado de un escritor activo. |
| **A4** | Continuación durable: los cursores son posiciones confirmadas, no timestamps ni cantidades mostradas. |
| **A5** | Memoria acotada en bytes, registros y retención, no sólo en número de páginas. |
| **A6** | Aislamiento: un lector lento no bloquea directamente la publicación ni la ejecución. Si la persistencia no puede seguir, la política de fallo es explícita. |
| **A7** | Honestidad epistemológica: ausencia de datos, stream abierto, stream sellado, lectura rechazada y recuperación pendiente son estados distintos. |
| **A8** | Contrato neutro: CLI, Fabric y otros consumidores reciben las mismas capacidades. |
| **A9** | Evolución sobre lo existente: sin segundo runtime, sin segundo codec. |
| **A10** | Certificación por evidencia del SHA exacto, no por estado declarado en documentos. |

## 3. Defectos que los bloques deben cerrar

| Código | Problema | Bloque | Prioridad |
|---|---|---|---|
| PC-01 | Apertura de lector acoplada a recuperación destructiva | OBS-1 | P0 |
| PC-02 | Recuperación sin exclusividad garantizada entre JVM | OBS-1 | P0 |
| PC-03 | Captura por pipes de JVM sin continuidad demostrada tras la muerte del propietario | OBS-2 | P0 de garantía |
| PC-04 | Escritura e indexación costosas con ventanas pequeñas | OBS-3 | P1 |
| PC-05 | `framesOfRun` recorre la historia completa para páginas pequeñas | OBS-3 | P1 |
| PC-06 | Lecturas y presentación materializan datos en algunas rutas | OBS-4 | P1 |
| PC-07 | Rechazos de eventos ilegibles no llegan a `observe` | OBS-4 | P1 |
| PC-08 | Decodificación UTF-8 por frame sin estado de caracteres | OBS-4 | P1 |
| PC-09 | Fin de run, fin de output y fin de observador sin contrato integrado | OBS-4/OBS-5 | P1 |
| PC-10 | Equivalencia CLI/Fabric/Jenkins no certificada en la misma candidata | OBS-6/OBS-7 | P1 |

Cada punto debe **recomprobarse sobre el HEAD local**: una evidencia histórica no sustituye la medición actual.

## 4. Bloques

| Bloque | Entrega visible | Depende de | Estado |
|---|---|---|---|
| **OBS-1** | Observador seguro frente a escritor activo | — | `PARTIAL` — ADR y código aplicados; UAT y ley de fitness pendientes |
| **OBS-2** | Captura incremental correcta con ciclo de vida definido | OBS-1 | `NOT_RUN` |
| **OBS-3** | Salida y consultas escalables con presupuestos medidos | OBS-1, OBS-2 | `NOT_RUN` |
| **OBS-4** | CLI `follow`/`tail`/replay correctos y acotados | OBS-1..3 | `NOT_RUN` |
| **OBS-5** | Consola global y por Step con contexto semántico | OBS-3, OBS-4 | `NOT_RUN` |
| **OBS-6** | Integración real con Fabric/Jenkins | OBS-5 | `NOT_RUN` |
| **OBS-7** | Integración S6 y certificación final | OBS-1..6 | `NOT_RUN` |

Un bloque **no** se cierra porque otro haya implementado anticipadamente parte de su código.

### Hitos de valor

- **Primer resultado utilizable:** OBS-1 + OBS-2 → lector externo seguro de salida viva.
- **Primer resultado escalable:** OBS-3 → la consola no estrangula al productor y se sigue un histórico grande.
- **Primer resultado CLI completo:** OBS-4.
- **Primera experiencia Jenkins comparable a la consola clásica:** OBS-5 + OBS-6.
- **Resultado production-ready integrado:** OBS-7.

## 5. Estado por bloque, clasificado por evidencia real

Sólo se admite `IMPLEMENTED`, `PARTIAL`, `BLOCKED`, `NOT_RUN` o `CERTIFIED`. Nunca se adelanta estado.

### OBS-1 — Frontera segura de observación y recuperación

| UAT | Enunciado | Estado |
|---|---|---|
| OBS-PC-101 | Escritor detenido entre `reserve` y `commit`; lector externo no modifica reservas | `IMPLEMENTED` — `ObsGReaderRecoveryInterferenceUatTest` |
| OBS-PC-102 | Dos runs comparten control-root sin interferirse | `IMPLEMENTED` — `ObsPcReadRecoveryOwnershipUatTest` |
| OBS-PC-103 | `SIGKILL` del escritor; recuperación autorizada desde otra JVM | `IMPLEMENTED` — misma clase, fila `RECOVER-2` |
| OBS-PC-104 | Segunda recuperación idempotente | `IMPLEMENTED` — `ObsPcReadRecoveryOwnershipUatTest` |
| OBS-PC-105 | Cursor anterior válido tras recuperación, sin duplicar rangos | `IMPLEMENTED` — `ObsPcReadRecoveryOwnershipUatTest` |
| OBS-PC-106 | Un estado que necesita reconciliación no se devuelve como página vacía exitosa | `IMPLEMENTED` — `ObsPcReadRecoveryOwnershipUatTest` |
| OBS-PC-107 | Un lector muerto no altera el resultado de la run ni los bytes de otro lector | `IMPLEMENTED` — `ObsPcReadRecoveryOwnershipUatTest` |

**Ley de fitness:** `ObsPcReadRecoverySeamFitnessTest` — un único sitio de construcción del store, la
apertura de lectura no puede recuperar, y sólo el lado de escritura y la retención nombran una apertura
que recupera. Cada una de sus tres filas la tumba su propia mutación (M-OWN-4/5/6).

**Mutaciones medidas:** M-OWN-1 → 102 · M-OWN-2b → 102/104/105/106 · M-OWN-3 → 102/105/106/107.
Dos atribuciones que este bloque dio por buenas antes de medir quedaron corregidas por la medición, y
las correcciones están en el recibo.

**Gate OBS-1:** `GO` para la condición STOP. Ningún lector puede liberar, truncar o modificar una reserva
de un escritor activo, demostrado con procesos reales. `STEP-CERT` y `PRODUCT-GATE`: `NOT_RUN`.

Recibo: `docs/v2/07-uat/OBS1_READ_RECOVERY_OWNERSHIP_RECEIPT.md` (base `3567bbc6`).

**Decisión registrada:** `ADR-OBS-002-read-recovery-ownership.md`. La propiedad de un stream la sostiene un
`FileLock` de kernel tomado de `reserve()` a `commit()`; un lector abre sin recuperar y su `recover()` falla
en voz alta. O3 de ADR-M1 se reconcilia **por su invariante** —un lector se sirve por debajo del offset
comprometido— y no por su letra.

**STOP condition:** cualquier lector puede liberar, truncar o modificar una reserva de un escritor activo.

### OBS-2 — Captura incremental y ciclo de vida del productor

Nivel **A** (caída del observador o del controlador) es obligatorio para Fabric. Nivel **B** (caída de la
JVM propietaria mientras el `sh` continúa) exige un spike antes de cualquier afirmación de continuidad: la
captura actual viaja por pipes propiedad de la JVM y `console.log` fue eliminado como buffer de staging,
así que la salida del hijo posterior a esa muerte **no tiene hoy camino al Output Plane**. Prohibido un
spool de secretos en claro como solución de recuperación.

### OBS-3, OBS-4, OBS-5, OBS-6, OBS-7

Detalle en el enunciado del evolutivo. Los presupuestos numéricos de OBS-3 se fijan **con línea base
medida**; un umbral inventado es un defecto, no un gate.

## 6. Contrato de no regresión transversal

Una regresión demostrada en cualquiera de estas propiedades bloquea el cierre del bloque, aunque las
pruebas funcionales nuevas sean verdes:

ausencia de secretos en bytes persistidos y salida observada · separación Event/Output/TypedStepValue ·
un solo origen durable para stdout/stderr · inmutabilidad de los bytes confirmados · cursores que no
retroceden ni apuntan a otra run · rechazos explícitos ante estados ilegibles · ausencia de resultados
semánticos derivados del texto de consola · recuperación sin ejecución duplicada de Steps · memoria
acotada en lectura incremental · ausencia de dependencias Jenkins en el motor · persistencia del
protocolo público S6 y de sus garantías de admisión.

## 7. Gobierno y evidencia

Por bloque: obtener estado SDDK vigente → revisar código (no creer receipts) → registrar premisas, riesgos
y límites → **al menos una prueba RED** que reproduzca el defecto o la ausencia de capacidad → implementar
la vertical → pruebas focalizadas y mutaciones discriminantes → UAT del binario instalado cuando aplique →
anotar SHA, comandos, versiones, hashes, resultados, *skips* y limitaciones → commit atómico con
Conventional Commits → actualizar este documento y el estado SDDK → cerrar sólo con el gate verificado.

Resultados de SHAs, worktrees o artefactos distintos **no se agregan** como una sola certificación.

## 8. Referencias

`ADR-M1-output-authority.md` · `ADR-OBS-001-live-output-authority-and-lpr011r2-supersession.md` ·
`ADR-OBS-002-read-recovery-ownership.md` · `ADR-0088-cli-observation-contract.md` ·
`OBSG_READER_RECOVERY_INTERFERENCE_RECEIPT.md` · `OBSF_PUBLISHED_READ_SIDE_AND_CHUNK_COST_RECEIPT.md` ·
`S5_4_OBSERVATION_VERTICAL_RECEIPT.md`