# **Manual Exhaustivo de Plugins para el Compilador Kotlin K2: De Cero a Profesional**

## **Parte 1: Fundamentos del Compilador K2 y los Plugins**

### **Capítulo 1: La Revolución de K2: ¿Qué es y por qué es importante?**

La llegada de Kotlin 2.0 marca un punto de inflexión en la historia del lenguaje, y su pieza central es el nuevo compilador, conocido internamente como K2. Lejos de ser una simple actualización incremental, K2 representa una reescritura completa y estratégica del *frontend* del compilador, la parte responsable de analizar el código fuente, resolver tipos y verificar errores. Esta monumental empresa fue motivada por la necesidad de superar las limitaciones arquitectónicas y la deuda técnica acumulada en el compilador original (K1), con el objetivo de acelerar el desarrollo de futuras características del lenguaje, unificar el comportamiento en todas las plataformas soportadas y, de manera crucial, ofrecer mejoras de rendimiento drásticas.

#### **La Nueva Arquitectura**

La arquitectura de un compilador se puede dividir a grandes rasgos en dos componentes principales: el *frontend* y el *backend*. El frontend analiza el código fuente para comprender su estructura y significado, mientras que el backend toma esta representación intermedia y genera el código ejecutable para una plataforma específica (JVM, JavaScript, Nativo o WebAssembly). La revolución de K2 reside casi en su totalidad en el rediseño del frontend.  
El cambio fundamental es la adopción de una única estructura de datos central llamada **FIR (Frontend Intermediate Representation)**. El árbol FIR se convierte en la única fuente de verdad semántica, reemplazando el complejo y a menudo ineficiente entramado de estructuras de K1, que incluía el Árbol de Sintaxis de Programa (PSI), los Descriptores y un masivo mapa de contexto llamado BindingContext.

#### **Beneficios Clave**

La transición a esta nueva arquitectura trae consigo beneficios tangibles para todo desarrollador de Kotlin:

* **Rendimiento Exponencialmente Mejorado:** Este es el beneficio más celebrado de K2. Las mediciones en proyectos del mundo real demuestran ganancias significativas. Por ejemplo, el proyecto Anki-Android experimentó una reducción en los tiempos de compilación limpia de 57.7 segundos con Kotlin 1.9 a solo 29.7 segundos con Kotlin 2.0. Esto representa una mejora de casi el doble (un 94% de ganancia de velocidad). Las fases individuales de la compilación, como la inicialización y el análisis, pueden ser hasta un 488% y un 376% más rápidas, respectivamente. Para los desarrolladores, esto se traduce directamente en ciclos de iteración más cortos y una mayor productividad.  
* **Evolución Acelerada del Lenguaje:** La arquitectura de K1, con sus múltiples backends y un frontend complejo, hacía que implementar nuevas características del lenguaje fuera una tarea lenta y propensa a errores, que a menudo requería implementaciones separadas para cada plataforma. La arquitectura unificada de K2, con un frontend común y un pipeline compartido para los backends, permite a JetBrains diseñar, implementar y probar nuevas características una sola vez, asegurando su disponibilidad y consistencia en todas las plataformas (JVM, Native, JS, Wasm) de manera mucho más rápida.  
* **Mejoras en el Lenguaje:** La mayor comprensión semántica del compilador K2 permite mejoras en características existentes. El ejemplo más notable son los *smart casts*. K2 es capaz de realizar conversiones de tipo inteligentes en escenarios mucho más complejos que antes, como a través de variables booleanas que capturan una comprobación de tipo o dentro de funciones lambda, lo que resulta en un código más limpio y menos propenso a errores.  
* **Experiencia Superior en el IDE:** Los IDEs como IntelliJ IDEA y Android Studio utilizan el motor del compilador de Kotlin para proporcionar análisis de código en tiempo real. El "K2 Mode", habilitado por defecto en las versiones recientes del IDE, utiliza el motor de K2 para el resaltado de sintaxis, la detección de errores y el autocompletado. Gracias a la eficiencia de K2, estas características son notablemente más rápidas y estables, reduciendo los bloqueos del IDE y proporcionando una experiencia de desarrollo más fluida.

La transición a K2 no es meramente una actualización técnica; simboliza la madurez del ecosistema Kotlin. El compilador original, K1, fue diseñado para permitir una evolución rápida del lenguaje en sus primeras etapas, priorizando la flexibilidad sobre el rendimiento. La reescritura completa hacia K2 y su consolidación como el compilador por defecto en Kotlin 2.0 marca el fin de esta fase "infantil". Este cambio fundamental obliga a todo el ecosistema —plugins de compilador, herramientas de procesamiento de anotaciones como KSP y KAPT, y frameworks como Jetpack Compose— a modernizarse y alinearse con la nueva arquitectura. Aunque esto implica un esfuerzo de migración a corto plazo, el resultado a largo plazo es un ecosistema más robusto, unificado y de alto rendimiento. Para cualquier desarrollador de herramientas o plugins, adoptar K2 no es una opción, sino el único camino viable y sostenible hacia el futuro.

### **Capítulo 2: Anatomía del Frontend: Entendiendo FIR (Frontend Intermediate Representation)**

Para escribir plugins de compilador efectivos, es indispensable comprender la estructura sobre la que operan. En K2, esa estructura es el **FIR (Frontend Intermediate Representation)**. El árbol FIR es la abstracción central del nuevo frontend, una representación semántica y mutable de todo el código fuente que el compilador procesa.  
Esta arquitectura contrasta marcadamente con la de K1. El antiguo frontend se basaba en un conjunto de estructuras de datos dispares: el PSI (Program Structure Interface), que es un árbol de sintaxis concreto; los Descriptores, que contenían información semántica de forma perezosa; y el BindingContext, un enorme mapa que conectaba los nodos PSI con sus Descriptores. Esta arquitectura "perezosa" (lazy) provocaba que el compilador saltara constantemente entre diferentes partes del código para resolver la información necesaria, lo que resultaba en un rendimiento deficiente y una gran imprevisibilidad.  
K2 abandona esta pereza implícita en favor de un proceso explícito y por fases.

#### **El Ciclo de Vida del Árbol FIR**

El código fuente se transforma en un árbol FIR completamente resuelto a través de un pipeline bien definido:

1. **Parser y Raw FIR Builder:** El proceso comienza cuando el *parser* toma el código fuente y genera un **Parse Tree** (también conocido como Concrete Syntax Tree o CST). En el ecosistema de IntelliJ, esta estructura se conoce como PSI. El PSI retiene toda la información sintáctica del fichero, incluyendo espacios en blanco, comentarios y paréntesis. A continuación, el Raw FIR Builder recorre este PSI para construir un árbol **FIR "crudo"**. Este árbol es una representación de sintaxis abstracta (AST), lo que significa que se descarta la información no semántica. En esta etapa también se realiza una "desugarización" inicial, donde ciertas construcciones sintácticas se transforman en formas más simples (por ejemplo, un bucle for se convierte en un while con un iterador) para facilitar las fases posteriores.  
2. **Fases de Resolución:** El árbol FIR crudo, que solo contiene información explícita del código, se enriquece progresivamente con información semántica a través de una secuencia de **fases de resolución**. Estas fases se ejecutan de manera estrictamente secuencial para todos los ficheros de un módulo. La garantía fundamental de este sistema es que si una fase B se ejecuta después de una fase A, toda la información que la fase A debía resolver ya está disponible y es visible en la fase B. Las fases principales incluyen:  
   * IMPORTS: Resuelve todas las directivas de importación.  
   * SUPER\_TYPES: Resuelve todos los supertipos de las clases y las expansiones de alias de tipo.  
   * TYPES: Resuelve todos los demás tipos escritos explícitamente en las cabeceras de las declaraciones (parámetros, tipos de retorno, etc.).  
   * STATUS: Resuelve la modalidad, visibilidad y otros modificadores de las declaraciones.  
   * CONTRACTS: Resuelve los contratos de las funciones.  
   * IMPLICIT\_TYPES\_BODY\_RESOLVE: Infiere los tipos de retorno para las funciones y propiedades que no los tienen explícitos.  
   * BODY\_RESOLVE: Resuelve completamente los cuerpos de todas las funciones y los inicializadores de las propiedades.  
3. **Fase de Checkers:** Una vez que el árbol FIR está completamente resuelto (es decir, ha pasado por todas las fases de resolución), se entrega a la fase de CHECKERS. En esta etapa, varios analizadores (checkers), incluidos los proporcionados por los plugins de compilador, recorren el árbol para detectar errores, advertencias y otras cuestiones diagnósticas.

#### **Componentes del Árbol FIR (FirElement)**

El árbol FIR está compuesto por nodos que heredan de FirElement. Las tres categorías principales de nodos son :

* **FirDeclaration**: Representa cualquier declaración en el código, como una clase (FirRegularClass), una función (FirSimpleFunction) o una propiedad (FirProperty). Cada declaración tiene un symbol único que la identifica de forma inequívoca en todo el compilador.  
* **FirExpression**: Representa cualquier expresión, como una llamada a una función (FirFunctionCall) o un acceso a una propiedad (FirPropertyAccessExpression). Todas las expresiones tienen un campo typeRef que contiene una referencia a su tipo.  
* **FirTypeRef**: Representa una referencia a un tipo en el código. Es importante distinguir entre la referencia al tipo y el tipo en sí. Un FirTypeRef puede estar en uno de tres estados:  
  * FirUserTypeRef: Una referencia no resuelta, tal como está escrita en el código.  
  * FirImplicitTypeRef: Una referencia a un tipo que no está escrito explícitamente, como en val x \= 10\.  
  * FirResolvedTypeRef: Una referencia que ya ha sido resuelta a un ConeKotlinType concreto, que es la representación interna del tipo en FIR.

#### **Acceso a la Información: FirSymbolProvider y FirScope**

Para navegar por el código y encontrar declaraciones, los plugins utilizan principalmente dos abstracciones :

* **FirSymbolProvider**: Es la herramienta principal para buscar declaraciones por su identificador (como un ClassId). El compilador utiliza un proveedor de símbolos compuesto que puede buscar en el código fuente del módulo actual, en las declaraciones generadas por otros plugins y en las dependencias binarias del proyecto.  
* **FirScope**: Representa un ámbito léxico y se utiliza para buscar declaraciones por su nombre dentro de un contexto específico, como los miembros de una clase.

La arquitectura de fases explícitas de FIR no es solo una optimización de rendimiento; define el modelo mental fundamental para escribir plugins en K2. La garantía de que la información de una fase previa está completamente resuelta es el contrato más importante para un autor de plugins. A diferencia de K1, donde la lógica de un plugin podía desencadenar cascadas de resolución impredecibles, en K2 el autor debe plantearse una pregunta clara: "¿En qué fase de compilación necesita ejecutarse mi lógica y qué información estará disponible de forma garantizada en ese punto?". Por ejemplo, un plugin que necesita añadir un supertipo a una clase (FirSupertypeGenerationExtension) sabe que se ejecutará después de que los supertipos explícitos ya hayan sido resueltos en la fase SUPER\_TYPES. Esta predictibilidad y claridad arquitectónica es la base sobre la cual JetBrains planea construir una futura API de plugins estable y pública.

### **Capítulo 3: Introducción a los Plugins de Compilador**

Un plugin de compilador es un componente que se integra directamente en el proceso de compilación de Kotlin para extender o modificar su comportamiento. Estos plugins pueden analizar el código para reportar errores personalizados, transformar el código existente o incluso generar código nuevo desde cero. Ejemplos bien conocidos que demuestran su poder incluyen kotlinx.serialization, que genera automáticamente código para serializar y deserializar objetos; all-open, que modifica las clases para que sean abiertas por defecto para frameworks como Spring; y Parcelize, que implementa la interfaz Parcelable de Android.

#### **El Dilema: KSP vs. Compiler Plugin**

Antes de embarcarse en la creación de un plugin de compilador, es crucial decidir si es la herramienta adecuada para el trabajo. Kotlin ofrece una alternativa más simple y estable para muchos casos de uso: **KSP (Kotlin Symbol Processing)**.

* **KSP (Kotlin Symbol Processing):** Es una API de alto nivel diseñada para la metaprogramación ligera, principalmente para procesadores de anotaciones. KSP permite analizar el código fuente de Kotlin (sus símbolos) y generar nuevos ficheros de código. Su principal ventaja es que abstrae la complejidad interna del compilador, ofreciendo una API estable y más fácil de aprender. KSP es ideal para tareas como la inyección de dependencias (usada por Dagger/Hilt) o la generación de adaptadores de datos (usada por Moshi). Con la llegada de K2, KSP ha evolucionado a **KSP2**, que está diseñado para funcionar de manera nativa y eficiente con el nuevo compilador.  
* **Compiler Plugins:** Son la opción más potente, pero también la más compleja. A diferencia de KSP, que se limita a analizar el código existente y generar ficheros nuevos, los plugins de compilador pueden **modificar el código existente** en el momento de la compilación, transformar su estructura (AST/IR), añadir diagnósticos personalizados que se integran con el IDE y realizar optimizaciones de bajo nivel. Se recurre a ellos cuando la tarea requiere una manipulación del código que va más allá de lo que se puede lograr con la generación de código basada en anotaciones, como es el caso de Jetpack Compose, que altera la semántica de las funciones anotadas con @Composable.

La siguiente tabla resume las diferencias clave para ayudar a tomar una decisión informada.

| Característica | KSP (Kotlin Symbol Processing) | Plugin de Compilador (FIR/IR) |
| :---- | :---- | :---- |
| **API** | Estable y pública | Interna, inestable y sujeta a cambios frecuentes entre versiones de Kotlin |
| **Curva de Aprendizaje** | Baja, bien documentada | Alta, requiere un conocimiento profundo de los internos del compilador |
| **Capacidades** | Análisis de símbolos (solo lectura), generación de nuevos ficheros | Análisis, modificación y generación de código (lectura/escritura en el AST/IR) |
| **Caso de Uso Típico** | Inyección de dependencias, serialización de datos, generación de adaptadores | Creación de DSLs, transformaciones de lenguaje (@Composable), linters personalizados, optimizaciones |
| **Integración** | Se ejecuta como un procesador de anotaciones | Se integra directamente en el pipeline del compilador |
| **Complejidad de Debugging** | Relativamente simple (KSP2 es una librería standalone ) | Complejo, a menudo requiere adjuntar un debugger al demonio de Gradle |

#### **Puntos de Extensión de K2 (FIR)**

Si se determina que un plugin de compilador es la herramienta correcta, el siguiente paso es entender los "ganchos" o puntos de extensión que K2 ofrece en su frontend FIR. Cada extensión está diseñada para operar en una fase específica del compilador y para un propósito concreto.  
La siguiente tabla sirve como un mapa de referencia de las principales extensiones de FIR que se explorarán en este manual.

| Punto de Extensión | Fase de Compilación | Propósito Principal | Ejemplo de Uso (Plugin Famoso) |
| :---- | :---- | :---- | :---- |
| FirAdditionalCheckersExtension | CHECKERS | Añadir validaciones personalizadas y reportar errores/advertencias. | Linters como ktlint, plugins de análisis estático. |
| FirDeclarationGenerationExtension | STATUS en adelante | Generar nuevas clases, funciones y propiedades. | kotlinx.serialization (para generar métodos serializer()). |
| FirStatusTransformerExtension | STATUS | Cambiar modificadores de declaración (visibilidad, modalidad open, etc.). | all-open (para hacer clases open para Spring). |
| FirSupertypeGenerationExtension | SUPER\_TYPES | Añadir supertipos (interfaces, clases base) a clases existentes. | kotlinx.serialization (para añadir KSerializer a los serializadores). |
| FirExpressionResolutionExtension | BODY\_RESOLVE | Añadir receptores de extensión implícitos a llamadas de función. | Plugin experimental Kotlin Assignment. |
| FirAssignExpressionAltererExtension | BODY\_RESOLVE | Transformar una asignación de variable en otra declaración. | Plugin experimental Kotlin Assignment (para sobrecargar \=). |

Estas extensiones, junto con la extensión de backend IrGenerationExtension, forman el conjunto de herramientas fundamental para cualquier desarrollador de plugins de K2.

## **Parte 2: Creando su Primer Plugin de K2**

### **Capítulo 4: Configuración del Entorno de Desarrollo Paso a Paso**

Construir un plugin de compilador de K2 requiere una configuración de proyecto específica pero bien definida. La mejor práctica, promovida a través de plantillas oficiales y de la comunidad, es una estructura multi-módulo que separa claramente las responsabilidades.

#### **La Estructura de Proyecto Canónica**

Un proyecto de plugin de compilador robusto generalmente consta de tres módulos:

* **:plugin-annotations**: Este es un módulo de Kotlin puro y ligero. Su única responsabilidad es definir las anotaciones que los usuarios del plugin utilizarán para marcar su código. Al ser un módulo simple, se puede incluir como una dependencia api o implementation en los proyectos de los usuarios sin añadir el peso del compilador.  
* **:compiler-plugin**: Este es el núcleo del plugin. Contiene toda la lógica de análisis y transformación del código, implementando las diversas extensiones de FIR e IR. Este módulo depende directamente de los artefactos del compilador de Kotlin, como kotlin-compiler-embeddable.  
* **:gradle-plugin**: Este módulo es un plugin de Gradle personalizado. Su función es actuar como el "instalador" del plugin de compilador. Cuando un usuario aplica este plugin de Gradle, se encarga de dos tareas principales:  
  1. Añadir el módulo :compiler-plugin al classpath del compilador de Kotlin (no al classpath del proyecto).  
  2. Añadir el módulo :plugin-annotations como una dependencia normal del proyecto del usuario.  
  3. Pasar cualquier configuración desde el build.gradle.kts del usuario a la tarea de compilación de Kotlin.

Esta separación tripartita no es una mera convención, sino un diseño crucial para la correcta distribución y uso del plugin. Las anotaciones (:plugin-annotations) constituyen la API pública y ligera. El plugin de compilador (:compiler-plugin) es la implementación "pesada" y privada que no debe contaminar el classpath de ejecución del usuario. Finalmente, el plugin de Gradle (:gradle-plugin) es el mecanismo de orquestación que une estos dos mundos en el momento preciso de la compilación. Este patrón, validado por numerosos proyectos de código abierto , es fundamental para crear un plugin fácil de usar y mantener.

#### **Configuración de Gradle (build.gradle.kts)**

A continuación se muestran ejemplos de las dependencias clave para cada módulo:

* **compiler-plugin/build.gradle.kts**:  
  `plugins {`  
      `kotlin("jvm")`  
  `}`

  `dependencies {`  
      `// La dependencia fundamental para escribir plugins de compilador`  
      `compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.0")`  
  `}`

* **gradle-plugin/build.gradle.kts**:  
  `plugins {`  
      `` `kotlin-dsl` ``  
  `}`

  `dependencies {`  
      `// API para interactuar con el plugin de Gradle de Kotlin`  
      `implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.0.0")`  
  `}`

#### **El Punto de Entrada: CompilerPluginRegistrar**

El punto de entrada para cualquier plugin de compilador es una clase que implementa la interfaz CompilerPluginRegistrar. Esta clase es descubierta por el compilador a través del mecanismo de ServiceLoader de Java (declarando su nombre completo en resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar).  
Es vital notar que CompilerPluginRegistrar reemplaza al obsoleto ComponentRegistrar de K1, que tenía una dependencia indeseada de las APIs de IntelliJ.

#### **Registro de Extensiones FIR**

Dentro de CompilerPluginRegistrar, se registran las extensiones. Para los plugins de K2, el proceso es el siguiente:

1. **Indicar soporte para K2:** La sobreescritura de supportsK2 debe devolver true. Este es un paso obligatorio para que el compilador cargue las extensiones de K2.  
2. **Registrar un FirExtensionRegistrar:** Dentro del método registerExtensions, se registra una instancia de una clase que hereda de FirExtensionRegistrar.  
3. **Configurar las extensiones FIR:** La clase FirExtensionRegistrar es la responsable de registrar todas las extensiones específicas de FIR (como FirAdditionalCheckersExtension, etc.) que componen el plugin.

Aquí se muestra un ejemplo completo del cableado:  
`// En el módulo :compiler-plugin`

`// 1. El punto de entrada principal`  
`@AutoService(CompilerPluginRegistrar::class) // Usando la anotación de Google AutoService para generar el fichero de servicios`  
`class MyCompilerPluginRegistrar : CompilerPluginRegistrar() {`

    `override val supportsK2: Boolean = true // ¡Paso crucial para habilitar K2!`

    `override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {`  
        `// 2. Registrar el registrador específico de FIR`  
        `FirExtensionRegistrar.registerExtension(MyFirExtensionRegistrar())`  
    `}`  
`}`

`// 3. El registrador para todas las extensiones de FIR`  
`class MyFirExtensionRegistrar : FirExtensionRegistrar() {`  
    `override fun ExtensionRegistrarContext.configurePlugin() {`  
        `` // 4. Registrar cada extensión de FIR usando la sintaxis de `+::` ``  
        `+::MyFirCheckersExtension`  
        `// +::OtraExtensionFir`  
    `}`  
`}`

Este código muestra cómo MyCompilerPluginRegistrar actúa como el punto de entrada global, que a su vez delega el registro de las extensiones específicas del frontend de K2 a MyFirExtensionRegistrar.

### **Capítulo 5: El "Hola Mundo" de los Plugins: Reportando un Diagnóstico Personalizado**

La forma más sencilla y didáctica de empezar a desarrollar un plugin es crear un *linter* personalizado: un analizador que detecta patrones de código específicos y reporta una advertencia o un error de compilación. Este capítulo guiará al lector en la creación de un plugin que prohíbe el uso del método copy() en data class que tienen un constructor privado, un caso de uso práctico inspirado en el proyecto de ejemplo NoCopy-Compiler-Plugin.  
El objetivo es que el siguiente código, que antes de Kotlin 2.0.20 era válido, ahora genere un error de compilación:  
`data class User private constructor(val id: Int, val name: String)`

`val user = User.create(1, "John")`  
`val copiedUser = user.copy(name = "Jane") // <- ¡Esto debería ser un error!`

#### **Paso 1: Definir el Checker**

La lógica de validación se implementará en un checker. Dado que queremos analizar llamadas a funciones, usaremos FirFunctionCallChecker.  
`// En el módulo :compiler-plugin`  
`object NoCopyCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {`  
    `override fun check(`  
        `expression: FirFunctionCall,`  
        `context: CheckerContext,`  
        `reporter: DiagnosticReporter`  
    `) {`  
        `// Aquí irá la lógica de verificación`  
    `}`  
`}`

#### **Paso 2: Implementar la Lógica de Verificación**

Dentro del método check, tenemos acceso a la llamada a la función (FirFunctionCall) y al contexto del compilador. La lógica para verificar nuestra regla es la siguiente:

1. **Comprobar el nombre de la función:** Nos interesan solo las llamadas a copy.  
2. **Obtener el tipo del receptor:** Necesitamos saber en qué objeto se está llamando a copy.  
3. **Resolver a una declaración de clase:** A partir del tipo, obtenemos el símbolo de la clase (FirRegularClassSymbol).  
4. **Inspeccionar la clase:** Verificamos si es una data class y si su constructor primario tiene visibilidad privada.

`override fun check(expression: FirFunctionCall, context: CheckerContext, reporter: DiagnosticReporter) {`  
    `// 1. Verificar si la llamada es a 'copy'`  
    `if (expression.calleeReference.name.asString()!= "copy") return`

    `// 2. Obtener el tipo del receptor de la llamada (el objeto antes del '.')`  
    `val receiverType = expression.dispatchReceiver?.resolvedType?: return`  
    `val classSymbol = receiverType.toRegularClassSymbol(context.session)?: return`

    `// 3. Obtener la declaración de la clase`  
    `val firClass = classSymbol.fir`

    `// 4. Verificar si es una data class con constructor primario privado`  
    `if (firClass.isData) {`  
        `val primaryConstructor = firClass.getPrimaryConstructorOrNull()`  
        `if (primaryConstructor?.isPrivate == true) {`  
            `// Si todas las condiciones se cumplen, reportamos el error`  
            `reporter.reportOn(expression.source, NoCopyErrors.COPY_ON_PRIVATE_CONSTRUCTOR_DATA_CLASS, context)`  
        `}`  
    `}`  
`}`

#### **Paso 3: Definir y Reportar el Diagnóstico**

Para reportar un error, necesitamos definirlo primero usando KtDiagnosticFactory. Esto se hace en un objeto, que contendrá la información del error, como su severidad (error o advertencia) y el mensaje que se mostrará.  
`// Objeto para definir los diagnósticos del plugin`  
`object NoCopyErrors {`  
    `val COPY_ON_PRIVATE_CONSTRUCTOR_DATA_CLASS by error0<KtExpression>(`  
        `positioningStrategy = PositioningStrategies.DECLARATION_NAME`  
    `)`  
`}`

`// En el checker, usamos el reporter para emitir el diagnóstico`  
`reporter.reportOn(`  
    `expression.source, // La ubicación en el código fuente donde se mostrará el error`  
    `NoCopyErrors.COPY_ON_PRIVATE_CONSTRUCTOR_DATA_CLASS,`  
    `context`  
`)`

#### **Paso 4: Registrar el Checker**

El último paso es registrar nuestro NoCopyCallChecker para que el compilador lo ejecute. Esto se hace a través de una implementación de FirAdditionalCheckersExtension, que a su vez se registra en el FirExtensionRegistrar.  
`// En el módulo :compiler-plugin`

`// La extensión que proporciona los checkers adicionales`  
`class NoCopyAdditionalCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {`  
    `override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {`  
        `// Registramos nuestro checker de llamadas a funciones`  
        `override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(NoCopyCallChecker)`  
    `}`  
`}`

`// El registrador de FIR, que registra la extensión de checkers`  
`class NoCopyFirExtensionRegistrar : FirExtensionRegistrar() {`  
    `override fun ExtensionRegistrarContext.configurePlugin() {`  
        `+::NoCopyAdditionalCheckersExtension`  
    `}`  
`}`

Este ejemplo demuestra por qué el análisis estático con plugins de FIR es tan potente. Un linter externo o un checker basado en PSI (K1) tendría que realizar un trabajo de inferencia complejo para determinar si una llamada a una función llamada "copy" corresponde realmente al método de una data class y cuál es la visibilidad resuelta de su constructor. En cambio, con FIR, cuando nuestro checker se ejecuta en la fase CHECKERS, toda esta información semántica ya ha sido calculada y está directamente accesible en los nodos del árbol FIR. Esto simplifica enormemente la lógica del checker, haciéndola más robusta, precisa y menos propensa a errores de interpretación.

## **Parte 3: Nivel Intermedio: Modificando el Código Existente**

Una vez dominado el análisis de código, el siguiente nivel de complejidad en el desarrollo de plugins es la transformación: modificar el código existente durante la compilación. Esta sección explora cómo alterar declaraciones y tipos, utilizando como guía plugins bien establecidos.

### **Capítulo 6: Transformando Declaraciones: El Plugin all-open como Caso de Estudio**

El plugin all-open es una herramienta esencial para los desarrolladores que utilizan frameworks basados en proxies, como Spring o Quarkus. Estos frameworks requieren que las clases y sus miembros sean open para poder crear subclases en tiempo de ejecución. Escribir open manualmente en cada clase y función es tedioso y propenso a errores. El plugin all-open automatiza este proceso, haciendo que cualquier clase o miembro anotado con una anotación específica sea open por defecto.  
Replicar esta funcionalidad es un excelente ejercicio para entender la transformación de declaraciones en K2.

#### **Paso 1: La Extensión FirStatusTransformerExtension**

La herramienta clave para esta tarea es FirStatusTransformerExtension. Como su nombre indica, esta extensión permite transformar el FirDeclarationStatus de cualquier declaración. El FirDeclarationStatus es un objeto que contiene los modificadores de una declaración, como su visibilidad (public, private, etc.) y su modalidad (final, open, abstract).

#### **Paso 2: Implementación**

La implementación de la extensión consta de dos métodos principales:

1. **needTransformStatus(declaration: FirDeclaration)**: Este método actúa como un predicado. El compilador lo llama para cada declaración y, si devuelve true, entonces se invocará el método transformStatus. Aquí es donde se coloca la lógica para decidir si una declaración debe ser transformada, por ejemplo, comprobando si tiene nuestra anotación personalizada (ej. @OpenForFramework).  
2. **transformStatus(status: FirDeclarationStatus, declaration: FirDeclaration)**: Este es el método que realiza la transformación real. Recibe el estado original de la declaración y debe devolver un nuevo FirDeclarationStatus con la modalidad modificada.

`// En :plugin-annotations`  
`annotation class OpenForFramework`

`// En :compiler-plugin`  
`class AllOpenStatusTransformer(session: FirSession) : FirStatusTransformerExtension(session) {`

    `override fun needTransformStatus(declaration: FirDeclaration): Boolean {`  
        `// Solo transformamos si la declaración o su clase contenedora tienen la anotación`  
        `return declaration.hasAnnotation(OPEN_FOR_FRAMEWORK_CLASS_ID, session) ||`  
               `declaration.getContainingClass(session)?.hasAnnotation(OPEN_FOR_FRAMEWORK_CLASS_ID, session) == true`  
    `}`

    `override fun transformStatus(`  
        `status: FirDeclarationStatus,`  
        `declaration: FirDeclaration`  
    `): FirDeclarationStatus {`  
        ``// Solo cambiamos la modalidad si es `final` (la modalidad por defecto)``  
        `if (status.modality == Modality.FINAL) {`  
            `return status.copy(modality = Modality.OPEN)`  
        `}`  
        `return status`  
    `}`  
`}`

*(Nota: hasAnnotation y getContainingClass son funciones de ayuda que habría que implementar usando las APIs de FIR para inspeccionar anotaciones y la jerarquía de contenedores).*

#### **Paso 3: Registro**

Finalmente, la extensión se registra en el FirExtensionRegistrar como en los capítulos anteriores:  
`class AllOpenFirExtensionRegistrar : FirExtensionRegistrar() {`  
    `override fun ExtensionRegistrarContext.configurePlugin() {`  
        `+::AllOpenStatusTransformer`  
    `}`  
`}`

La existencia del método needTransformStatus es una optimización de diseño fundamental. El compilador podría, en teoría, llamar a transformStatus para cada una de las miles de declaraciones en un proyecto. Sin embargo, al separar el "predicado" de la "transformación", se permite al compilador evitar el costoso trabajo de crear nuevos objetos de estado para la inmensa mayoría de las declaraciones que no necesitan ser modificadas. Para un autor de plugins, esta es una lección clave: los plugins deben ser lo más específicos y "perezosos" posible en su activación para no degradar el rendimiento de la compilación, que es, irónicamente, uno de los principales beneficios de K2.

### **Capítulo 7: Añadiendo Supertipos Dinámicamente**

Otra poderosa capacidad de los plugins es la de modificar la jerarquía de tipos de una clase, añadiendo supertipos (clases base o interfaces) dinámicamente. Un caso de uso canónico es el del plugin kotlinx.serialization, que hace que las clases anotadas con @Serializable implementen implícitamente la interfaz KSerializer con los argumentos de tipo correctos.  
Como ejercicio, crearemos un plugin más simple que haga que cualquier clase anotada con @SerializableObject implemente la interfaz java.io.Serializable.

#### **Paso 1: La Extensión FirSupertypeGenerationExtension**

La extensión diseñada para esta tarea es FirSupertypeGenerationExtension. Se ejecuta en una fase muy temprana del pipeline de compilación, la fase SUPER\_TYPES, justo después de que el compilador haya resuelto los supertipos que el desarrollador escribió explícitamente en el código.

#### **Paso 2: Implementación**

Al igual que la extensión anterior, esta también sigue un patrón de predicado y transformación:

1. **needTransformSupertypes(declaration: FirClassLikeDeclaration)**: Devuelve true si la clase (FirClassLikeDeclaration) tiene nuestra anotación @SerializableObject.  
2. **computeAdditionalSupertypes(...)**: Si el predicado fue true, este método se invoca. Su trabajo es construir y devolver una lista de FirResolvedTypeRef que representan los nuevos supertipos a añadir.

`// En :plugin-annotations`  
`annotation class SerializableObject`

`// En :compiler-plugin`  
`class SerializableSupertypeGenerator(session: FirSession) : FirSupertypeGenerationExtension(session) {`

    `companion object {`  
        `private val SERIALIZABLE_CLASS_ID = ClassId.fromString("java/io/Serializable")`  
    `}`

    `override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {`  
        `return declaration.hasAnnotation(SERIALIZABLE_OBJECT_CLASS_ID, session)`  
    `}`

    `override fun computeAdditionalSupertypes(`  
        `classLikeDeclaration: FirClassLikeDeclaration,`  
        `resolvedSupertypes: List<FirResolvedTypeRef>`  
    `): List<FirResolvedTypeRef> {`  
        `// Verificamos si ya implementa Serializable para no añadirlo dos veces`  
        `if (resolvedSupertypes.any { it.classId == SERIALIZABLE_CLASS_ID }) {`  
            `return emptyList()`  
        `}`

        `// Construimos una referencia de tipo resuelta para java.io.Serializable`  
        `val serializableType = SERIALIZABLE_CLASS_ID.constructClassLikeType(emptyArray(), isNullable = false)`  
          `.toFirResolvedTypeRef()`

        `return listOf(serializableType)`  
    `}`  
`}`

*(Nota: toFirResolvedTypeRef es una función de ayuda que crea el objeto FirResolvedTypeRef a partir de un ConeKotlinType)*.  
La fase en la que se ejecuta este plugin es de vital importancia. FirSupertypeGenerationExtension se invoca durante la fase SUPER\_TYPES. Si se ejecutara más tarde, por ejemplo, después de la resolución de miembros, el compilador ya habría tomado decisiones críticas basadas en la jerarquía de tipos original (como qué métodos son override y cuáles no). Añadir un nuevo supertipo en una etapa tardía invalidaría todo ese trabajo y podría llevar a un estado inconsistente. Al ejecutarse en esta fase temprana, se asegura que el resto del pipeline de compilación vea la clase con su conjunto completo y final de supertipos, como si el desarrollador los hubiera escrito a mano desde el principio, garantizando la coherencia del modelo semántico.

## **Parte 4: Nivel Profesional: Generación de Código con FIR e IR**

Esta sección aborda la capacidad más avanzada y potente de los plugins de compilador: la generación de código. A diferencia de la modificación, aquí se crean nuevas declaraciones (clases, funciones, propiedades) desde cero, permitiendo una reducción drástica del código repetitivo y la creación de abstracciones de alto nivel.

### **Capítulo 8: La Magia de la Generación de Código: FirDeclarationGenerationExtension**

La FirDeclarationGenerationExtension es la piedra angular para la generación de código en el frontend de K2. Es, con diferencia, la extensión más potente y, por ende, la más compleja. Es utilizada por plugins como kotlinx.serialization para generar métodos serializer() y constructores de deserialización.  
Su diseño en K2 difiere significativamente de su predecesor en K1 (SyntheticResolveExtension). En lugar de que el plugin genere proactivamente todos los miembros para una clase de una sola vez, FirDeclarationGenerationExtension sigue una API de tipo "proveedor" (provider-like). El compilador *pregunta* al plugin si puede generar una declaración para un identificador específico (ClassId para clases, CallableId para funciones/propiedades).

#### **El Contrato de la API**

La interacción con el compilador se realiza a través de varios métodos clave:

* **Métodos de predicado (get...):** Antes de intentar generar nada, el compilador pregunta al plugin qué declaraciones *podría* generar.  
  * getTopLevelClassIds() / getNestedClassIds(classId): Informa al compilador sobre los ClassId de las clases de nivel superior o anidadas que el plugin puede generar.  
  * getCallableNamesForClass(classSymbol): Informa al compilador sobre los nombres de las funciones y propiedades que el plugin puede generar para una clase específica. Por ejemplo, para generar un constructor, se debe devolver SpecialNames.INIT.  
* **Métodos de generación (generate...):** Si el compilador encuentra una referencia a un nombre o ID que un plugin ha declarado poder generar, llamará al método de generación correspondiente.  
  * generateClassLikeDeclaration(classId): Construye y devuelve el árbol FIR para una nueva clase o interfaz.  
  * generateFunctions(callableId, context): Construye y devuelve una lista de FirSimpleFunction para un CallableId dado.  
  * generateProperties(callableId, context): Construye y devuelve una lista de FirProperty.

#### **El Reto de la Resolución**

Un requisito fundamental y desafiante de esta extensión es que **todas las declaraciones que se devuelven deben estar completamente resueltas**. Esto significa que su resolvePhase debe ser FirResolvePhase.BODY\_RESOLVE, todos sus FirTypeRef deben ser FirResolvedTypeRef, y su estado (FirDeclarationStatus) debe ser un FirResolvedDeclarationStatus. Esto implica que el autor del plugin es responsable de construir un fragmento de árbol FIR semánticamente completo y coherente, lo cual requiere un conocimiento profundo de las APIs y los builders de FIR.

### **Capítulo 9: Caso Práctico 1: Generando un método toString() optimizado**

Para ilustrar el proceso, crearemos un plugin que genera un método toString() personalizado. Para cualquier data class anotada con @CustomToString, el plugin generará una implementación de toString() que solo incluye las propiedades que, a su vez, estén anotadas con @IncludeInToString.  
Este ejemplo es ideal para demostrar el patrón de diseño fundamental para la generación de código en K2: una generación en dos fases que abarca el frontend (FIR) y el backend (IR).

#### **Paso 1: Análisis e Información al Compilador (FIR)**

Usando FirDeclarationGenerationExtension, primero informamos al compilador de nuestra capacidad para generar la función toString().  
`class ToStringGenerationExtension(session: FirSession) : FirDeclarationGenerationExtension(session) {`  
    `override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: DeclarationGenerationContext): Set<Name> {`  
        `// Si la clase está anotada con @CustomToString, le decimos al compilador que podemos generar 'toString'`  
        `return if (classSymbol.hasAnnotation(CUSTOM_TO_STRING_ANNOTATION_ID, session)) {`  
            `setOf(Name.identifier("toString"))`  
        `} else {`  
            `emptySet()`  
        `}`  
    `}`  
    `//... aquí iría el método generateFunctions`  
`}`

#### **Paso 2: Generación de la Firma en el Frontend (FIR)**

Cuando el compilador necesite resolver la función toString() para una clase anotada, llamará a generateFunctions. En esta etapa, solo generamos la **firma** de la función, no su cuerpo.  
`override fun generateFunctions(callableId: CallableId, context: DeclarationGenerationContext?): List<FirSimpleFunctionSymbol> {`  
    `val owner = context?.owner?: return emptyList()`

    `// Construimos la función 'toString' usando builders de FIR`  
    `val function = buildSimpleFunction {`  
        `moduleData = session.moduleData`  
        `origin = FirDeclarationOrigin.Plugin(key)`  
        `status = FirResolvedDeclarationStatus(Visibilities.Public, Modality.OPEN, EffectiveVisibility.Public).apply {`  
            `isOverride = true // Es un override de Any.toString()`  
        `}`  
        `returnTypeRef = session.builtinTypes.stringType`  
        `name = callableId.callableName`  
        `symbol = FirNamedFunctionSymbol(callableId)`  
        `dispatchReceiverType = owner.defaultType()`  
    `}.symbol`

    `return listOf(function)`  
`}`

Es crucial observar que **no generamos el cuerpo de la función (block) en FIR**. La razón se explorará a continuación. Al generar solo la declaración, permitimos que el resto del frontend (análisis de tipos, resolución de llamadas) "vea" nuestra nueva función y la trate como si existiera en el código fuente. Esto es vital para la coherencia del modelo semántico y para que el IDE la reconozca para el autocompletado y el análisis.

#### **Paso 3: Generación de la Implementación en el Backend (IR)**

La implementación real del cuerpo de la función se delega al backend, utilizando una IrGenerationExtension. Esta extensión opera sobre el **IR (Intermediate Representation)**, que es la estructura de datos que utilizan los backends para generar el código final (bytecode, JS, etc.).  
`class ToStringIrGenerationExtension(private val session: FirSession) : IrGenerationExtension {`  
    `override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {`  
        `val irBuilder = IrBuilderWithScope(pluginContext, moduleFragment.descriptor)`  
          
        `// Transformador que recorre el árbol IR`  
        `moduleFragment.transform(object : IrElementTransformerVoid() {`  
            `override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {`  
                `// Buscamos nuestra función 'toString' generada (identificada por su origen de plugin)`  
                `if (!declaration.isToStringFunctionGeneratedByOurPlugin()) {`  
                    `return super.visitSimpleFunction(declaration)`  
                `}`

                `// Generamos el cuerpo de la función usando el IrBuilder`  
                `declaration.body = irBuilder.irBlockBody {`  
                    `val propertiesToInclude = getPropertiesWithAnnotation(declaration.parentAsClass, INCLUDE_ANNOTATION_ID)`  
                    `val stringParts = mutableListOf<IrExpression>()`  
                    `//... lógica para construir una cadena concatenando los valores de las propiedades...`  
                    `+irReturn(buildStringConcatenation(stringParts))`  
                `}`  
                `return declaration`  
            `}`  
        `}, null)`  
    `}`  
`}`

Esta separación de responsabilidades entre FIR e IR es el patrón canónico para la generación de código en K2. Generar la firma en FIR asegura la corrección semántica y la integración con el IDE. Delegar la implementación del cuerpo a IR es más simple porque en esa etapa ya no nos preocupamos por el análisis semántico, sino únicamente por la generación de instrucciones para la plataforma de destino. Este patrón de dos fases es la forma robusta y recomendada de abordar la generación de código compleja.

### **Capítulo 10: Caso Práctico 2: Creando un Builder a partir de una data class**

Para consolidar el patrón de generación de código FIR/IR, abordaremos un ejemplo más ambicioso: la creación automática de un patrón Builder para una data class. Dada una clase como data class Person(val name: String, val age: Int), nuestro plugin generará una clase anidada Person.Builder con métodos withName(String), withAge(Int) y un método build() que devuelve una instancia de Person.

#### **Paso 1: Declarar la Clase Anidada en FIR**

Utilizando FirDeclarationGenerationExtension, primero informamos al compilador sobre la existencia de nuestra clase Builder.

* **getNestedClassIds(ownerClassId)**: Si la clase propietaria (Person) está anotada con @GenerateBuilder, devolvemos el ClassId para la clase anidada Person.Builder.  
* **generateClassLikeDeclaration(classId)**: Cuando el compilador nos pida generar Person.Builder, usamos buildRegularClass para construir el árbol FIR de la clase Builder. En esta etapa, no es necesario añadir sus miembros todavía.

#### **Paso 2: Declarar los Métodos y Propiedades del Builder en FIR**

A continuación, generamos los miembros de la clase Builder.

* **Propiedades de respaldo:** Para cada propiedad de Person (ej. name), generamos una propiedad mutable correspondiente en Builder (ej. var name: String? \= null). Esto se hace implementando generateProperties para el CallableId de Person.Builder.name.  
* **Métodos withX():** Para cada propiedad, generamos un método withName(value: String): Builder. La implementación de generateFunctions construirá la firma de estos métodos, que toman un parámetro y devuelven el propio Builder para permitir llamadas encadenadas.  
* **Método build():** Finalmente, generamos la firma del método build(), que no toma parámetros y devuelve un tipo Person.

#### **Paso 3: Implementar los Cuerpos en IR**

Con todas las firmas declaradas en FIR, pasamos a la IrGenerationExtension para implementar la lógica.

1. **Encontrar la clase Builder en IR:** Recorremos el IrModuleFragment para encontrar nuestra clase Builder generada.  
2. **Implementar los métodos withX():** Usando un IrBuilderWithScope, generamos el cuerpo para cada método with.... La lógica es simple: asignar el valor del parámetro a la propiedad de respaldo correspondiente en el Builder y devolver this (irReturn(irGet(builderInstance))).  
3. **Implementar el método build():** Este es el paso más importante. Generamos un cuerpo que:  
   * Crea una llamada al constructor primario de la clase Person original (irCall(personConstructor)).  
   * Para cada parámetro del constructor, proporciona el valor de la propiedad de respaldo correspondiente del Builder (irGetField(irGet(builderInstance), builderPropertyField)).  
   * Devuelve la nueva instancia de Person creada.

Este ejemplo avanzado demuestra cómo los plugins de compilador pueden implementar patrones de diseño complejos de forma automática, eliminando una enorme cantidad de código repetitivo y propenso a errores, todo de una manera segura y consciente de los tipos. La combinación de la declaración en FIR y la implementación en IR permite que estas generaciones de código complejas se integren perfectamente en el proceso de compilación y sean totalmente transparentes para el desarrollador que utiliza el plugin.

## **Parte 5: Asegurando la Calidad: Testing y Debugging**

Un plugin de compilador es una pieza de software de bajo nivel que puede tener un impacto profundo en el código de un usuario. Por lo tanto, es absolutamente crítico asegurar su corrección y robustez a través de pruebas exhaustivas. Un plugin sin un buen conjunto de pruebas es un riesgo inaceptable para cualquier proyecto.

### **Capítulo 11: Pruebas Unitarias y de Integración para sus Plugins**

La herramienta estándar de facto para probar plugins de compilador de Kotlin es la librería **kotlin-compile-testing**. Esta librería permite ejecutar el compilador de Kotlin directamente dentro de una prueba de JUnit, proporcionando una forma potente y flexible de verificar el comportamiento de un plugin. Afortunadamente, ha sido actualizada para soportar la compilación con K2.

#### **Estructura de las Pruebas**

El enfoque de kotlin-compile-testing se basa en la compilación de ficheros de código fuente que se proporcionan como cadenas de texto o ficheros reales. Típicamente, los casos de prueba se organizan en un directorio testData, donde cada subdirectorio representa un conjunto de pruebas. Existen dos tipos principales de pruebas:

1. **Pruebas de Diagnóstico (diagnostics):** Se utilizan para verificar que el plugin reporta los errores o advertencias correctos en el código incorrecto. La prueba compila un fragmento de código que se espera que falle y luego afirma que el resultado de la compilación contiene el diagnóstico esperado.  
   `@Test`  
   ``fun `copy en data class con constructor privado debe fallar`() {``  
       `val source = SourceFile.kotlin("MyClass.kt", """`  
           `data class User private constructor(val name: String)`  
           `fun test() {`  
               `val user = User.create("test")`  
               `user.copy() // Error esperado aquí`  
           `}`  
       `""")`

       `val result = KotlinCompilation().apply {`  
           `sources = listOf(source)`  
           `compilerPluginRegistrars = listOf(NoCopyCompilerPluginRegistrar())`  
           `useK2 = true // Habilitar K2`  
       `}.compile()`

       `assertThat(result.exitCode).isEqualTo(ExitCode.COMPILATION_ERROR)`  
       `assertThat(result.messages).contains("COPY_ON_PRIVATE_CONSTRUCTOR_DATA_CLASS")`  
   `}`

2. **Pruebas de Generación de Código (box tests):** Se utilizan para verificar que el código generado por el plugin es correcto y se comporta como se espera. La prueba compila código que utiliza las declaraciones generadas y, si la compilación es exitosa, utiliza reflexión para cargar las clases compiladas y ejecutar métodos para afirmar su comportamiento.  
   `@Test`  
   ``fun `toString generado debe funcionar correctamente`() {``  
       `val source = SourceFile.kotlin("MyClass.kt", """`  
           `@CustomToString data class Person(@IncludeInToString val name: String, val age: Int)`  
       `""")`

       `val result = KotlinCompilation().apply {`  
           `sources = listOf(source)`  
           `compilerPluginRegistrars = listOf(ToStringGenerationPluginRegistrar())`  
           `inheritClassPath = true // Para que las anotaciones estén disponibles`  
           `useK2 = true`  
       `}.compile()`

       `assertThat(result.exitCode).isEqualTo(ExitCode.OK)`

       `val personClass = result.classLoader.loadClass("Person")`  
       `val constructor = personClass.getConstructor(String::class.java, Int::class.java)`  
       `val instance = constructor.newInstance("John", 30)`

       `val toStringValue = instance.toString()`  
       `assertThat(toStringValue).isEqualTo("Person(name=John)")`  
   `}`

#### **Configuración para K2**

Para que kotlin-compile-testing utilice el compilador K2, se deben cumplir dos condiciones :

1. En el objeto KotlinCompilation, establecer la propiedad useK2 \= true.  
2. Asegurarse de que el CompilerPluginRegistrar del plugin que se está probando devuelva true en su propiedad override val supportsK2.

Adoptar un enfoque de desarrollo dirigido por pruebas (TDD) para la metaprogramación es extremadamente beneficioso. La API del compilador es compleja y el ciclo de feedback de compilar el plugin, aplicarlo a un proyecto de prueba y observar el resultado es lento. Escribir una prueba primero permite definir de forma precisa el comportamiento deseado (un diagnóstico específico o un código generado concreto) y luego iterar rápidamente en la implementación del plugin hasta que la prueba pase. Librerías como kotlin-compile-testing hacen que este flujo de trabajo sea no solo posible, sino también eficiente.

### **Capítulo 12: Depurando el Compilador**

Cuando las pruebas fallan o el comportamiento del plugin no es el esperado, es necesario depurar. Depurar un plugin de compilador puede ser intimidante, pero existen varias estrategias efectivas.

* **Debugging Remoto de Gradle:** El compilador de Kotlin se ejecuta a menudo en un proceso separado del demonio de Gradle. La forma más robusta de depurar es configurar una ejecución de Gradle para que espere a que se conecte un depurador. Se puede iniciar la tarea de compilación con los flags \-Dorg.gradle.debug=true \-Dkotlin.daemon.jvm.options="-Xdebug,-Xrunjdwp:transport=dt\_socket,server=y,suspend=y,address=5005", y luego conectar el depurador de IntelliJ a ese proceso en el puerto 5005\. Esto permite establecer puntos de interrupción y recorrer el código del plugin como en cualquier otra aplicación.  
* **Dumping de FIR/IR:** A veces, el problema no es un error lógico, sino que el árbol FIR o IR no tiene la estructura que esperamos. El compilador de Kotlin proporciona flags para volcar el estado del árbol en diferentes fases. El flag \-Xphases-to-dump=FIR\_RESOLVE,FIR\_TO\_IR es particularmente útil. También se pueden utilizar funciones internas para la depuración, como FirElement.render(), que proporciona una representación de texto simple del subárbol FIR, aunque herramientas más visuales como HtmlFirDump son para uso interno del equipo de JetBrains. Inspeccionar estos volcados puede revelar por qué una transformación no se está aplicando o por qué una declaración no tiene la forma correcta.  
* **Logging:** La técnica más simple y a menudo más rápida es añadir sentencias de println o usar un logger dentro del código del plugin. Imprimir el estado de los nodos FIR o IR en puntos clave puede ayudar a trazar la ejecución y entender el flujo de datos.  
* **Debugging Simplificado con KSP2:** Vale la pena mencionar que una de las grandes ventajas de KSP2 es que ha sido rediseñado para poder ejecutarse como una librería standalone, en lugar de un plugin de compilador incrustado. Esto significa que para depurar un procesador KSP2, a menudo se puede llamar a su punto de entrada directamente desde una prueba unitaria, permitiendo establecer puntos de interrupción sin la necesidad de configurar un depurador remoto para Gradle.

## **Parte 6: Ecosistema Avanzado y Futuro**

Con los fundamentos y las técnicas de desarrollo cubiertas, esta última parte sitúa el conocimiento de los plugins de K2 en el contexto más amplio del ecosistema de Kotlin, analizando herramientas clave y mirando hacia el futuro de la metaprogramación en la plataforma.

### **Capítulo 13: Plugins Clave del Ecosistema K2**

Analizar cómo los plugins más importantes utilizan las APIs del compilador es una de las mejores maneras de aprender técnicas avanzadas.

#### **Análisis de KSP2**

Kotlin Symbol Processing (KSP) es la herramienta recomendada para la mayoría de los casos de uso de metaprogramación basados en anotaciones. Su evolución, KSP2, está intrínsecamente ligada a K2.

* **Arquitectura:** A diferencia de KSP1, que se implementó como un plugin del compilador K1, KSP2 es una **librería standalone**. Internamente, utiliza el compilador K2 para analizar el código fuente, pero expone su propia API estable y de alto nivel. Este cambio de arquitectura tiene implicaciones profundas:  
  * **Mejor Rendimiento:** Al alinearse con la forma en que K2 compila los ficheros, KSP2 puede procesar todos los fuentes una sola vez, mejorando el rendimiento en proyectos multiplataforma.  
  * **Depurabilidad Superior:** Al ser una librería, se puede invocar KSP2 programáticamente desde una prueba, lo que permite establecer puntos de interrupción directamente en los procesadores sin la complejidad de adjuntar un depurador remoto al demonio de Gradle.  
  * **Consistencia y Productividad:** KSP2 refina el comportamiento de la API para ser más predecible y proporcionar una mejor recuperación de errores, por ejemplo, resolviendo correctamente tipos contenedores incluso si un argumento de tipo no existe.

#### **Análisis del Plugin de Jetpack Compose**

El plugin de compilador de Jetpack Compose es, sin duda, el ejemplo más sofisticado y poderoso de lo que se puede lograr con estas herramientas. No solo genera código, sino que altera fundamentalmente la semántica del lenguaje para crear un paradigma de programación declarativo.  
Estudiar sus internos es una clase magistral sobre el uso de las extensiones del compilador :

* **Análisis y Diagnósticos (Frontend):** Compose utiliza una extensión similar a FirAdditionalCheckersExtension para imponer sus reglas. Por ejemplo, si se intenta llamar a una función anotada con @Composable desde una función normal que no lo está, el plugin detecta esta violación y reporta un error de compilación que se muestra directamente en el IDE.  
* **Transformación y Generación de Código (Backend):** La verdadera "magia" de Compose ocurre en el backend, a través de una IrGenerationExtension. Esta extensión realiza una transformación profunda (un *lowering*) de las funciones @Composable:  
  1. **Inyección del Composer:** El plugin reescribe la firma de cada función composable para añadir un parámetro oculto, el Composer, que es el objeto central del runtime de Compose para gestionar el árbol de la UI.  
  2. **Gestión de Estado y Recomposición:** Las llamadas a remember y los State se transforman en código que interactúa con el sistema de snapshots de Compose.  
  3. **Generación de Grupos y Memorización:** El plugin envuelve el código de las funciones en "grupos" que permiten al runtime saltar la ejecución de composables cuyos parámetros no han cambiado, logrando así la recomposición inteligente y eficiente.

El plugin de Compose demuestra que las extensiones del compilador no son solo para reducir código repetitivo, sino que pueden ser utilizadas para construir nuevos paradigmas de programación sobre la base del lenguaje Kotlin.

### **Capítulo 14: Migración de Plugins de K1 a K2**

Con K2 siendo el compilador por defecto y el plan de JetBrains para deprecir K1 , la migración de los plugins existentes de K1 a K2 no es opcional, sino una necesidad. Aunque no existe una guía oficial única y exhaustiva para la migración de plugins, se pueden derivar los principios clave de las guías de migración de código y de la Analysis API.

#### **Cambios Clave de API**

La migración implica una reescritura significativa, ya que las abstracciones fundamentales han cambiado:

* **De Descriptores y BindingContext a Símbolos FIR:** Este es el cambio más fundamental. Toda la lógica que dependía de la resolución perezosa de Descriptores y de la consulta al BindingContext debe ser reemplazada por la navegación explícita del árbol FIR y el uso de Símbolos FIR para obtener información semántica.  
* **De ComponentRegistrar a CompilerPluginRegistrar:** Como se ha visto, el punto de entrada del plugin debe migrar a la nueva interfaz CompilerPluginRegistrar, que está desacoplada de las APIs de IntelliJ.  
* **De Extensiones de K1 a Extensiones de K2:** Se debe mapear la funcionalidad de las antiguas extensiones a sus equivalentes en K2. Por ejemplo, la lógica de una SyntheticResolveExtension de K1 debe ser portada a una FirDeclarationGenerationExtension y una IrGenerationExtension en K2.

#### **Estrategia de Migración**

Se recomienda un enfoque por fases para una migración controlada:

1. **Compatibilidad Dual:** En una primera etapa, se puede hacer que el plugin soporte tanto K1 como K2. Esto se logra manteniendo el código de K1 y añadiendo la nueva implementación para K2, seleccionando cuál usar en tiempo de ejecución. El CompilerPluginRegistrar debe devolver override val supportsK2 \= true.  
2. **Eliminación de K1:** Una vez que la base de usuarios haya migrado mayoritariamente a Kotlin 2.0, se puede lanzar una nueva versión mayor del plugin que elimine por completo el código de K1, simplificando el mantenimiento.

### **Capítulo 15: El Futuro de los Plugins de Kotlin**

El desarrollo de plugins de compilador en Kotlin se encuentra en un momento emocionante y de transición. La estabilización de K2 ha sentado las bases para la siguiente gran evolución.

#### **Hacia una API Estable**

Uno de los mayores obstáculos para el desarrollo de plugins ha sido la inestabilidad de las APIs del compilador, que podían cambiar drásticamente entre versiones menores de Kotlin. Con la arquitectura de K2 ya consolidada, el equipo de JetBrains ha anunciado que su trabajo se centrará en diseñar y lanzar una **API de plugins de compilador estable**. Este será un cambio de juego, ya que reducirá drásticamente la barrera de entrada y el coste de mantenimiento para los autores de plugins, fomentando un ecosistema de herramientas mucho más rico y robusto.

#### **Roadmap del Compilador y el Ecosistema**

El roadmap público de Kotlin proporciona una visión clara de las prioridades de JetBrains. Las áreas de enfoque que influirán en el futuro de los plugins incluyen:

* **Soporte Mejorado para Kotlin/Wasm:** A medida que WebAssembly madura, los plugins jugarán un papel clave en la optimización y la interoperabilidad del código Kotlin compilado a Wasm.  
* **Exportación Directa a Swift:** La capacidad de generar cabeceras de Swift directamente desde Kotlin abrirá nuevas vías para que los plugins modifiquen o enriquezcan las APIs que se exponen a los desarrolladores de iOS.  
* **Build Tools API y Gradle Declarativo:** Se está trabajando en nuevas APIs para la integración con herramientas de construcción, lo que podría simplificar la forma en que los plugins se configuran y se aplican a través de Gradle.

#### **Conclusiones**

Escribir plugins de compilador otorga un poder inmenso para moldear el lenguaje Kotlin, pero conlleva una gran responsabilidad. El compilador K2, con su arquitectura FIR basada en fases, ha creado un entorno más predecible, performante y robusto para la metaprogramación. Dominar sus extensiones, desde los simples checkers hasta la compleja generación de código en dos fases (FIR/IR), permite a los desarrolladores crear herramientas que pueden eliminar el código repetitivo, imponer arquitecturas limpias e incluso inventar nuevos paradigmas de programación. A medida que el ecosistema avanza hacia una API de plugins estable, ahora es el momento perfecto para experimentar, contribuir a proyectos de código abierto y prepararse para una nueva era de extensibilidad en el universo Kotlin.

### **Anexo: Deconstruyendo @Composable: Magia del Compilador en Acción**

Una de las preguntas más frecuentes entre los desarrolladores de Kotlin es: "¿Cómo funciona realmente @Composable?". La respuesta es que esta anotación es quizás el ejemplo más poderoso y visible de lo que un plugin de compilador puede lograr. No es una característica nativa del lenguaje, sino una transformación profunda realizada por el plugin de compilador de Jetpack Compose.

#### **¿Qué hace el plugin de Compose?**

Cuando el compilador encuentra una función anotada con @Composable, el plugin de Compose intercepta el proceso de compilación en múltiples fases para alterar su comportamiento y estructura.

1. **Análisis en el Frontend (FIR):**  
   * **Validación de Reglas:** El plugin registra checkers personalizados (FirAdditionalCheckersExtension) que imponen las reglas del universo Compose. Por ejemplo, emite un error si intentas llamar a una función @Composable desde una función que no lo es. Esto proporciona feedback inmediato en el IDE.  
   * **Inferencia de Tipos Especiales:** Intercepta la resolución de tipos para que una expresión lambda anotada con @Composable sea reconocida como un tipo de función especial, no como una lambda estándar.  
2. **Transformación en el Backend (IR):** La verdadera "magia" ocurre en la fase de generación de IR, a través de una IrGenerationExtension. Esta extensión reescribe el código de la función de manera fundamental:  
   * **Inyección de Parámetros:** La firma de la función es alterada para incluir parámetros ocultos. Los más importantes son una instancia del Composer, el objeto del runtime que gestiona el árbol de la UI, y un entero changed que es un campo de bits para rastrear qué parámetros han cambiado desde la última ejecución.  
   * **Envoltura de Control de Flujo:** El cuerpo de la función se envuelve en llamadas a composer.startRestartGroup(...) y composer.endRestartGroup(...). Estos "grupos" son los que permiten al runtime de Compose saltarse la ejecución de un Composable si sus entradas no han cambiado, lo que se conoce como "recomposición inteligente".  
   * **Transformación de Estado:** Las llamadas a funciones como remember y el uso de State\<T\> son transformadas en código que se registra con el Composer para que los cambios de estado puedan ser rastreados y desencadenen una recomposición.

En esencia, el código que escribes:  
`@Composable`  
`fun Greeting(name: String) {`  
    `Text(text = "Hello $name!")`  
`}`

Es transformado por el plugin en algo conceptualmente similar a esto:  
`fun Greeting(name: String, composer: Composer, changed: Int) {`  
    `composer.startRestartGroup(...)`  
    `// Lógica para comprobar si 'changed' indica que 'name' ha cambiado`  
    `// y si la función debe ser re-ejecutada o saltada.`  
      
    `Text(text = "Hello $name!", composer = composer, changed = 0)`  
      
    `composer.endRestartGroup(...)?.updateScope {... }`  
`}`

#### **Ejemplo Práctico: Creando Nuestra Propia Anotación Transformadora**

Para entender cómo podríamos replicar una parte de esta magia, creemos un plugin que implemente una anotación @Traceable. El objetivo es que cualquier función anotada con @Traceable imprima automáticamente un mensaje al entrar y al salir de la función, incluyendo su nombre y los valores de sus parámetros.  
Esta tarea no se puede realizar con KSP, ya que requiere **modificar el cuerpo de una función existente**. Por lo tanto, debemos usar una IrGenerationExtension.  
**Paso 1: Definir la anotación**  
En el módulo :plugin-annotations, creamos la anotación:  
`@Target(AnnotationTarget.FUNCTION)`  
`annotation class Traceable`

**Paso 2: Crear la transformación de IR**  
En el módulo :compiler-plugin, creamos nuestra IrGenerationExtension.  
`class TraceableIrGenerationExtension : IrGenerationExtension {`  
    `override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {`  
        `// Un transformador que visitará cada elemento del árbol IR`  
        `moduleFragment.transform(TraceableTransformer(pluginContext), null)`  
    `}`  
`}`

**Paso 3: Implementar el IrElementTransformer**  
El transformador es donde ocurre la modificación del código. Visitaremos cada función, comprobaremos si tiene nuestra anotación y, si es así, reescribiremos su cuerpo.  
`class TraceableTransformer(private val pluginContext: IrPluginContext) : IrElementTransformerVoid() {`

    `override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {`  
        `// Solo nos interesan las funciones con cuerpo y anotadas con @Traceable`  
        `val body = declaration.body?: return super.visitSimpleFunction(declaration)`  
        `if (!declaration.hasAnnotation(FqName("com.example.plugin.Traceable"))) {`  
            `return super.visitSimpleFunction(declaration)`  
        `}`

        `// Usamos un IrBuilder para construir nuevo código IR`  
        `val irBuilder = IrBuilderWithScope(`  
            `pluginContext,`  
            `Scope(declaration.symbol),`  
            `declaration.startOffset,`  
            `declaration.endOffset`  
        `)`

        `// Referencia a la función println`  
        `val printlnFun = pluginContext.referenceFunctions(FqName("kotlin.io.println"))`  
          `.single { it.owner.valueParameters.size == 1 && it.owner.valueParameters.first().type.isAny() }`

        `// Creamos el nuevo cuerpo`  
        `val newBody = irBuilder.irBlockBody {`  
            `// 1. Log de entrada`  
            `+irCall(printlnFun).apply {`  
                `putValueArgument(0, irString("==> Entering: ${declaration.name}"))`  
            `}`

            `// 2. Log de parámetros`  
            `declaration.valueParameters.forEach { param ->`  
                `val paramValue = irGet(param)`  
                `val text = irConcat().apply {`  
                    `addArgument(irString("    ${param.name.asString()} = "))`  
                    `addArgument(paramValue)`  
                `}`  
                `+irCall(printlnFun).apply {`  
                    `putValueArgument(0, text)`  
                `}`  
            `}`

            `// 3. Copiamos el cuerpo original`  
            `for (statement in (body as IrBlockBody).statements) {`  
                `+statement`  
            `}`

            `// 4. Log de salida`  
            `+irCall(printlnFun).apply {`  
                `putValueArgument(0, irString("<== Exiting: ${declaration.name}"))`  
            `}`  
        `}`

        `declaration.body = newBody`  
        `return super.visitSimpleFunction(declaration)`  
    `}`  
`}`

**Paso 4: Registrar la extensión**  
Finalmente, registramos nuestra IrGenerationExtension en el CompilerPluginRegistrar. A diferencia de las extensiones de FIR, las de IR se registran directamente.  
`@AutoService(CompilerPluginRegistrar::class)`  
`class TraceableCompilerPluginRegistrar : CompilerPluginRegistrar() {`  
    `override val supportsK2: Boolean = true`

    `override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {`  
        `// Las extensiones de IR se registran directamente aquí`  
        `IrGenerationExtension.registerExtension(TraceableIrGenerationExtension())`  
    `}`  
`}`

Este ejemplo, aunque más simple que el de Compose, demuestra el principio fundamental: un plugin de compilador puede usar una IrGenerationExtension para interceptar el código en su forma intermedia y reescribirlo por completo, añadiendo nueva lógica, inyectando llamadas a funciones y alterando el flujo de ejecución. Es este poder el que permite que anotaciones como @Composable transformen Kotlin en un lenguaje con capacidades que van mucho más allá de su sintaxis base.

### **Anexo: Implementando un Sandbox de Seguridad con Plugins de Compilador**

Una aplicación potente de los plugins de compilador es la creación de un "sandbox" de seguridad, un entorno restringido donde se puede ejecutar código no confiable (por ejemplo, scripts de usuario) con la garantía de que no podrá acceder a APIs peligrosas. El concepto es similar al Groovy Sandbox de Jenkins, pero la implementación con un plugin de Kotlin es fundamentalmente diferente y, en muchos aspectos, más segura.

#### **El Enfoque de Kotlin: Sandbox en Tiempo de Compilación**

El Groovy Sandbox de Jenkins funciona principalmente en tiempo de ejecución. Intercepta cada llamada a un método o acceso a una propiedad mientras el script se está ejecutando y lo comprueba contra una lista de operaciones permitidas (whitelist). Si se intenta una operación no permitida, se lanza una excepción.  
Un plugin de compilador de Kotlin adopta un enfoque de **análisis estático en tiempo de compilación**. En lugar de esperar a que el código se ejecute, el plugin analiza el código fuente y, si detecta el uso de cualquier API fuera de la lista blanca, **falla la compilación**. Esto significa que el código malicioso o no permitido nunca llega a generarse ni a ejecutarse.  
**Ventajas del enfoque en tiempo de compilación:**

* **Seguridad Proactiva:** Los problemas se detectan antes de la ejecución. El código inseguro ni siquiera se compila.  
* **Feedback Inmediato:** Los errores de compilación aparecen directamente en el IDE, guiando al desarrollador para que escriba código válido.  
* **Sin Sobrecarga en Runtime:** Como todas las comprobaciones se realizan en tiempo de compilación, no hay ninguna penalización de rendimiento en el código ejecutado.

#### **Arquitectura de un Sandbox con Plugins de K2**

La implementación de un sandbox de este tipo se basa en la extensión FirAdditionalCheckersExtension, que nos permite añadir nuestras propias reglas de validación al compilador.  
**Paso 1: Definir la API Segura y la Anotación de Sandbox**  
Primero, definimos una anotación para marcar el código que debe ser verificado por nuestro sandbox. También definimos nuestra "lista blanca" de paquetes y clases permitidos.  
`// En :plugin-annotations`  
`@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)`  
`annotation class Sandboxed`

`// En :compiler-plugin`  
`object SandboxWhitelist {`  
    `val ALLOWED_PACKAGES = setOf(`  
        `"kotlin",`  
        `"kotlin.collections",`  
        `"com.example.safeapi" // Nuestro paquete de API segura`  
    `)`

    `fun isAllowed(fqName: FqName): Boolean {`  
        `return ALLOWED_PACKAGES.any { fqName.asString().startsWith(it) }`  
    `}`  
`}`

**Paso 2: Implementar los Checkers de FIR**  
El núcleo del sandbox son los checkers que recorren el árbol FIR y validan cada parte del código.

1. **Checker de Llamadas a Funciones (FirFunctionCallChecker):** Este checker intercepta todas las llamadas a funciones.  
   `object SandboxFunctionCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {`  
       `override fun check(expression: FirFunctionCall, context: CheckerContext, reporter: DiagnosticReporter) {`  
           `val functionSymbol = expression.toResolvedCallableSymbol()?: return`  
           `val functionFqName = functionSymbol.callableId.asSingleFqName()`

           `if (!SandboxWhitelist.isAllowed(functionFqName)) {`  
               `reporter.reportOn(expression.source, MySandboxErrors.DISALLOWED_FUNCTION_CALL, functionFqName, context)`  
           `}`  
       `}`  
   `}`

2. **Checker de Importaciones (FirImportChecker):** Este checker valida que no se importen clases o funciones de paquetes no permitidos.  
   `object SandboxImportChecker : FirImportChecker(MppCheckerKind.Common) {`  
       `override fun check(import: FirImport, context: CheckerContext, reporter: DiagnosticReporter) {`  
           `val importedFqName = import.importedFqName?: return`  
           `if (!SandboxWhitelist.isAllowed(importedFqName)) {`  
               `reporter.reportOn(import.source, MySandboxErrors.DISALLOWED_IMPORT, importedFqName, context)`  
           `}`  
       `}`  
   `}`

3. **Checker de Creación de Objetos (FirObjectConstructorCallChecker):** Este checker se asegura de que solo se puedan instanciar clases de la lista blanca.  
   `object SandboxInstantiationChecker : FirObjectConstructorCallChecker(MppCheckerKind.Common) {`  
       `override fun check(expression: FirObjectConstructorCall, context: CheckerContext, reporter: DiagnosticReporter) {`  
           `val classId = expression.resolvedType.toRegularClassSymbol(context.session)?.classId?: return`  
           `if (!SandboxWhitelist.isAllowed(classId.asSingleFqName())) {`  
               `reporter.reportOn(expression.source, MySandboxErrors.DISALLOWED_INSTANTIATION, classId.asSingleFqName(), context)`  
           `}`  
       `}`  
   `}`

**Paso 3: Registrar los Checkers y los Diagnósticos**  
Finalmente, definimos los mensajes de error y registramos nuestros checkers para que se apliquen solo al código anotado con @Sandboxed.  
`// Definición de errores`  
`object MySandboxErrors {`  
    `val DISALLOWED_IMPORT by error1<KtImportDirective, FqName>("Import de '${0}' no permitido en el sandbox.")`  
    `val DISALLOWED_FUNCTION_CALL by error1<KtExpression, FqName>("Llamada a '${0}' no permitida en el sandbox.")`  
    `val DISALLOWED_INSTANTIATION by error1<KtExpression, FqName>("Instanciación de '${0}' no permitida en el sandbox.")`  
`}`

`// Extensión que registra los checkers`  
`class SandboxCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {`  
    `override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {`  
        `// Aquí activamos los checkers solo si la declaración está anotada`  
        `override val fileCheckers: Set<FirFileChecker> = setOf(SandboxFileChecker)`  
    `}`  
`}`

`// Un checker de fichero para activar los otros checkers solo si es necesario`  
`object SandboxFileChecker : FirFileChecker(MppCheckerKind.Common) {`  
    `override fun check(file: FirFile, context: CheckerContext, reporter: DiagnosticReporter) {`  
        `// Lógica para ver si el fichero contiene código @Sandboxed`  
        `// Si es así, se podrían registrar los otros checkers dinámicamente o`  
        `// los checkers individuales podrían comprobar la anotación en su contexto.`  
        `// Por simplicidad, asumimos que los checkers individuales validan la anotación.`  
    `}`  
`}`

*(Nota: Una implementación completa requeriría que cada checker navegue hacia arriba en el árbol FIR desde la expresión actual para encontrar la declaración contenedora (función o clase) y verificar si tiene la anotación @Sandboxed antes de reportar un error.)*

#### **Conclusión**

Implementar un sandbox con un plugin de compilador de Kotlin ofrece un modelo de seguridad robusto y estático. A diferencia del enfoque dinámico de Jenkins, que valida las operaciones en tiempo de ejecución, el enfoque de Kotlin previene la compilación de código inseguro desde el principio. Esta es una herramienta extremadamente poderosa para cualquier plataforma que necesite ejecutar código de terceros de forma segura, garantizando que solo una API predefinida y verificada sea accesible.

#### **Obras citadas**

1\. kotlin/docs/fir/fir-basics.md at master · JetBrains/kotlin · GitHub, https://github.com/JetBrains/kotlin/blob/master/docs/fir/fir-basics.md 2\. www.apollographql.com, https://www.apollographql.com/blog/journey-to-k2-using-the-new-compiler-in-apollo-kotlin\#:\~:text=K2%20is%20a%20complete%20rewrite,type%20inference%20%C2%BB%20(source). 3\. All New K2 Compiler\! \- Sakhawat Hossain \- Medium, https://shakilbd.medium.com/all-new-k2-compiler-766dd7d24d14 4\. What's new in Kotlin 2.0.0, https://kotlinlang.org/docs/whatsnew20.html 5\. Let's Talk about Kotlin K2 \- ProAndroidDev, https://proandroiddev.com/lets-talk-about-kotlin-k2-3e1c6f10d74 6\. Kotlin Upgrade advances K2 Compiler \- Itlize, https://www.itlize.com/blogfull193.html 7\. Crash Course on the Kotlin Compiler | K1 \+ K2 Frontends, Backends | by Amanda Hinchman | Google Developer Experts | Medium, https://medium.com/google-developer-experts/crash-course-on-the-kotlin-compiler-k1-k2-frontends-backends-fe2238790bd8 8\. Kotlin Compiler Plugins \- Kt. Academy, https://kt.academy/article/ak-compiler-plugin 9\. K2 compiler migration guide | Kotlin Documentation, https://kotlinlang.org/docs/k2-compiler-migration-guide.html 10\. K2 Compiler Performance Benchmarks and How to Measure Them on Your Projects, https://blog.jetbrains.com/kotlin/2024/04/k2-compiler-performance-benchmarks-and-how-to-measure-them-on-your-projects/ 11\. Try the K2 compiler in your Android projects, https://android-developers.googleblog.com/2023/07/try-k2-compiler-in-your-android-projects.html 12\. Kotlin 2.0 Launched with New, Faster, More Flexible K2 Compiler \- InfoQ, https://www.infoq.com/news/2024/05/kotlin-2-k2-compiler/ 13\. Meet Renovated Kotlin Support – K2 Mode: What You Need to Know | The IntelliJ IDEA Blog, https://blog.jetbrains.com/idea/2024/08/meet-the-renovated-kotlin-support-k2-mode/ 14\. K2 Kotlin Mode (Alpha) in IntelliJ IDEA \- The JetBrains Blog, https://blog.jetbrains.com/idea/2024/03/k2-kotlin-mode-alpha-in-intellij-idea/ 15\. Preparing for K2 \- Zac Sweers, https://www.zacsweers.dev/preparing-for-k2/ 16\. KSP2 Adds Kotlin K2 Support \- I Programmer, https://www.i-programmer.info/news/90-tools/16853-ksp2-adds-kotlin-k2-support.html 17\. ahinchman1/Kotlin-Compiler-Crash-Course: A repository of ... \- GitHub, https://github.com/ahinchman1/Kotlin-Compiler-Crash-Course 18\. The Story Behind K2 Mode and How It Works | The IntelliJ IDEA Blog, https://blog.jetbrains.com/idea/2025/04/the-story-behind-k2-mode-and-how-it-works/ 19\. Upcoming Kotlin language features teased at KotlinConf 2025 \- SD Times, https://sdtimes.com/softwaredev/upcoming-kotlin-language-features-teased-at-kotlinconf-2025/ 20\. Writing Your First Kotlin Compiler Plugin \- JetBrains, https://resources.jetbrains.com/storage/products/kotlinconf2018/slides/5\_Writing%20Your%20First%20Kotlin%20Compiler%20Plugin.pdf 21\. Extending Kotlin compiler with KSP \- Nativeblocks, https://nativeblocks.io/blog/extending-kotlin-compiler-with-ksp/ 22\. do we have docs yet on how to write compiler plugins for k2 kotlinlang \#compiler, https://slack-chats.kotlinlang.org/t/18889146/do-we-have-docs-yet-on-how-to-write-compiler-plugins-for-k2- 23\. KSP2 Aims to Improve Kotlin Meta-Programming, Adds Support for the K2 Kotlin Compiler, https://www.infoq.com/news/2024/01/ksp2-kotlin-metaprogramming/ 24\. Migrating to Kotlin 2.0 causes error with KSP \- android \- Stack Overflow, https://stackoverflow.com/questions/78552223/migrating-to-kotlin-2-0-causes-error-with-ksp 25\. KotlinConf 2018 \- Writing Your First Kotlin Compiler Plugin by Kevin Most \- YouTube, https://www.youtube.com/watch?v=w-GMlaziIyo 26\. kotlin/docs/fir/fir-plugins.md at master · JetBrains/kotlin \- GitHub, https://github.com/JetBrains/kotlin/blob/master/docs/fir/fir-plugins.md 27\. demiurg906/kotlin-compiler-plugin-template \- GitHub, https://github.com/demiurg906/kotlin-compiler-plugin-template 28\. The useful template to get started with K2 Compiler Plugin development. \- GitHub, https://github.com/kitakkun/k2-compiler-plugin-template 29\. Kotlin/compiler-plugin-template \- GitHub, https://github.com/Kotlin/compiler-plugin-template 30\. Foso/KotlinCompilerPluginExample: This is an example ... \- GitHub, https://github.com/Foso/KotlinCompilerPluginExample 31\. Deprecate \`ComponentRegistrar\` : KT-52665 \- JetBrains YouTrack, https://youtrack.jetbrains.com/issue/KT-52665/Deprecate-ComponentRegistrar 32\. For k2 plugin FirExtensionRegistrar now there is no need to kotlinlang \#k2-adopters, https://slack-chats.kotlinlang.org/t/16061706/for-k2-plugin-firextensionregistrar-now-there-is-no-need-to- 33\. kitakkun/NoCopy-Compiler-Plugin: The K2 Kotlin Compiler ... \- GitHub, https://github.com/kitakkun/NoCopy-Compiler-Plugin 34\. kotlin/compiler/fir/checkers/src/org/jetbrains/kotlin/fir/analysis/extensions/FirAdditionalCheckersExtension.kt at master \- GitHub, https://github.com/JetBrains/kotlin/blob/master/compiler/fir/checkers/src/org/jetbrains/kotlin/fir/analysis/extensions/FirAdditionalCheckersExtension.kt 35\. Kotlin K2 FIR Quickstart Guide – Handstand Sam, https://handstandsam.com/2024/05/30/kotlin-k2-fir-quickstart-guide/ 36\. kotlin/compiler/fir/providers/src/org/jetbrains/kotlin/fir/extensions/FirDeclarationGenerationExtension.kt at master \- GitHub, https://github.com/JetBrains/kotlin/blob/master/compiler/fir/providers/src/org/jetbrains/kotlin/fir/extensions/FirDeclarationGenerationExtension.kt 37\. When returning names with \`FirDeclarationGenerationExtension kotlinlang \#compiler, https://slack-chats.kotlinlang.org/t/16633865/when-returning-names-with-firdeclarationgenerationextension- 38\. Hi TLDR how we can properly bind generated in FirDeclaration kotlinlang \#k2-adopters, https://slack-chats.kotlinlang.org/t/23212073/hi-tldr-how-we-can-properly-bind-generated-in-firdeclaration 39\. Compiler plugin for K2 : r/Kotlin \- Reddit, https://www.reddit.com/r/Kotlin/comments/1gmk2jz/compiler\_plugin\_for\_k2/ 40\. Writing Your Second Kotlin Compiler Plugin, Part 1 — Project Setup | by Brian Norman, https://bnorm.medium.com/writing-your-second-kotlin-compiler-plugin-part-1-project-setup-7b05c7d93f6c 41\. A library for testing Kotlin and Java annotation processors, compiler plugins and code generation \- GitHub, https://github.com/tschuchortdev/kotlin-compile-testing 42\. Issue \#302 · tschuchortdev/kotlin-compile-testing \- Support K2 \- GitHub, https://github.com/tschuchortdev/kotlin-compile-testing/issues/302 43\. How do you use \`HtmlFirDump\` in a Kotlin K2 compiler plugin? \- Stack Overflow, https://stackoverflow.com/questions/78138209/how-do-you-use-htmlfirdump-in-a-kotlin-k2-compiler-plugin 44\. KSP2 Preview: Kotlin K2 and Standalone Source Generator \- Android Developers Blog, https://android-developers.googleblog.com/2023/12/ksp2-preview-kotlin-k2-standalone.html 45\. Jetpack Compose internals \- ‍ Jorge Castillo, https://jorgecastillo.dev/book/ 46\. KotlinConf 2019: The Compose Runtime, Demystified by Leland Richardson \- YouTube, https://www.youtube.com/watch?v=6BRlI5zfCCk 47\. Reverse-Engineering the Compose Compiler Plugin: Intercepting the Frontend, https://hinchman-amanda.medium.com/reverse-engineering-the-compose-compiler-plugin-intercepting-the-frontend-657162893b11 48\. Kotlin roadmap, https://kotlinlang.org/docs/roadmap.html 49\. Compatibility guide for Kotlin 2.1, https://kotlinlang.org/docs/compatibility-guide-21.html 50\. Migrating Kotlin Plugins to K2 Mode \- YouTube, https://www.youtube.com/watch?v=HDyWQLNMJQ8 51\. K2 Compiler Migration Guide | Baeldung on Kotlin, https://www.baeldung.com/kotlin/k2-migration-guide 52\. Kotlin Multiplatform roadmap \- JetBrains, https://www.jetbrains.com/help/kotlin-multiplatform-dev/kotlin-multiplatform-roadmap.html 53\. Jetpack Compose Internals | PDF | Function (Mathematics) \- Scribd, https://www.scribd.com/document/647800592/Jetpack-Compose-Internals 54\. Compose Internals | PDF | Android (Operating System) | Scope (Computer Science) \- Scribd, https://www.scribd.com/document/757854860/Compose-Internals 55\. Modify Kotlin compiled code output based on annotation (not generate separate code), https://stackoverflow.com/questions/75771040/modify-kotlin-compiled-code-output-based-on-annotation-not-generate-separate-co 56\. Exploring Kotlin IR. At the time of writing this article… | by Brian Norman \- Medium, https://bnorm.medium.com/exploring-kotlin-ir-bed8df167c23 57\. In-process Script Approval \- Jenkins, https://www.jenkins.io/doc/book/managing/script-approval/ 58\. GroovyInterceptor.java \- jenkinsci/groovy-sandbox \- GitHub, https://github.com/jenkinsci/groovy-sandbox/blob/master/src/main/java/org/kohsuke/groovy/sandbox/GroovyInterceptor.java 59\. POTD: Groovy Sandbox \- Kohsuke Kawaguchi, https://kohsuke.org/2012/04/29/potd-groovy-sandbox/