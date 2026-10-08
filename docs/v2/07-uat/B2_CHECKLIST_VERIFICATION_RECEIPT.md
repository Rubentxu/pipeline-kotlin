# B2 — verificación de la lista B2.2 contra el árbol (no reimplementar lo ya probado)

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08 · **Base:** `94e0817c`
**Método:** para cada fila de la lista que el plan pone en B2.2, buscar la implementación real y **ejecutar** la prueba que la sostiene. Una fila cuyo dueño ya existe y está probado no se reimplementa: se cita.

Todas las corridas de esta rebanada son mías, con `--rerun-tasks` y XML fresco. No se modificó una línea de producción.

---

## 1. Fila por fila

### `apiRange` y versión de motor — **YA ESTABA, VERIFICADO**

Implementación: `PluginAdmission.kt:71` (`if (!manifest.apiRange.accepts(runtimeVersion))`) produce
`PluginManifestRejection.IncompatibleApiRange`; el rechazo vive en la pasada de admisión previa a la
carga (`PreLoadPluginAdmission.admitAll(artifacts, runtimeVersion)`), cableada desde
`PreResolvedComposition`. La versión de motor sale de `RuntimeApiVersion.current()`, y su ausencia se
reporta como `CompositionOutcome.RuntimeVersionUnavailable` — un caso propio, no un rechazo de plugin,
"porque ningún plugin tiene la culpa y nombrar a uno sería mentir".

Ejecutado (`:pipeline-application:test`, 2026-10-08T11:42:06Z):

```text
S6/D admission decides BEFORE plugin code is initialised      5 tests  0 fail  0 err  0 skip
  - a refused plugin leaves NO sentinel: admission ran before the code      <- apiRange que excluye 0.47.0
  - NON-VACUITY: admitted -> the plugin is initialised and the sentinel DOES appear
  - a duplicate plugin identity names BOTH the newcomer and the incumbent
  - a malformed manifest is refused with a TYPED reason, never an exception
  - an artifact that declares NOTHING is refused, not admitted on the strength of ...
```

La fila del rango no se limita a "rechazado": afirma que **el código del plugin nunca corrió** (sin
centinela) y trae su control de no-vacuidad en la fila siguiente. Es la forma fuerte.

### `plugins incompatibles` y `errores de admisión` por el binario instalado — **YA ESTABA, VERIFICADO**

```text
PluginAdmissionInstalledDistributionUatTest       2 tests  0 fail  0 err  0 skip   (11:42:11Z)
  - un artefacto SIN manifest es rechazado antes de componer
  - un manifest bien formado que miente sobre sus Steps es rechazado en la pasada ...
```

HF2: binario instalado real, artefacto real. El recibo de esa clase documenta por qué un tercer
mutante (`apiRange` insatisfecho) se descartó: *«probaría el mismo eslabón dos veces añadiendo un
cuarto fixture que mantener»*. Es una razón de coste de cobertura, no un hueco: el eslabón ya lo prueba
la clase in-process de arriba.

### `cargas duplicadas` — **YA ESTABA, VERIFICADO**

`PluginManifestRejection.DuplicateIdentity(identity, incumbent)` nombra al recién llegado **y** al
incumbente, y la fila "a duplicate plugin identity names BOTH..." está verde. Nunca "el primero gana"
ni "el último gana".

### `BOM y dependencias transitivas` — **ENTREGADO en B2a** (`1378eb47`)

`:pipeline-sdk-bom` publicado; consumidor externo que resuelve por `platform(...)` con coordenadas sin
versión. La transitividad de los POM la prueba el consumidor `fabric-contract-consumer` (independiente,
sin `project(...)`), que compila contra las cuatro coordenadas publicadas y nada más.

### `repositorios de publicación parametrizables` — **YA ESTABA, VERIFICADO**

`-PsdkRepo=` y `-PsdkVersion=` en los cinco builds externos. La aspereza que esta fila declaraba ya se
cerró en `f1770e37`: el default `0.1.0-SNAPSHOT` se retiró de los cuatro builds, porque un default de
versión sólo puede ser una versión que falla en resolver.

### `metadatos de artefacto` — **YA ESTABA, VERIFICADO**

Manifest dentro del JAR real (`META-INF/pipelinek/plugin-manifest.json` + descriptor ServiceLoader),
propiedades de release, y el digest de procedencia corregido en B0.2 con digests idénticos al base.

### `ServiceLoader y metadata generada` — **YA ESTABA, VERIFICADO**

Cuatro adaptadores de descubrimiento por familia (steps, directives, events, capabilities) más el
loader de credenciales, que es otro dominio. El KSP se retiró: no hay segunda autoridad de metadatos
(`NoSecondStepMetadataAuthorityFitnessTest`).

### `S5.5 Reactor` — **DIFERIDO por ADR-0104**, sin acción.

---

## 2. Lo que queda REALMENTE abierto en B2

```text
B2.4  el conjunto de fixtures que consumirá S7 (certificación contractual por plugin). Es
      preparación de B3 y no existe hoy: es el residuo honesto de B2.
B2-h  disposición del puerto `BranchInvoker`: sin llamante de producción (parallel se ejecuta en
      `ParallelStageEngine`, medido). Es higiene de diseño — mantener con llamante, mantener como
      seam documentado, o retirar — y no un defecto: `parallel` funciona.
```

---

## 3. Lo que esta rebanada NO hace

```text
- No reimplementa nada: las filas cerradas ya tenían dueño y prueba, y se citan con su corrida.
- No afirma nada sobre el harness externo: la certificación de artefacto instalado y de proyectos
  externos es de `pipelinek-release-harness`.
- No convierte en PASS las filas que no ejecuté en esta rebanada: sólo aparecen aquí las que tienen
  XML fresco mío en la tabla.
- No decide la disposición del puerto `BranchInvoker`.
```
