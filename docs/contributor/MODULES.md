# Los 27 módulos, uno a uno

> **Antes de nada, una advertencia que te va a ahorrar tiempo.** Existe otro
> `docs/v2/02-architecture/MODULES.md` que **no coincide con el build actual**: nombra módulos que no
> existen (`pipeline-worker-runtime`, `pipeline-worker-gateway`, `pipeline-jenkins-plugin`,
> `pipeline-step-codegen`…) y no menciona 27 que sí existen. Data del **2026-08-21** y describe una
> arquitectura aspiracional, no la vigente.
>
> **La lista autoritativa es `v2/settings.gradle.kts:29-71`.** Este documento es la explicación legible
> de esa lista.

Índice: [núcleo](#1-núcleo--1-módulo) · [contratos](#2-contratos--5-módulos) ·
[adaptadores](#3-adaptadores--15-módulos) · [calidad y soporte](#4-calidad-y-soporte--6-módulos) ·
[árboles muertos](#5-árboles-que-no-compilan) · [cómo-verificar](#6-cómo-verificar-que-lo-entendiste)

---

## 1. Núcleo — 1 módulo

### `pipeline-domain`

**Qué es.** Los tipos que *son* el producto: `CompiledPipeline`, `StepNode`, `StepDescriptor`,
`RunOutcome`, `Effect`, `ReplayPolicy`, `StepRegistry`.

**Qué NO puede tener.** Framework. Ni Spring, ni la CLI, ni SQLite. Lo vigila
`FArch001DomainFrameworkFreeTest` — con un hueco conocido: su lista es de 8 tokens fijos y su escáner
exige un literal entre comillas, así que `implementation(libs.sqlite.jdbc)` **no lo detecta**. La
dirección de dependencias la vigila `FArchRP030HexagonalDependencyDirectionTest`.

**Con qué habla.** Con nadie. Es el punto del embudo al que todo apunta.

> **Por qué es uno solo.** Un solo módulo del que dependen 26 es la forma más barata de garantizar que
> "las dependencias apuntan hacia dentro": si `domain` no depende de nada, el error es imposible de
> cometer por accidente en él. La disciplina se concentra en un solo sitio.

---

## 2. Contratos — 5 módulos

Son **públicos**: otro proyecto puede depender de ellos sin arrastrar nada de aquí.

### `pipeline-events`

**Qué es.** El plano de eventos: `EventSink`, `RunStarted`, `StepFailed`, `EventRegistry`.

**Lo que NO lleva.** Nada de SQLite ni de ficheros. Esta es la frontera que permite que otro runtime
implemente el mismo plano.

### `pipeline-scripting-api`

**Qué es.** El contrato del host de scripting **y el DSL público**: `pipeline { }`, `PipelineSpec`,
`StepSpec`, `StageScope`.

> **Ojo con el nombre.** Existe un paquete `dsl/` y módulos `scripting-api` y
> `scripting-kotlin24`. El DSL *público* vive en `scripting-api`; lo que lo *implementa* hablando con
> el compilador de Kotlin vive en `scripting-kotlin24`. No son lo mismo.

### `pipeline-step-sdk:api`

**Qué es.** La superficie de **integración** de un plugin: `StepContext`, `JenkinsSurface`,
`LspMetadata`, `PipelineJson`, `CompatibilityLevel`, `FailureKindBridge`, `BlockStepFlattener`,
`WorkspacePathAnchors`.

> **Corrección importante.** `StepContract`, `StepHandler`, `StepCodec` y `requiredCapabilities`
> **no viven aquí**: están declarados en **`pipeline-domain`** (`domain/step/StepRegistry.kt:70,83,88,104`).
  Es el módulo del que dependen los contratos donde un plugin los obtiene realmente.

### `pipeline-output`

**Qué es.** El **lado lectura** del Output Plane: lo que un observador externo necesita para consumir
salida sin conocer la implementación.

### `pipeline-credentials-api`

**Qué es.** Los puertos de secretos: `SecretStore`, `CredentialProvider`.

---

## 3. Adaptadores — 15 módulos

### `pipeline-application`

**Qué es.** **El corazón operativo.** El CLI (`Main.kt`), el coordinador durable, el motor de dispatch,
el registro de pasos del core (los `Core*Step.kt`) y el compilador DSL→IR.

**Por qué es tan grande y está solo.** Concentrar aquí la composición evita que la lógica se disperse.
Cuando se añadió el registro abierto de eventos de plugin, el coordinador **bajó** de tamaño: se extrajo
una costura. Ésa es la señal de salud: cuando un módulo crece por encima de su purview, la respuesta es
extraer una costura, no subirle el techo.

### `pipeline-events-store`

**Qué es.** `SqliteEventStore`, `OperationJournal`, `ReplayCursorStore`, `RunExecutionLease`.

**No se publica**, a propósito. Lleva la infraestructura que el contrato `pipeline-events` no debe conocer.

### `pipeline-output-store`

**Qué es.** Escritura de segmentos y ficheros del Output Plane. **Tampoco se publica.**

### `pipeline-scripting-kotlin24`

**Qué es.** El adaptador que habla con el compilador de Kotlin. Único módulo autorizado a tocar
`kotlin.script.experimental.*`.

### `pipeline-step-sdk` (9 submódulos; 8 adaptadores + `:api`, ya contado en contratos)

Los pasos oficiales, empaquetados como plugins:

| Módulo | Aporta |
|---|---|
| `:api` | El contrato (el de arriba) |
| `:processor` | Procesamiento en tiempo de compilación |
| `:runtime` | Pegamento de runtime para los pasos |
| `:scm-git` | `scm-git.checkout` |
| `:http` | `http.request` |
| `:junit` | `junit.results` |
| `:files` | Operaciones de fichero |
| `:utilities` | `readJson`, `writeJson`, `sha256`, `zip`… |
| `:workflow-control` | Control de flujo |

> **Detalle que importa:** `http.request` es la prueba viva de que la arquitectura aguanta. Es un Step
> **externo al core**, con sus eventos y su capability, añadido **sin una sola línea de cambio en el
> core**. Si algún día hay que tocar el core para añadir un plugin, la arquitectura está rota.

### `pipeline-credentials` (3 módulos)

`:local` (cifrado con BouncyCastle), `:multipart`, `:executor`.

---

## 4. Calidad y soporte — 6 módulos

### `pipeline-architecture-tests` ⭐

**Qué es.** **47** ficheros `FArch*`, 11 `Lfc2*`, 7 `S3*`, 3 `Lfc0*`… y **88 clases de test** en total
(9 ficheros son helpers sin `@Test`). No prueban comportamiento: **prueban la
arquitectura**.

**Por qué deserves atención aunque no escribas runtime.** Si tocas un módulo, tienes que tocar **este**
también — pero con un matiz importante: **no es un proyecto Gradle independiente**, es un
subproyecto del build `v2` (`settings.gradle.kts:41`) enganchado al `check` raíz. Lo que sí es cierto,
y es la trampa: su tarea `test` es distinta de `:pipeline-application:test`, así que un `--tests`
sobre un módulo **no las ejecuta**. Es la trampa clásica de "los tests pasaron" cuando en realidad no
se ejecutaron.

> **Analogía:** es el seguro de un coche que no comprueba la velocidad, sino que el motor está montado
> donde debe. Un coche puede ir perfectamente y tener el motor en el maletero.

### `pipeline-testkit`

Helpers de test: `PipelineFixture`, `pipelineDefinitionShould`.

### `pipeline-event-harness`

Harness de contratos de eventos.

### `pipeline-release`, `pipeline-binding-factory`, `pipeline-artefacts-local`

Identidad de producto, construcción de bindings y artefactos locales.

---

## 5. Árboles que no compilan

Esto no es un módulo, pero es la mayor trampa para un recién llegado.

```text
pipeline-kotlin/
├── v2/          ← ESTE es el build activo
│
├── core/                 ← V1. Tiene su propio build.gradle.kts… que NADA compila.
├── pipeline-cli/         ← V1. shadowJar, GraalVM native, flags -c/-s: todo obsoleto.
├── pipeline-backend/     ← V1. Una API REST que el producto actual no tiene.
├── pipeline-config/      ← V1.
├── pipeline-steps-system/← V1 (prohibido en el camino crítico de V2).
└── v2/pipeline-protocol/ ← HUÉRFANO: existe en disco, no está en el build.
```

El `settings.gradle.kts` de la raíz contiene, en lo esencial, **una línea**: `includeBuild("v2")`.

Los `build.gradle.kts` de V1 están **vivos en disco** con sus propios catálogos de dependencias, y eso es
justo lo que hace que parezcan parte del build. **No lo son.** Editarlos no tiene ningún efecto.

---

## 6. Cómo verificar que lo entendiste

**Ejercicio A — ¿dónde tocarías?**
Quieres añadir un Step nuevo que borre un directorio. ¿En qué módulo? ¿Y en cuál más, siempre?
<details><summary>Pista</summary>
El handler, y luego el módulo de fitness. Y no en `pipeline-domain`.
</details>

**Ejercicio B — el test invisible.**
Mueves una dependencia de `pipeline-domain` a `pipeline-application` para "simplificar". Compila. ¿Qué
pasa cuando corres el gate?
<details><summary>Pista</summary>
Sección 4.
</details>

**Ejercicio C — publicar sin querer.**
Añades `import java.sql.Connection` a `pipeline-events-store`. Funciona. ¿Es un problema? ¿De qué
depende tu respuesta?
<details><summary>Pista</summary>
El store no se publica, pero `events` sí. Si algún día el store se absorbiera en el contrato,
dependeríamos de JDK pero no de un framework de BD. La pregunta real es qué pasa si el contrato
**crece**.
</details>

---

**Volver a:** [`ARCHITECTURE.md`](ARCHITECTURE.md) · o al
[README principal](../../README.es.md).