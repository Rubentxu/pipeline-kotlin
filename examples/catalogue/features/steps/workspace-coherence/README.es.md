# Workspace Coherence

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

*El ejemplo dice de si mismo:*

> The four workspace-aware Steps must agree on ONE base: the effective working
> directory of the 'dir(...)' scope they run in.

## Las etapas y los pasos

### 1. `coherence`

- `writeFile` — `file = "at-root.txt", text = "ROOT"`
- `sh` — `mkdir -p victim && echo KEEP-ROOT > victim/keep.txt`
- `dir(inner)` — un bloque de directorio
  - `sh` — `echo INNER > anchor.txt`
  - `sh` — `mkdir -p victim && echo KEEP-SCOPE > victim/keep.txt`
  - `writeFile` — `file = "written.txt", text = "WRITTEN"`
  - `sh` — `test -f written.txt && echo WROTE_HERE || echo LEG_write_scope`
  - `stash` — `name = "coherence", includes = "*.txt"`
  - `sh` — `rm -f anchor.txt`
  - `unstash` — `name = "coherence"`
  - `sh` — `test -f anchor.txt && echo RESTORED || echo LEG_restore_scope`
  - `sh` — `test ! -e at-root.txt && echo SCOPE_CLEAN || echo LEG_root_leaked`
  - `deleteDir` — `victim`
  - `sh` — `test ! -f victim/keep.txt && echo VICTIM_GONE || echo LEG_delete_scope`
- `sh` — `test -f at-root.txt && echo ROOT_SURVIVED || echo LEG_root_lost`
- `sh` — `test -f victim/keep.txt && echo ROOT_VICTIM_SURVIVED || echo LEG_root_victim_lost`
- `sh` — `test ! -f anchor.txt && echo ROOT_NOT_POLLUTED || echo LEG_restore_escaped`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `WROTE_HERE`, `RESTORED`, `SCOPE_CLEAN`, `VICTIM_GONE`, `ROOT_SURVIVED`, `ROOT_VICTIM_SURVIVED`, `ROOT_NOT_POLLUTED` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/workspace-coherence/workspace-coherence.pipeline.kts
pipelinek run      examples/catalogue/features/steps/workspace-coherence/workspace-coherence.pipeline.kts
```

## Su linaje

- **Nombre historico:** `34-workspace-coherence.pipeline.kts`
- **Ruta de origen:** `examples/steps/34-workspace-coherence.pipeline.kts`
- **Huella del cuerpo:** `sha256:5a27cee42ed5cf642702093f642827361cdf0f9f3890a1ac24e8b7e472bed872`
