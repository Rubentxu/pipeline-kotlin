# Roadmap de refactorización y correcciones — PipelineKotlin

Base: `main` · commit `bd5edb8de` · v0.91.0

La propuesta es reorganizar `pipeline-application` y simplificar `pipeline-step-sdk` mediante vertical slices, separación de responsabilidades y reducción de connascencia, aprovechando las abstracciones existentes.

No se añadirán funcionalidades. Se conservarán los contratos públicos y los comportamientos correctos; únicamente cambiarán los comportamientos en los que se haya demostrado un defecto.

## 1. Objetivo arquitectónico

El problema no se resuelve simplemente dividiendo archivos grandes. Hay que conseguir que cada caso de uso tenga un propietario claro de sus decisiones, dependencias y efectos.

Organización objetivo de las responsabilidades

```
pipeline-application
├── run/
│   ├── admission/       # Validar ejecución
│   ├── compilation/     # Preparar pipeline
│   ├── execution/       # Coordinar ejecución
│   ├── recovery/        # Recuperación existente
│   └── composition/     # Construcción de dependencias
├── observe/
│   ├── console/         # Lectura de salida
│   ├── events/          # Consulta de eventos
│   ├── inspect/         # Inspección
│   └── projections/     # Explain, Cost, Telemetry
├── plugins/
│   ├── admission/       # Integridad y confianza
│   └── composition/     # Registros y capacidades
└── cli/                 # Parsing, dispatch y formato

pipeline-step-sdk/runtime
└── durable/
    ├── shell/           # Orquestación shell
    ├── process/         # Lanzamiento y terminación
    ├── transcript/      # Captura y drenaje
    ├── watchdog/        # Timeout y cancelación
    ├── agent/           # Adaptación al agente existente
    └── protocol/        # Archivos y compatibilidad
```

Es una organización lógica orientativa dentro de los módulos actuales, no una propuesta para crear nuevos módulos Gradle ni nuevas API públicas.

El recorrido de ejecución debe quedar claramente delimitado:


El SDK no debe conocer decisiones de negocio del CLI, y la capa de aplicación no debe implementar detalles de procesos Unix o FIFOs.

## 2. Connascencia: qué corregiremos

La connascencia mide cuánto necesitan coordinarse dos piezas de código para funcionar. El objetivo es mantener los acoplamientos fuertes dentro de límites pequeños y evitar que atraviesen módulos.


connascence.io

|Tipo|Problema identificado|Refactor|
|---|---|---|
|Significado|`fencingToken` empleado como instante temporal|Tipos de identidad y tiempo distintos|
|Ejecución|Recover depende del orden de apertura, lease, lectura y escritura|Separar inspección, decisión y aplicación|
|Timing|Watchdog, resultado y terminación del shell dependen de carreras|Decisor de terminal y propiedad del ciclo de vida|
|Identidad|IDs de operaciones interpretados mediante convenciones de cadenas|Resolver la identidad una sola vez|
|Posición|Algunas funciones agrupan numerosos parámetros relacionados|Requests y contextos tipados, sin bolsas genéricas|
|Algoritmo|Varias proyecciones reconstruyen asociaciones entre eventos|Compartir la regla de correlación|

Principio de diseño: connascencia fuerte cerca, connascencia débil lejos. No se creará una abstracción nueva si únicamente desplaza la complejidad.

## 3. Roadmap propuesto

## H1 — Corregir invariantes de recuperación

P0

Alcance: `pipeline-runtime`, `pipeline-runtime-api` y `pipeline-application`.

Este hito corrige los tres defectos funcionales principales de la auditoría anterior, antes de mover sus implementaciones.

- `--dry-run`: ninguna adquisición de lease ni recuperación implícita con escritura.
    
- `deadlineMs`: reemplazar el cálculo desde `fencingToken` por una decisión temporal coherente con `Clock`.
    
- `cursors.load`: diferenciar ausencia legítima de error de lectura.
    
- Reutilizar los puertos y ADTs actuales siempre que expresen correctamente estos casos.
    
- Conservar los formatos de almacenamiento existentes.
    

Entrega verificable: recuperación real e inspección simulada con resultados coherentes; ninguna repetición de efectos externos y fallos de almacenamiento expresados mediante negativas tipadas.

### Estado de H1 — cerrado

Los tres invariantes están corregidos y la puerta completa está en verde.

|Defecto|Corrección|Commits|
|---|---|---|
|`--dry-run` tomaba la lease|La acquisition se declara en vez de hacerse; el dry run no escribe|`eb9a22723`|
|El plazo se calculaba desde `fencingToken`|El decider no calcula tiempo; el plazo lo lee el adaptador de su propio reloj|`eb9a22723`|
|La lectura fallida del marcador se leía como ausencia|Una lectura que falla es `StorageError`; solo la ausencia es ausencia|`eb9a22723`|

La ruptura de ABI que implica el plazo —`RecoveryChoice.Reattach` pierde `deadlineMs`— está autorizada por el maturity `EXPERIMENTAL` de `pipeline-runtime-api` y recibida en `published-contract-exceptions.json` (entrada 21, con el SHA que la introduce). El tren 0.90.0 quedó cerrado en `v0.90.0`; como el árbol ya no producía los mismos bytes, la 0.91.0 se abre aquí (`ed22ff91f`) y su candidata es `v0.91.0-rc1`.

Medido sobre `bd5edb8de` con `scripts/verify-all.sh` sin filtros:

```
PASS  sha=bd5edb8de33a89e6b9f56d80c5ea09bc3c1acfe4  elapsed=170s  budget=900s  tests=rewritten:1339 read:0  scenarios=observed:62 executed:62
```

- 183 informes JUnit reescritos por esta ejecución, 1339 tests, 0 fallos, 0 omitidos.
- 62 de 62 escenarios E2E ejecutados y en verde, 588 comprobaciones sobre 112 ejecuciones.
- 170 s de reloj de pared frente al presupuesto de 900 s.

## H2 — Refactor del núcleo durable del SDK

Foco principal: `DurableShellExecutor.kt` — aproximadamente 1.803 líneas.

Actualmente concentra materialización de scripts, construcción del wrapper, creación de FIFOs, lanzamiento del agente, procesos, watchdog, cancelación y limpieza.

La separación propuesta conserva `DurableShellExecutor` como fachada compatible y extrae responsabilidades internas:

|Componente interno|Responsabilidad única|
|---|---|
|`ShellProtocolLayout`|Nombres y rutas del protocolo persistente|
|`ShellLaunchPreparation`|Preparar scripts, wrapper y entorno|
|`ShellProcessLauncher`|Lanzar procesos y devolver su identidad|
|`AgentRendezvous`|Coordinar los FIFOs y el agente ya existente|
|`ShellWatchdog`|Vigilar tiempos y solicitar terminación|
|`ShellTerminalDecision`|Clasificar salida, timeout o fallo|
|`ShellResourceCleanup`|Liberar los recursos pertenecientes al lanzamiento|

Los nombres son orientativos. Antes de crear cada componente se debe comprobar si `DurableShellFiles`, `LaunchedShell`, `TranscriptDrain` u otra abstracción ya resuelve esa responsabilidad.

No habrá dos motores shell. `executeCore()` debe convertirse en un coordinador legible, no duplicarse en una nueva implementación.

Entrega verificable: ejecutar, terminar, cancelar y recuperar `sh` con las mismas salidas, efectos y protocolos persistentes.

### Estado de H2 — abierto, con la investigación previa hecha

Antes de crear cada componente se comprobó qué abstracción existente ya es la dueña de cada responsabilidad. El resultado, medido sobre `DurableShellExecutor.kt` (1.802 líneas):

|Responsabilidad|¿Existe ya?|Consecuencia|
|---|---|---|
|Transcripto de consola: nombre canónico y compatibilidad de lectura|`DurableShellFiles` (41 líneas)|Ya es la dueña del nombre `console.log`; se conserva y se le añade el resto del protocolo|
|Identidad de un lanzamiento: hijo, bombas, sumideros, descriptores de calentamiento|`LaunchedShell`|Ya es la forma de retorno del lanzador; no se crea otra|
|Bombas de transcripción, drenaje y veredicto de persistencia|`TranscriptDrain.kt`|Ya está fuera del ejecutor; no se toca|
|Materialización de scripts y wrapper, FIFOs, agente, watchdog, cookie-scan, limpieza|Dentro del ejecutor|Es lo que se extrae|

Lo que sí falta es una autoridad única de los nombres del protocolo persistente: 26 apariciones de literales (`.cookie`, `result.txt`, `result.txt.tmp`, `script.sh`, `script.sh.copy`, `wrapper.sh`, `output.txt`, `fifos/stdout.fifo`, `fifos/stderr.fifo`, `fifos/agent.ready`, `agent.log`, `timeout.flag`) repartidas entre el ejecutor y el contenido del wrapper. `ShellProtocolLayout` es por tanto el primero de los siete componentes, y el que menos mueve comportamiento.

Los otros seis se extraen conservando `DurableShellExecutor` como fachada compatible y sin crear un segundo motor de shell: `executeCore()` debe quedar como un coordinador legible, no como una copia de la implementación actual.

## H3 — Vertical slice completo `core.sh`

Foco conjunto: `pipeline-application` + `pipeline-step-sdk`.

Es la extracción transversal de mayor valor, porque conecta los componentes que actualmente comparten conocimientos sobre el proceso shell.

Archivos principales:

- `CoreShellStep.kt`    
- `durable/ShExecution.kt`    
- `durable/StepDispatchEngine.kt`    
- `DurableShellExecutor.kt`    
- `ProcessDurableTaskRuntime.kt`    

Las responsabilidades finales quedarían así:

|Propietario|Debe conocer|No debe conocer|
|---|---|---|
|`CoreShellStep`|Contrato y codecs de `core.sh`|`ProcessBuilder`, rutas físicas|
|`ShExecution`|Coordinación del caso de uso, resultados y puertos|FIFOs, señales Unix|
|`DurableShellExecutor`|Ejecución concreta del proceso|Eventos de dominio, CLI|
|`Output Plane`|Persistencia y lectura de bytes|Decisiones sobre el pipeline|
|`StepDispatchEngine`|Admisión, invocación y resultado del step|Implementación interna del shell|

Se revisarán especialmente los dos recorridos de ejecución de shell existentes, para distinguir compatibilidad legítima de lógica duplicada. No se fusionarán implementaciones diferentes sin demostrar equivalencia.

Entrega verificable: un `sh` completo con stdout, stderr, redacción, captura tipada y reanudación conserva la misma semántica pública.

## H4 — Refactorizar los casos de uso de `pipeline-application`

Foco principal: ejecución durable, composition y despacho.

`MainDurableRun.kt` concentra compilación, identidad, stores, leases y ejecución. `StepDispatchEngine.kt` incorpora preparación, reconciliación, invocación, bloques y limpieza de credenciales.

Propongo extraer verticalmente:

|Slice|Trabajo|
|---|---|
|Run admission|Reunir decisiones de admisión e identidad|
|Run preparation|Separar compilación y preparación del contexto|
|Run execution|Mantener `runCanonical()` como único punto de coordinación|
|Step invocation|Separar preparación, reconciliación y despacho|
|Plugin admission|Conservar un único propietario de confianza y composición|

`CanonicalRunRequest`, `CanonicalRunState` y `CanonicalRunPolicy` ya son buenos límites. No deben sustituirse por un gran `ApplicationContext` con referencias a todos los servicios.

Reducir la dependencia de `CanonicalRuntimeCapabilityAccess` sobre capacidades no relacionadas; agrupar únicamente las que pertenezcan al mismo ciclo de vida.

Entrega verificable: ambas modalidades de ejecución existentes utilizan los mismos contratos canónicos, sin duplicar el camino de ejecución durable ni alterar el comportamiento del registro de plugins.

## H5 — Separar CLI, consultas y proyecciones

Foco: `CliParser.kt`, `MainConsoleCli.kt`, Observe, Explain, Cost y Telemetry.

El parser actual, de unas 1.931 líneas, debería delegar en parsers por familia de comando. La interpretación de opciones debe terminar antes de comenzar cualquier efecto.

`MainConsoleCli.kt` debe separar selección, lectura, seguimiento y presentación, reutilizando los lectores existentes.

Las proyecciones Explain, Cost y Telemetry deben compartir las reglas de identidad y asociación de pasos, no una única proyección gigantesca.

Se corregirá además la inconsistencia de `CLI_VERBS` para que la ayuda refleje los comandos ya implementados, incluido `runs recover`.

Entrega verificable: mismas invocaciones válidas, mismas respuestas públicas y mismo comportamiento de lectura, sin lógica de consulta duplicada en los controladores CLI.

## H6 — Reducir deuda del harness y asegurar la publicación

Foco: `examples/run.sh`, `verify-all.sh` y scripts de release.

Refactorizar internamente el lanzador E2E, conservando los escenarios actuales que aporten garantías diferentes.
Corregir el procedimiento existente de promoción estable y su evidencia de firma, sin introducir un sistema de publicación nuevo.
Consolidar verificaciones redundantes cuando protejan exactamente la misma propiedad, y mantener la clasificación entre pruebas parciales y puerta completa.
Entrega verificable: misma cobertura contractual o superior con menor código de infraestructura de pruebas, release reproducible y procedencia verificable.

## 4. Orden y dependencias


H1 y H2 pueden desarrollarse en paralelo, en equipos separados. Después, H3 debe integrar los cambios del shell antes de acometer H4. Así se evitan múltiples agentes modificando simultáneamente `ShExecution` o `StepDispatchEngine`.

Para estos refactors grandes emplearía migraciones incrementales en `main`, manteniendo el proyecto compilable tras cada integración. Branch by Abstraction es apropiado cuando existe un límite estable y se necesita sustituir gradualmente una implementación; no debería utilizarse para crear capas temporales innecesarias.


martinfowler.com

## 5. Reglas obligatorias para el agente

Los agentes deberán trabajar con estos criterios:

1. Refactor antes que rediseño. Identificar la responsabilidad, moverla y eliminar la implementación anterior; no dejar dos caminos activos indefinidamente.    
2. Una autoridad por concepto. Especialmente identidad de operación, ciclo de vida de procesos, resultado terminal, redacción y persistencia.    
3. Sin cambios accidentales de ABI ni de formatos durables. Toda modificación externa debe justificarse por un defecto demostrado.    
4. Programación funcional donde aporta valor. Decisiones mediante funciones puras y ADTs; filesystem, relojes, procesos y streams aislados tras puertos.    
5. Propiedad explícita de recursos. Cada proceso, pipe, hilo y descriptor debe pertenecer a un lanzamiento concreto, y su limpieza debe producirse también ante excepciones o cancelaciones. El ciclo de vida estructurado de Kotlin es una referencia útil, sin necesidad de reescribir todo el shell utilizando coroutines.    
6. No multiplicar interfaces. Introducirlas donde separen una política de un mecanismo o exista una necesidad real de sustitución, no por cada clase.    
7. Borrar código reemplazado. El objetivo es reducir complejidad neta, no aumentar la cantidad de envoltorios.    
8. Verificar comportamiento, no estructura interna. Las pruebas deben seguir siendo válidas si una responsabilidad cambia de archivo o paquete.
    
## 6. Política de testing y aceptación

No hace falta construir otra suite. Para cada hito usaría la infraestructura ya existente:

|Fase|Verificación|
|---|---|
|Desarrollo|Unitarios puros de las decisiones modificadas|
|Integración|Tests reales mínimos sobre puertos afectados|
|Cierre funcional|1–2 escenarios discriminantes existentes o efímeros|
|Antes de release|`scripts/verify-all.sh` completo|
|Después del bloque|Eliminar harness auxiliar, UAT/E2E/AAT efímeras y fixtures desechables|

Los objetivos medibles serán:

# 0
Funcionalidades nuevas y cambios de comportamiento no justificados

# 0
Duplicaciones de caminos de ejecución introducidas
# ≤ 15 min
Presupuesto total de la puerta completa
# ≤ 1×

Objetivo de líneas de pruebas permanentes frente a producción

Metas de aceptación, no resultados ya medidos tras los refactors.

Para valorar la mejoría, además de LOC mediría dependencias cíclicas, número de responsables por caso de uso, supresiones de complejidad, tiempo de compilación incremental y tiempo real de pruebas. Una reducción de líneas en `DurableShellExecutor` que simplemente traslade su complejidad a otro archivo no contará como mejora.

## 7. Qué queda expresamente fuera

No se implementará `output release`, ni nuevas opciones CLI, ni proveedores de ejecución, ni protocolos de agente, ni capacidades de observabilidad. Tampoco se modificarán las políticas de retención actualmente elegidas.

En el caso del Output Plane solo se refactorizarán las responsabilidades existentes y se corregirán los problemas demostrados de propiedad de recursos, caché o gestión de ciclos de vida.

Cada hito debe acabar con una release candidata integrada en Git remoto, verificable de forma independiente, sin depender de que el siguiente hito llegue a completarse.

Mi prioridad sería H2 y H3, después de corregir H1. Es donde confluyen la mayor complejidad, la connascencia temporal y el riesgo de errores difíciles de reproducir. A continuación abordaría H4, que es donde la extracción por vertical slices puede producir la mayor reducción de acoplamiento en `pipeline-application`.
