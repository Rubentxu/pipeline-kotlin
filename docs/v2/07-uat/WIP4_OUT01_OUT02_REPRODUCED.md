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