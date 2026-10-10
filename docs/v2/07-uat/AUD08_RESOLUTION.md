# AUD-08 resuelto y los símbolos ausentes que arrastra

Investigación de 2026-10-09. Corrige la hipótesis de `MAIN_COMMIT_AUDIT.md`, que
presentaba AUD-08 como "dos resolvers incompatibles", y sustituye la afirmación más estrecha
que hice después ("el bloqueante es `safeStreamName`") por la que está medida.

## Parte 1 — AUD-08 no es un conflicto de estrategias

### Lo que dije antes, y por qué estaba mal

Escribí: *"Recomiendo la de OBS: `safeStreamName` es la función que ya existe en el código que
trae los 20 `feat`, y la de main tendría que replicarse."*

Afirmé eso **sin leer la implementación**. Cuando la leí:

```kotlin
// main, SegmentOutputStore.kt:715
fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

// OBS, SegmentOutputStore.kt:876
internal fun safeStreamName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
```

**El mismo cuerpo, carácter por carácter.** La estrategia no difiere en el algoritmo: difiere en
el alcance.

### La diferencia real: un consumidor más

```text
main, v2/.../output/store/:     OutputWritePorts.kt, SegmentOutputStore.kt
OBS,  v2/.../output/store/:     OutputWritePorts.kt, SegmentOutputStore.kt,
                                SegmentFrameIndex.kt  <-- NUEVO
                                SegmentReader.kt      <-- NUEVO
```

La extracción de `safeStreamName` responde a un consumidor que en `main` no existe. El KDoc de
OBS lo dice: *"Shared rather than duplicated so the byte store and the frame index cannot drift
into naming the same run differently."*

`main` resolvió AUD-08 correctamente para un mundo con un consumidor. OBS lo resolvió para un
mundo con dos. **La estrategia de OBS es la de `main` más una garantía que `main` no necesitaba.**
Portarla no es elegir un ganador.

**El paso 0 que propuse ("decisión de arquitectura") desaparece como decisión.** Pasa a ser
aplicación mecánica: traer los ficheros nuevos, y con ellos el símbolo.

## Parte 2 — `safeStreamName` no era el único símbolo ausente

Yo mismo dejé anotada esta comprobación como pendiente, y al hacerla resultó insuficiente. El
criterio correcto no es un símbolo sino **cuántos ficheros nuevos trae OBS**, porque cada uno
puede arrastrar sus propias dependencias.

```text
git diff --diff-filter=A --name-only $(merge-base) origin/par/cli-observation | grep '\.kt$'
  → 78 ficheros nuevos

v2/pipeline-application         66
v2/pipeline-output-store        6
v2/pipeline-output               3
v2/pipeline-architecture-tests  2
v2/pipeline-step-sdk            1
```

### Símbolos confirmados ausentes de `main`

| Símbolo | Se declara en | `main` | OBS |
|---|---|---|---|
| `safeStreamName` | `SegmentOutputStore.kt` (OBS) | 0 ocurrencias | 3 |
| `OutputFrame` | `pipeline-output/OutputFrameIndex.kt` | **el fichero no existe** | `data class` |

Además, el paquete `pipeline-application/.../observation/` **no existe en `main`**, y
`MainObserveCli.kt` es un entrypoint nuevo.

### Por qué esto importa y `safeStreamName` no bastaba

`git merge-tree` no marca ninguno de estos conflictos: son dependencias que solo aparecen al
compilar. Un merge masivo no produce un error legible en `compileKotlin`; produce un error que
apunta a un símbolo sin explicar que la rama de origen lo tenía y la de destino no.

**El conteo de 10 conflictos textuales del inventario subestima la fricción real.** Diez es el
número que Git sabe medir, no el número de cosas que hay que arbitrar.

## Conclusión: el bloqueo no es una decisión, es una etapa de integración

Ni AUD-08 ni los CLI exigen criterio de arquitectura ya:

- **AUD-08**: aplicar, no decidir.
- **`MainEventsCli`**: integrar secciones no solapadas; ambos cumplen §12.
- **`MainConsoleCli`**: trabajo nuevo (implementar §12), compartido por ambas ramas.

Lo que queda por encima es una decisión de secuencia: **integrar los 78 ficheros nuevos de OBS
antes de intentar compilar la mezcla.** Sin ellos, cualquier revisión de conflictos se hace
sobre un árbol que no puede compilar por construcción, y los errores que aparecen no son
informativos.

## Lo que este documento NO afirma

- **No se ha compilado ninguna mezcla.** Todo lo anterior es lectura de símbolos y de árbol de
  ficheros (`git ls-tree`, `git grep`, `git diff --diff-filter=A`). No hay resultado de
  `compileKotlin` que lo confirme.
- **Los 78 ficheros nuevos pueden arrastrar más símbolos ausentes** que los dos confirmados. Los
  dos confirmados son los que la comprobación directa tocó; no son un exhaustivo.
- **No se ha resuelto `BodyExecutionEngine`** (`d1869963` en `main`, con 15 sitios de reloj
  auditados). Sigue sin comprobarse si OBS movió esa superficie.