# PK-RC-RENDER-001 — Reparación del renderer y cierre de certificación

## Estado
`CERTIFICATION_FAILED / NO_GO` sobre `v0.56.0-rc4` (0/13 escenarios).
Nueva candidata objetivo: `v0.56.0-rc5` desde `origin/main`.

## Problema
El renderer rechaza el evento `RunCorrelated` (emitido antes de `RunStarted`) antes
de llegar al primer stage. `ConsoleFamilyLines.run()` no trata `RunCorrelated`
dentro de la familia RUN, por lo que el dispatcher lanza `unrouted` o equivalente
y aborta la pipeline antes de que el primer step ejecute.

## Por qué
`v0.56.0-rc4` se publicó sin que la prueba `RendererRoutePartitionTest` (en
`pipeline-architecture-tests`) detectara la discrepancia entre las rutas
encaminadas por el dispatcher y las familias que las manejan. Hay que
investigar por qué no impidió la publicación de rc4 antes de aplicar el
arreglo definitivo.

## Alcance
- Corregir `ConsoleFamilyLines.run()` para tratar `RunCorrelated` dentro de la
  familia RUN.
- Mantener leyes semánticas: el evento transporta la correlación, pero su
  renderización no crea otra identidad ni decide el resultado de ejecución.
- Revisar el escapado de cualquier dato externo que se muestre en consola.
- No añadir `ArtifactEntry`, `StashedEntry`, `RestoredEntry` ni `HtmlReportEntry`
  al dispatcher: son estructuras de payload, no variantes de `DomainEvent`.

## Tareas
- [ ] **B1 — Reproducir el fallo de rc4** sobre el ZIP publicado (SHA-256
  `1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`), sin
  Gradle ni mocks. Confirmar que `RunCorrelated` llega al renderer, que es
  rechazado, y que la pipeline aborta antes del primer stage.
- [ ] **B2 — Investigar `RendererRoutePartitionTest`** sobre el código defectuoso
  de rc4 (sin caché, conservando salida). Comprobar si detecta la discrepancia
  de `RunCorrelated`. Si falla, investigar por qué no impidió publicar rc4. Si
  pasa, corregir el oráculo o la extracción de rutas.
- [ ] **B3 — Verificar la detección mediante mutación deliberada** del
  dispatcher: una mutación que rompa la ruta de `RunCorrelated` debe hacer
  rojo el test.
- [ ] **B4 — Catálogo exhaustivo de `DomainEvent::class.sealedSubclasses`**:
  exactamente una ruta, una familia capaz de manejarla, sin `unrouted` para
  variantes legítimas, comportamiento explícito en NORMAL/QUIET/EVENTS, acuerdo
  entre incremental y batch. El test debe fallar automáticamente cuando aparezca
  una nueva variante sin renderización compatible.
- [ ] **B5 — Aplicar el fix** en `ConsoleFamilyLines.run()` (tratar
  `RunCorrelated` dentro de RUN) + escapado defensivo de datos externos.
- [ ] **B6 — Gate completo pre-publicación**:
  - `:pipeline-architecture-tests:test` (con el catálogo y la mutación)
  - `:pipeline-application:test`
  - `:pipeline-release:releaseGate`
  - `:pipeline-application:distZip`
  - UAT de distribución instalada
- [ ] **B7 — UAT real de ejecución** sobre el ZIP instalado, los 13 escenarios
  con oráculos individuales.
- [ ] **B8 — Publicación de `v0.56.0-rc5`** con release script, tag remoto,
  SHA-256 verificado.
- [ ] **B9 — Entrega al harness independiente** y espera del veredicto externo.

## Restricciones
- No modificar `v0.56.0-rc4` ni sus artefactos.
- No declarar CERTIFIED sin veredicto externo positivo.
- No iniciar PK-2 ni `PAIR_CERTIFIED` sin CERTIFIED.

## Criterio de cierre
Producto ejecutable + veredicto externo positivo sobre los 13 escenarios.
