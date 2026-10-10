# WIP-1 — Cierre: resolución de conflicto de tag v0.47.0-rc1

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** `02e8f0ab-3319-4c6e-b1c7-5b176e74e024` (ciclo `b1-v0-48-0-rc2`)
**HEAD tras cierre:** `3c449cb9` (commit de preservation + feature doc)

## Resultado

`git fetch origin --tags --prune` ahora completa sin rechazos. El ref `v0.47.0-rc1` local
apunta a `3a7058ec610b9b6bcab1facfe0e7fe62431cf345` (commit `chore(release): abrir el
tren 0.47.0 sin tag y sin fingir certificacion`), idéntico al de origin.

## Pasos aplicados

1. Preservación de metadata en `V0470_RC1_ANNOTATED_TAG_PRESERVATION.md` (commit
   `3c449cb9`).
2. `git tag -d v0.47.0-rc1` — borrado del ref annotated local `58670c9b...`.
3. `git fetch origin --tags --prune` — primera ejecución: "ok fetched (1 new refs)".
4. Verificación punto por punto: `v0.47.0-rc1` MATCH entre local y remoto.

## Deuda residual detectada durante la verificación

Al comparar TODOS los tags `v0.4*` entre local y remoto, se observa que muchos difieren
en el SHA subyacente, no solo en tipo. Ejemplos verificados:

| Tag | Local `^{}` | Remote | Estado |
|---|---|---|---|
| v0.40.0-rc1 | `05ddaf0a` | `05ddaf0ab` | MATCH |
| **v0.47.0-rc1** | `3a7058ec` | `3a7058ec` | **MATCH (objetivo de WIP-1)** |
| v0.40.0 | `b71ec999` | `043e74c0` | DIFF |
| v0.43.0 | `396b836f` | `84ef26a1` | DIFF |
| v0.44.0 | `57cb05ca` | `5b05498f` | DIFF |
| v0.45.0 | `a277d67a` | `a46e67c` | DIFF |
| v0.46.0 | `63ef3220` | `1e0209bc` | DIFF |
| v0.47.0 | `3ec99a4c` | `4c372f2f` | DIFF |
| v0.48.0-rc1 | `6e8e86bd` | `8834a4f4` | DIFF |

Total observado en el barrido: 23 tags divergentes, 2 coinciden (incluido el de WIP-1).

## Tratamiento de la deuda residual

Estos tags NO bloquean `fetch --tags --prune`, por lo que NO bloquean WIP-1. Son
evidencia de re-tagging local no publicado o reescritura upstream. Resincronizarlos de
forma destructiva (`fetch --tags --force`) borraría metadata local; la acción correcta
es investigarlos uno a uno en un WIP dedicado, posiblemente ligado a §1.1 (reconciliación
de Git y producto) o a una limpieza de refs sin alterar contenido durable.

**Decisión:** NO se actúa sobre ellos en WIP-1. Se registran como residual debt y se
añaden a la lista de tareas de §1.1 (WIP-2).

## Estado del gate

WIP-1: **CERRADO**. Cumple su objetivo (resolver el conflicto de tag que bloqueaba fetch).
No abre, no cierra, ni modifica bytes durables de producto.

## Próximo paso

WIP-2: §1.1 Reconciliación de Git y producto — incluye (a) el diff `v0.48.0-rc1..HEAD`,
(b) la auditoría de los 8 docs nuevos en `docs/v2/07-uat/` y `docs/`, (c) la
investigación de los 23 tags divergentes residuales.