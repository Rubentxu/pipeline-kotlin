# WIP-4 — §1.3 OUT-01 y OUT-02 reproducidos (test adversarial)

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** `1326e005-61cf-4651-b486-70827fe152d9` (ciclo `b1-v0-48-0-rc2`)
**Veredicto:** dos defectos **reproducidos** bajo su contrato de entrada; pendiente fix con política de migración de formato durable.

## Comando

```bash
cd v2 && ./gradlew :pipeline-output-store:test \
  --tests "*OUT-01*" --tests "*OUT-02*" \
  --rerun-tasks --console=plain --no-daemon -i
```

Log: `/tmp/wip4-out01-out02-v2.log`. BUILD FAILED in 44 s (compilación + 2 tests).

## OUT-01 reproducido

```
SegmentOutputStoreTest > OUT-01 prune of a short runId must not delete streams of a longer runId sharing the safe prefix(Path) FAILED
    org.opentest4j.AssertionFailedError: only run A's stream should be removed
        ==> expected: <1> but was: <2>
```

**Setup:**
- `runA = "run"` → safe = `"run"` → prefix = `"run_"`
- `runB = "run_x"` → safe = `"run_x"` → directorio `run_x_stdout`
- Ambos stream dirs existen bajo `<root>/streams/`

**Resultado:** `prune(runA)` eliminó **2 streams** en lugar de 1. El filtro `startsWith("run_")` coincide con `run_stdout` (legítimo de A) **y** con `run_x_stdout` (de B, indebido).

**Causa raíz:** `SegmentOutputStore.kt:358` y `:367` — `runPrefix(runId) = safe(runId) + "_"` combinado con `startsWith(prefix)` no es una pertenencia inequívoca. El prefijo es demasiado permisivo.

## OUT-02 reproducido

```
SegmentOutputStoreTest > OUT-02 distinct runIds whose safe forms are equal collide on disk and serve each other's bytes(Path) FAILED
    org.opentest4j.AssertionFailedError: OUT-02: reading through runB must be refused as UnknownStream once runIds are unambiguous;
        if this returned a Page, runA and runB still share a directory because the durable format cannot tell their runIds apart.
        ==> Unexpected type, expected: <dev.rubentxu.pipeline.v2.output.OutputReadResult.Refused>
            but was: <dev.rubentxu.pipeline.v2.output.OutputReadResult.Page>
```

**Setup:**
- `runA = "run/abc"` → safe = `"run_abc"`
- `runB = "run_abc"` → safe = `"run_abc"` (idéntico al anterior)
- `streamA = OutputStreamId("run/abc/stdout")` → dir `run_abc_stdout`
- `streamB = OutputStreamId("run_abc/stdout")` → dir `run_abc_stdout` (idéntico)

**Resultado:** `store.open(streamA)` + escritura + `store.read(streamB)` devuelve `Page` con los bytes escritos a través de streamA. La razón de `runA` y `runB` colapsa en disco. No existe `UnknownStream` para `streamB` porque el formato durable no distingue los runIds.

**Causa raíz:** `safeStreamName` (`SegmentOutputStore.kt:905`) — `name.replace(Regex("[^A-Za-z0-9._-]"), "_")` **no es inyectiva**. Distintos runIds mapean al mismo path físico. `SegmentFrameIndex.kt:199,202,217` heredan la misma limitación porque también usan `safeStreamName(runId)`.

## Veredicto sobre §1.3 OUT-01 / OUT-02

Ambos defectos del roadmap §1.3 están **reproducidos bajo su contrato de entrada**:

| Item | Roadmap | Reproducido |
|---|---|---|
| OUT-01 — aislamiento entre runs | "filtrado por safe(runId) + \"_\" puede seleccionar streams pertenecientes a otro run" | ✓ `expected 1, got 2` |
| OUT-02 — colisiones de identidad física | "saneamiento no es inyectivo y puede producir rutas idénticas para IDs diferentes" | ✓ `runA/runB` sirven el mismo `Page` |

## Decisión sobre el fix

El roadmap exige **antes** de modificar bytes durables:

> "Definir un formato físico seguro y una política de compatibilidad/migración antes de modificar bytes durables."

Cualquier fix de OUT-01/OUT-02 toca el formato físico del Output Plane. Por ejemplo:

- Cambiar `safeStreamName` para incluir un digest (hash) — formato durable nuevo.
- Cambiar `runPrefix` para usar hash inequívoco — formato durable nuevo.
- Cualquiera de los dos necesita política de migración: ¿qué pasa con stores existentes? ¿se reescriben? ¿se releen? ¿se abandonan?

**Decisión:** NO aplicar fix en este WIP. Marcar WIP-4 como "defectos reproducidos; fix pendiente de política de migración". Pasar a WIP-5 (RUN-01) que no requiere cambio de bytes durables.

Si la política de migración se define en B1 (este bloque), WIP-4 reabre. Si se difiere a otro bloque (B2 certificación, B3 ASX, etc.), se arrastra como deuda clasificada.

## Cambios a este WIP (committed)

1. `SegmentOutputStoreTest.kt` con dos tests adversariales OUT-01 y OUT-02 que fallan en el código actual.
2. WIP1 closure receipt ya documentaba la falta de divergencia de tags (corregido en WIP-1.5).
3. Este recibo: `WIP4_OUT01_OUT02_REPRODUCED.md`.

## Próximo paso

WIP-5: §1.3 RUN-01 — atomicidad de `RunIdDirectory.record`.
---

# Addendum 2026-10-18 — la decisión anterior era un menu cerrado, y OUT-01 ya está cerrado

Este addendum no reescribe la reproduced arriba; la conserva como evidencia de
su propio SHA. Registra por qué la "Decisión sobre el fix" de arriba ya no
describe el estado real.

## La premisa de la decisión era falsa

El recibo decidió **no** tocar OUT-01 porque "cualquier fix toca el formato
físico", y a continuación listó las dos únicas opciones que contempló:

```text
  - hashear safeStreamName          -> cambia nombres en disco
  - hashear runPrefix               -> cambia nombres en disco
```

Ambas cambian el formato. La conclusión "necesita política de migración" se
sostiene **dentro de ese menú** — pero el menú estaba incompleto. La tercera
rama nunca se escribió: **cambiar solo el predicado de selección**. No cambia
ningún nombre en disco. Por tanto la decisión pendiente era del artefacto, no
del formato, y la pregunta que había que responder era "¿qué tendría que
cambiar en disco?", no "¿qué formato nuevo propondemos?".

## Además, OUT-01 es una COLISIÓN DE PREFIJO, no una falta de inyectividad

`safe` podía ser inyectiva y el defecto seguiría existiendo: `run` produce el
prefijo `run_`, y el directorio de `run_x` empieza por `run_`. Nada se pliega.
Esto importa porque descarta de raíz la mitad del menú (hash por nombre), que
era la que sí habría exigido migración.

## Y el arreglo "solo lo declarado" tampoco era el fix

Es la rama que este addendum midió y **descartó**. `declareStream` rechaza por
diseño un id cuyo último segmento no sea un token de canal, y
`ShExecution.ingestTranscriptIntoOutputPlane` (`ShExecution.kt:148`) escribe
exactamente esa forma (`OutputPlaneProvider.streamId(runId, opId)` →
`.../transcript`) en cada paso `sh`. Seleccionar solo lo declarado deja la
transcripción durable de producción **permanentemente impodable**: no borra
nada, no avisa a nadie. Medido el 2026-10-18 como **10 filas rojas en 5 clases**
de `:pipeline-output-store:test`.

```text
  prefix puro     prune("run") borra run_x   -> perdida de datos en otro run
  solo declarado  prune("run") borra nada    -> fuga de retencion silenciosa
```

## Estado real tras 4f220c1c

```text
OUT-01  CERRADO. Predicado de seleccion de prune por pertenencia probada.
OUT-02  Sigue @Disabled. Requiere decision de formato y NO fue tocado:
        `safeStreamName` no es inyectivo (`run/abc` y `run_abc` colapsan a
        `run_abc`) y no hay ningun byte dentro del directorio que cargue el
        runId. No se mejora con este trabajo, y no se afirma lo contrario.
```

Layout en disco: **sin cambios**. Los directorios mantienen sus nombres.

Evidencia (mismo SHA, XML fresco):

```text
:pipeline-output-store:test  15 clases, 136 tests, 0 fallos, 0 errores, 1 skip (OUT-02)
consumidores de prune, :pipeline-application:test
  RunOutputRetentionTest, RetentionAuthorityFitnessTest,
  B1aOutputPlaneProviderLifecycleCharacterizationTest,
  B1aShNonDurableRouteCharacterizationTest, ScriptedStageExecutionTest
                             5 clases, 33 tests, 0 fallos
```

Dientes por mutación, ambas ramas del predicado:

```text
  quitar la exclusión por declaración -> solo prefijo   -> mata OUT-01
  quitar la rama de no declarados     -> solo declarado -> mata 11 filas
```

### Residuo irreducible, declarado

Dos runs cuyos nombres seguros colisionan por prefijo y cuyos streams son
**ambos no declarados** siguen siendo indistinguibles en disco, y podar el más
corto borra la transcripción legacy del más largo. No hay byte dentro del
directorio que cargue el runId (`Layout` solo contiene cur.seg / cur.cmt /
cur.res / sealed / seal / ownership, y el lock de ownership nunca se lee como
contenido). Ese resto es más estrecho que el defecto original exactamente en el
conjunto de streams que algún run declaró.

## Contrato de cierre del WU

```text
Implementacion de referencia consultada: ninguna externa. La semantica de
  retencion y la forma del Output Plane son internas; el baseline de Jenkins no
  aporta aqui.
Comportamiento adoptado:  la pertenencia se prueba, no se infiere del nombre.
Desviaciones intencionadas: ninguna.
Implicaciones de seguridad revisadas: n/a para este WU (no hay credenciales ni
  superficie de datos exposed; el cambio reduce una via de perdida de datos).
Tests que demuestran el contrato:
  SegmentOutputStoreTest."OUT-01 prune of a short runId must not delete
    streams of a longer runId sharing the safe prefix"
  SegmentOutputStoreTest."an undeclared pre-channel stream is still pruned -
    declaring is not the only ownership evidence"
```
