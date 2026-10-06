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

## Lo que este bloque NO hace

Se declara aquí para que no se lea como hecho:

- **La identidad por bytes no está implementada.** `MeasuredArtifactIdentity` separa lo
  declarado de lo medido y `verdict` distingue `Unverified` de `Verified`, pero **nadie mide
  todavía**: S6 no tiene noción de procedencia de artefacto, así que `measuredDigest` es hoy
  siempre `null` y el veredicto es siempre `Unverified`. La comparación real es la condición
  de apertura de EVO-M3b. El tipo existe para que ese verificador encaje sin cambiar la
  firma de `admit`.
- **El manifest no se escribe todavía en los plugins.** El codec y la ruta canónica existen y
  hay prueba de ida y vuelta, pero los cuatro plugins siguen construyendo su manifest en
  memoria. Cablear `RESOURCE_PATH` en el `build.gradle.kts` de cada plugin es el paso
  siguiente, y hasta entonces `admitThenLoad` no los admitirá.
- **`admitContributions` existe pero no se invoca en producción.** El cross-check está escrito
  y probado en sus piezas, pendiente de engancharse al gate.
- Sin Reactores: ADR-0104 sigue `DEFERRED` y esta release no abre esa puerta.

## ADR-EVO-003 y su estado

`ADR-EVO-003-static-manifest-before-classloading.md` está en **Proposed**. Este bloque
implementa su mitad estructural — el orden: leer, validar, admitir, y solo entonces cargar
código — y **no** su mitad de identidad por bytes, que el propio ADR exige y que S6 no puede
sostener. Esa frontera queda nombrada en el KDoc de `MeasuredArtifactIdentity` para que nadie
la lea como cerrada.