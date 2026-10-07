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
