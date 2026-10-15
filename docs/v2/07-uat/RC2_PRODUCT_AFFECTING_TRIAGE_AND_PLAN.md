# Plan de correcciones — qué del `releaseGate` de `v0.61.0-rc2` afecta realmente a PipelineK

**Base:** `17c37f18` (main) · **Fecha:** 2026-10-15
**Alcance:** los 25 fallos del `releaseGate`, re-derivados uno a uno contra este SHA.

La nota diagnóstica del harness los repartió en «18 problemas de tests/harness» y «6 que requieren
decisión», sin verificar ninguno de los 18. Este documento los re-deriva todos y separa lo que toca
`src/main` de lo que no. **Resultado: de 25 fallos, tres afectan a PipelineK.**

---

## Parte 1 — El reparto verificado

Todos los estados de esta tabla se midieron contra `17c37f18` con XML fresco y canario, no heredados
de la nota ni del `releaseGate`.

| # | Bloque | Filas | Estado en main | Toca `src/main` | Dueño |
|---|---|---|---|---|---|
| 1 | `UatDsl003ParallelTest` decode JSON | **7** | rojo | no | harness — corregido en `wt-ga-gate` |
| 2 | `BuiltPluginManifestArtifactTest` apiRange | 4 | **verde 16/16** | no | ya corregido antes de esta triaje |
| 3 | `UatDsl005` + `UatEvt001` + `UatEvt002` | 3 | rojo | no | contrato — escalado |
| 4 | `P3DPluginEventInstalledDistributionUatTest` | 2 | rojo | **sí** (parcial) | test pide un duplicado |
| 5 | `PluginAdmissionInstalledDistributionUatTest` | 1 | rojo | **sí** | test **+ producto** |
| 6 | `f09` | 1 | verde | no | corregido `c3c77fa0` |
| 7 | `UatS2R0RunOwnershipCliTest` | 1 | verde | no | corregido `1405b21a` |
| 8 | `DirectivePluginContractSuiteTest` | 1 | verde 8/8 | no | recertificado `3ee1bad8` |
| 9 | `InstalledDistributionHarnessFitnessTest` | 1 | verde 1/1 | no | ya corregido |
| 10 | `ObsC23NoChannelFusionFitnessTest` | 1 | rojo | no | **el propio fitness** |
| 11 | `ObsPcReadRecoverySeamFitnessTest` | 1 | rojo | no | **el propio fitness** |
| 12 | `ObsBJvmDeathOutputRecoveryUatTest` | 1 | rojo | **no** | diagnóstico cerrado |
| 13 | `TestSandboxFitnessTest` | 1 (28 ofensores) | rojo | no | harness |
| | **Total** | **25** | | **3 filas** | |

El recuento agregado de la nota era correcto. Su clasificación por hallazgo no lo era: los 18 que
llamó «problemas de harness» incluían un bloque de 7 filas que eran una sola línea de código, y un
bloque de 3 filas donde una de ellas nunca llega a ejecutar la comprobación que dice probar.

---

## Parte 2 — Lo que sí afecta a PipelineK

### P1 — La deduplicación de plugins promete más de lo que entrega

**Fichero:** `v2/pipeline-application/.../PreResolvedComposition.kt:124-131`

```kotlin
// "`distinct()` because a user may name a bundled JAR again on `--plugin-jar`"
val artifacts = (computeBundledPlugins() + pluginJars).distinct()
```

`.distinct()` deduplica **cadenas de ruta**. Si el operador nombra el JAR por su ruta empaquetada,
`<install>/lib/example-uppercase-plugin-0.1.0.jar`, la deduplicación funciona y se carga una vez. Si lo
nombra por otra ruta — el JAR del árbol de fuentes, una copia, un symlink — las cadenas difieren,
`.distinct()` no hace nada, y la admisión rechaza con `DuplicateIdentity`.

El comentario afirma una propiedad que el código no tiene. Y esa propiedad es exactamente la que un
operador da por hecha: *«ya viene incluido, no hago falta cargarlo otra vez»*.

**Distinción que falta y que sí es la correcta:**

```text
el MISMO artefacto nombrado dos veces      -> cargar una vez, no es un conflicto
dos artefactos DISTINTOS con misma identidad -> fail-closed, DuplicateIdentity
```

Hoy ambas caen en el mismo camino, y la ley de fail-closed (que es correcta y hay que conservar)
aplicada al primer caso produce un rechazo que confunde sin proteger nada.

**Alcance:** `main` se ve afectado hoy por tres filas. La decisión es de semántica de admisión, no un
refactor, y por eso no se aplica sin elshape que el operador elija (abajo).

**Coste estimado:** medio. `PluginArtifactIdentity` ya existe; hace falta una identidad de artefacto
(hash del JAR o `releaseDigest` del manifiesto) para distinguir los dos casos. Con ella, la
deduplicación pasa de ser por ruta a ser por artefacto, y `DuplicateIdentity` queda reservado para lo
que la ley protege.

### P2 — El rechazo de la gramática de `stage` escapa como excepción no capturada

**Ficheros:** `DslCompiledPipelineCompiler.kt:107` (lanza) · `Main.kt:498` y `:883` (no atrapan)

Medido contra el binario instalado:

```console
EXIT=1
STDOUT (280 bytes)  {"schema":"pipelinek.launch/1",...,"status":"Launched"}
STDERR (428 bytes)  Exception in thread "main" java.lang.IllegalStateException:
                     Stage 'ParallelTest' cannot mix a parallel body with sibling steps
	  at ...DslCompiledPipelineCompiler.stageNode(DslCompiledPipelineCompiler.kt:107)
```

Funciona: falla cerrado, exit 1, mensaje accionable que nombra el stage y el contrato. **El guard es
correcto y no se toca.**

Lo que queda es la disciplina del canal. AGENTS.md, "Typed errors are values" §11: las excepciones se
reservan para fronteras adaptador/framework y defectos del programador. Un `stage` que mezcla un body
`parallel` con steps hermanos es **entrada de usuario inválida**, y sale como un dump de stack desde
`main`. Un `catch` alrededor de `DslCompiledPipelineCompiler.compile` en ambas ramas — con la misma
forma que ya usan los `catch (e: IllegalArgumentException)` de `:653` y `:686` — lo convierte en un
rechazo tipado en stderr con exit 1.

Añade además que el `LaunchReceipt` con `"status":"Launched"` se emite **antes** de compilar
(`Main.kt:436-444` frente a `:498`). Un consumidor que solo lea el receipt ve un lanzamiento que
después falla. Es correcto por contrato de join, pero sin un mensaje de error legible en el mismo
canal hace que el fallo sea más difícil de diagnosticar de lo que debería.

**Coste:** bajo. Wrapper en dos puntos de llamada + una fila que lo fije.

### P3 — El redactor depende de un contrato que no posee (hardening, inalcanzable hoy)

`StreamingRedactor.kt:474` — `relieveFullRing` está **anidada** dentro de `if (inputOffset < inputLimit)`
(`:452`). La única salida cuando el anillo está lleno desaparece en el instante en que una fuente
devuelve 0, y `InputStream.read` con `len > 0` devuelve ≥1 o −1 por contrato JDK — así que hoy es
inalcanzable. Verificado: 60 000 casos aleatorios, 0 entradas al estado alegado, 0 lecturas de 0 bytes.

Pero el invariante que evita el spin **no es del redactor**: es una garantía del JDK. Los dos
consumidores (`DurableShellExecutor.kt:927`, `OutputWritePorts.kt:902`) tampoco tienen guarda `n == 0`.
Una fuente que violara el contrato los metería en spin al 100 % de CPU. Hoistear la comprobación fuera
del bloque, o añadir la guarda, convierte la seguridad en algo local.

**Coste:** bajo, pero sin urgencia y con la presión de una ley pendiente (`7c0d0fba` tocó este fichero
por última vez).

---

## Parte 3 — Lo que NO afecta a PipelineK

### H4 — Una fila verde que no ejecuta lo que dice probar · **PRIORIDAD 1**

`PluginAdmissionInstalledDistributionUatTest` fila 2, `aManifestThatLiesAboutItsStepsIsRefusedByTheCrossCheck`
(`:186-248`). Su aserción es que el rechazo **nombre** el Step declarado sin implementar
(`example.uppercase.ghost`).

Lo que ocurre: el mutante lleva la **misma identidad** que el plugin empaquetado, así que
`PluginAdmission.kt:88-95` lo rechaza como `DuplicateIdentity` **antes** de la pasada 2. Las
aserciones `:232-242` (exit 2, «Plugin admission refused») se cumplen por el rechazo equivocado, y la
tercera — la que nombra el step fantasma — nunca se evalúa contra la verificación cruzada.

**La verificación cruzada de contribuciones (`PluginContributionVerifier.kt:108-124`) no se ha ejecutado
en este SHA.** La fila que existe para probarla no la probó.

Esta es la clase de defecto que este repositorio ha pagado más caro: un test que afirma compatibilidad
sin ejecutarla. Es exactamente lo que `SEMANTIC CONSTUTION` §2 prohíbe y lo que
`CERTIFICATION_PROTOCOL` §4 llama *tests que afirman semántica desde la compilación sola*.

**Arreglo:** dentro del `repack` existente, reescribir también la identidad del mutante a un valor
único. `admitContributions` (`PluginAdmission.kt:162-186`) compara solo el conjunto declarado contra el
provisto del propio mutante, nunca la identidad del manifiesto contra el id del contributor, y
`PluginContributionVerifier` agrupa por `codeSource` del provider. El mutante admitiría en la pasada 1 y
sería rechazado en la pasada 2 nombrando `example.uppercase.ghost`. La aserción sigue sin poder ponerse
verde por la razón equivocada, porque **solo** la verificación cruzada nombra un Step fantasma.

### H3 — `P3DPluginEventInstalledDistributionUatTest` pide un duplicado (2 filas)

El plugin `example-uppercase` está **empaquetado** en la distribución — decisión de producto
documentada (`PK-RELEASE-CONSOLIDATION H1.4`, `v2/pipeline-application/build.gradle.kts:187`,
`v2/build.gradle.kts:714-746`) — y el test lo pasa **además** por `--plugin-jar`. `DuplicateIdentity`
es la respuesta correcta: la ley dice que dos artefactos con una identidad fallan cerrado, y su forma
canónica está fijada por unidades en `PluginAdmissionPreLoadOrderingTest.kt:310-330`.

El hermano `wt-ga-gate` está verde porque construyó un mirror de la distribución **sin** el JAR
empaquetado, para que `--plugin-jar` sea genuinamente externo. Esa es la corrección correcta: la
afirmación del KDoc de la clase es que el plugin cruza un límite de proceso real, y degradarla a
«plugin empaquetado» contradiría lo que la clase dice probar.

Salvedad: el mirror debe **afirmar** que el JAR excluido está ausente, o la fila vuelve al rojo falso
en silencio.

### H1 — `ObsC23NoChannelFusionFitnessTest`: el scan no acota lo que su propio KDoc promete

Encuentra `redirectErrorStream(true)` en `DurableShellExecutor.kt:311` (el helper `mkfifo`) y `:488`
(el log del agente de ingesta). Ninguno es el camino del transcript del hijo — que es donde el
comentario de `:714` documenta que el call **se quitó**.

Su KDoc dice: *«It does not forbid `redirectErrorStream(true)` everywhere. Only the **production
transcript path** is pinned, and the exclusion is stated rather than implied.»* La implementación
escanea el fichero entero. El principio está escrito; el código no lo cumple. Esto es un fitness que
entrenaría a su gente a relajar la ley en lugar de a obedecerla.

### H2 — `ObsPcReadRecoverySeamFitnessTest`: la allowlist de escritores está incompleta

Encuentra `ObsPc2IngestAgent.kt:140 val store = OutputPlaneProvider.storeForWriting(controlDirRoot)`.
La allowlist es `{OutputPlaneProvider, ShExecution.kt, CompositionRoot.kt}` — y **no incluye al agente
de ingesta, que es un escritor de producción**.

El scan no distingue escritores de lectores: comprueba quién *nombra* la apertura, que es lo que su
propio KDoc declara como el límite honesto de la técnica. El fitness hizo su trabajo: un escritor nuevo
sin declarar debe salir rojo. La corrección es **declararlo** (`INGEST_WRITER`), no relajar la ley.

### H5 — `TestSandboxFitnessTest`: 28 offenses de sandbox

Tests que bifurcan un hijo y escriben fuera de su `@TempDir`. HARNESS FIDELITY §4. No toca producto y
no bloquea nada bajo `BLOCKED_EXTERNAL`. Deuda de deuda técnica.

### H6 — `ObsBJvmDeathOutputRecoveryUatTest`: diagnóstico cerrado

No es defecto de producto. La fila sigue roja porque su premisa — 400 líneas «comfortably past the 1
KiB live window» — murió con `419f9192`, que subió la ventana a 64 KiB. Qué debe certificar OBS-B bajo
64 KiB es una pregunta de diseño de UAT.

---

## Parte 4 — Secuencia de ejecución

Orden por riesgo decreciente y por valor. H4 primero porque es el único que **falsea una afirmación de
evidencia**, y falsearla es peor que tenerla roja.

| WU | Contenido | Riesgo | Esfuerzo |
|---|---|---|---|
| **W1** | H4 — identidad única para el mutante; la fila ejecuta de verdad su verificación cruzada | bajo, solo test | S |
| **W2** | H1 + H2 — acotar el scan de no-fusión al camino del transcript; declarar `INGEST_WRITER` | bajo, solo test | S |
| **W3** | H3 — `discoverWithoutBundledJar` que afirma la ausencia del JAR excluido | medio, toca harness compartido | M |
| **W4** | P2 — rechazo tipado para el fallo de compilación, en ambas ramas | medio, toca `src/main` | S |
| **W5** | P1 — **requiere decisión**: ¿deduplicar por artefacto, o corregir el comentario? | medio, cambia semántica de admisión | M |
| **W6** | P3 — hoist de `relieveFullRing` o guarda `n == 0` en el pump | bajo | S |
| **W7** | H6 — rediseñar `ObsBJvmDeath` bajo la ventana de 64 KiB | bajo, decisión de UAT | M |
| **W8** | H5 — los 28 offenses de sandbox | bajo | L |

W1, W2, W4 y W6 no dependen de ninguna decisión. W5 sí, y es la única que cambia semántica pública.

---

## Parte 5 — La decisión que necesita el operador

**P1**: hoy, un operador que pase por `--plugin-jar` el plugin que ya viene empaquetado — por otra
ruta, desde el árbol, desde una copia — recibe `DuplicateIdentity` y un exit 2. La ley de fail-closed
protege contra dos plugins distintos reclamando una identidad, y eso está bien. Lo que no protege es
contra alguien nombrando **el mismo** plugin dos veces, y ahí el rechazo confunde sin proteger.

Tres salidas:

- **A — Deduplicar por artefacto.** Un mismo artefacto (mismo digest) nombrado dos veces se carga una
  vez; `DuplicateIdentity` queda para identidades compartidas por artefactos **distintos**. Es la
  lectura natural del comentario que el código ya promete, y la que hace que el rechazo signifique
  algo. Toca `src/main` y necesita el digest como identidad de artefacto.
- **B — Corregir el comentario.** Se deja el comportamiento (es defendible: fail-closed ante
  cualquier re-declaración) y se corrige `PreResolvedComposition.kt:125-127` para que deje de prometer
  lo que no hace. Coste: cero. Beneficio: el código deja de mentir. Perjudicio: un operador que
  re-nombra un plugin incluido sigue recibiendo un rechazo que no se explica.
- **C — Dejarlo y documentarlo.** Se acepta como comportamiento intencionado y se registra en el
  contrato de admisión. Solo si hay una razón de producto para que re-declarar sea siempre un error.

**Recomendación: A**, con B como parte del mismo cambio — si se implementa A, el comentario pasa a ser
verdadero; si se elige B, el comentario se corrige y se cierra como deuda.

El resto del plan no necesita decisión y se puede ejecutar en cuanto se confirme P1.