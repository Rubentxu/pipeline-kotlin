# W4 — Candidato 0.48.0: ZIP reproducible y admisión de producto

- **Ciclo / WorkItem:** TRAIN-0 · `f8fc07e6-6f98-4b4a-81c0-3f5b717bd146`
- **Base SHA:** `076982b9`
- **Estado del gate al abrir:** `PRODUCT-GATE = BLOCKED_EXTERNAL` (sin superficie de CI; ver AGENTS.md §Política de verificación 2026-10-03)
- **Versión de producto:** `0.48.0`, sin sufijo de candidata. La identidad de candidata vive en `CandidateHandoff`, según TARGET-VERSION CANDIDATE LAW.

---

## 1. El defecto

La primera candidata construida en este WU **no era reproducible**. Dos
`distZip` sobre el mismo árbol limpio en `076982b9` produjeron dos archivos distintos:

| build | SHA-256 del ZIP |
|---|---|
| 1 | `0a341cb8f29c07da0c68a942f7d4ad637e91164f7cb8b66f3e5e6baeb8d3671e` |
| 2 | `3d4caf89b5f1b6372124acedc60310b42325fa72dda7e994ca1fccd3261fcaf8` |

Esta candidada y su handoff **quedan superseded y no deben publicarse jamás**.

### Localización

Se compararon los 45 ficheros del archivo por SHA-256 individual, no por
suposición. La diferencia se aisló en **uno**:

```text
pipelinek-0.48.0/lib/junit-0.1.0.jar
```

Dentro de él, `META-INF/junit-release.properties` mostraba `releaseDigest`
distinto entre builds. Todos los demás ficheros eran byte-idénticos.

### Causa raíz

`computeJunitDigest` hashea `build/classes/kotlin/main` + `build/resources/main`.
Ese directorio de recursos contiene **dos** documentos que esta tarea alimenta:

1. `junit-release.properties` — donde el digest se **escribe**.
2. `META-INF/pipelinek/plugin-manifest.json` — donde el digest se **lee** de vuelta,
   y que `emitJunitManifest` escribe en ese mismo directorio (`dependsOn computeJunitDigest`).

La exclusión existente era sólo `it.absolutePath != excludedOutput`, es decir, sólo
el documento 1. El documento 2 del build **N−1** seguía dentro del hash del build
**N**, de modo que el digest de cada build era entrada del siguiente. Circular por
construcción.

El comentario de cabecera de esa misma tarea ya prometía lo correcto
(*"excluding the provenance file itself to avoid chicken-and-egg"*): la intención
era correcta, la implementación cubría uno de los dos ficheros. **Un comentario no
es un mecanismo**, que es exactamente lo que la nueva fila de fitness comprueba.

### Hipótesis probada y refutada

Se hipotetizó una fuga de rutas absolutas en el `commandLine`. **Refutada**:
`sha256sum` emite rutas relativas tras el `cd`, luego el pipeline es independiente
del directorio de trabajo. La ruta absoluta del conjunto de exclusión no es la
causa y el arreglo no la relativiza.

### Alcance: sólo JUnit, y los otros tres se comprobaron

Una primera versión de la fila de fitness afirmaba la propiedad sobre los cuatro
plugins del SDK. **Pasó de inmediato**, lo que la convertía en una guarda de nada.
La causa: `scm-git`, `utilities` y `http` no usan el shell pipeline en absoluto; llaman
al compartido `dev.rubentxu.pipeline.build.ProvenanceDigest` con un conjunto
explícito `*ExcludedResourcePaths`, y `http` documenta que sus tres documentos
generados se excluyen precisamente para que "cannot feed back into the digest".
**Nunca estuvieron afectados.** Una fila que afirmara lo contrario habría sido un
resumen falso del codebase.

---

## 2. El arreglo

`pipeline-step-sdk/junit/build.gradle.kts`: el conjunto de exclusión pasa de un
comparador único a un conjunto de dos rutas absolutas que incluye el manifest.

```kotlin
val excludedFromDigest: Set<String> = setOf(
    excludedOutput,
    junitManifest.get().asFile.absolutePath,
)
```

---

## 3. Evidencia

### 3.1 RED → GREEN (fitness R-BUILD-01)

`PluginProvenanceDigestSelfReferenceFitnessTest` — fila de **nivel fuente**, declarada
como tal en el KDoc. No reimplementa el hashing: comprueba que el conjunto de
exclusión del propio script alcance al manifest, que es la propiedad decidible al
construir la lista de entradas.

| estado del código | XML | resultado |
|---|---|---|
| defecto presente (mutado) | `tests=1 skipped=0 failures=1 errors=0` | **RED** |
| arreglo presente (restaurado) | `tests=1 skipped=0 failures=0 errors=0` | **GREEN** |

Restauración verificada por `md5sum` idéntico entre fichero y copia previa.

### 3.2 Las dos guarda-as que fallaron, y por qué importa

La fila pasó por cuatro versiones; dos de ellas eran **falsos verdes**, y una tercera
era una guarda que no protegía nada. Se registran porque el patrón es el defecto
recurrente de este repositorio:

1. **Ancla inexistente.** Buscó `computeJunitDigest = tasks.register`; el script
   declara `tasks.register<Exec>("computeJunitDigest")`. `substringAfter` devolvió
   el fichero entero, `substringBefore("commandLine")` cortó antes del walk, y la fila
   inspeccionó un bloque que no contenía lo que decía vigilar. Una guarda que no
   encuentra a su sujeto es peor que no tener guarda: aparenta cobertura.
2. **Literal demasiado estrecho.** Exigió la cadena `plugin-manifest.json` dentro
   del bloque; el arreglo nombra un `val` declarado más abajo y nunca escribe la ruta
   ahí. Rechazó un arreglo **correcto**.
3. **Patrón demasiado flojo.** Al aflojar a "cualquier identificador con *manifest*"
   pasó a coincidir con `emitJunitManifest`, una palabra presente en el comentario
   **pre-existente** de la tarea, tanto en el fichero defectuoso como en el arreglado.
   La fila se puso verde **contra el defecto que escribía para proteger**.

La versión final quita los comentarios antes de buscar, y exige además que la
exclusión **llegue al walk** (una exclusión declarada y no aplicada no es exclusión).

### 3.3 Reproducibilidad comportamental

Cinco `:pipeline-application:distZip --rerun-tasks` desde el mismo árbol:

| build | SHA-256 del ZIP |
|---|---|
| 1 (con manifest borrado a mano) | `2d2df18db…` |
| 2 (con manifest borrado a mano) | `2d2df18db…` |
| 3 (**sin** borrar nada) | `2d2df18db…` |
| 4 | `2d2df18db…` |
| 5 | `2d2df18db…` |

```text
2d2df18dbf63a77bee8e4f56d833027143a68724fc458e377588e703d25c3c11
```

El build **3** es el que importa: sin preparación manual del directorio de
recursos, que es exactamente el escenario donde el estado residual reventaba antes.

El `releaseDigest` embebido es ahora `sha256:563804512db1e747d…` y coincide entre
`junit-release.properties` y `plugin-manifest.json`. Antes era `b90dd4f9…` en el
primer build y `52b0635f…` en el segundo.

### 3.4 PRODUCT IDENTITY LAW — cinco comprobaciones por token exacto

| superficie | valor | procedencia |
|---|---|---|
| ProductVersion | `0.48.0` | `v2/build.gradle.kts` |
| AssetVersion | `0.48.0` | nombre del ZIP |
| ArchiveRootVersion | `0.48.0` | raíz del archivo |
| EmbeddedVersion | `0.48.0` | `Implementation-Version` del manifest de `pipeline-application-0.48.0.jar` |
| RuntimeVersion | `0.48.0` | `pipeline version` del **propio candidato descomprimido** → `pipeline 0.48.0` |

Ninguna coincidencia es por subcadena.

### 3.5 Round gate completo (`./gradlew check`)

```text
timeout 1700 ./gradlew check
BUILD SUCCESSFUL in 26m 29s
```

Agregado sobre el XML **de esta ejecución** (mtime ≥ 10:31:46Z, hora de arranque
del build), separado del material arrastrado de ejecuciones anteriores:

| clases | tests | fallos | errores | skips |
|---|---|---|---|---|
| 452 | 3061 | **0** | **0** | 133 |

Los 301 XML restantes (301 clases / 1651 tests / 1 skip) son **carried-over** de
runs previos y quedan excluidos: no los produjo este gate. El único skip
carried-over es `DistributionIdentityProbeTest$AgainstRealCheckedOutArtifacts`,
que además está `@Disabled` y no pertenece a este WU.

Los 133 skips frescos son casos `@Disabled` pre-existentes por la frontera de
responsabilidad con el harness externo; ninguno está en los ficheros de este WU.

**Corrección de una cifra mal reportada.** Una primera versión de este recibo
declaraba `753 clases / 4712 tests`. Esa era la suma de todo el XML en disco
(452 frescos + 301 arrastrados), no el resultado del gate. El canary que se usó
para detecting esa confusión —comprobar el mtime más reciente— prueba que *algo*
se regeneró, no que *todo* lo hiciera. La cifra correcta es **452/3061**, y es
coherente con el gate W3 inmediatamente anterior (450 clases / 3058 tests).

Fila propia dentro de ese gate, XML fresco:

```text
TEST-...PluginProvenanceDigestSelfReferenceFitnessTest.xml
tests="1" skipped="0" failures="0" errors="0"
```

Nota operativa registrada para el próximo: lanzar `check` con `nohup … &` desacopla
el wrapper pero **el proceso Gradle sigue vivo** y retiene el lock del checkout; los
reintentos posteriores fallan con *"Another Gradle invocation is already using this
v2 checkout"*, que **no** es un defecto del código. Un `BUILD SUCCESSFUL` ausente no
debe leerse como fallo: hay que seguir el proceso real, no el wrapper.

### 3.6 SAST

`detektTest` no es la tarea que ejecuta el gate: `build.gradle.kts:348` ata
`check` a **`detekt`**, no a `detektTest`. Ejecutado el correcto, con la baseline
del módulo aplicada: **BUILD SUCCESSFUL**. Los 77 hallazgos de `detektTest`
pertenecen a ficheros pre-existentes no tocados por este WU y no son puerta de
entrada. El único defecto propio (falta de newline final) quedó corregido.

### 3.7 Admisión de candidata

**Primer intento — REFUSED**, con este motivo textual:

```text
the working tree carries uncommitted changes to tracked files (1 modified, 0 staged).
The manifest would name commit 076982b9… while the distribution ZIP was compiled from
content that commit does not contain, so the recorded provenance would be false.
Commit the work, or build from a clean tree.
```

**El gate obró correctamente.** El árbol contenía el fix sin commitear, luego un
manifiesto que nombrara `076982b9` afirmaría una procedencia falsa. La orden correcta
es commitear y después admitir, no admitir antes.

**Segundo intento, sobre el árbol limpio en `54c56179` — PASSED:**

```text
[release] candidate admission PASSED
[release]   candidate_id: sha256:2d2df18dbf63a77bee8e4f56d833027143a68724fc458e377588e703d25c3c11
[release]   identity: identity INCOMPLETE: expected 0.48.0 but these surfaces were not
            observable: RUNTIME_VERSION, MANIFEST_VERSION
[release]   provenance: source provenance VERIFIED: clean tree at 54c56179e4c7f4e8b364b46ae034802bfdc65b92
```

El `candidate_id` es **exactamente** el digest del ZIP reproducible. Eso confirma
que la identidad de candidata se derivó de los bytes correctos y no de una entrada
de build anterior.

Sobre el `identity INCOMPLETE`: `RUNTIME_VERSION` y `MANIFEST_VERSION` no son
derivables del ZIP por sí solas, las aporta el harness externo. La quinta
identidad (`RuntimeVersion = 0.48.0`) **sí** quedó verificada por separado, con el
launcher del candidato descomprimido, en §3.4.

Documentos emitidos (ambos nuevos, `11:00`):

| campo | valor |
|---|---|
| `candidate_id` | `sha256:2d2df18db…` |
| `release_train` | `0.48.0` |
| `candidate_sequence` | `1` |
| `product_version` | `0.48.0` |
| `source_commit` | `54c56179e4c7f4e8b364b46ae034802bfdc65b92` |
| `artifact.sha256` | `2d2df18db…` |
| `size` | `92,119,849` bytes |

El manifiesto de distribución declara la versión de producto `0.48.0` en
`version`, `asset.name`, `asset.archive_root` e `asset.implementation_version`.
La promoción a estable sigue perteneciendo al harness externo; este recibo no la
certifica.

---

## 4. Referencia de implementación

```
Reference implementation consulted: Gradle reproducible-archives / build-cache inputs
                                    (comportamiento estándar), no un proyecto concreto
Behaviour adopted:                el conjunto de entradas de una tarea de digest excluye
                                    sus propios productos, incluidos los generados por
                                    tareas que dependen de ella
Intentional deviations:           ninguno; se conserva el shell pipeline existente y sólo
                                    se amplía el conjunto de exclusión
Security implications reviewed:   n/a — el cambio afecta a los bytes de un artefacto de
                                    distribución, no a autorización ni a credenciales
Tests demonstrating the contract: PluginProvenanceDigestSelfReferenceFitnessTest
                                  (RED/GREEN + mutación), 5× distZip con digest idéntico
```

---

## 5. Lo que este recibo NO certifica

- `PRODUCT-GATE` sigue `BLOCKED_EXTERNAL`. No hay CI remoto (`754ddda0` los retiró);
  su ausencia no es verde.
- La fila de fitness es una comprobación **de fuente**. La mitad comportamental
  (dos builds, bytes idénticos) quedó verificada fuera de banda contra el build real
  y registrada arriba, no simulada en un test.
- `identity INCOMPLETE` en `RUNTIME_VERSION` y `MANIFEST_VERSION` no es un defecto
  observado: son superficies que el harness externo aporta, no derivables del ZIP.
- No se ha empujado, movido ningún tag, ni publicado artefacto alguno. La promoción
  a estable sigue perteneciendo al `pipelinek-release-harness` externo.

## 6. Estado final de la candidata

```text
54c56179e4c7f4e8b364b46ae034802bfdc65b92
fix(build): stop the junit provenance digest from hashing its own manifest
```
