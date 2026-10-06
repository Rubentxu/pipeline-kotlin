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

## Lo que este bloque NO hace

Se declara aquí para que no se lea como hecho:

- **La identidad por bytes no está implementada.** `MeasuredArtifactIdentity` separa lo
  declarado de lo medido y `verdict` distingue `Unverified` de `Verified`, pero **nadie mide
  todavía**: S6 no tiene noción de procedencia de artefacto, así que `measuredDigest` es hoy
  siempre `null` y el veredicto es siempre `Unverified`. La comparación real es la condición
  de apertura de EVO-M3b. El tipo existe para que ese verificador encaje sin cambiar la
  firma de `admit`.
- **Los cuatro plugins oficiales emiten y son admitibles.** El ejemplo externo de
  `BLOCK 1-I` todavía no lo hace, porque todavía no existe.
- **`admitContributions` existe pero no se invoca en producción.** El cross-check está escrito
  y probado en sus piezas, pendiente de engancharse al gate.
- Sin Reactores: ADR-0104 sigue `DEFERRED` y esta release no abre esa puerta.

## ADR-EVO-003 y su estado

`ADR-EVO-003-static-manifest-before-classloading.md` está en **Proposed**. Este bloque
implementa su mitad estructural — el orden: leer, validar, admitir, y solo entonces cargar
código — y **no** su mitad de identidad por bytes, que el propio ADR exige y que S6 no puede
sostener. Esa frontera queda nombrada en el KDoc de `MeasuredArtifactIdentity` para que nadie
la lea como cerrada.