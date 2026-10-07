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

`ExternalCapabilityContributorDiscovery` **queda fuera** de este bloque y es una asimetría
medida, no supuesta: usa `ServiceLoader` sin cambiar el TCCL y se evalúa como valor por defecto
*después* de que la composición restaurase el loader. Su propia KDoc justifica que no hace
falta porque «un JAR de plugin ya está en el classpath de la distribución»
(`BundledPluginClasspathPlan`), lo cual es cierto en la distribución instalada pero **no** en el
camino `--plugin-jars`, que usa un loader acotado. Queda anotado como trabajo pendiente en vez
de darse por resuelto: moverlo exige además el `credentialProvider`, que es estado por run, y no
se ha medido todavía si esa vía está rota.

### La ley, probada por mutación

Cinco filas en `PreResolvedCompositionTest` cruzan `PluginComposition.resolve` sin sustituto
(HF1, in-process): el camino CORE-only, el diagnóstico que no miente cuando no hubo
descubrimiento, un **JAR real** con un `META-INF/services` que nombra una clase inexistente, y
el TCCL restaurado incluso cuando la composición falla.

Siete filas en `FArchPreResolvedCompositionAuthorityTest` fijan la parte estructural, que desde
dentro de un módulo no se puede observar: cada uno de los tres puntos de descubrimiento tiene
**exactamente un** llamante en producción, `CompositionRoot` no compone nada, y el parámetro
`composition` no tiene valor por defecto. La primera de esas siete es de no-vacuidad: sin ella,
«exactamente uno» lo satisface un escaneo vacío.

Mutaciones ejecutadas, con atribución 1:1:

- reintroducir un segundo `ExternalEventDefinitionDiscovery.compose()` en `Main.kt` tumba
  **exactamente 1 de 7** filas y deja las otras 6 verdes;
- quitar el `finally` que restaura el TCCL rompe la fila que lo afirma, y sólo esa.

### Lo que este bloque NO hace

No sustituye a BLOCK 2: esto es caracterización de la decisión de composición **en proceso**, no
certificación de comportamiento sobre la distribución instalada.




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