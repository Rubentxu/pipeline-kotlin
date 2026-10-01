# 07 — Breaking change, compatibilidad y riesgo

## 1. Qué rompe realmente

Cambiar:

```text
no --workspace => managed scratch
```

por:

```text
no --workspace => invocation directory attached
```

es un **behavioral breaking change** para pipelines que dependían del scratch implícito.

No rompe estos usos:

```bash
pipelinek run --workspace . pipeline.kts
pipelinek run --workspace /specific/path pipeline.kts
```

Y el comportamiento histórico queda representado explícitamente por:

```bash
pipelinek run --isolated pipeline.kts
```

## 2. Riesgo por área

| Área | Riesgo | Mitigación |
|---|---|---|
| CLI default | Alto | `--isolated`, release note, installed UAT |
| `dir` semantics | Medio/alto | RED matrix cross-step + nested + parallel |
| filesystem safety | Alto | ownership + root guard antes del flip |
| replay | Medio | fresh/replay divergence tests |
| plugins workspace | Medio | capability bridge aditivo |
| API binary | Medio | no retirar tipos en primer slice |
| self-hosting | Positivo | dogfood sin flag se vuelve natural |

## 3. Compatibilidad por fases

### Phase C0 — additive

- ADTs nuevos.
- capability nueva.
- `--isolated` añadido.
- default aún sin flip si se necesita separar release commits.
- cero removals.

### Phase C1 — semantic alignment

- Steps migrados a root/cwd correctos.
- attached root safety cerrada.
- explicit `--workspace` unchanged.

### Phase C2 — default flip

- no flag => invocation dir.
- old default => `--isolated`.
- docs/help/migration.

### Phase C3 — cleanup

- deprecations/removals sólo con API compatibility review.

## 4. ¿Hace falta un ciclo de deprecation previo?

Decisión operativa sugerida:

- si PipelineK ya tiene consumidores externos relevantes que dependen del scratch implícito, hacer una RC donde `--isolated` exista y el CLI avise del próximo flip;
- si el consumo sigue principalmente bajo control del proyecto y el producto está en `0.x`, el flip puede entrar en el mismo minor/RC train, siempre con migration note y old behavior explícito.

No mantener indefinidamente una variable de entorno o modo `legacy`; eso crearía dos defaults duraderos.

## 5. No usar autodetección como compatibility shim

Rechazado:

```text
si hay .git -> attached
si no -> scratch
```

Razones:

- monorepos/subdirs;
- worktrees;
- repos sin VCS;
- pipelines lanzados desde directorios intermedios;
- tests/temp dirs;
- comportamiento difícil de explicar y reproducir.

La CLI debe ser determinista.

## 6. SemVer/release note

Texto sugerido:

> **Workspace default changed:** `pipelinek run` now attaches the caller's current directory as the workspace. Use `--isolated` to request the previous PipelineK-managed scratch workspace. Existing explicit `--workspace <path>` invocations are unchanged.

## 7. Compatibilidad Jenkins

La evolución preserva la intuición Jenkins:

```text
workspace allocation != dir/current directory
```

y mejora dos aspectos para el caso local:

- ownership explícito del checkout del usuario;
- confinement y root-destructive protection.

Las desviaciones deliberadas deben documentarse, especialmente paths absolutos externos y limpieza de un workspace attached.
