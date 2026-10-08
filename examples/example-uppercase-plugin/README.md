# Escribir un plugin externo para PipelineK

Este directorio **es** el plugin de referencia: el artefacto externo que demuestra que la
extensibilidad funciona sin tocar el núcleo. `example.uppercase` está certificado y es la
plantilla que hay que copiar. Si algo de esta guía no coincide con el código de al lado, el
código manda: la guía se escribió ejecutándolo.

**Qué demuestra, en una frase:** un JAR independiente, compilado contra los contratos publicados
del SDK por una compilación de Gradle propia, aporta cuatro Steps, una directiva, un evento y una
capacidad, y el binario instalado lo descubre y lo ejecuta **sin un solo cambio en el núcleo**.

---

## 1. Ejecutarlo ya

```bash
cd v2
./gradlew publishSdkForExternalPlugin buildExamplePlugin :pipeline-application:installDist
```

`buildExamplePlugin` forka una compilación de Gradle **separada** (el plugin tiene su propio
`settings.gradle.kts`) que resuelve el SDK como coordenadas Maven ordinarias desde
`v2/build/sdk-repo`, producido por esta misma revisión de fuentes.

```bash
BIN=v2/pipeline-application/build/install/pipelinek/bin/pipelinek
JAR=examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar
$BIN run --plugin-jar $JAR examples/example-uppercase-plugin/scripts/uppercase-demo.pipeline.kts
```

Salida real (abreviada a lo que importa):

```text
Discovered external Step plugins: scm-git, http, junit, utilities, example.uppercase
Discovered external directive plugins: example.uppercase.UppercaseDirectiveContributor
Discovered external event definitions: example.uppercase.applied
...
{"kind":"StepStarted","stepName":"external/registrystep-0","stepType":"example"}
{"kind":"StepFinished","stepName":"external/registrystep-0","stepType":"example"}
{"kind":"StageFinished","stageName":"External","outcome":"success"}
{"kind":"RunFinished","outcome":"success"}
Pipeline finished with SUCCESS
```

### El par de aislamiento, y por qué es el test que de verdad cuenta

Quitar `--plugin-jar` es la mitad que casi nadie escribe. Salida real:

```text
$BIN run examples/example-uppercase-plugin/scripts/uppercase-demo.pipeline.kts
Discovered external Step plugins: scm-git, http, junit, utilities
{"kind":"CompilationFinished","diagnostics":[
   {"severity":"ERROR","message":"Unresolved reference 'example'.","line":1,"column":8},
   {"severity":"ERROR","message":"Unresolved reference 'uppercase'.","line":6,"column":13}]}
Pipeline finished with FAILURE: Unresolved reference 'uppercase'.
EXIT 1
```

Sin el JAR el plugin no está *a medias*: no está, y falla cerrado con un error de compilación
que nombra la referencia que falta. Con el JAR, verde. Esa asimetría es la que detecta una fuga
de classpath. Un plugin que "funciona" por lo que el host ya tenía en el classpath no está
demostrado.

---

## 2. El camino, paso a paso, con el fichero real de al lado

```text
 1. Input/Output tipados                    UppercaseInput / UppercaseOutput  (@Serializable)
 2. StepCodec<Input> y StepCodec<Output>     UppercaseCodec / UppercaseOutputCodec
 3. StepDescriptor                           effects, replayPolicy, executionLocation, pluginId
 4. StepContract                             key + descriptor + ambos codecs + capacidades
 5. StepHandler con SOLO lo declarado        `{ input, _ -> ... }` — nada de contexto ni coordinador
 6. StepDefinition                           objeto que une contrato y handler
 7. StepDefinitionContributor                UppercaseContributor; `definitions()` devuelve los 4
 8. empaquetar el contribuidor en un JAR     build.gradle.kts (independiente)
 9. registrar vía ServiceLoader              META-INF/services/... (4 descriptores, §4)
10. extensión DSL propia (Kotlin)            UppercaseDsl.kt
11. la extensión baja SOLO a registryStep()  una línea, sin efectos
12. registryStep produce RegistryStepSpec    IR declarativo, no ejecución
13. el compilador del núcleo la baja genéricamente (no conoce `uppercase`)
14. el runtime resuelve tu StepDefinition    por descubrimiento, por clave
15. se ejecuta por la espina durable canónica
16. StepContractSuite
17. CERTIFIED antes de considerarlo listo
```

El paso 5 merece énfasis porque es donde se rompe la arquitectura si uno se relaja: el handler
**no recibe** coordinador, servicio, journal ni `CanonicalRuntimeContext`. Recibe su Input y su
capacidad. Si necesitas el host para algo que no sea una capacidad declarada, el hueco es del
SDK: se clasifica y se pide, no se rodea.

---

## 3. El Step mínimo, tal cual

De `UppercaseStepDefinition.kt`, y esto es todo lo que hay:

```kotlin
object UppercaseStepDefinition : StepDefinition<UppercaseInput, UppercaseOutput> {
    val KEY = PluginStepId("example.uppercase")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "uppercase",
            configRef = "",
            pluginId = "example.uppercase",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = UppercaseCodec,
        outputCodec = UppercaseOutputCodec,
        requiredCapabilities = emptySet(),   // <- el valor de referencia, ver abajo
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, _ ->
        UppercaseOutput(value = input.text.uppercase())
    }
}
```

`requiredCapabilities = emptySet()` no es un descuido: es la afirmación más fuerte que un plugin
puede hacer sobre no necesitar cambios en el núcleo. Este Step no pide **nada** al host y aun así
corre. Por eso la observación de eventos vive en un Step **distinto**
(`example.uppercase.observed`) que declara exactamente una capacidad: fundirlos en uno con un
`boolean observe` habría hecho que todo llamador dependiera de una costura que la forma simple
nunca necesitó.

Y la extensión DSL, completa:

```kotlin
fun StageScope.uppercase(text: String) {
    registryStep(
        stepKey = UppercaseStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
}
```

Construye datos. No resuelve el registro, no ejecuta el handler, no toca capacidades, no consulta
estado.

---

## 4. Registro: los cuatro descriptores, literalmente

`src/main/resources/META-INF/services/` — un fichero por SPI, con el nombre de la clase dentro:

```text
dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
    example.uppercase.UppercaseContributor

dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor
    example.uppercase.UppercaseDirectiveContributor

dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
    example.uppercase.UppercaseEventContributor

dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
    example.uppercase.UppercaseCaseTableContributor
```

ServiceLoader es el adaptador de descubrimiento **actual**, no una ley eterna: lo que la
arquitectura exige es el SPI `StepDefinitionContributor`. Y una clave duplicada **falla cerrado**
nombrando al recién llegado y al que ya estaba; nunca gana el primero ni el último.

---

## 5. El manifiesto: sin él, no hay admisión

El JAR lleva `META-INF/pipelinek/plugin-manifest.json`, emitido derivándolo de la declaración
real (no escrito a mano). Contenido real del artefacto de este directorio:

```json
{
  "codec": "pipelinek-manifest-codec/v1",
  "schemaVersion": "manifest/v1",
  "plugin": "v1:plugin:example-uppercase-plugin/plugin/uppercase",
  "releaseVersion": "0.1.0",
  "releaseDigest": "sha256:4fbd537b...",
  "apiRange": "[0.47.0, 0.49.0)",
  "publisher": "example-uppercase",
  "delivery": "EXTERNAL_REFERENCE",
  "trust": "unverified",
  "families": ["UTILITIES"],
  "steps": [
    {"stepKey": "example.uppercase",                 "declaredCapabilities": []},
    {"stepKey": "example.uppercase.cased",           "declaredCapabilities": ["example.uppercase.case-table"]},
    {"stepKey": "example.uppercase.observed",        "declaredCapabilities": ["plugin.event-emission"]},
    {"stepKey": "example.uppercase.announcedCased",  "declaredCapabilities": ["example.uppercase.case-table", "plugin.event-emission"]}
  ],
  "directives": ["example.uppercase.casedOn"],
  "events": ["example.uppercase.applied"],
  "capabilities": ["example.uppercase.case-table", "plugin.event-emission"]
}
```

Dos cosas que conviene leer bien:

- **`declaredCapabilities` por step.** Es donde "capacidad declarada == capacidad usada" deja de
  ser una promesa y pasa a ser un dato que el host puede comprobar. Declarar de más te hace
  fallar; declarar de menos, también.
- **`apiRange`.** El host rechaza un plugin cuyo rango no cubra la versión que corre. La admisión
  es **previa a cualquier efecto**: si tu rango no encaja, tu handler nunca se ejecuta.

Un plugin sin este fichero es **rechazado** por el gate de admisión. Si construyes el tuyo a mano y
"funciona", míralo con lupa: hay una ruta que descubre contribuidores sin consultar el gate, y
depender de ella no es un contrato.

---

## 6. Lo que no haces tú

```text
EL PLUGIN ES TUYO:                        EL NÚCLEO ES SUYO:
  StepKey                                   RegistryStepSpec (representación estructural)
  Input / Output / codecs                   bajada genérica del compilador
  StepDescriptor, StepContract              descubrimiento
  handler                                   StepRegistry
  StepDefinition, contributor               protocolo durable
  extensión DSL ergonómica                  admisión de capacidades
                                            frontera de ejecución común
                                            journal / replay / recuperación
```

El compilador **no** sabe convertir tus argumentos en tu Input: eso lo hace tu fachada DSL, que
es código tuyo y se compila contigo.

---

## 7. Reglas duras

```text
- NO añadas tu StepKey a ningún catálogo del núcleo.
- NO modifiques el routing del coordinador ni añadas un caso al dispatcher.
- NO añadas un caso concreto al compilador.
- NO definas un subtipo propio de StepSpec: RegistryStepSpec es la única representación
  estructural para semántica abierta.
- NO te registres a mano en la fábrica del núcleo.
- NO ejecutes handlers durante la construcción del DSL.
- NO importes paquetes internos (coordinador, aplicación, durable, dispatcher).
- NO alcances CanonicalRuntimeContext desde un handler.
- NO te saltes la admisión de capacidades.
- NO infieras replay de la presencia de una salida codificada: ReplayPolicy es la autoridad.
- NO crees una ruta durable o de recuperación propia.
```

Si al construir tu plugin te encuentras necesitando cambiar algo del núcleo, **no lo cambies**:
es un hueco del SDK, se clasifica y se pide. La prueba de que esto es posible está en este mismo
directorio: cero modificaciones al núcleo.

---

## 8. Verificación antes de decir que está listo

```bash
cd v2
# Admisión de las CUATRO familias sobre el artefacto externo real, con fila CONTROL que exige
# que el plugin sin mutar SEA admitido (si el gate estuviera vacío, esa fila falla), y mutantes
# por dirección y familia. Construye el plugin solo: la tarea test depende de buildExamplePlugin.
./gradlew :pipeline-application:test --tests '*ExternalPluginFourFamilyAdmissionTest*'

# Suites de contrato POR STEP (no existe una clase única `StepContractSuite`: son Core*StepContractSuiteTest,
# una por Step, más las de los Steps del plugin).
./gradlew :pipeline-application:test --tests '*StepContractSuite*'

# El par de aislamiento sobre el binario instalado, con y sin --plugin-jar
$BIN run --plugin-jar $JAR  <tu>.pipeline.kts    # debe ir verde
$BIN run                    <tu>.pipeline.kts    # debe fallar nombrando lo que falta
```

Las dos primeras, juntas, medidas en esta revisión: **20 clases, 380 tests, 0 fallos**, EXIT 0.

Un plugin no es `CERTIFIED` por compilar ni por pasar tests unitarios. Necesita el artefacto real
pasando por descubrimiento, compilador, distribución instalada y ejecución. Y ojo con la lección
que el propio `ExternalPluginFourFamilyAdmissionTest` documenta: ese gate estuvo **verde sin probar
nada** porque sólo recogía Steps y entregaba el resto como defaults vacíos, así que los cuatro
plugins oficiales —que no declaran directiva ni evento— pasaban `emptySet() - emptySet()`. Un check
cuyo verde viene de la ausencia de su sujeto no se ha ejecutado.

---

## 9. Limitaciones honestas

- El paso 16 del camino (§2) se llama `StepContractSuite` en la documentación de arquitectura, y
esa **clase no existe**: la implementación es una suite por Step (`Core*StepContractSuiteTest`)
más las específicas del plugin. Los comandos que funcionan están en §8 y se ejecutaron.
- **`-PsdkVersion` es obligatorio y no tiene valor por defecto.** Es deliberado: un default sólo
  podría ser uno que no resuelve, y el fallo aparecería cientos de líneas más abajo nombrando una
  versión que nadie pidió. Pásala explícita.
- Las dependencias del SDK son `compileOnly`. Tu JAR lleva **sólo tus clases**; los contratos los
  pone el host en runtime. Si un build task tuyo necesita el SDK en runtime, dale su propia
  configuración (míralo en `sdkForManifestEmission`) en vez de ensanchar el scope del JAR.
- El repositorio SDK se resuelve como SNAPSHOT con TTL de caché a cero, para que un SDK obsoleto
  haga fallar tu build en lugar de satisfacerlo en silencio.
- Esto es la guía de **un plugin**. No cubre marketplace, hot reload, resolución de dependencias,
  firma, repositorio remoto ni gestor de ciclo de vida: nada de eso está construido.
