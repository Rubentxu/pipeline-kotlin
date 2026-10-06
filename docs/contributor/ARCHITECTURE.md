# Arquitectura de PipelineK — guía para quien empieza

> **Qué es este documento y qué no es.** Es una **guía de orientación escrita para un developer que
> acaba de llegar**. No es normativa. Cuando algo de aquí contradiga a la documentación autoritativa,
> **manda la autoritativa**:
>
> | Si la duda es sobre… | La respuesta está en |
> |---|---|
> | Decisiones architecturales y *por qué* | [`docs/v2/04-adrs/`](../v2/04-adrs/) y [`docs/v2/02-architecture/`](../v2/02-architecture/) |
> | Reglas que no hay que romper | [`01-semantic-constitution.md`](../pipelinek-semantic-evolution/01-semantic-constitution.md) y [`AGENTS.md`](../../AGENTS.md) |
> | Qué está verificado y qué no | [`CERTIFICATION_PROTOCOL.md`](../v2/07-uat/CERTIFICATION_PROTOCOL.md) y los recibos de [`docs/v2/07-uat/`](../v2/07-uat/) |
> | Vocabulario oficial | [`CONTEXT.md`](../../CONTEXT.md) |
> | Cómo **usar** la herramienta | [`docs/user/`](../user/README.es.md) |
>
> Índice: [orientación](#1-orientación-en-60-segundos) · [capas](#2-las-cuatro-capas) ·
> [un run de principio a fin](#3-un-run-de-principio-a-fin) · [por qué](#4-por-qué-está-diseñado-así) ·
> [módulos](#5-los-27-módulos) · [errores comunes](#6-cinco-errores-comunes) ·
> [ejercicios](#7-ejercicios-para-comprobar-que-lo-entendiste)

---

## 1. Orientación en 60 segundos

**Analogía útil: PipelineK es una cocina profesional.**

- El **pipeline** es la receta que escribe el chef.
- El **compilador** comprueba que la receta sea coherente *antes* de encender el fuego.
- El **registro de pasos** es la carta del local: si un ingrediente no está en la carta, el ticket se
  rechaza. No se improvisa con lo que hay en la nevera.
- El **coordinador** es el jefe de cocina: dice qué se cocina y cuándo.
- El **motor de dispatch** es quien realmente cocina.
- El **journal** es el libro de incidencias: si se apaga el fogón a mitad, al volver se sabe qué se
  había hecho y se retoma en vez de empezar de cero.

Tres frases que resumen el diseño:

1. **Si hay duda, se para.** Ante un paso desconocido, un token desconocido o un schema incompatible,
   se rechaza **antes de producir cualquier efecto**. Nunca "se ignora y ya".
2. **Los eventos son semántica, no logs.** Cada paso publica hechos tipados que otros procesos pueden
   observar y sobre los que pueden reaccionar.
3. **El estado se persiste**, y por eso un run se puede reanudar.

> **Checkpoint 1.** Si con esto ya te suena coherente, puedes seguir. Si algo te chirría, vuelve después
> a la sección 4 (por qué está diseñado así), que es donde se explican las decisiones.

---

## 2. Las cuatro capas

**Analogía: mira el edificio desde fuera hacia dentro.**

```
┌─────────────────────────────────────────────────────────────┐
│  ADAPTADORES            application · events-store ·        │
│  (la machinery)         output-store · scripting-kotlin24 ·  │
│                         step-sdk:* · credentials-*           │
├─────────────────────────────────────────────────────────────┤
│  CONTRATOS              scripting-api · events · output ·    │
│  (la interfaz)          step-sdk:api · credentials-api       │
├─────────────────────────────────────────────────────────────┤
│  NÚCLEO                 pipeline-domain                      │
│  (las leyes del dominio)                                    │
└─────────────────────────────────────────────────────────────┘
```

**Las flechas apuntan hacia dentro, siempre.** Un adaptador puede depender de un contrato; un contrato
puede depender del núcleo; **el núcleo no depende de nadie**.

### 2.1 El núcleo: `pipeline-domain`

Aquí viven los conceptos que *son* el producto: un pipeline compilado, un paso, un descriptor, un
resultado de run, una política de replay.

**No puede depender de Spring, ni de la CLI, ni de SQLite.** Y no es una regla de estilo: hay un test
de arquitectura, `FArch001DomainFrameworkFreeTest` — **con un hueco que conviene conocer**: su lista es
de 8 tokens fijos (`jenkins, kubernetes, koin, docker, flyway, exposed, jooq, hikari`) y su escáner
exige un literal entre comillas, así que una dependencia escrita como `implementation(libs.sqlite.jdbc)`
**no casa y pasa el filtro**. La dirección de dependencias sí la vigila
`FArchRP030HexagonalDependencyDirectionTest`, que sí parsea los `project(...)` por módulo.

> **Pregunta que te harás: "¿por qué es tan estricto?"**
> Porque el núcleo es lo que alguien puede querer reutilizar en otro lenguaje, otro runtime o una
> herramienta distinta. Si el núcleo arrastra una librería de infraestructura, ya no se puede
> implementar el mismo contrato fuera de aquí. La separación no es purismo: es lo que permite que
> exista más de una implementación.

### 2.2 Los contratos

Un **contrato** es la promesa de "esto es lo que prometo hacer, sin decir cómo". Los más importantes:

| Contrato | Promete |
|---|---|
| `pipeline-events` | El **plano de eventos**: qué hechos existen y cómo se publican |
| `pipeline-scripting-api` | El **DSL**: qué puedes escribir dentro de `pipeline { }` |
| `pipeline-step-sdk:api` | Cómo se **declara un paso**: su contrato, sus capacidades, sus codecs |
| `pipeline-output` | El **Output Plane** en modo lectura |
| `pipeline-credentials-api` | Cómo se **usan credenciales** sin conocer su almacenamiento |

### 2.3 La regla que más sorprende: contrato publicado ≠ implementación

Mira esto, porque es el corazón de una decisión de diseño:

| Artefacto | Lleva | ¿Se publica? |
|---|---|---|
| `:pipeline-events` | El contrato del plano de eventos | **Sí** |
| `:pipeline-events-store` | Journal, cursor, lease, los stores de verdad | **No — a propósito** |
| `:pipeline-output` | El lado **lectura** del Output Plane | **Sí** |
| `:pipeline-output-store` | La escritura de segmentos | **No — a propósito** |

**¿Por qué no se publica el store?** Porque si se publicara, el contrato arrastraría SQLite y ficheros
dentro, y cualquier otra implementación del contrato tendría que hablar SQLite. Separando el
**qué** del **cómo**, otra implementación —incluso en otro lenguaje— puede cumplir el mismo contrato.

> **Checkpoint 2.** Intenta responder sin mirar: ¿qué pasaría si `pipeline-events` trajera el código
> del store dentro? ¿Qué implementación concreta queda atada a lo que el contrato mencione?

### 2.4 Un detalle que se te va a saltar

`.tool-versions` en la raíz fija `java`, `gradle` y `maven`. **asdf ya lo lee**. Si algún día
`pipelinek` se registra como herramienta, `pipelinek` aparecería ahí también. Pero hoy **no** está, y
añadir la línea sin plugin rompería a todo el mundo con `asdf`. Es el mismo principio de la capa
contratual aplicado a un fichero: no prometas algo que no puedes cumplir.

---

## 3. Un run de principio a fin

Este es el camino que recorre una ejecución. Léelo con nombres reales para que te sirva el día que
hayas que depurar algo.

### 3.1 Del fichero al IR

1. **Se compila el script.** El fichero es Kotlin real y lo compila el compilador de Kotlin real
   (`Kotlin24ScriptingHost`). Si hay un error de tipos, **no se ejecuta nada**.
2. **El cuerpo del script construye el IR del DSL** sin efectos secundarios. El DSL *describe*, no
   *hace*.
3. **Se extrae el `PipelineSpec`** por reflexión. Si el script compila pero no produce spec, el
   resultado es un fallo tipado, nunca una excepción por `null`.

### 3.2 Admisión: la puerta que casi siempre se olvida

4. **Se registra lo que se sabe hacer.** Se cargan los pasos del core y los de los plugins.
5. **Admisión *fail-closed*.** Si un paso no está en el registro, o su schema no encaja, **se rechaza
   antes de cualquier efecto**.

> **Analogía:** es el control de seguridad del aeropuerto. No es que te retengan si algo está mal; es
> que **nunca pasas el control** si algo está mal. La diferencia importa: lo que no se rechaza, se
> ejecuta.

### 3.3 La ejecución

6. **El coordinador durable** recorre los stages. Es el **único** bucle de control del run: decide,
   no ejecuta.
7. **El motor de dispatch** (`StepDispatchEngine`) ejecuta lo decidido, por cada paso:
   admisión → lectura de journal → replay → decode tipado → `StepStarted` → **efecto** →
   `StepFinished`/`StepFailed` → escritura de journal → cursor.

   Ojo al orden, porque no es el intuitivo: la admisión estructural va **antes** del journal;
   `StepStarted` se emite **antes** del efecto; y el append terminal del journal va **después** del
   efecto (`DurableStepExecutor.kt:51-52` lo documenta como ley de orden). El journal aparece dos
   veces: lectura al principio, escritura al final.
8. **El handler pide capacidades por nombre**, no "un contexto". Si necesita lanzar procesos,
   declara `SHELL_OPERATIONS_CAPABILITY`. No existe el "permiso para todo".
9. **Se emiten los eventos.** La secuencia real es más larga de lo que parece, y empieza **antes**
   del run:
   `CompilationStarted → CompilationFinished → RunStarted → (StageStarted → StepStarted →
   … → StepFinished → StageFinished)×N → RunFinished`.
   `CompilationStarted` es el **primer evento del run**, emitido antes de que exista `RunStarted`
   (`UatDsl003ParallelTest.kt:185` lo fija). En un run de `echo` también aparece `EchoOutputCaptured`
   entre `StepStarted` y `StepFinished`.

### 3.4 El resultado

10. Se clasifica el outcome y se elige el **exit code**. `UNSTABLE` existe como tercer estado: el run
    terminó, pero no del todo bien.

> **Ejercicio.** Toma un fallo real y localiza en qué de estos diez pasos estás. Casi todos los bugs
> de esta arquitectura se explican como "pasó algo que debía haberse parado en el paso 5".

---

## 4. Por qué está diseñado así

Las cuatro decisiones que más te van a costar entender si no te las cuentan.

### 4.1 Todo objeto, nada de `String` para la semántica

El proyecto ha estado **convirtiendo cadenas en tipos cerrados**. El caso reciente: `failureKind` pasó
de `String` a `FailureKind`, un tipo cerrado.

**¿Por qué importa tanto?** Porque con `String`, esto **compila**:

```kotlin
error("boom", "USR")   // nadie comprueba nada aquí
```

…y el error aparece segundos después, cuando el decoder encuentra un token fuera del vocabulario. Es
decir: el autor tomaba una decisión **creyendo que estaba aceptada**.

Con un tipo cerrado, `"USR"` **no compila**. El error se atrapa en el único sitio donde puede atraparse
sin ejecutar nada.

### 4.2 Un recibo vale para su propio SHA y no hereda nada

Si el código se movió, la evidencia caducó. Por eso hubo que **recertificar** un receipt cuyo árbol era
equivalente pero cuyo SHA ya no era alcanzable.

**Analogía:** es como una foto de la cocina. Si luego mueves una pared, la foto sigue siendo bonita
pero ya no describe esa cocina. Hay que volver a hacerla.

### 4.3 Un test que no puede fallar no es un test

Aquí es donde se invierte el orden de las cosas. No basta con que un test pase: hay que
**haberlo visto fallar**. Y se comprueba **mutando el código a propósito** para ver qué se rompe.

> **Trampa real que se ha pagado en este repo.** Un test afirmaba que ningún handler leía eventos como
> texto plano. Al mutarlo, **el escaneo pasó**. ¿Por qué? Porque el patrón de búsqueda no podía
> encontrar el defecto que decía encontrar. Un test que no puede detectar su propia mentira no es una
> red: es decoración.

### 4.4 Fallar cerrado, siempre

Ante la duda: **parar**. Es contraintuitivo para quien viene de un producto donde "ser tolerante" es
lo habitual, y aquí es al revés.

**Analogía:** un tribunal no "intenta funcionar con lo que hay". Una receta con un ingrediente desconocido se
rechaza entera. El coste es molestar al usuario; el beneficio es que nunca produce un resultado
silenciosamente incorrecto.

---

## 5. Los 27 módulos

Los tienes en [`MODULES.md`](MODULES.md), módulo por módulo, con qué responsibility tiene y con qué se
comunica. Resumen por capa:

| Capa | Módulos | Cantidad |
|---|---|---|
| Núcleo | `pipeline-domain` | 1 |
| Contratos | `scripting-api`, `events`, `output`, `step-sdk:api`, `credentials-api` | 5 |
| Adaptadores | `application`, `events-store`, `output-store`, `scripting-kotlin24`, `step-sdk` (7), `credentials` (3) | 15 |
| Calidad y soporte | `architecture-tests`, `testkit`, `event-harness`, `release`, `binding-factory`, `artefacts-local` | 6 |

> **Aviso importante, porque es una trampa de newcomer.** Hasta el **2026-10-06** esta carpeta
> contenía un `MODULES.md` y un `ARCHITECTURE.md` que describían una arquitectura **aspiracional**
> —distributed, con *workers*, *gateway*, plugin de Jenkins— que **no coincide con el build**: de los
> 14 módulos que nombraban, 9 no existen, y no mencionaban 27 que sí. **Se han borrado**, junto con
> un `C4.md` igualmente ficticio. No los busques: no están. Si los encuentras en un checkout viejo o
> en el historial de git, están describiendo algo que nunca existió.
>
> **La lista autoritativa es `v2/settings.gradle.kts:29-71`**, y ese documento es su explicación legible.

---

## 6. Cinco errores comunes

Los cinco que verás en tus primeras semanas.

| Error | Cómo se manifiesta | Qué hacer |
|---|---|---|
| **Confundir `pipelinek` (producto) con `core/`, `pipeline-cli/`** (V1) | Editas `core/` y no pasa nada | El build activo es `v2/`. La raíz sólo tiene `includeBuild("v2")`. El V1 está en disco como historia, y sus `build.gradle.kts` están **vivos pero no se compilan** |
| **`./gradlew` desde la raíz** | "no such file" | El wrapper vive en `v2/`. Usa `cd v2 && ./gradlew …` |
| **Documentarse por `README.es.md` viejo** | Aprendes una arquitectura que ya no existe | Empieza por [`docs/user/README.es.md`](../user/README.es.md) |
| **Fijarte en el `MODULES.md` antiguo de `docs/v2/02-architecture/`** | Persigues módulos inexistentes (`pipeline-worker-runtime`, `pipeline-jenkins-plugin`…) | Ya no existe: se borró el 2026-10-06. Contrasta siempre con `v2/settings.gradle.kts` |
| **Creerte un badge de CI verde** | Asumes que algo está probado | **No hay CI remota desde el 2026-09-30.** La verificación es local y manual |

---

## 7. Ejercicios para comprobar que lo entendiste

Hazlos en orden. Si alguno te cuesta, vuelve a la sección correspondiente; es normal.

**Ejercicio 1 — fail-closed.**
Escribe un pipeline que use `load("otro.pipeline.kts")`. Compila. ¿Qué crees que pasa al ejecutarlo?
Ahora compruébalo y vuelve a leer [la sección 5.2 del documento del DSL](../user/pipeline-dsl.es.md).
<details><summary>Pista</summary>
Busca `load` en la tabla de "falla cerrado".
</details>

**Ejercicio 2 — una autoridad, no dos.**
Un plugin externo declara su propio tipo de evento y lo emite en una ejecución real. ¿Cuántos ficheros
del **core** habría que tocar para que funcionase? La respuesta es cero. ¿Qué haría ese diseño
imposible?
<details><summary>Pista</summary>
Pregunta qué pasaría si el motor tuviera que hacer `when (nombreDelPaso)`.
</details>

**Ejercicio 3 — el test que no puede fallar.**
Toma un fitness test de `pipeline-architecture-tests`, muévelo a propósito y mira si se pone rojo. Si
no se pone rojo, **ese test no está probando lo que dice**. ¿Qué harías después?
<details><summary>Pista</summary>
Mira los mutadores de los recibos de docs/v2/07-uat/.
</details>

**Ejercicio 4 — dependencias.**
Quieres que `pipeline-domain` escriba un fichero de log al disco. ¿Debería poder hacerlo? Si crees que
no, ¿qué harías en su lugar?
<details><summary>Pista</summary>
Sección 2.1.
</details>

---

**Siguiente paso:** [`MODULES.md`](MODULES.md) para el mapa módulo por módulo, y después los seis
ficheros de [`Contribuir en el README](../../README.es.md#contribuir) para saber por dónde atacar.

Si algo de esta guía no cuadra con lo que ves en el código, **eso es información útil**: probablemente
o el código cambió o esta guía quedó vieja. En ambos casos, dilo en el PR — es exactamente el tipo de
divergencia que este proyecto paga caro si nadie la reporta.