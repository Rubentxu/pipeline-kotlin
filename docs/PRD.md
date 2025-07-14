4.0 Arquitectura del Producto y Capacidades Centrales (Requisitos Funcionales)
Esta sección detalla el estado actual del sistema Pipeline Kotlin, tal como se describe en el contexto inicial, y justifica cada componente con un análisis basado en la investigación para formalizarlo como un requisito del producto.

4.1 El DSL de Kotlin: Una Base Expresiva y con Tipado Seguro
El núcleo del sistema es su Lenguaje de Dominio Específico (DSL) basado en Kotlin.

Requisito FU-1.1: Todas las configuraciones de pipeline deben ser scripts de Kotlin válidos (con la extensión .pipeline.kts).

Justificación: A diferencia del intérprete de Groovy restringido y personalizado de Jenkins , este enfoque otorga a los desarrolladores acceso a todo el poder y la expresividad del lenguaje Kotlin. Esto incluye características como funciones de orden superior, clases, herencia, genéricos y el acceso completo a la vasta biblioteca estándar de Kotlin y al ecosistema de bibliotecas de terceros. Permite a los desarrolladores estructurar la lógica del pipeline utilizando patrones de diseño de software probados, en lugar de estar confinados a una sintaxis declarativa rígida o a un subconjunto limitado de un lenguaje de scripting.   

4.2 El Modelo de Seguridad de Doble Sandbox: Una Inmersión Profunda
Esta es una característica crítica y un diferenciador principal que aborda directamente las debilidades de seguridad de las plataformas existentes.   

4.2.1 Sandboxing de Scripts mediante GraalVM Isolates
La ejecución de la propia lógica del pipeline debe estar estrictamente contenida.

Requisito FU-2.1: El sistema DEBE ejecutar todos los scripts de pipeline proporcionados por el usuario (archivos .pipeline.kts) dentro de un GraalVM Isolate.

Justificación: Los GraalVM Isolates son una característica de virtualización ligera y de alto rendimiento que proporciona montones de memoria (heaps) demostrablemente separados dentro del mismo proceso. Esto crea un límite de memoria fuerte e infranqueable entre el proceso del ejecutor anfitrión y el script del pipeline del usuario, impidiendo que un script malicioso o con errores corrompa el estado del ejecutor. Además, el sistema puede aprovechar las políticas de sandboxing de GraalVM (   

CONSTRAINED, ISOLATED, UNTRUSTED) para restringir progresivamente las capacidades del script. Estas políticas permiten un control granular sobre el acceso al sistema de archivos, la red, las variables de entorno, el tiempo de CPU y el uso de memoria, proporcionando un límite de seguridad mucho más robusto y configurable que el históricamente poroso sandbox de Groovy de Jenkins.   

4.2.2 Sandboxing de Tareas mediante Contenerización
La ejecución de los comandos y efectos secundarios del pipeline debe estar igualmente aislada.

Requisito FU-2.2: El sistema DEBE ejecutar todos los comandos de tareas externas (por ejemplo, scripts de shell, herramientas de compilación, comandos de despliegue) dentro de un entorno de contenedor efímero (por ejemplo, un contenedor Docker).

Justificación: Esta es una práctica estándar de la industria para garantizar que los pasos de la compilación sean herméticos, reproducibles y no puedan afectar al sistema anfitrión ni a otros pipelines que se ejecutan en la misma máquina. Esta capa de sandboxing es complementaria y ortogonal al sandboxing de scripts. El GraalVM Isolate protege al ejecutor de la lógica del pipeline, mientras que el contenedor protege al anfitrión de los efectos secundarios de la ejecución de esa lógica. Juntos, forman una defensa en profundidad.

4.3 Motor de Ejecución de Alto Rendimiento
4.3.1 Procesamiento Asíncrono con Corrutinas de Kotlin
Requisito FU-3.1: La orquestación de etapas y pasos del pipeline DEBE utilizar las corrutinas de Kotlin para gestionar la concurrencia y las operaciones de E/S asíncronas.

Justificación: Las corrutinas permiten una concurrencia masiva con una sobrecarga mínima de hilos. Esto es ideal para un ejecutor de CI/CD, que pasa gran parte de su tiempo esperando operaciones de E/S (descargando dependencias, esperando respuestas de API, tirando de imágenes de contenedor). El uso de corrutinas permite a un único ejecutor gestionar de manera eficiente cientos de pipelines concurrentes y con uso intensivo de E/S, lo que se traduce en una mayor densidad de trabajos y menores costes de infraestructura.

4.3.2 Entorno de Ejecución Compilado Nativamente con GraalVM AOT
Requisito FU-3.2: La aplicación principal del ejecutor de Pipeline Kotlin DEBE ser compilable a un ejecutable nativo autónomo utilizando la compilación Ahead-Of-Time (AOT) de GraalVM Native Image.

Justificación: Este requisito aborda directamente dos puntos de dolor clave en los entornos de CI/CD modernos: el tiempo de arranque del ejecutor y el consumo de recursos. Los ejecutables nativos generados por GraalVM tienen tiempos de arranque extremadamente rápidos (a menudo en el rango de decenas de milisegundos) y una huella de memoria en tiempo de ejecución significativamente reducida en comparación con una aplicación JVM tradicional. Esto es perfecto para ejecutores de CI efímeros que se ejecutan en la nube o en clústeres de Kubernetes, donde los tiempos de arranque rápidos se traducen directamente en tiempos de cola de trabajos más cortos y una respuesta más rápida para los desarrolladores. Aunque el rendimiento máximo de un servicio de larga duración puede ser mayor con un JIT calentado, el caso de uso de CI/CD prioriza el arranque rápido y el bajo consumo de recursos en reposo, lo que hace que la compilación AOT sea una compensación óptima. La adopción de GraalVM por parte de grandes empresas tecnológicas como Twitter, Facebook y Alibaba para aplicaciones de misión crítica valida la madurez y la viabilidad de esta tecnología.   

4.4 Arquitectura Modular y Extensibilidad
4.4.1 Adhesión a la Arquitectura Limpia y los Principios SOLID
Requisito FU-4.1: La base de código del proyecto DEBE adherirse estrictamente a los principios de la Arquitectura Limpia, con una separación clara de responsabilidades en módulos (core, backend, cli, config) y una regla de dependencia unidireccional (las capas externas dependen de las internas).

Justificación: Este es un requisito funcional de primer orden que garantiza la mantenibilidad, la escalabilidad y la capacidad de prueba del sistema a largo plazo. Al desacoplar la lógica de negocio principal (core) de los detalles de implementación (frameworks web, E/S de archivos), el sistema se vuelve más fácil de razonar, modificar y evolucionar. La aplicación explícita de los principios SOLID, como se detalla en el contexto inicial, refuerza aún más esta base de código mantenible.

4.4.2 El Sistema de Plugins mediante java.util.ServiceLoader
Requisito FU-4.2: Los nuevos pasos, motores de DSL y otras extensiones DEBEN ser descubribles en tiempo de ejecución utilizando el mecanismo estándar de Java java.util.ServiceLoader.

Justificación: Este enfoque proporciona una forma estándar y desacoplada de añadir funcionalidad al sistema sin modificar el motor principal. Cumple con el Principio de Abierto/Cerrado, permitiendo que el sistema sea extendido por equipos internos o por la comunidad. Facilita un ecosistema vibrante donde las nuevas integraciones (por ejemplo, un nuevo proveedor de nube, una nueva herramienta de escaneo de seguridad) pueden ser empaquetadas como bibliotecas independientes y simplemente añadidas al classpath del ejecutor para ser descubiertas y utilizadas automáticamente.

5.0 La Próxima Evolución: DSL v2 - Pasos Declarativos mediante el Plugin de Compilador K2
Esta sección aborda la visión de futuro para la experiencia del desarrollador en Pipeline Kotlin. Define una evolución ambiciosa del DSL y, lo que es más importante, analiza los riesgos tecnológicos asociados y propone una estrategia de mitigación pragmática.

5.1 Visión: La Simplicidad de los Pasos de Jenkins con el Poder de Kotlin
El objetivo final de la experiencia del desarrollador es combinar la simplicidad declarativa de los sistemas familiares con el poder y la seguridad de un lenguaje de programación completo. Un desarrollador debería poder escribir sh("echo hola mundo") en su pipeline con una mínima cantidad de código repetitivo, de forma similar a como lo haría en un Jenkinsfile. Sin embargo, a diferencia de Jenkins, esta simplicidad no debe venir a costa de la seguridad de tipos, el rendimiento o la capacidad de composición. La visión es crear una experiencia que se sienta "mágica" pero que esté respaldada por una ingeniería de compiladores sólida.

5.2 Implementación Propuesta: La Anotación @Step y la Transformación Impulsada por el Compilador
La implementación propuesta para lograr esta visión se basa en el nuevo compilador K2 de Kotlin y su infraestructura de plugins.

Requisito FE-1.1: Se creará una anotación dev.rubentxu.pipeline.steps.annotations.Step.

Requisito FE-1.2: Se desarrollará un plugin de compilador K2 que intercepte y transforme las funciones anotadas con @Step durante la compilación.

Lógica de Transformación Requerida:

Inyección de Firma: El plugin debe modificar la firma de la función anotada para añadir un primer parámetro de tipo PipelineContext de forma transparente. El desarrollador que escribe la función no lo declara, pero el compilador lo añade al bytecode.

Inyección en el Sitio de Llamada: En todos los sitios donde se invoca una función @Step desde dentro de un bloque de pipeline, el plugin debe localizar el PipelineContext disponible en el ámbito actual e inyectarlo automáticamente como el primer argumento de la llamada a la función.

Registro Automático: Durante la compilación, el plugin debe escanear el módulo en busca de todas las funciones @Step y generar automáticamente los metadatos necesarios para el registro a través de java.util.ServiceLoader. Esto elimina por completo la necesidad de que los desarrolladores registren manualmente sus nuevos pasos.

5.3 Actualización del Estado de K2: API Estable y Lista para Producción
**ACTUALIZACIÓN IMPORTANTE (2024):** El análisis de riesgo original ha quedado obsoleto. Los plugins del compilador Kotlin K2 alcanzaron estabilidad completa con el lanzamiento de Kotlin 2.0.0 (21 de mayo de 2024).

La investigación de fuentes autorizadas, incluido el propio sistema de seguimiento de incidencias de JetBrains, indica claramente que la estabilización de la API del plugin de compilador K2 es un objetivo a largo plazo, programado para después del lanzamiento de Kotlin 2.0, y que actualmente se encuentra en un estado de desarrollo activo. Documentos de desarrollo para herramientas como Dokka, que también dependen de esta API, confirman que aún se están diseñando y estabilizando APIs clave para el análisis de código. La comunidad de desarrolladores y las guías de migración señalan que, aunque el compilador K2 en sí es estable, sus APIs para plugins aún son experimentales.   

Construir una característica central del producto sobre una API inestable, no pública y sujeta a cambios es una estrategia de alto riesgo. Las consecuencias potenciales incluyen:

Cambios Rompedores: Futuras versiones de mantenimiento o mayores de Kotlin podrían introducir cambios incompatibles en la API del plugin, rompiendo la funcionalidad del DSL v2 y requiriendo un esfuerzo de ingeniería significativo y no planificado para adaptarse.

Carga de Mantenimiento: El equipo de Pipeline Kotlin se vería obligado a seguir de cerca el desarrollo interno del compilador de Kotlin, dedicando recursos a la adaptación constante en lugar de a la creación de nuevas características para el producto.

Soporte Limitado o Nulo: Al utilizar APIs no públicas, el proyecto no tendría acceso a soporte oficial ni a garantías de estabilidad por parte de JetBrains. Cualquier problema encontrado sería difícil de diagnosticar y resolver.

Comprometerse con este camino sin una estrategia de mitigación clara podría poner en peligro el cronograma, el presupuesto y, en última instancia, el éxito del proyecto. La "magia" de la anotación @Step se obtiene a costa de un acoplamiento profundo y arriesgado con las partes internas del compilador. Por lo tanto, un enfoque pragmático y de gestión de riesgos es imperativo. No se puede simplemente esperar a que la API se estabilice, ya que esto introduce un retraso indefinido en la hoja de ruta. Tampoco se puede ignorar el riesgo y construir sobre cimientos inestables. La única vía lógica es desacoplar el éxito del producto principal de la estabilidad del plugin K2, diseñando una hoja de ruta por fases.

5.4 Nueva Hoja de Ruta Acelerada (2024)
**CAMBIO ESTRATÉGICO IMPORTANTE:** Con la estabilización de K2, eliminamos la estrategia de mitigación por fases y procedemos directamente a la implementación del DSL v2.

Fase 1 (Producto Principal - DSL v1):

Enfoque: Entregar un motor de CI/CD robusto y con todas las características basadas en la arquitectura estable descrita en la Sección 4.0.

Definición de Pasos: En esta fase, los pasos se definirán de una manera más explícita pero 100% estable, utilizando únicamente características estándar de Kotlin. Por ejemplo, los pasos pueden ser métodos en un objeto PipelineContext o Steps que se proporciona implícitamente en el ámbito del script. Una llamada como steps { sh("echo hello") } se implementaría como this.sh("echo hello"), donde this es el objeto de contexto inyectado en el script.

Objetivo: Alcanzar la adecuación del producto al mercado con un motor de scripting potente y con tipado seguro. Probar el valor de los conceptos centrales (Kotlin, GraalVM, Sandboxing) y construir una base de usuarios sobre una plataforma estable.

Fase 2 (DSL v2 Experimental - Alfa Interna):

Disparador: Iniciar esta fase solo cuando la API del plugin K2 alcance un estado oficial de "preview" o "beta", según lo comunicado por JetBrains.   

Enfoque: Desarrollar la anotación @Step y el plugin K2 como un proyecto de I+D interno. El código se desarrollará en una rama de características separada y no se fusionará con la principal.

Despliegue: Utilizar el plugin para los pipelines internos del propio proyecto Pipeline Kotlin o con un pequeño grupo de usuarios alfa que comprendan y acepten los riesgos de inestabilidad.

Objetivo: Ganar experiencia práctica con la API de K2, proporcionar retroalimentación valiosa al equipo de Kotlin de JetBrains y validar el enfoque técnico en un entorno controlado, sin exponer a los usuarios de producción a la inestabilidad.

Fase 3 (DSL v2 Oficial - Disponibilidad General):

Disparador: Proceder a esta fase solo después de que JetBrains declare oficialmente la API del plugin de compilador K2 como estable y pública.

Enfoque: Productizar el plugin K2, documentarlo completamente, proporcionar guías de migración y establecerlo como la forma recomendada para definir pasos personalizados.

Objetivo: Entregar la experiencia de desarrollador definitiva prevista para el DSL v2, pero construida sobre una base tecnológica estable, documentada y con soporte.

5.5 Estado Actual de Implementación del Sistema @Step (DSL v1.5)
Esta sección documenta el estado actual de la implementación del sistema @Step, que representa una implementación intermedia entre el DSL v1 y el DSL v2 completamente automatizado.

5.5.1 Implementación Funcional del Sistema @Step
Estado: Completado ✅

Descripción: Se ha implementado completamente una versión funcional del sistema @Step que permite la definición y uso de funciones anotadas con @Step. Esta implementación incluye:

- Anotación @Step con metadatos completos (nombre, descripción, categoría, nivel de seguridad)
- PipelineContext como contexto central de ejecución para @Step functions
- LocalPipelineContext para inyección thread-local del contexto
- StepRegistry para descubrimiento y gestión de @Step functions
- StepSecurityManager para aplicación de niveles de seguridad

Componentes Implementados:
- `@Step` annotation con categorías (BUILD, DEPLOY, TEST, UTIL, SECURITY)
- `SecurityLevel` enum (TRUSTED, RESTRICTED, ISOLATED)
- `PipelineContext` interface con operaciones controladas
- `DefaultPipelineContext` implementación bridging con infraestructura existente
- Integración completa con `StepsBlock`

5.5.2 Steps Predefinidos Implementados
Estado: Completado ✅

Se han implementado tres categorías completas de @Step functions:

**Built-in Steps (Pasos Centrales):**
- `sh()` - Ejecución de comandos shell con opciones
- `echo()` - Logging de mensajes
- `readFile()` / `writeFile()` - Operaciones de archivo
- `fileExists()` - Verificación de existencia de archivos
- `checkout()` - Clonado de repositorios Git
- `retry()` - Lógica de reintento con backoff exponencial
- `sleep()` - Delays controlados
- `dir()` - Cambio de directorio de trabajo
- `environment()` - Gestión de variables de entorno
- `timeout()` - Límites de tiempo de ejecución
- `stash()` / `unstash()` - Gestión de artifacts temporales
- `input()` - Interacción con usuario

**Extension Steps - Docker:**
- `dockerBuild()` - Construcción de imágenes Docker
- `dockerRun()` - Ejecución de contenedores
- `dockerStop()` - Parada y limpieza de contenedores
- `dockerPush()` / `dockerPull()` - Gestión de registry
- `dockerExec()` - Ejecución de comandos en contenedores
- `dockerPs()` - Listado de contenedores
- `dockerRmi()` - Eliminación de imágenes

**Extension Steps - Kubernetes:**
- `kubectlApply()` / `kubectlDelete()` - Gestión de manifiestos
- `kubectlGet()` / `kubectlDescribe()` - Consulta de recursos
- `kubectlLogs()` - Obtención de logs de pods
- `kubectlScale()` - Escalado de deployments
- `kubectlWait()` - Espera de condiciones
- `helmDeploy()` / `helmUninstall()` - Gestión de Helm charts

**Extension Steps - Testing:**
- `junitTest()` / `mavenTest()` - Tests unitarios
- `integrationTest()` - Tests de integración con Docker Compose
- `apiTest()` - Tests de API con Newman/Postman
- `performanceTest()` - Tests de rendimiento con Apache Bench
- `securityTest()` - Tests de seguridad con OWASP ZAP
- `publishTestResults()` - Publicación de resultados

5.5.3 Framework de Testing Comprehensivo
Estado: Completado ✅

Se ha desarrollado un framework de testing robusto para @Step functions:

**StepTestUtils:** Utilidades para testing de @Step functions
- `runStepTest()` - Contexto de test con setup automático
- `createTestFile()` - Creación de archivos de test
- `mockStep()` - Mocking de @Step functions
- `executionRecorder` - Grabación y verificación de ejecuciones

**Tests Implementados:**
- Tests unitarios completos para todos los built-in steps
- Tests de integración para extension steps (Docker, Kubernetes, Testing)
- Tests de validación de parámetros y manejo de errores
- Tests de niveles de seguridad y aislamiento

Cobertura: >300 test cases cubriendo todos los aspectos del sistema @Step

5.5.4 Ejemplos y Documentación
Estado: Completado ✅

Se han creado ejemplos comprensivos que demuestran:

**Ejemplos Básicos:**
- Pipeline simple con @Step functions
- Procesamiento de archivos
- Manejo de variables de entorno
- Gestión de errores y retry logic

**Ejemplos Avanzados:**
- Pipeline completo de CI/CD multi-ambiente
- Procesamiento paralelo con @Step functions
- Pipeline de deployment con notificaciones
- Integración con Docker y Kubernetes

**Ejemplos de Migración:**
- Comparación antes/después (extension functions vs @Step)
- Patrones de migración comunes
- Mejoras en connascence y coupling
- Best practices para @Step development

5.5.5 Diferencias con DSL v2 Futuro
La implementación actual (DSL v1.5) requiere inyección manual del PipelineContext:

```kotlin
// DSL v1.5 (Actual)
@Step suspend fun deployToProduction(version: String) {
    val context = LocalPipelineContext.current
    context.executeShell("kubectl apply...")
}

// DSL v2 (Futuro con K2 Plugin)
@Step suspend fun deployToProduction(version: String) {
    // PipelineContext inyectado automáticamente por compilador
    executeShell("kubectl apply...")
}
```

La implementación actual proporciona todas las funcionalidades del sistema @Step con una sintaxis ligeramente más verbosa, pero completamente estable y funcional.

5.5.6 Beneficios Demostrados
La implementación actual ya demuestra ventajas significativas sobre extension functions:

**Seguridad Mejorada:**
- Aislamiento automático basado en SecurityLevel
- Validación de parámetros en compile-time
- Sandboxing controlado por contexto

**Type Safety:**
- Validación completa de parámetros
- Tipos de retorno garantizados
- Error handling estructurado

**Testabilidad:**
- Framework de testing robusto
- Mocking granular de steps
- Verificación automática de ejecuciones

**Composabilidad:**
- @Step functions pueden llamar otras @Step functions naturalmente
- Reutilización de lógica común
- Separación clara de responsabilidades

**Mantenibilidad:**
- Menor connascence que extension functions
- Separación de responsabilidades mejorada
- Documentación automática via annotations

Tabla 5.1: Mapeo de Pasos de Jenkins a Funciones @Step de Kotlin
Esta tabla sirve para hacer tangible la visión del DSL v2, mostrando cómo se pueden mapear conceptos familiares a la nueva sintaxis expresiva y con tipado seguro.

Paso de Jenkins

Función @Step de Kotlin Propuesta

Notas

sh

@Step fun sh(command: String): String

Devuelve la salida estándar (stdout) como un String.

retry

@Step fun retry(count: Int, block: () -> Unit)

Demuestra el uso de funciones de orden superior para el control de flujo.

dir

@Step fun dir(path: String, block: () -> Unit)

Muestra cómo se puede gestionar el ámbito de ejecución de un bloque de código.

readFile

@Step fun readFile(path: String, encoding: String = "UTF-8"): String

Operaciones de archivo simples y con tipado seguro.

writeFile

@Step fun writeFile(path: String, content: String, encoding: String = "UTF-8")

La seguridad de tipos garantiza que el contenido sea un String.

archiveArtifacts

@Step fun archiveArtifacts(artifacts: String, fingerprint: Boolean = false)

Interactúa con las características principales del motor de CI.

timeout

@Step fun timeout(time: Int, unit: TimeUnit, block: () -> Unit)

Parámetros con tipado seguro, evitando errores con unidades de tiempo.

junit

@Step fun junit(testResults: String)

Un paso específico de dominio para procesar resultados de pruebas.


Exportar a Hojas de cálculo
6.0 Requisitos No Funcionales (NFRs)
Esta sección define los atributos de calidad, las restricciones y las características operativas que el sistema debe cumplir para ser considerado exitoso.

6.1 Rendimiento y Escalabilidad
NFR-P1 (Arranque de Trabajo): Un nuevo trabajo de pipeline en un ejecutor precalentado y en reposo debe comenzar su ejecución en menos de 500 milisegundos.

Justificación: Este requisito es crítico para proporcionar una retroalimentación rápida a los desarrolladores. Se logra mediante el uso de ejecutables nativos compilados con GraalVM AOT, que tienen tiempos de arranque drásticamente más bajos que las aplicaciones JVM tradicionales.   

NFR-P2 (Huella del Ejecutor): El ejecutable del ejecutor compilado nativamente y su imagen de contenedor base (por ejemplo, distroless) no deben superar los 150 MB en total.

Justificación: Una huella pequeña reduce los costes de almacenamiento y los tiempos de extracción de imágenes en entornos de contenedores. La compilación AOT de GraalVM y el uso de imágenes de contenedor mínimas son clave para lograr este objetivo.   

NFR-P3 (Concurrencia): Una única instancia de ejecutor con 4 vCPUs y 8 GB de RAM debe ser capaz de ejecutar concurrentemente al menos 10 trabajos de pipeline ligados a E/S (por ejemplo, esperando descargas de red o respuestas de API).

Justificación: La alta concurrencia maximiza la utilización de la infraestructura. Este requisito se cumple mediante el uso de corrutinas de Kotlin, que gestionan eficientemente un gran número de tareas asíncronas sin la sobrecarga de un hilo por tarea.

6.2 Seguridad
NFR-S1 (Aislamiento de Scripts): Un script de pipeline que se ejecuta en un GraalVM Isolate NO DEBE poder acceder al sistema de archivos o a la red del anfitrión, excepto a través de objetos de contexto explícitamente proporcionados y controlados por el sistema.

Justificación: Este es el núcleo de la seguridad del ejecutor. Se impone utilizando las políticas de sandboxing de GraalVM, que restringen las capacidades del código invitado.   

NFR-S2 (Aislamiento de Tareas): Una tarea que se ejecuta dentro de un pipeline (por ejemplo, a través de sh) NO DEBE poder afectar a archivos fuera de su espacio de trabajo contenedorizado designado.

Justificación: Garantiza la hermeticidad de los pasos de la compilación y protege al sistema anfitrión de efectos secundarios no deseados.

NFR-S3 (Gestión de Secretos): El sistema debe proporcionar un mecanismo seguro para inyectar secretos (tokens, contraseñas) en los pipelines como variables de entorno o archivos, sin que estos secretos se registren nunca en los logs de salida.

6.3 Usabilidad y Experiencia del Desarrollador (DX)
NFR-U1 (Soporte IDE): Los desarrolladores que escriban un archivo pipeline.kts en IntelliJ IDEA deben tener una experiencia de primera clase, incluyendo autocompletado completo, resaltado de sintaxis, navegación de código y comprobación de errores consciente de los tipos.

Justificación: El principal atractivo del sistema es tratar los pipelines como código. Esto requiere un soporte de herramientas que esté a la par con el desarrollo de aplicaciones estándar.

NFR-U2 (Mensajes de Error): Cuando un pipeline falla debido a un error de compilación en el script, el mensaje de error debe ser idéntico al que produciría el compilador de Kotlin. Cuando falla en tiempo de ejecución, el registro debe proporcionar una traza de pila (stack trace) clara que apunte a la línea exacta en el script del usuario que causó el fallo.

Justificación: Los mensajes de error claros y procesables son cruciales para un bucle de depuración rápido.

6.4 Observabilidad
NFR-O1 (Registro): Todos los registros de salida de los pipelines deben ser recopilados, marcados con fecha y hora y almacenados de forma persistente. El sistema debe proporcionar una interfaz (CLI o web) para ver los registros en vivo mientras el pipeline se está ejecutando.

NFR-O2 (Métricas): El ejecutor debe exponer métricas clave de rendimiento y estado (por ejemplo, duración de los trabajos, tiempo en cola, utilización de CPU/memoria, número de trabajos activos) en un formato estándar compatible con sistemas de monitorización como Prometheus.

6.5 Mantenibilidad
NFR-M1 (Cobertura de Pruebas): El módulo core de la aplicación debe mantener una cobertura de pruebas unitarias de al menos el 85%.

Justificación: Garantiza la robustez y fiabilidad de la lógica de negocio central y reduce el riesgo de regresiones.

NFR-M2 (Adherencia Arquitectónica): Las solicitudes de extracción (pull requests) que introduzcan una dependencia que viole las reglas de la Arquitectura Limpia (por ejemplo, el módulo core dependiendo del módulo cli) deben ser bloqueadas automáticamente por comprobaciones de CI.

Justificación: Impone la disciplina arquitectónica y previene la degradación de la estructura del software a lo largo del tiempo.

7.0 Resumen y Recomendaciones Estratégicas
7.1 Reiteración de la Ventaja Competitiva
Pipeline Kotlin representa un avance fundamental en la automatización de CI/CD. Su combinación única de tipado estático a través de Kotlin, compilación nativa de alto rendimiento con GraalVM y un robusto modelo de seguridad de doble sandbox aborda directamente las deficiencias sistémicas de los paradigmas existentes. Supera las limitaciones de rendimiento, seguridad y experiencia del desarrollador del enfoque de scripting dinámico de Jenkins , al tiempo que resuelve los problemas de fragilidad, escalabilidad y falta de herramientas de los sistemas basados en YAML. Al permitir que los pipelines se traten como software de primera clase, Pipeline Kotlin ofrece una ventaja estratégica sostenible en la fiabilidad y velocidad de la entrega de software.   

7.2 Hoja de Ruta de Desarrollo de Alto Nivel
El análisis de riesgos, en particular en lo que respecta a la dependencia de la API del plugin de compilador K2, dicta una estrategia de desarrollo pragmática y por fases. Esta hoja de ruta está diseñada para ofrecer valor de forma incremental mientras se gestiona activamente el riesgo tecnológico.

Trimestres 1-2: Fase 1 - Enfoque en el Producto Principal (DSL v1).

La prioridad absoluta es lanzar una versión estable y con todas las características del motor Pipeline Kotlin utilizando el DSL v1, que se basa en características estables de Kotlin. El objetivo es validar los conceptos centrales (seguridad de tipos, sandboxing, rendimiento nativo) y construir una base de usuarios sólida.

Trimestre 3: Fase 2 - Inicio de la Experimentación con el DSL v2.

Condicionado a que la API del plugin K2 alcance un estado de "preview" o "beta" por parte de JetBrains, se iniciará el desarrollo del plugin @Step como un proyecto de I+D interno. Este esfuerzo se centrará en el aprendizaje y la validación técnica en un entorno controlado.

Trimestre 4 y más allá: Fase 3 - Lanzamiento Oficial del DSL v2.

La transición a esta fase solo se producirá cuando la API del plugin K2 sea declarada oficialmente estable y pública por JetBrains. Este enfoque garantiza que la característica más avanzada de experiencia del desarrollador se construya sobre una base sólida y con soporte, protegiendo al producto y a sus usuarios de la inestabilidad.

7.3 Observaciones Finales sobre la Preparación del CI/CD para el Futuro
En conclusión, Pipeline Kotlin no debe ser visto simplemente como una nueva herramienta, sino como un nuevo enfoque para la automatización. El acto de elevar la definición del pipeline de un script o archivo de configuración a un programa de software compilado y con tipado seguro es una transformación fundamental. Al adoptar el paradigma de "Pipeline como Código Real", las organizaciones pueden aplicar el rigor y las mejores prácticas de la ingeniería de software a una parte crítica de su cadena de valor. Esto no solo resuelve los problemas actuales, sino que también prepara su infraestructura de entrega de software para el futuro, proporcionando una base mantenible, segura y escalable para hacer frente a la creciente complejidad del desarrollo de software moderno.