# RP6-CLOSEOUT — cierre de RP-6 y disposición de la cola

**Decisión:** RP-6 queda **CLOSED**. Sus tres elementos en cola llegaron a
`CERTIFIED_AT_SHA`. WU-094 y Tier C quedan **NOT STARTED by decision**, no por falta de
tiempo.

**Alcance:** este documento no implementa funcionalidad. Regenera inventario, reconcilia la
clasificación y decide, por necesidad real, si RP-6 conserva algún requisito vivo.

---

## 1. Lo que RP-6 entregó

| WU | Step | Forma de entrega | Recibo | Decisión que lo gobierna |
|---|---|---|---|---|
| RP6-A / WU-091 | `core.lock` | core BlockStep, `HANDLER_CONTINUATION`, backend de fichero POSIX | `WU091_LOCK_RELEASE_RECEIPT.md` | same-host; sin controller/worker (RP-8) |
| RP6-B / WU-092 | `core.input` | core Step | `WU092_INPUT_RELEASE_RECEIPT.md` | pregunta cerrada con valor tipado |
| RP6-C / WU-093 | `http.request` | **OFFICIAL_PLUGIN** | `WU093_HTTP_IMPLEMENTATION_RECEIPT.md` | `STEP_ECOSYSTEM_POLICY.md:59` |

El tercero cambió de forma a mitad de tren. `core.httpRequest` se descubrió, especificó y
construyó (G1–G3b) y luego se contrastó con la política de ecosistema publicada, que ya lo
situaba como concern de protocolo/vendor. El trabajo se conservó cambiando **ownership**, no
reescribiendo el diseño: `docs/v2/07-uat/WU093_HTTP_DELIVERY_RECONCILIATION.md`. Los
documentos de diseño y spec anteriores se conservan con su título original porque son el
registro de lo que se decidió y de por qué se corrigió.

## 2. Inventario regenerado, y dos defectos reales del generador

`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` estaba generado el 2026-09-27 contra
`417b02218b13`, una SHA que ya no refleja el árbol. Sus tres entradas de Tier B eran
incorrectas, y no por descuido de una línea:

**`core.lock` figuraba como `NO_GO per SESSION_POINTER; preserved in stash`.** Eso leyó un
fichero que `AGENTS.md` clasifica explícitamente como no autoritativo, y lo hizo años
—instalaciones— antes que los recibos. `SESSION_POINTER.md` lleva sin tocarse desde el 28 de
septiembre.

**`core.input` y `core.httpRequest` figuraban como `TBD`**, siendo que ambos se
implementaron, certificaron y cerraron el 2 de octubre.

Al regenerar aparecieron dos defectos en el propio generador, y estos sí son la parte
interesante del closeout:

1. **El descubrimiento dependía del nombre del fichero.** `parse_sdk_step_keys` filtraba por
   `*Key.kt` y `*Contract.kt`. `http.request` declara su clave en `HttpRequestStep.kt`
   (`HttpRequestKey.VALUE = PluginStepId("http.request")`), que no cumple ninguno de los dos
   sufijos, así que **un Step `CERTIFIED_AT_SHA` era invisible para el inventario**. El
   contrato por el que se descubre un Step de plugin es el literal `PluginStepId("…")`, no
   cómo se llama el fichero. Se eliminó el filtro. Un Step que se vuelve invisible por su
   nombre de fichero es exactamente el fallo que ese programa existe para impedir.

2. **Un Step registrado sin mapeo se leía como ausente, no como pendiente.** `CoreLockStep` y
   `CoreInputStep` estaban en producción y en el registry, pero `resolve_core_step_key` no los
   conocía, así que el generador emitía `DRIFT` y los omitía. Un mapeo que falta es una tabla
   que no se ha mirado, no una afirmación de que el Step no existe.

Ambos fixes están en `.agent/scripts/regenerate_step_inventory.py`. Resultado: `drift=0`, 34
filas de Step, y los tres Steps de RP-6 con `CERTIFIED_AT_SHA` y su recibo enlazado.

**Deuda de gobierno que esto deja visible:** el inventario se declara a sí mismo fuente de
verdad y delega la autoridad en «the script output», pero el script vive bajo `.agent/`, el
árbol que `AGENTS.md` degrada a histórico. Un documento normativo no debería apuntar su
autoridad a un árbol no autoritativo. No se ha movido en este closeout porque cambiar la
localización del generador es una decisión de gobernanza con su propio recibo, no un efecto
colateral de cerrar RP-6.

## 3. Clasificación core vs OFFICIAL_PLUGIN

La distinción que RP-6 construyó y que el closeout ratifica:

| Pertenece al **plugin** | Pertenece al **runtime** |
|---|---|
| el protocolo: método, cabeceras, rangos de estado | el permiso de salir a la red: `NETWORK_EGRESS_CAPABILITY` |
| el transporte, único que abre un socket | la política por ejecución: `ShOptions.networkEgress` |
| el contrato: codecs, descriptor, `ReplayPolicy.NEVER` | la credencial, resuelta en el borde |
| la façade DSL que baja a `registryStep` | el sink de eventos y el presupuesto de ejecución |

`NETWORK_EGRESS_CAPABILITY` y `NetworkEgressPolicy` viven en `pipeline-domain` porque esquema,
host y puerto son propiedades de un **destino de red**, no del protocolo HTTP que un Step
hable. Un `git` por https, un pull de registro y un feed de artefactos necesitan los mismos
tres hechos. Ponerlos en el plugin ataría la allowlist a un Step; duplicarlos crearía dos
opiniones sobre qué significa el puerto 443, que es el punto donde una allowlist deja de
significar lo que dice.

## 4. WU-094 no se abre

`markdown-toolkit-plugin` era una **propuesta** en la línea de cola, escrita antes de que
nadie la pidiera. Cerrar RP-6 no la convierte en requisito: un TBD no es un criterio de
salida, y fabricar uno para poder declarar una work unit «cerrada» es exactamente el
movimiento que hace que un roadmap deje de significar nada.

La demanda que la motivaba —renderizar Markdown a HTML, generar TOC, validar headings— se
sigue cubriendo hoy con `sh("markdownlint …")`, con el coste ya conocido: el transcript de
consola mezclado con la salida y dependencia de binarios presentes en la imagen. Si esa
fricción aparece en un pipeline real, hay una razón concreta para abrirla, y el precedente
`http.request` da la forma exacta: plugin tipado, `registryStep`, sin rama de compilador.

Tier C (readTOML/writeTOML, tar/untar): **NOT STARTED by decision**, misma regla.

## 5. Lo que RP-6 deja abierto a propósito

Estos no son pendientes de RP-6; son límites registrados en
`WU093_HTTP_IMPLEMENTATION_RECEIPT.md`.

| Deuda | Decisión de no volver aquí |
|---|---|
| `timeout { }` no acota `http.request` | el presupuesto del bloque lo consume el watchdog de `core.sh`; cerrarlo exige leer `EXECUTION_BUDGET_CAPABILITY` y es cambio de comportamiento |
| la salida de un Step no es observable desde la distribución instalada | requiere un miembro nuevo de `DomainEvent` o una clave que el CLI no expone |
| `http.request` es fail-closed en admisión | correcto por defecto: el permiso de egress existe y sólo se concede con `--allow-network` |
| `PRODUCT-GATE` `BLOCKED_EXTERNAL` | no hay superficie de CI desde `754ddda0` |

Ninguno se arregla aquí. Todos son trabajo de un tren posterior, y los dos primeros son
cambios de contrato público que necesitan su propia autorización.

## 6. Contadores del dashboard

```text
Steps CERTIFIED_AT_SHA:   ver docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md (34 filas, drift=0)
Legacy executable Steps:  0
Registry-primary Steps:   todos los de la tabla
```

El informe heredado de «16 frente a 20 Steps» que la cola de RP-6 mencionaba está resuelto
por la regeneración: la tabla se deriva de `CoreStepRegistryFactory`, del árbol de
`pipeline-step-sdk` y del plugin de ejemplo, no de un recuento manual.

## 7. Siguiente paso

RP-7, y antes de implementarlo una **WÚ de disposición e integración**: reconciliar S0–S2 de
`docs/pipelinek-semantic-evolution/` contra el código actual (mucho ya está implementado) e
insertar S3–S8 y el paquete `pipelinek-agent-secretless` como secuencia explícita dentro de
RP-7 en este `ROADMAP.md`, dejando **un único roadmap autoritativo** en vez de tres colas
paralelas.
