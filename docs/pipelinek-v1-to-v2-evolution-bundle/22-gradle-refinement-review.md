# Revisión y refinamiento con prácticas de Gradle Kotlin DSL

**Fecha:** 6 de octubre de 2026. **Revisión:** r2. **Estado:** especificaciones propuestas; no implementación ni release certificada.

## Qué mejora esta revisión

El paquete original ya acertaba al separar bibliotecas y plugins, proyectar introspección desde las autoridades de V2, compartir la decisión de preparación y exigir la retirada final de V1. Conservo esas decisiones y los hitos M0–M10. El refinamiento convierte las ideas sobre compilación y rendimiento en contratos comprobables, apoyados en Gradle 8.14.5, Gradle 9.8.0, Kotlin y el código de PipelineK inspeccionado.

La mejora principal es separar **compilar código**, **cargarlo**, **evaluarlo con un contexto nuevo** y **ejecutar operaciones por el runtime existente**. Podemos reutilizar la compilación sin reutilizar parámetros, credenciales, resultados de Steps, objetos de ejecución o decisiones de admisión de otro run.

## Antes y después

| Tema | Propuesta original | Refinamiento |
| --- | --- | --- |
| Compilación | CacheKey.v2 y spike de rendimiento | Fases explícitas, perfil efectivo único y equivalencia de ambos frontends antes de reutilizar |
| Identidad | Digests y configuración relevante | Fuente original/transformada, orden efectivo, transitivas, código generado, JDK/API roots, plantilla/opciones y versiones compatibles |
| Lectura de dependencias | Resolver/admitir antes de usar | Congelar los mismos bytes admitidos que consume el compilador; prueba determinista contra reemplazos entre hash y lectura |
| Caché | Memoria opcional; persistencia no asumida | Tres decisiones medidas: memoria, persistencia y sesiones; artefacto real, leases, concurrencia, corrupción y publicación atómica |
| Bibliotecas | JAR local/Maven, distinto de plugin | Grafo transitivo completo y reproducible; bibliotecas raíz y JAR de soporte tienen papeles distintos |
| SDK/manifest | Admisión estática y contraste en runtime | Reutilizar el manifest canónico de S6 para todas sus familias; conservar compatibilidad hasta demostrar paridad |
| Diagnósticos | Códigos y sugerencias desde metadatos | Conservar severidad/ubicación originales, distinguir fases y mantener warnings también en hits |
| Recursos | Presupuestos honestos | Cola/caché/leases con límites verificables; RSS/heap/native observados por separado; cancelación real del backend |
| Certificación | UAT/AAT y retirada de V1 | Trazabilidad requisito→ADR→unidad→prueba, evidencia del candidato exacto y NO-GO explícito para optimizaciones deshabilitadas |

## Qué copiar de Gradle

1. **Configuración propiedad del adaptador.** Gradle construye su entorno y sus opciones. En PipelineK, el mismo perfil efectivo debe configurar realmente host/mapper y producir la identidad; versiones/opciones copiadas a mano en varios transportes acabarían divergiendo.
2. **Dependencias preparadas antes del cuerpo.** Gradle procesa fases de dependencias/plugins y genera accessors. PipelineK puede preparar manifests, admisión, plan y façades desde la configuración existente, sin otra DSL de dependencias.
3. **Artefactos compilados reales.** Gradle genera clases y después ejecuta el programa. Las APIs de Kotlin para guardarlas no demuestran por sí solas que podamos reconstruir toda la plantilla, metadatos y entry point de PipelineK. El spike exige el round trip real.
4. **Cachés con responsabilidades distintas.** Resolver JARs, guardar bytecode y retener clases cargadas son mecanismos diferentes. Una caché en la JVM de un comando termina con ese proceso; mejorar comandos CLI independientes exige persistencia probada o un propietario duradero ya existente.
5. **Evaluación fresca.** Reutilizar clases no permite conservar el objeto evaluado de una ejecución anterior. También hay que caracterizar singletons y estado estático: un receiver nuevo no aísla ese estado.

Estos patrones se implementan dentro de los puertos y propietarios existentes. La documentación canónica ya define `PipelineScriptEngine.compile/evaluate` y `PipelineCompiler`; el código actual expone un `ScriptingHost.compile` que compila y evalúa. Por eso primero se prepara el inventario de consumidores y se preserva su contrato mediante adaptación, antes de cualquier migración pública/ABI. No hace falta un tercer motor.

## Cómo encaja la corrección del warning de Kotlin

El aviso de `sun.misc.Unsafe::invokeCleaner` viene del lector rápido de JAR de Kotlin, no de la sintaxis del pipeline. La política propuesta selecciona el lector estándar en host y mapper y comprueba compilación real en una JVM nueva con Unsafe prohibido. Los warnings y errores del script conservan su severidad y ubicación.

Gradle 9.8 añade `--sun-misc-unsafe-memory-access=allow` para su daemon en Java 24+: silencia el aviso manteniendo la operación. Gradle 8.14.5 usa otra combinación de Kotlin/lenguaje y no incluye esa política en el archivo inspeccionado. Esta diferencia explica por qué no debemos copiar indiscriminadamente el launcher de Gradle. Aquí la corrección evita la operación concreta y se prueba con `deny`.

El lector estándar puede emitir una nota INFO de configuración. Se conserva en evidencia estructurada; su presentación humana tiene una regla estrecha en el renderer. No se filtran todos los INFO, warnings, stderr ni diagnósticos con ubicaciones transformadas. Mediremos el coste del lector antes de buscar otra optimización. El archivo FastJar inspeccionado es igual en Kotlin 2.4.10 y 2.4.20; subir solo la versión no constituye una corrección demostrada.

## Límites que quedan explícitos

- La caché guarda código/metadatos/diagnósticos de compilación. El modelo de Gradle Configuration Cache no se traslada a un `PipelineSpec` evaluado sin observar todas sus entradas y efectos; esa evolución queda fuera de esta propuesta.
- `validate` actualmente compila y evalúa construcción DSL. La separación de fases no lo convierte automáticamente en comprobación pura de Kotlin arbitrario. `step --plan` sí mantiene su contrato puro sobre una invocación tipada.
- Los classloaders delimitan visibilidad y tipos; no son un sandbox del sistema operativo ni una prueba de ausencia de I/O en una biblioteca.
- La caché persistente inicial es local al propietario. Digests detectan inconsistencias, pero no autentican código frente a alguien que reescribe metadata y payload. Un cache ejecutable compartido requiere otra decisión.
- Las sesiones reutilizan un propietario/worker existente cuando hay valor medido. Esta revisión no propone otro daemon, controller, provisioner, protocolo remoto o scheduler.

## Orden de implementación

| Momento | Resultado necesario | Evidencia de salida |
| --- | --- | --- |
| M0 / trabajo de compilador ya autorizado | Caracterizar consumidores/diagnósticos/perfil; corrección enfocada del lector cuando corresponda al ciclo activo | Baseline exacto y probes reales; sin abrir otro TRAIN |
| M3–M5 | Manifest canónico, snapshots admitidos y grafo completo de bibliotecas | Admisión antes de carga, transitivas/offline, orden y bytes coherentes |
| M6 | Proyección de diagnósticos/perfil desde el mismo modelo | Warning/error/source map reales, sin catálogos duplicados |
| M7 obligatorio | Perfil efectivo, identidad v2, fases y compatibilidad | Corpus real, ABI/consumidores y decisión medida |
| M7 condicional | Memoria → persistencia, y sesiones existentes por decisión propia | GO separado, artefactos reales, conteo del compilador y gates de cada modo |
| M8–M9 | Convergencia de herramientas y certificación | Candidato/distribución exactos; decisiones NO-GO visibles |
| M10 último y obligatorio | Retirar V1 y residuos seleccionados | Dependencia cero antes de borrar; certificación posterior desde clon limpio |

La propuesta no interrumpe el tren semántico/SDK de S6 en curso. Se integra en el roadmap vigente; los IDs de este bundle son planificación hasta que la autoridad existente los adopte. NO-GO de una optimización es válido: no se anuncia como entregada ni se conserva V1 para suplirla.

## Dónde revisar los cambios

- [Compilación/evaluación/perfil y JVM](specifications/19-script-compilation-and-evaluation.md).
- [Identidad v2 y medición](specifications/09-cachekey-v2-and-compilation-performance.md).
- [Artefactos, memoria y persistencia](specifications/20-compiled-artifact-cache.md).
- [Sesiones y recursos](specifications/21-compiler-session-lifecycle.md).
- [Roadmap](plans/11-roadmap.md) y [unidades de trabajo](plans/12-work-units.md).
- [Trazabilidad de 16 requisitos](quality/gradle-traceability.md), [UAT](quality/13-UAT-master-plan.md), [AAT](quality/14-AAT-master-plan.md) y [protocolo de benchmark](quality/gradle-benchmark-protocol.md).
- [Fuentes primarias y versiones](REFERENCES.md) y [snapshot de evidencia](quality/gradle-primary-evidence.json).

El ZIP original se verificó contra su manifest. Este refinamiento conserva todos sus documentos, modifica contratos/planes afectados y añade las especificaciones/ADR/criterios necesarios. La [validación del paquete](quality/gradle-document-validation.json) distingue consistencia documental de aceptación del producto: nuevas UAT/AAT/benchmarks están NOT_RUN; las pruebas enfocadas del estudio anterior no certifican esta arquitectura. JDK 25 y el gate completo del repositorio no se presentan como aprobados.
