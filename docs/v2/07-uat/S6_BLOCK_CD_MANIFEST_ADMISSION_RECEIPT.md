# S6/C+D — Manifest machine-readable y PluginAdmission antes de cargar código

**Ciclo:** `p-733fb505b5a6bd2d/rp7-sem-s6-plugin-sdk`
**WorkItem:** `19ce6536-85c5-4c8a-9eea-730f41c1dc5b`
**Rama:** `s6-plugin-sdk`
**Bloque:** 1-C (manifest en artefacto) + 1-D (admisión central)

---

## Qué cambia

El manifest deja de ser un objeto que cada plugin construye en memoria y pasa a ser un
**documento que el runtime lee antes de ejecutar una línea del plugin**.

| | Antes | Después |
|---|---|---|
| Dónde vive la declaración | `PluginManifest(...)` construido en el contributor | `META-INF/pipelinek/plugin-manifest.json` dentro del JAR |
| Quién valida | el propio plugin, desde dentro | `PluginAdmission`, antes de inicializar la clase |
| Identidad del artefacto | el digest que el plugin declara de sí mismo | `MeasuredArtifactIdentity`, que separa lo declarado de lo medido |
| Orden | `ServiceLoader` → código → descubrir que el manifest era inválido | link sin inicializar → leer → admitir → **entonces** inicializar |

## Ficheros

**Dominio (`pipeline-domain`)**

- `PluginManifestCodec.kt` — formato, ruta canónica `RESOURCE_PATH`, `CODEC`, encode
  determinista y decode **total** (toda entrada malformada es un rechazo tipado, nunca una
  excepción).
- `PluginArtifactIdentity.kt` — `MeasuredArtifactIdentity`, `ArtifactIdentityVerdict`
  (Verified / Mismatch / **Unverified**) y `ArtifactOrigin`.
- `PluginAdmission.kt` — `PluginAdmission.admit`, `AdmittedPlugin`,
  `PluginContributionCrossCheck`.

**Aplicación (`pipeline-application`)**

- `PluginManifestResourceReader.kt` — lee el recurso por nombre; `strict` exige que el
  manifest y la clase vengan del **mismo artefacto**.
- `PluginAdmissionGate.kt` — las cuatro fases, en orden.

**Pruebas**

- `PluginManifestCodecFidelityTest` — 11 casos.
- `PluginAdmissionPreLoadOrderingTest` — 5 casos.
- `SentinelPluginArtifact.kt` — fixture: compila en el test un JAR real cuya clase escribe un
  sentinel al inicializarse.

## Evidencia ejecutada

```
:pipeline-application:test --tests '*PluginManifestCodecFidelityTest*' \
                           --tests '*PluginAdmissionPreLoadOrderingTest*'
BUILD SUCCESSFUL — 16 casos, 0 fallos, 0 errores, 0 skips
```

Leído del XML de resultados, no del exit code.

## No-vacuidad por mutación

La ley de ordenación se puso a prueba, y **falló dos veces antes de valer**.

**Mutación 1 — `initialize = false` → `true` en la fase 1.** Resultado: `BUILD SUCCESSFUL`,
5/5 en verde. La mutación NO rompio nada. Causa: el plugin se cargaba desde el classloader
padre, y una clase se inicializa una sola vez por classloader, así que el test de control
positivo ya la había inicializado y la inicialización mutada no producía ningún hecho
observable. **La ley era vacua.**

**Corrección.** El fixture pasa a compilarse en el propio test con el compilador del JDK y a
cargarse desde un JAR real, con el cargador de **plataforma** como padre. Cada test tiene su
propio cargador y su propio inicializador. Sin binarios en el repositorio: el fuente viaja
como string.

**Mutación 2 — la misma, sobre el fixture real.** Resultado: **2 fallos**, exactamente en
`a refused plugin leaves NO sentinel` y `a malformed manifest is refused`. El control
positivo siguió en verde, que es lo que confirma que el mecanismo sigue siendo capaz de
producir el fichero. Código de producción restaurado y verde re-verificado.

## Tres defectos encontrados por el camino

1. **En el arnés (mío).** El primer `SentinelPlugin` exponía un helper que el test tenía que
   llamar. «No hay sentinel» no probaba nada, porque lo único que escribía el fichero era la
   propia aserción. Corregido moviendo la escritura al inicializador estático.

2. **En el arnés (mío).** El precondition `assertFalse(wasInitialised)` fallaba porque *leer*
   la propiedad de un objeto Kotlin dispara su `init`. La comprobación rompía su propio sujeto.

3. **En producción.** `PluginManifestResourceReader` comparaba la URL del recurso
   (`jar:file:/…jar!/META-INF/…`) contra el code source (`file:/…jar`). Nunca son iguales, así
   que **rechazaba todos los plugins legítimos**. Solo apareció al cargar un artefacto real;
   con clases del classloader de test la ruta no se ejercitaba. Corregido con `artifactRootOf`,
   que quita el sufijo `!/` y el prefijo `jar:`.

## Cierre de C: el artefacto REAL emite y es admitido

`utilities` es el primero de los cuatro en cablear el documento, y lo hace **derivado del
código**, no escrito a mano:

- `UtilitiesPluginDeclaration` es la autoridad única. La lee el contributor en runtime **y** el
  build en build time. Escribir el JSON a mano en `build.gradle.kts` habría creado una segunda
  autoridad capaz de describir Steps que el código no tiene.
- El build lanza `emitUtilitiesManifest` (`JavaExec`) **después** de `computeUtilitiesDigest`,
  porque el manifest reporta el digest y sin procedencia fallaría cerrado.
- Verificado sobre el artefacto construido: `META-INF/pipelinek/plugin-manifest.json` está
  dentro de `utilities-0.36.0.jar`, con los 8 Steps, `apiRange [0.47.0, 0.49.0)` y el digest
  real.

### Prueba sobre el producto, no sobre un fixture

`BuiltPluginManifestArtifactTest` carga el JAR que el build produce de verdad:

- el JAR contiene el documento en la ruta canónica;
- el gate lo **admite** a 0.47.0;
- el gate lo **rechaza** a 0.60.0.

Los dos últimos casos son **mutuamente falsables**: admitir prueba que el documento se lee, y
ser rechazado fuera del rango prueba que se lee el dato correcto. Si la admisión ignorase el
manifest, el segundo caso fallaría.

Si el JAR no existiera la prueba **falla**, no se salta: un skip ahí sería un verde que no
prueba nada.

### Los cuatro plugins, no uno

El cableado se replicó en `http`, `scm-git` y `junit`, cada uno con su objeto de declaración
(`HttpPluginDeclaration`, `ScmGitPluginDeclaration`, `JUnitPluginDeclaration`) siguiendo el
mismo contrato. Los cuatro JAR construidos contienen el documento en la ruta canónica.

`BuiltPluginManifestArtifactTest` pasó a ser **parametrizada sobre los cuatro**, con 12 casos:
cada plugin lleva su documento, es admitido a 0.47.0 y rechazado a 0.60.0.

Regresión de los cuatro módulos de plugins: **289 tests, 0 fallos**, leída del XML.

El arnés de esa prueba paramètresada necesitó dos correcciones, y **las dos las encontré por
RED, no leyendo**:

1. Con el classloader de test como padre, las clases de los plugins se resuelven desde el
   directorio de salida de tests, no desde el JAR. `strict` rechazaba, y hacía bien: desde su
   punto de vista un plugin se declaraba en un artefacto y aportaba código de otro.
2. Al cambiar al cargador de plataforma, `pipeline-domain` desaparecía y el resolve fallaba
   con `NoClassDefFoundError`. La solución es un padre que delega en el de tests **ocultando
   los cuatro paquetes de plugin y el propio recurso del manifest** — sin esto último, el
   padre seguía publicando `META-INF/pipelinek/plugin-manifest.json` y ganaba el JAR equivocado:
   el fallo nombraba el JAR de scm-git cargando la clase de http.

En los dos casos la comprobación de producción era correcta y el arnés estaba mal. Es la
tercera vez que este arnés se autoengaña, y la razón es siempre la misma: dejar que el
classloader resuelva por uno.

### Una fabricacion eliminada antes de cerrar

`PluginManifestResourceReader.measure` hasheaba el **fichero del manifest** y ofrecía ese hash
como `measuredDigest`. Eso compara un documento contra un artefacto: siempre discreparía, y
discreparía *con significado* — un veredicto `Mismatch` dice «este plugin mintió», y el plugin
estaría diciendo la verdad. Fabricar una medida es peor que no medir, así que ahora devuelve
`measuredDigest = null`, que es `Unverified`: un tercer estado nombrado, no un pass.

## BLOCK 1-E — el cross-check, enganchado y con no-vacuidad probada

El gate ahora **descarta** era el punto ciego: la fase 4 forzaba la construcción del
contributor y tiraba el resultado. `admitContributions` queda enganchado, y su valor está en
que la prueba lo demuestra con **mutantes de artefacto real**, no con mocks.

| Caso | Artefacto | Resultado |
|---|---|---|
| declara un Step que no aporta | JAR de junit con el manifest reescrito | rechazado nombrando `junit.ghost` |
| aporta un Step que no declara | JAR de utilities con `readJson` eliminado del manifest | rechazado nombrando `core-utils.readJson` |
| control: los 4 oficiales sin mutar | los 4 JAR reales | admitidos |

El control importa: un cross-check que rechazara todo también rechazaría los dos mutantes, y
la puerta parecería funcionar mientras no admitiera nada.

### Tres defectos que encontró el cross-check

1. **En el gate (producción).** El cross-check comparaba el manifest admitido contra **todos**
   los contributors del classloader, no sólo los del plugin admitido: `ServiceLoader` devuelve
   todos. Con cuatro plugins en el classpath, cada plugin veía los Steps de los otros como
   "implementados sin declarar". Habría hecho que **todo plugin fuese rechazado en runtime**.
   Corregido acotando los providers al artefacto admitido — el mismo ámbito que `strict` ya
   aplicaba al manifest.

2. **En el arnés.** El helper que construye el JAR mutante **aplastaba** las rutas `META-INF/`
   a su nombre base. Eso destruía `META-INF/services` y dejaba al mutante sin proveedor: el
   arnés fabricaba exactamente el fallo que decía estar probando.

3. **En el arnés, y era verdad.** El fixture de ordenación de BLOCK 1-D declaraba
   `sentinel.step` y **no lo implementaba**. El cross-check lo detectó como drift. Era una
   inconsistencia real en una prueba que llevaba días en verde precisamente porque nada la
   comparaba. Ahora declara una capability y ningún Step, que es lo que de verdad aporta.

### Corrección de una predicción mía

En el ack de SDDK de `961bda11` afirmé que `apiCheck` exigiría una excepción BCV por las
superficies nuevas. **Era falso.** Medido: el diff del dump tiene **cero eliminaciones**, sólo
adiciones. Una excepción registra rupturas deliberadas, y aquí no hay ninguna. El propio
repositorio tiene el precedente exacto — `EventQuery.matches` quedó fuera del ledger del
mismo modo — y la regla dice que registrar una adición sería mentir sobre lo que se publicó.
Lo que corresponde es `apiDump`, y `apiCheck` queda verde con él.

## BLOCK 1-F — los registries se congelan: componer y observar son capacidades distintas

`StepRegistry` exponía `register` y su propio KDoc pedía tratarlo como lectura. Era falso
mientras tuviera ese método: cualquier tenedor de la referencia podía extender el registry que
un lector está consultando, así que el "snapshot inmutable" sólo lo era por convención.

Ahora la inmutabilidad es **una propiedad del tipo**, no una promesa:

| | Antes | Después |
|---|---|---|
| Composición | `StepRegistry.register(...)` | `StepRegistryBuilder.add(...)` |
| Congelado | no existía | `build()` copia el mapa |
| Lectura | la misma referencia | `StepRegistry` sin ningún método de registro |
| `keys()` | el key set vivo | vista `unmodifiable` |

`EventRegistry` se convierte igual: `register` pasa a su `Builder` y `create()` devuelve un
registro **ya congelado**, que es exactamente lo que necesitan los tres call sites de producción
que lo usaban como "vacío". `registerContributors` deja de ser extensión de `StepRegistry` y
pasa a serlo de `StepRegistryBuilder`.

El patrón no es nuevo: es `DirectiveRegistry.Builder`, que ya estaba probado aquí. Lo que S6
hace es aplicarlo al segundo registry que se había quedado mutable.

### Radio y reparto

**92 ficheros** usaban `InMemoryStepRegistry` y se migraron al builder. Se repartieron en tres
cubos por directorio — aplicación top-level, subdirectorios, y `pipeline-step-sdk` — más los
tests de contrato del dominio, que escribí aparte porque son la prueba de la ley nueva y no una
migración mecánica.

El cambio completo toca **104 ficheros de test**, y los 12 que no estaban en esos 92 tienen una
causa distinta que merece nombrarse: dos decoradores `CountingRegistry` que delegaban
`register` (eliminados, no migrados, porque no tenían a qué migrar: delegaban a un método que ya
no existe), dos contratos de `EventRegistry`, un test que comprobaba el rechazo de duplicado
pasando un registry ya congelado, uno con una línea que se pasó de largo, uno cuyo nombre
nombraba la clase borrada, y el test nuevo del validador.

Y **44 de producción**, de los cuales sólo 26 son de este bloque: los otros 18 son las firmas
`registerInto` de los Steps y contributors que ahora reciben un builder.

En seis ficheros de prueba hay un builder que **no se congela a propósito**: son las pruebas de
`duplicate registration fails closed`, que necesitan el lado mutable para provocar el segundo
`add` y afirmar que lanza. Medido, no estimado: son exactamente seis ficheros y siete
declaraciones de builder, todas dentro de una prueba de rechazo por duplicado. Congelarlos
haría imposible probar la ley.

### La ley, probada por mutación

Tres filas nuevas, cada una con la mutación que la mata:

1. **`build()` copia.** Mutar `FrozenStepRegistry(entries)` por el mapa vivo hace que un `add`
   posterior aparezca en un registry ya entregado.
2. **`keys()` es inmodificable.** Mutar `keys()` por `entries.keys` sin `unmodifiableSet` permite
   añadir Steps por el lado de lectura.
3. **`StepRegistry` no expone registro.** Re-añadir `register` deja verdes las dos filas
   anteriores mientras el runtime recupera un camino de mutación. Esta es la fila que lo nota.

La tercera es la que Justifica las otras dos: sin ella, las dos primeras pasarían aunque la
interfaz volviera a ser mutable.

### Lo que encontró el gate, y era culpa de bloques anteriores

BLOCK 1-C se certificó con pruebas dirigidas. **El gate completo no se había ejecutado desde
`961bda11`.** Al correrlo por primera vez en este bloque aparecieron cuatro fallos que no
pertenecían a 1-F:

1. **detekt rojo en cinco ficheros** de 1-C/1-D: sin newline final, y `decodeOrNull` con 129
   líneas y complejidad 28 sobre límites de 120 y 25. Extraído en tres secciones puras
   (`decodeHeader`, `decodeContract`, `decodeContributions`) **conservando el orden exacto de
   rechazo**, porque qué campo falla primero es parte del contrato.
2. **Un bug que me introduce esa extracción**: al sacar `PluginContributions(...)` del
   `runCatching` superior, su constructor — que rechaza un StepKey duplicado — pasó a lanzar
   `IllegalArgumentException` cruzando la frontera de decode. Lo detectó la prueba de fidelidad;
   el guard quedó restablecido donde la construcción ocurre ahora.
3. **`PluginApiParent`**, un duplicado muerto de `PluginArtifactFixture.ScopedParent`, con un
   KDoc que describía una protección del arnés que en realidad vive en el otro sitio.
4. **Cobertura de `pipeline-domain` al 72,86 %** con mínimo 75: `PluginManifest` y
   `PluginManifestValidator` estaban al **0 %** en su propio módulo, porque sus pruebas vivían
   en `pipeline-application`. No es relleno: el validador es una función de decisión pura y sus
   pruebas estaban en un módulo que no es dueño de la clase.

### Un defecto real que encontró esa prueba nueva

Al escribir el test de dominio del validador vi que su KDoc promete que las capabilities
requeridas por los contratos **aparecen** en el conjunto de nivel superior del manifest, y el
código comprobaba sólo lo contrario: `topLevel - usedBySteps`. Una dirección no es una
comprobación más débil, es la comprobación del conjunto equivocado — un Step podía exigir una
capability que el plugin nunca pidió.

Ahora se comprueban **las dos direcciones**, y cada una tiene su fila con su mutación. La
mutación ejecutada — quitar el check de under-claiming — tumba **exactamente una fila** y deja
las otras 16 en verde.

### BCV: aquí sí procede la excepción

En C/D/E medí que no había ruptura y **no** la registré. Aquí la medición dice lo contrario:

- `pipeline-domain`: se **elimina** la clase `InMemoryStepRegistry` completa, desaparecen los
  dos `register` de la interfaz `StepRegistry`, y `registerContributors` cambia de receptor.
- `pipeline-events`: `EventRegistry.register` desaparece del registry y aparece en su `Builder`.

Es ruptura binaria real y queda registrada en `v2/contract/published-contract-exceptions.json`
contra el SHA de implementación **`98a18992`**, con la alternativa rechazada por escrito:
mantener `register` con `@Deprecated` habría dejado `apiCheck` verde sin ninguna entrada, y a
la vez habría publicado dos maneras de componer, sólo una de las cuales obligaría a componer
antes de observar. Una ruta de mutación deprecada sobre un registry publicado es exactamente
la forma que este bloque elimina.

`create()` se conserva a propósito en `EventRegistry`: los tres call sites de producción que
querían un registry vacío siguen funcionando, y ahora reciben un valor que no puede crecer
después.

## BLOCK 1-G — una sola autoridad de composición

Con los registries congelados, la pregunta «qué contribuye esta instalación» tenía **tres
respuestas en tres ficheros**, resueltas en tres momentos distintos y bajo tres swaps de
classloader distintos:

| Qué | Dónde se componía |
|---|---|
| Steps | `Main.kt`, **dos veces** — una por rama, con el mismo bloque copiado |
| Events | dentro del cuerpo de `CompositionRoot` |
| Directives | dentro del cuerpo de `CompositionRoot`, más abajo |

Y `runCanonicalPipeline` **tenía un valor por defecto**: `stepRegistry =
CoreStepRegistryFactory.registry()`. Un llamante que olvidara el argumento obtenía un registry
sólo-core, sin error, ejecutando un pipeline que ignoraba en silencio todos los plugins de la
instalación. Eso es una vía *fail-open* justo por el hueco que 1-D y 1-E existen para cerrar.

### La forma

`PluginComposition.resolve(pluginClassLoader)` es ahora la única autoridad. Devuelve
`PreResolvedComposition`, que lleva los tres registries congelados, y su constructor interno hace
que **no exista el estado parcial**: si una composición falla, el valor no llega a construirse.
Fail-closed es una propiedad de la construcción, no una comprobación que alguien recuerde hacer.

`runCanonicalPipeline` recibe `composition: PreResolvedComposition` **sin valor por defecto**, y
`pluginClassLoader` desaparece de su firma: su KDoc afirmaba gobernar el descubrimiento de
directives, y tras mover la composición nada en ese fichero lo leía. Un parámetro que documenta
un papel que ya no tiene es una mentira más pequeña que un default que descarta plugins.

### La cuarta autoridad, medida y cerrada

`ExternalCapabilityContributorDiscovery` era la cuarta autoridad: usaba `ServiceLoader` **sin**
cambiar el TCCL y se evaluaba como valor por defecto *después* de que la composición restaurase
el loader. Su propia KDoc justifica que no hace falta porque «un JAR de plugin ya está en el
classpath de la distribución» (`BundledPluginClasspathPlan`), lo cual es cierto en la
distribución instalada pero **no** en el camino `--plugin-jars`, que usa un loader acotado.

**La sonda obvia habría mentido.** Preguntar por el plugin `http` da un verde cómodo:
`pipeline-application` tiene `implementation(project(":pipeline-step-sdk:http"))`, así que el
classpath de la app ya lleva el contributor *y* su fichero de servicios, y el descubrimiento
funciona sea cual sea el loader. Eso oculta el defecto en vez de refutarlo.

La sonda real usa un plugin que la app **no** tiene: sus clases vienen del padre —el classloader
del propio test— y sólo sus *declaraciones* de servicio viven en un JAR hijo. Eso aísla una
variable: ¿ve `ServiceLoader` una declaración que sólo existe por debajo del TCCL?

Medido, antes de arreglar:

- el Step `probe.scoped.step` **sí** se registraba, porque `resolve` cambia el TCCL;
- la capability `probe.scoped.transport` **no** la aportaba ningún contributor.

Es exactamente el fallo que la KDoc de `ExternalCapabilityContributorDiscovery` registra para
`http.request`: «descubierto por `ServiceLoader`, aparece en la lista de plugins que imprime la
CLI, y aun así no puede ejecutarse». Por eso se reconoció en vez de redescubrirse desde cero.

El arreglo es que `PreResolvedComposition` lleva ahora `capabilityContributors`, descubiertas
**dentro de la misma ventana**. El lado de credenciales se queda en `CompositionRoot` porque es
estado por run: envuelve el almacén que abrió *esta* invocación, así que no puede venir de un
descubrimiento por classpath. El default de `capabilityContributors` pasa a significar «lo que
la composición resolvió, más el seam de credenciales»; sigue siendo un default, pero ahora cierra
sobre un valor que la run ya resolvió en lugar de redescubrir bajo lo que el TCCL sea por entonces.

La fila de la sonda **invirtió su expectativa**, y la transición está escrita en el mensaje de
la aserción, no borrada en silencio: pasó de `assertFalse` con «MEASURED DEFECT» a `assertTrue`
con «REGRESSION». La fila de no-vacuidad que la acompaña afirma que el loader de la app **sigue**
sin ver ese contributor — si algún día lo viera, la fila anterior dejaría de probar que la ventana
de composición es lo que funciona.


### La ley, probada por mutación

Cinco filas en `PreResolvedCompositionTest` cruzan `PluginComposition.resolve` sin sustituto
(HF1, in-process): el camino CORE-only, el diagnóstico que no miente cuando no hubo
descubrimiento, un **JAR real** con un `META-INF/services` que nombra una clase inexistente, y
el TCCL restaurado incluso cuando la composición falla.

Siete filas en `FArchPreResolvedCompositionAuthorityTest` fijan la parte estructural, que desde
dentro de un módulo no se puede observar: cada uno de los **cuatro** puntos de descubrimiento
tiene **exactamente un** llamante en producción, `CompositionRoot` no compone nada, y el
parámetro `composition` no tiene valor por defecto. La primera de esas ocho es de no-vacuidad:
sin ella, «exactamente uno» lo satisface un escaneo vacío.

Mutaciones ejecutadas, con atribución 1:1:

- reintroducir un segundo `ExternalEventDefinitionDiscovery.compose()` en `Main.kt` tumba
  **exactamente 1 de 7** filas y deja las otras 6 verdes;
- devolver `ExternalCapabilityContributorDiscovery.discover()` al default de `CompositionRoot`
  tumba **exactamente 1 de 8** y deja las otras 7 verdes;
- quitar el `finally` que restaura el TCCL rompe la fila que lo afirma, y sólo esa.

### Lo que este bloque NO hace

No sustituye a BLOCK 2: esto es caracterización de la decisión de composición **en proceso**, no
certificación de comportamiento sobre la distribución instalada.

## BLOCK 1-H — el KSP era una segunda autoridad de metadata, y decía lo contrario

BLOCK 1-A quitó del KSP los interruptores semánticos por nombre. Este bloque audita lo que quedó:
comprobar si el generador que sobrevivió merecía sobrevivir.

### Lo que se midió, no lo que se suponía

| | La autoridad real | El fichero generado por el KSP |
|---|---|---|
| `requiredCapabilities` | `{EVENT_SINK_CAPABILITY}` | `emptyList()` |
| `configRef` | `""` | `"core.echo.config"` (inventado) |
| `jenkinsSurface` | `""` | `"echo\|workflow-durable-task-step\|F3"` (formato inventado) |
| `executionLocation`, `effects`, `replayPolicy` | coinciden | coinciden |

Y por encima de todo eso: **`GeneratedStepDescriptors.all` no tenía ni un consumidor** en todo el
repositorio. Nadie leía `@Step` ni `@JenkinsSurface` en runtime; la cadena entera era
`anotación → KSP → fichero → nada`.

### El defecto real ya estaba diagnosticado, y no donde parecía

`CoreShSingleAdmissionAuthorityTest` documenta, para `core.sh`, que «KSP lo convirtió en un
descriptor con `requiredCapabilities = emptyList()` donde el descriptor canónico exige
`SHELL_OPERATIONS_CAPABILITY`». P1 lo arregló borrando la **declaración duplicada**. El KSP que
fabricaba el descriptor equivocado se quedó.

O sea: el defecto nunca fue la declaración duplicada. Era que **el procesador emitía una lista de
capabilities vacía sin condiciones para todos los Steps que procesaba**. Si algo hubiera leído
ese fichero, toda decisión de admisión de capabilities habría sido errónea, en silencio.

### Lo que se retira

El módulo `pipeline-step-sdk:processor`, su `ksp(...)`, la declaración del plugin KSP en
`settings.gradle.kts`, las anotaciones `@Step` y `@JenkinsSurface`, y sus usos en `StepExecutors`.

`CompatibilityLevel` sobrevive: es un hecho sobre el ecosistema Jenkins, no sobre ningún Step, y
lo fija `CompatibilityLevelEnumTest`. La Jenkins surface de esos cuatro Steps **no se pierde**,
porque nunca se entregó: vivía en un fichero que nadie leía mientras el `StepDescriptor` real la
tenía a `""`. Recuperarla es trabajo de BLOCK 1-I/6 sobre la autoridad que sí se usa.

### La ley que sustituye a la anterior

`StepDescriptorGeneratorNoNameSemanticsFitnessTest` prohibía un `when` por nombre dentro de un
fichero que ya no existe — una prohibición satisfecha por un escaneo vacío, el verde más vacío que
hay. Se reemplaza por `NoSecondStepMetadataAuthorityFitnessTest`, que afirma sobre **ausencia**:

1. ninguna fuente de producción sintetiza una lista de `StepDescriptor`;
2. ningún build aplica un processor KSP para metadata de Step;
3. ninguna fuente de producción declara un Step por anotación;
4. y la mitad positiva: `core.echo` **sigue** declarando `EVENT_SINK_CAPABILITY`.

La primera fila es de no-vacuidad: sin ella, «no existe» lo satisface un árbol que nadie ha
mirado.

### No-vacuidad, y una lección sobre lo que enseña una mutación inaplicable

Reintroducir `@Step(` en `StepExecutors.kt` **no llegó a ejecutarse la fila**: el árbol no compiló,
porque la anotación ya no existe. Eso es una garantía más fuerte que un escaneo de fuentes — la
reintroducción es imposible por el compilador — y la fila queda como segunda línea de defensa para
el caso en que alguien reinstale la anotación *y* la use.

La lección general: cuando la mutación de una ley no puede aplicarse porque el tipo desapareció, eso
no es un fallo del experimento. Es un dato sobre la fuerza de la ley, y se registra como tal en
lugar de forzar una versión débil de la prueba.

### BCV: aquí NO procede la excepción, y el fitness tenía razón

`apiCheck` de `:pipeline-step-sdk:api` sí falló, con la ruptura medida: `Step` y `JenkinsSurface`
desaparecen del ABI, 15 líneas del dump. La primera reacción fue registrar una excepción BCV
como se hizo en 1-F con `pipeline-domain` y `pipeline-events`.

**Esa reacción era incorrecta, y el fitness la detectó.** Al añadir la entrada,
`P3EPublishedContractMaturityFitnessTest` falló con dos filas:

```text
excepciones que nombran modulos no publicados: [pipeline-step-sdk:api]
modulos clasificados que ya no se publican: [pipeline-step-sdk:api]
```

Es decir: una excepción sobre algo que no se publica es ruido que esconde las que sí importan.

#### La medición que decide, y que yo no había hecho

La pregunta que faltaba era **quién consume esto**. Medida, no asumida:

| | Evidencia medida |
|---|---|
| ¿Se publica como artefacto? | **No.** `pipeline-step-sdk/api/build.gradle.kts` no aplica `maven-publish` ni declara publicación `sdk`. No resuelve como coordenada Maven. |
| ¿Lo consume el plugin externo? | **No.** `examples/example-uppercase-plugin` compila contra `pipeline-domain`, `pipeline-scripting-api` y `pipeline-events`. Cero coincidencias de `pipeline.v2.sdk` en todo `examples/`. |

Y el precedente ya estaba escrito en el propio build: `pipeline-credentials-api` estaba en
`bcvModules` y **no** en `publishedContractModules`, sin excepción alguna.

#### Dos listas, dos preguntas

```text
bcvModules                -> que ABI interno quiero ver cuando cambia
publishedContractModules  -> que artefacto resuelve un consumidor externo
```

La primera es una petición de revisión. La segunda es una promesa a un consumidor. Un módulo BCV
puro no tiene programa de consumidores, así que no se clasifica ni recibe recibo de excepción: su
ruptura no puede ser silenciosa, porque sus llamadores están **dentro de este build** y un tipo
público eliminado revienta `compileKotlin` de inmediato. Lo que aporta el dump es el diff —
que alguien tiene que mirar —, no la detección.

Añadirlo a `publishedContractModules` habría sido la salida fácil, y habría sido una mentira por
tres vías: el módulo no se publica, `PublishedContractBoundaryFitnessTest` fija las mismas cuatro
entradas y falla ante la deriva, y no se cumple ninguna de las tres condiciones que el propio
comentario del build exige para entrar en esa lista.

Así que **la edición de gobernanza se revierte**. El registro de la ruptura es el diff del dump
commiteado, que es lo que corresponde a un módulo sin consumidores.

#### El comentario que mentía, y era el mismo defecto otra vez

Al medir salió que `build.gradle.kts` describía el módulo como «SDK contract for external plugin
authors». Falso por las dos filas de la tabla de arriba. Es **el mismo defecto que pagaríamos con
el KSP**: una afirmación escrita que ningún dato sostiene, apuntando al futuro lector a un programa
de consumidores que no existe. Y el mismo comentario contaba la lista como de cuatro módulos
cuando llevaba seis desde BLOCK 2.

Corregido, con la medición y su consecuencia al lado para que la próxima vez no haya que
redescubrirla.

### Evidencia ejecutada sobre el SHA

```text
cd v2 && PIPELINEK_SPIKE_HOME=/var/home/rubentxu/.local/state/pipelinek-bundles/e4c-4700f23d \
  ./gradlew -p . --no-daemon --offline check --rerun-tasks
```

```text
BUILD SUCCESSFUL in 32m 41s
318 actionable tasks: 318 executed
```

**773 clases · 5136 tests · 0 fallos · 0 errores · 140 skips**, en 19 módulos. Recuento leído de
los XML de `test-results` acotados por `mtime` desde el arranque de esta corrida, no por un total
acumulado del árbol.

Se corrió **dos veces** y esto merece registrarse. La primera, sin `--rerun-tasks`, dio verde en
32m 1s con el desglose `318 actionable tasks: 93 executed, 2 from cache, 223 up-to-date` y 505
clases en la ventana. Era verde, pero era **verde por omisión**: 223 tareas no se ejecutaron, y los
recibos de 1-F y 1-G registran 773 y 774 clases. Cerrar 1-H con 505 sería publicar una evidencia
más débil que la de los bloques anteriores sin decirlo, así que se repitió con el gate que fija la
política. La segunda corrida es la que cierra el bloque: `318 executed`, cero `up-to-date`.

Los tres fitness de gobernanza, uno a uno:

```text
P3EPublishedContractMaturityFitnessTest   16 tests  0 fallos  0 errores
NoSecondStepMetadataAuthorityFitnessTest   5 tests  0 fallos  0 errores
PublishedContractBoundaryFitnessTest        6 tests  0 fallos  0 errores
```

Las dos filas que fallaban —«excepciones que nombran modulos no publicados» y «modulos
clasificados que ya no se publican»— están verdes por la vía que corresponde: **el módulo no se
declara publicado**, en vez de declarar la publicación para que la excepción dejara de doler.

### Lo que este bloque NO hace

Se declara aquí para que no se lea como hecho:

Se declara aquí para que no se lea como hecho:

- **La identidad por bytes no está implementada.** `MeasuredArtifactIdentity` separa lo
  declarado de lo medido y `verdict` distingue `Unverified` de `Verified`, pero **nadie mide
  todavía**: S6 no tiene noción de procedencia de artefacto, así que `measuredDigest` es hoy
  siempre `null` y el veredicto es siempre `Unverified`. La comparación real es la condición
  de apertura de EVO-M3b. El tipo existe para que ese verificador encaje sin cambiar la
  firma de `admit`.
- **Los cuatro plugins oficiales emiten y son admitibles.** El ejemplo externo de
  `BLOCK 1-I` todavía no lo hace, porque todavía no existe.
- **El cross-check cubre Steps, Directives y Events, pero sólo los Steps tienen productor real
  hoy.** La dirección inversa para Directives y Events se comprueba con conjuntos vacíos,
  porque ningún plugin oficial los contribuye todavía; el caso con DirectoryKey y EventKind
  reales llega con el plugin externo de BLOCK 1-I.
- Sin Reactores: ADR-0104 sigue `DEFERRED` y esta release no abre esa puerta.

## ADR-EVO-003 y su estado

`ADR-EVO-003-static-manifest-before-classloading.md` está en **Proposed**. Este bloque
implementa su mitad estructural — el orden: leer, validar, admitir, y solo entonces cargar
código — y **no** su mitad de identidad por bytes, que el propio ADR exige y que S6 no puede
sostener. Esa frontera queda nombrada en el KDoc de `MeasuredArtifactIdentity` para que nadie
la lea como cerrada.
---

## BLOCK 1-I — un solo JAR externo con las cuatro familias, y el cross-check que era verde sin mirar nada

### El cross-check de Directives y Events no se ejecutaba

`PluginAdmissionGate.admitThenLoad` recogía **sólo** definiciones de Step y pasaba el resto a
`admitContributions` con sus defaults. `directiveKeys` y `eventKinds` llegaban siempre como
`emptySet()`.

La consecuencia medida, no la temida: `missingDirectives` y `missingEvents` eran exactamente lo
declarado, así que **un plugin que declarase una Directive o un Event no podía ser admitido**,
por fiel que fuera su implementación.

Y no lo detectó nadie porque nadie lo ejercitó. Los cuatro plugins oficiales no declaran ninguna de
las dos familias, así que `emptySet() - emptySet()` es vacío y la fila pasaba. Es el mismo defecto
de forma que la prohibición sobre un fichero inexistente en BLOCK 1-H: **un verde producido por la
ausencia del sujeto no es un verde, es la ausencia de una medición.**

### El sujeto: por fin un artefacto con Directive y Event

El plugin externo `example.uppercase` tenía Step y Event, pero **no tenía manifest**. No era un
plugin más pequeño: era un plugin **no admitido**, porque `PluginAdmissionGate` rechaza cualquier
contribuidor cuyo artefacto carezca de `META-INF/pipelinek/plugin-manifest.json`. Funcionaba
porque `PluginComposition.resolve` descubre por una ruta que no consulta la puerta. Dos rutas, una
guardada. Nombrarlo importa más que el código de abajo.

### Lo que aporta el JAR único

| | qué | por qué existe |
|---|---|---|
| Step | `example.uppercase` | pide **nada** al host y corre igual: la referencia de no-privilegio |
| Step | `example.uppercase.observed` | pide **exactamente un** seam ajeno y recibe sólo ese |
| Step | `example.uppercase.cased` | pide un seam que **este mismo JAR aporta** |
| Directive | `example.uppercase.casedOn` | `Evaluate`, para que lo interprete el host y no el plugin |
| Event | `example.uppercase.applied` | observación propia, ya existente |
| Capability | `example.uppercase.case-table` | tabla de mayúsculas que el plugin suministra |

El tercer Step es **nuevo y separado** a propósito: añadir la dependencia a cualquiera de los dos
primeros no habría ampliado la demostración, la habría borrado. Los dos son referencias vivas, y una
referencia que ha ganado una dependencia ya no es una referencia.

La capability no se cuelga de ningún Step existente porque la ley vigente —el set de capabilities
declarado debe **igualar** lo que exigen los contratos— sólo admite una capability que alguien
consume. `http` funciona exactamente así.

### La cuarta familia NO se comprueba en la puerta, y queda registrado como hueco medido

Este es el punto donde **cambié de opinión midiendo**, y la primera versión de esta sección afirmaba
lo contrario.

Escribí una fila, `unbackedTopLevelCapabilityIsRefused`, que añade al manifest una capability que
ningún Step exige. Salió **verde donde debía salir roja**: el mutante fue **ADMITIDO**.

Razón: la puerta nunca miró el set de capabilities. Sólo lo hacía `PluginManifestValidator`, que es
una comprobación **opcional** a la que cada plugin decide si llamar — los cuatro oficiales la llaman,
el externo no, y nadie falló. **Una familia cuyo cross-check es optativo no está cross-checkeada.**

Lo cerré, y el gate pasó a comparar el set de capabilities con una función pura compartida
(`capabilityDiscrepancy`). Entonces el gate completo se puso rojo en una fila **preexistente**:

```text
PluginAdmissionPreLoadOrderingTest :: NON-VACUITY: admitted -> the plugin is initialised
  top-level capabilities no Step contract requires: [test.sentinel]
```

Investigué, y el resultado invierte la decisión:

- `SentinelPluginArtifact` es una **clase marcador**: compila un `static {}` que escribe un fichero
  y no implementa ningún SPI. Su manifest declara `test.sentinel` **sólo** para que
  `PluginManifest` pase su `require(!contributions.isEmpty)`. No aporta nada.
- Su loader tiene **padre platform**, así que su clase no puede implementar interfaces de
  PipelineK, y en Java no puede construirse un `StepContract` porque `PluginStepId`, `StepCapability`
  y `EncodedStepValue` son `@JvmInline` (su constructor es `constructor-impl`, no válido en Java).

O sea: **ese fixture no puede ser un plugin lícito**, con esta arquitectura. Forzar la fila
requería rediseñar el sentinel, que es trabajo sobre otro sujeto y otro bloque.

La alternativa era dejar el cross-check de capabilities en la puerta **fuera del alcance de 1-I**,
que es lo que se ha hecho. El motivo es de alcance, no de comodidad:

- El mandato de 1-I es el cross-check de **Directives y Events**, que es el defecto medido de la
  puerta. Lo de capabilities no lo era.
- Forzarlo arrastraba un conflicto preexistente del sentinel que este bloque no puede resolver sin
  inventarse un redesign.
- Y encima **no hacía falta**: con el cross-check de capabilities fuera de la puerta, queda un único
  sitio que lo calcula (`PluginManifestValidator`), así que no se crea duplicación — que era el
  riesgo real de tocar las dos capas.

Consecuencia aceptada, y por eso se revierte también la ruptura de ABI de `pipeline-domain`: el
commit queda **sin excepción BCV que registrar** y `PluginManifestValidator` intacto.

**Lo que queda abierto, dicho sin adornos:** un plugin puede declarar una capability que ningún Step
exige y ser admitido, porque la comprobación es optativa por plugin. Quien lo tome debe decidir
primero **qué capa es la dueña** de esa comparación —la puerta o el validador— y arreglar después
`SentinelPluginArtifact`. El hueco está medido, no supuesto: la fila
`unbackedTopLevelCapabilityIsCurrentlyAdmitted` locharacteriza **a propósito**, y su mensaje de
fallo anuncia el arreglo el día que la puerta lo asuma, para que no aparezca más tarde como una
ruptura inexplicada.

### Una fila preexistente que afirmaba inventario en vez de descubrimiento

`DirectivePluginContractSuiteTest.registryFrom` afirmaba
`assertEquals(listOf("example.lock.LockContributor"), contributed)`. Es una afirmación de
**inventario** vestida de afirmación de **descubrimiento**: decía «el contributor de lock se
encuentra» por medio de «lock es el único contributor que existe».

Dar una directive al plugin uppercase —justo el tipo de cambio que la suite debe poder absorber— la
tumbó por el contributor añadido y no por el que nombra. La transición a pertenencia está escrita
en el propio mensaje de aserción, nunca como reescritura silenciosa de lo esperado.


### No-vacuidad por mutación, atribuida fila a fila

Revertir la puerta a `admitContributions(definitions = definitions)` tumba **exactamente 3 de 7**:

```text
CONTROL: the unmutated four-family plugin is admitted                    FAILED
a Directive the plugin contributes and the manifest does NOT declare     FAILED
an Event the plugin contributes and the manifest does NOT declare        FAILED
7 tests completed, 3 failed
```

Las otras cuatro siguen verdes **a propósito**: las dos de declarado-pero-ausente se rechazan
también con el conjunto vacío, la de los descriptores es independiente, y la de la capability la
cierra otra parte del arreglo. Una mutación que tumba de más no prueba el arreglo; prueba que la
prueba era frágil. Restaurado y verificado por hash:

```text
sha antes de la mutación : 797aa181eae17eaa7dc0d9712256546831e78424e51d6e06d12765e7fa47b388
sha tras restaurar       : 797aa181eae17eaa7dc0d9712256546831e78424e51d6e06d12765e7fa47b388
```

### Dos fallos reales que encontró el build, no la lectura

1. **`NoClassDefFoundError: PluginManifestCodec`** en el emisor de build. Todas las dependencias del
   plugin son `compileOnly` por diseño —el host las aporta—, así que `runtimeClasspath` está vacío
   para el `main()`. Resuelto con una configuración propia para el emisor. La alternativa, ampliar
   el runtime del plugin para que una tarea de build funcionara, habría metido los contratos de
   PipelineK **dentro** del artefacto cuya propiedad definitoria es llevar sólo sus propias clases.
2. **`Type mismatch: inferred type is List<Any!> but FileCollection! was expected`**: mezclar un
   `FileCollection` con un `NamedDomainObjectProvider` resuelve a `List` en tiempo de compilación del
   script. Compuesto con `files(...)`.

### El coste, dicho en voz alta

`buildExamplePlugin` pasa a ser dependencia de `:pipeline-application:test`. Eso significa que cada
`check` publica a `sdk-repo` y bifurca un segundo Gradle contra el árbol de trabajo — exactamente lo
que los otros tres builds externos evitaban manteniendo fuera de `check`. Se paga porque los otros
producen artefactos que nada bajo `check` inspecciona, y éste produce el artefacto que decide si
BLOCK 1-I es real. Un test cuyo sujeto es opcional es un test que pasa por omisión.

### Un KDoc obsoleto que sobrevivió a 1-F

`StepDefinitionContributor` —el SPI que lee **primero** un autor de plugin externo— seguía
documentando `[StepRegistry.register]`, un método que BLOCK 1-F borró al hacer el registry
inmutable. Sobrevivió porque **ningún test compila una frase**. Anotado en el propio KDoc para que
el coste de esa verdad sea visible la próxima vez.

### Evidencia ejecutada sobre el SHA

```text
cd v2 && PIPELINEK_SPIKE_HOME=/var/home/rubentxu/.local/state/pipelinek-bundles/e4c-4700f23d \
  ./gradlew -p . --no-daemon --offline check --rerun-tasks
```

```text
BUILD SUCCESSFUL in 29m 16s
318 actionable tasks: 318 executed
```

**774 clases · 5143 tests · 0 fallos · 0 errores · 140 skips**. Recuento leído de los XML de
`test-results` acotados por `mtime` desde el arranque, no acumulado del árbol.

Obsérvese que este bloque **construye** el plugin externo como parte de `check`, y que la corrida
no puede verse verde sin él: `ExternalPluginFourFamilyAdmissionTest` falla con
`no built example-uppercase-plugin JAR` si el artefacto no está. Eso es lo contrario de un test que
pasa por omisión.

Las tres clases que este bloque toca, una a una:

```text
ExternalPluginFourFamilyAdmissionTest   7 tests  0 fallos  0 errores
DirectivePluginContractSuiteTest        8 tests  0 fallos  0 errores
PluginAdmissionPreLoadOrderingTest      5 tests  0 fallos  0 errores
```

Y una confirmación de que el seam funciona de verdad, salida del propio log del gate:

```text
Discovered external directive plugins: example.uppercase.UppercaseDirectiveContributor, example.lock.LockContributor
Discovered external event definitions: example.uppercase.applied
```

### Lo que este bloque NO demuestra

- **No** es el no-core-change proof: eso es BLOCK 1-J, y aquí el core cambió (la puerta recogió tres
  familias y comparó la cuarta). Lo que este bloque prueba es que **el seam ya existía** y que el
  plugin lo ejercita entero.
- **No** hay prueba sobre la distribución instalada: eso es BLOCK 2.
- **No** toca identidad por bytes. `trust` sigue `unverified` y el digest sigue declarado, no medido
  en runtime. Es la misma frontera de ADR-EVO-003 que 1-H dejó nombrada.

---

## BLOCK 1-J — un constructo nuevo, cero cambios en el core, y la ley que lo impide volver atrás

### Lo que este bloque demuestra y lo que no

`Block1AuthorityClosureTest` ya fija «ningún dispatcher ramifica sobre una StepKey concrete». Eso es
una ley **negativa**: prohíbe una forma. No demuestra que la costura sea **suficiente**, que es la
afirmación que importa — «un plugin nuevo no necesita tocar el core».

Un escaneo sólo puede mostrar la ausencia de algo. La suficiencia se demuestra **haciendo**, y lo
hecho aquí fue añadir un Step nuevo a un plugin externo y medir el diff.

### El experimento

`example.uppercase.announcedCased` — un Step que depende de **dos** capabilities a la vez, de dueños
distintos:

```text
example.uppercase.case-table   lo SUMISTRE este mismo plugin
plugin.event-emission          sólo lo tiene el HOST
```

No añadí un cuarto Step simple porque eso ya estaba cubierto tres veces. Este es el que de verdad
estresa la costura: si el camino de composición fuera de dueño único, este Step es el que falla.

Medición del diff, por separado y sin mezclar:

```text
git diff --stat -- 'examples/**'
  UppercasePluginDeclaration.kt   | 4 ++++
  UppercaseStepDefinition.kt      | 1 +
  2 files changed, 5 insertions(+)

git diff --name-only -- 'v2/**/src/main/**'
  v2/pipeline-domain/.../StepRegistration.kt
```

La lectura correcta de esas dos líneas, que es donde es fácil engañarse:

- **El Step nuevo toca sólo `examples/`. Cero cambios en producción del core.** Eso es la prueba.
- El único fichero de producción del core que mueve 1-J es `StepRegistration.kt`, y **no** es por el
  Step: es el arreglo del hallazgo de abajo.

Un pathspec mal escrito (`v2/*/src/main`) devolvió vacío y casi reporto un diff limpio que no lo
era. El hallazgo salía en `git status` a la vista. Medido dos veces, no una.

### El hallazgo: el core conocía el espacio de nombres de un plugin

```kotlin
// antes, en producción del core:
val families: Set<PluginFamily> = if (key.value.startsWith("scm-git.")) {
    setOf(PluginFamily.SCM)
} else {
    setOf(PluginFamily.UTILITIES)
}
```

Un plugin escribía su nombre en el core que debe no conocer ninguno, y el propio KDoc del fichero
decía «this is metadata, not a verdict; the registry never branches on it» — mientras la calculaba
ramificando sobre él.

Medí quién lee `families` antes de tocarlo: los únicos lectores en producción son las dos
validaciones de no-vacío y el codec que serializa las familias que declara **el propio plugin**. En
el camino legacy no lo lee nadie. La rama compraba un nombre y no devolvía comportamiento.

**Eliminado.** `legacy()` reporta `UTILITIES` para toda registration legacy, y queda escrito en el
KDoc qué cambia exactamente y por qué, en vez de fingir que no cambia nada.

### La ley, auto-mantenida

`CoreKnowsNoExternalPluginNamespaceFitnessTest` forbid que producción del core nombre un namespace de
plugin. Dos decisiones que importan:

- **Los namespaces prohibidos se DERIVAN**, no se listan: se leen de los `PluginStepId("…")` que los
  plugins declaran de verdad y se reducen a su primer segmento. Una lista fija se queda obsoleta
  justo cuando hace falta, que es cuando aparece un plugin nuevo. Una ley que hay que actualizar
  cuando cambia aquello que vigila es más débil que una que lee aquello.
- **El punto es obligatorio.** `NetworkEgress` compara con el literal `"http"`, que es un esquema de
  URL, y el namespace del plugin `http` se escribe igual. Sin el punto, la ley tumbaría código
  correcto. Medido: sin el punto, 2 falsos positivos; con él, 0.

Y `pipeline-step-sdk` queda **fuera** de «core»: esos cuatro módulos SON plugins, y un plugin que
se nombra a sí mismo es el caso normal. Mi primera versión metió también `examples/` y la ley
falló contra quince autorreferencias honestas — es lo que se ve una ley demasiado ancha desde
dentro: encuentra cadenas reales y sigue contestando la pregunta equivocada.

Tres filas, y dos de ellas existen para que la tercera no sea decorativa:

```text
the plugin namespace set is derived and not empty          (el derivador funciona)
no core production source names an external plugin ...     (la ley)
the namespace detector actually fires on a plugin-shaped   (el detector no se ha roto)
```

### No-vacuidad por mutación

Reintroducir la rama `scm-git.` tumba **exactamente 1 de 3** — la fila de escaneo. Las otras dos no
dependen del arreglo y deben seguir verdes; si se hubieran puesto rojas, la mutación habría probado
que las filas estaban mal escritas en vez de que la ley muerde.

```text
CoreKnowsNoExternalPluginNamespaceFitnessTest > no core production source names an external plugin namespace in code() FAILED
3 tests completed, 1 failed
```

Restaurado con hash verificado antes y después:

```text
sha antes de la mutación : 07e711a726a8c1c0617a791c25de57db243169cacf481d34d1859a62f82a1e6d
sha tras restaurar       : 07e711a726a8c1c0617a791c25de57db243169cacf481d34d1859a62f82a1e6d
```

### Dos fallos míos que el build y el test atraparon

1. **`Syntax error: Unclosed comment`**: el KDoc del Step nuevo decía `v2/*/src/main`, y ese `*/`
   **cerraba el bloque de comentario por dentro**. El resto del fichero se lexía como código. Es un
   recordatorio de por qué un fallo de compilación no es un RED ni un hueco.
2. **Una aserción escrita contra una frase que inventé**: pedía «contributed Steps absent from the
   manifest» y el texto real es «implemented Steps absent from the manifest». La fila pasó el mutante
   correcto y falló en la palabra, que es la forma más fácil de tener un test que no prueba lo que
   dice.

### Evidencia ejecutada sobre el SHA

```text
cd v2 && PIPELINEK_SPIKE_HOME=/var/home/rubentxu/.local/state/pipelinek-bundles/e4c-4700f23d \
  ./gradlew -p . --no-daemon --offline check --rerun-tasks
```

```text
BUILD SUCCESSFUL in 29m 5s
318 actionable tasks: 318 executed
```

**775 clases · 5148 tests · 0 fallos · 0 errores · 140 skips**, contados desde los XML de
`test-results` acotados por `mtime` de esta corrida.

Las dos clases que este bloque introduce o amplía:

```text
CoreKnowsNoExternalPluginNamespaceFitnessTest   3 tests  0 fallos  0 errores
ExternalPluginFourFamilyAdmissionTest           9 tests  0 fallos  0 errores
```

Sin ruptura de ABI y sin excepción BCV que registrar: `StepRegistration.legacy()` cambió su
cuerpo, no su firma.

### Lo que este bloque NO demuestra

- **No** es una prueba sobre la distribución instalada. Es BLOCK 2.
- **No** toca identidad por bytes: `trust` sigue `unverified`, con la misma frontera de ADR-EVO-003.
- **No** cierra el hueco de capabilities que 1-I dejó caracterizado. Esa decisión —qué capa es la
  dueña de esa comparación, y si cerrarla exige rediseñar `SentinelPluginArtifact`— sigue abierta y
  no se ha tomado aquí.

---

## BLOCK 2 — el product proof encontró que la cadena de admisión es INERTE en producto

### Lo que se midió, no lo que se leyó

Empiezo por la comprobación de la evidencia que ya parecía establecida, porque este bloque
existe para comprobarla.

El bundle `PIPELINEK_SPIKE_HOME` que ha acompañado cada gate es del SHA `4700f23d`, **anterior a
todo S6**. Un recibo de otro SHA no hace verde éste, así que la prueba de producto tiene que correr
contra una distribución construida desde el árbol actual. `installDist` corre dentro del gate
(`pipeline-application/build.gradle.kts:112`), y el binario resultante se reconstruyó a las
`2026-10-07T07:18:12Z`, posterior al arranque del gate de 1-J. `pipeline version` reporta `0.47.0`.

Y una observación que se lleva por delante porque no esmia:

> **`PIPELINEK_SPIKE_HOME` no lo lee nadie.** Ni una línea de código, ni un build, ni un test. Aparece
> únicamente en los recibos, incluido el mío, donde lo he copiado como parte del argv del gate durante
> toda la sesión. Es una decoración que se ha presentado como parte de la evidencia y no lo es. El
> mecanismo real es que `installDist` corre dentro del gate — cosa que el recibo de S5.4 ya decía
> explícitamente y que aquí no se había propagado. **No es un defecto de producto: es un defecto de
> mis recibos, y queda corregido a partir de este bloque.**

### El hallazgo

`PluginAdmissionGate` **no tiene un solo llamador en producción**. Los ocho que tiene están en
`src/test`. `PluginManifestResourceReader` y `PluginAdmission.admit` sólo son alcanzables desde dentro
de la propia puerta, que producción nunca invoca.

Es decir: **todo lo que S6/B, S6/C, S6/D y S6/E construyeron está verificado y es código muerto en la
superficie de producto.** La puerta existe, es fail-closed, y ninguna orden del binario instalado la
cruza.

Comprobado de las dos maneras, sobre la distribución instalada y no en un test:

```text
P1  plugin SIN manifest en META-INF/pipelinek/
    -> EXIT=0, "Pipeline finished with SUCCESS"
    -> "Discovered external Step plugins: scm-git, http, junit, utilities, example.uppercase"

P2  plugin CON manifest que declara example.uppercase.ghost, Step que NO implementa
    -> EXIT=0, "Pipeline finished with SUCCESS"
    -> ninguna mencion a la direccion del desajuste
```

P1 es el caso que `PluginAdmissionPreLoadOrderingTest.artifactWithoutManifestIsRefused` afirma que se
rechaza. El producto lo ejecuta.

### Radio de impacto: cero

Medido antes de decidir nada, porque cambiar esto cambia lo que hace `pipelinek run` con cualquier
plugin:

| lugar | qué hace | ¿rompe al cablear? |
|---|---|---|
| `P3DPluginEventInstalledDistributionUatTest` filas 1 y 3 | ejecuta el binario con `--plugin-jar` | **No.** Usan el JAR real, bien formado, que pasa la admisión. |
| `MainCliParsingTest` | parsea `--plugin-jar` dos veces | No. No ejecuta nada. |

No hay ningún test que dependa del hueco. Lo único que lo cambiaría es una ejecución de producto con
un plugin mal formado, y eso es exactamente lo que la admisión debe rechazar.

### La decisión que NO se toma aquí

Cablear la puerta tiene una consecuencia semántica que no es mia para elegir en un bloque de prueba:

`ServiceLoader.load(...)` **instancia** los proveedores al iterarlos. Eso dispara carga de clases e
inicialización estática — exactamente lo que ADR-EVO-003 ordena que ocurra **después** de admitir. Un
cableado ingenuo (`resolve` admitiendo mientras descubre) sería un verde de producto que rompe el
orden que el propio ADR fija, y sería peor que el hueco actual porque se leería como conforme.

Las dos salidas reales:

1. **Descubrimiento en dos pasadas.** Pasada 1: leer los `META-INF/services/*` como TEXTO, resolver
   cada artefacto, leer su manifest y admitirlo — sin cargar ninguna clase de plugin. Pasada 2:
   componer ServiceLoader sobre un classloader que contenga **sólo** artefactos admitidos. Es la
   arquitectura que ADR-EVO-003 describe, y es trabajo de verdad.
2. **Admitir después de instanciar.** Más pequeño, y viola el orden del ADR.

Corresponde al bloque que cablee la puerta, no a este. Queda medido, con radio de impacto y con la
fricción de diseño nombrada.

---

## S6-PRE — integridad del harness de subprocesos

Bloque insertado entre BLOCK 2 y el cableado de la admision, porque el gate completo no era una fuente
de evidencia creible mientras existiera. No es una feature nueva: es la obra que hace fiable el
instrumento con el que se certificara el SDK.

### S6-PRE.0 — lo que el gate fallo de verdad

`cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks`

| | |
|---|---|
| Resultado | `BUILD FAILED in 41m 44s` |
| Tareas | `307 actionable tasks: 307 executed` (cero `up-to-date`) |
| Recuento (XML acotado por `mtime` del arranque) | 672 ficheros · **4618 tests** · **3 fallos** · 0 errores · 130 skips |
| Fallos | `S54ExternalVerticalRestartUatTest` parte 2 · `UatCompat001CorpusSmokeRunTest` · `CompatibilityCorpusTest` fixture11 y fixture12 |

**La primera hipotesis era falsa y se descarto por medicion.** No era saturacion de maquina:

- CPU **87% ociosa**, 46 GiB disponibles, PSI de CPU y de memoria a cero, `wa` 1-2%.
- **30 de 31 fixtures tardaban 5,0-7,1 s.**
- El segundo barrido de `UatCompat001CorpusSmokeRunTest` corre **los mismos 31 fixtures** y pasa en
  **178,8 s**.
- Aislado, `CompatibilityCorpusTest` pasa entero: 30 tests, 0 fallos, `fixture11` en 10,1 s.

Subir presupuestos habria sido fabricar un verde por tolerancia.

### S6-PRE.1 — la causa, por volcado de hilos

Proceso capturado en vivo (471 s de vida cuando lo normal son 5,5 s) y volcado con
`jcmd <pid> Thread.print -l`. Evidencia: `.agent/s6pre-fixture12-thread-dump.txt`.

```text
"main" #3 ... runnable
    at java.io.FileOutputStream.writeBytes(java.base@24.0.2/Native Method)
    at java.lang.System$Out.write(java.base@24.0.2/System.java:1888)
    ...
    at dev.rubentxu.pipeline.v2.application.MainKt.main(Main.kt:431)
```

Proceso: `--isolated compatibility/12-error-handling.pipeline.kts`. Y `Main.kt:431` es:

```kotlin
println(JsonEventLog.encode(events))
```

La cadena completa:

1. el harness hace `waitFor()` **antes** de drenar;
2. `fixture12-error-handling` emite mas de 64 KiB de eventos por esa unica llamada;
3. el pipe se llena y `main` se bloquea en `writeBytes`: el proceso nunca sale;
4. `waitFor()` no retorna; `@Timeout` corta a los 600 s;
5. el hijo sobrevive, porque `@Timeout` no posee el proceso.

### S6-PRE.2 — clasificacion

| | |
|---|---|
| `PIPE_BACKPRESSURE_DEFECT` | **CONFIRMED**, y es LA causa de este cuelgue |
| `ORPHAN_PROCESS_DEFECT` | **CONFIRMED** — `@Timeout` aborta el hilo, no mata al hijo |
| `FUTEX_HANG_CAUSE` | **RESOLVED** = backpressure en `Main.kt:431` |
| Lifecycle del CLI | **LIMPIO** |

Sobre el lifecycle: el dump **no** muestra executor, dispatcher de coroutines ni hilo non-daemon
propio. Solo `main` mas infraestructura de la JVM (hilos G1, Service Thread, compiladores C1/C2).
La JVM sobrevive unicamente porque `main` no puede terminar de escribir, luego el producto queda
limpio de sospecha en este cuelgue.

`wchan` habia dado `futex_do_wait`, que fue un artefacto de muestreo; el volcado es la fuente
autoritativa y apunta a `writeBytes`.

### S6-PRE.3-6 — la primitiva y la migracion

`CliRun.kt` introduce `OwnedSubprocess` como **la unica** via de lanzar la distribucion instalada:

- resultado tipado `Completed` / `TimedOut` / `LaunchFailed`, nunca un `Boolean`;
- drenaje concurrente de **ambos** pipes desde el arranque del hijo;
- deadline propio del proceso, distinto del `@Timeout` de clase;
- `reap` de descendientes en `finally`, con verificacion de muerte;
- volcado de hilos capturado **antes** del kill, que es lo unico que permite clasificar un cuelgue
  despues.

Migrados `CompatibilityCorpusTest.runFixturePass` y `runFixtureFail`, los dos que colgaban.

### S6-PRE.6b — la primera migracion no bastaba, y por que

Con el gate relanzado aparecio un hijo de **330 s** en `12-error-handling.pipeline.kts`: el deadlock
**volvia**. La causa no era que el arreglo fallara, sino que **el corpus se barre dos veces por dos
harnesses distintos**, y solo se habia migrado uno:

- `CompatibilityCorpusTest.runFixturePass` / `runFixtureFail` — migrados.
- `UatCompat001CorpusSmokeRunTest` — barre los mismos 31 fixtures y seguia con `waitFor()` antes de
  drenar.
- `UatDsl001JenkinsFamiliarityTest` — el grammar-full script emite el mismo registro completo por
  `Main.kt:431` y murio con su `@Timeout(120)` de clase.

Los tres migrados, con el mismo resultado medido:

| Harness | Antes | Ahora |
|---|---|---|
| `UatCompat001` barrido | 600,0 s (timeout) | **193,2 s** |
| `CompatibilityCorpusTest` fixture11 | 600,0 s (timeout) | **10,7 s** |
| `UatDsl001` grammar-full | 120 s (timeout) | **12,6 s** |

Un lesson que sale de ahi: **arreglar un sitio no arregla una propiedad**. El defecto era de una
clase de harness, y esa clase tenia tres miembros. Por eso la ley siguiente existe.

### S6-PRE.7 — falsaciones, y un bug encontrado por la propia ley

`OwnedSubprocessRunTest` con hijos reales (JVM del mismo JDK, sin shell): `saturate`, `hang`,
`grandchild`, y un comando que no arranca. **4/4 verdes.**

La fila de saturacion **tumbo la primera version de la primitiva**: el hijo salia con exit 0 y
`stdout` con **0 caracteres**. Causa: `return X` evalua X antes de correr el `finally`, asi que se
leian los `StringBuilder` antes de que los drenadores terminaran. El arreglo es unir los drenadores
antes de construir el resultado.

La migracion del corpus tambien fallo al principio, y por un motivo distinto: al migrar se perdio el
`.trim()` que hacia el codigo original, y `Main.kt:431` escribe con `println`. **19 de 30 fixtures
fallaron** con `stdout must end with ']'` sobre fixtures perfectamente verdes. Una migracion tiene
que preservar cada observable que el codigo viejo producia, incluido un `trim()`.

### La ley que impide la reaparicion

`InstalledDistributionHarnessFitnessTest` escanea las fuentes de test y exige que todo harness que
lanza la distribucion instalada pase por `OwnedSubprocess`.

El alcance es deliberadamente estrecho, y medirlo fue parte del trabajo: el repo tiene **196
`ProcessBuilder` en 95 ficheros**, y la mayoria lanzan `git`, `tar` o `sh` y no escriben en un pipe.
Una prohibicion global habria sido una ley sin relacion con el defecto. La propiedad que fallo es
mas estrecha: solo ese binario vuelca el registro de eventos entero en una llamada.

Los **30** deudores que quedan estan escritos uno a uno en el allowlist, y bajaron de 32 al migrar
`UatCompat001CorpusSmokeRunTest` y `UatDsl001JenkinsFamiliarityTest`. No es una concesion: es el
libro de deuda, y la asercion es que el conjunto de reincidentes nuevos este vacio y que las entradas
obsoletas **tumban** el test, para que la lista solo pueda encogerse.

No-vacuidad por mutacion: quitar `UatStep004SleepTimingTest.kt` del allowlist pone la ley ROJA
nombrando exactamente ese fichero (`BUILD FAILED`, `fallos= 1`). Restaurado y verificado por `diff`
contra la lista real: la ley y la realidad coinciden exactamente.

### S6-PRE.12 — el barrido dominado, eliminado despues del verde

La secuencia fue `RED -> arreglo del harness -> GREEN -> eliminar -> GREEN`, y el orden importa: si el
barrido se hubiera eliminado al mismo tiempo que se arreglaba el cuelgue, no habria forma de
distinguir un harness arreglado de uno silenciado.

La dominacion se verifico leyendo el codigo, no recordandolo. Los dos tests llamaban al **mismo**
`sweepFixture` sobre los mismos 31 fixtures, con el mismo staging, el mismo credential store y el
mismo binario; y el segundo comprobaba estrictamente menos:

| | test 1 (`corpus smoke-runs green`) | test 2 (`each corpus fixture ...`) |
|---|---|---|
| codigo de salida | si, por fixture | **no** |
| eventos no vacios | si, por fixture | si, por fixture |

Una corrida que hace rojo el segundo siempre hace rojo el primero, y al reves nunca. No podia fallar
que el primero hubiera pasado.

Coste medido: la clase paso de **399,6 s a 191,7 s**. Exactamente la mitad, que es lo que ocupaba el
barrido eliminado. Verificacion: **1 test · 0 fallos** en el XML fresco, y la clase compilada
expone un unico metodo de test.

Lo que **no** se elimino, y por que: `UatCompat001` en su conjunto **no** esta dominado por
`CompatibilityCorpusTest`. Barren los mismos fixtures pero con una configuracion distinta —el usa
`--isolated` para todos, mientras este reparte entre `--isolated` y `--workspace` segun
`fixturesWithIsolatedWorkspace`, y ambos inyectan el credential store—, asi que la cobertura que
aporta es real y se conserva.

### Estado verificado de este bloque

Corridas aisladas, cada una leida desde el XML de `test-results`:

| Corrida | Resultado |
|---|---|
| `OwnedSubprocessRunTest` | 4 tests · 0 fallos |
| `InstalledDistributionHarnessFitnessTest` | 1 test · 0 fallos |
| `UatCompat001CorpusSmokeRunTest` migrado | 2 tests · 0 fallos · 389 s (antes 600 s por test) |
| `UatCompat001` tras eliminar el barrido dominado | **1 test · 0 fallos · 191,7 s** (antes 399,6 s con dos barridos) |
| `UatDsl001JenkinsFamiliarityTest` migrado | 4 tests · 0 fallos · 45 s |
| `CompatibilityCorpusTest` migrado | 30 tests · 0 fallos · 181 s |
| `S54ExternalVerticalRestartUatTest` migrado | 2 tests · 0 fallos · 10,5 s |

**Gate completo del mismo arbol**, con el criterio doble de S6-PRE.10:

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks
BUILD SUCCESSFUL
318 actionable tasks: 318 executed        (cero up-to-date)
778 clases · 5155 tests · 0 fallos · 0 errores · 140 skips
procesos pipelinek supervivientes: 0
```

La aritmetica cuadra contra el ultimo gate verde de BLOCK 1-J (`2e9b4824`, 775 clases / 5148 tests /
0 fallos / 140 skips): **+3 clases y +7 tests**, exactamente las tres que introduce este bloque
(`PluginAdmissionInstalledDistributionUatTest` 2, `OwnedSubprocessRunTest` 4,
`InstalledDistributionHarnessFitnessTest` 1). Ni una clase perdida, ni un skip cambiado.

**Segundo gate**, el de S6-PRE.12 tras eliminar el barrido dominado:

```text
BUILD SUCCESSFUL
318 actionable tasks: 318 executed        (cero up-to-date)
778 clases · 5154 tests · 0 fallos · 0 errores · 140 skips
procesos pipelinek supervivientes: 0
```

**5155 → 5154**: un test menos, que es exactamente el eliminado y nada mas. Las clases siguen siendo
778 porque la clase sigue existiendo con un test menos, y los skips siguen siendo 140. Un verde
obtenido quitando lo que sobra, sobre un gate que ya era verde antes de quitarlo.

### H8-10, caracterizado y NO re-subido

`HttpInstalledUatTest > H8-10 peak memory does not scale with the size of the response()` fallo en un
gate intermedio con **ratio 0,65** frente al umbral 0,5: *"peak RSS grew by 166 MiB when the response
grew by 255 MiB"*. Aislado **pasa en 22,4 s**.

Lo que se sabe y lo que no:

- **No lo causa este bloque.** Ni el fichero ni el plugin `http` fueron tocados, y en el gate final
  pasa.
- Su propio KDoc ya documenta exactamente este modo de fallo — *"failed at 0.26 having passed at
  0.19, so it was measuring the machine, not the subscriber"* — y por eso el umbral ya se subio de
  0,25 a 0,5 **una vez**.
- Es una **asercion de tamano** sobre peak RSS, que Harness Fidelity §3 prohibe como evidencia.

**No se sube el umbral por tercera vez.** Subirlo seria fabricar un verde, y el valor ya no separa el
defecto que la fila busca de la maquina que la ejecuta. La salida que el repo ya tiene para esto es
el tag `performance` con su tarea `performanceTest`, pero **solo existe en `pipeline-credentials-api`
y `pipeline-output-store`**: aplicarla en `pipeline-application` es infraestructura nueva y queda
como decision, no como algo que se cierre dentro de un bloque de verificacion.

### Lo que queda abierto, con nombre

`UatDsl003ParallelTest` P1/P2/P3 fallaron en el run del modulo completo con
`IllegalStateException` por codigo de salida inesperado. El fixture `parallel.pipeline.kts` ejecutado
a mano con la distribucion instalada da **EXIT=0** y `Pipeline finished with SUCCESS`, luego no es un
defecto del producto; el mensaje real con la salida del CLI no se extrajo porque el run se detuvo
antes de volcar el XML. Queda **sin clasificar** y no se afirma nada sobre el.
