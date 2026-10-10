# WIP-1 — Cierre: resolución de conflicto de tag v0.47.0-rc1

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**WorkItem SDDK:** `02e8f0ab-3319-4c6e-b1c7-5b176e74e024` (ciclo `b1-v0-48-0-rc2`)
**HEAD tras cierre:** `8f77dc3b` (cierre de WIP-1 sobre el commit `3c449cb9` de preservation + feature doc)

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

## Verificación ampliada — CORRECCIÓN

La tabla inicial de divergencias fue generada con un script que comparaba
`git rev-parse "$t^{}"` (local, peeled) con el SHA-del-ref-remoto sin pelar. Para tags
anotados, el SHA-del-ref-remoto es el del tag object, no el del commit subyacente, lo
que producía falsos positivos. Re-hecha la comparación peeled-vs-peeled:

```text
v0.40.0         MATCH  b71ec999c  ==  b71ec999c
v0.40.0-rc1     MATCH  05ddaf0ab  ==  05ddaf0ab
v0.40.0-rc2     MATCH  165b6f9ad  ==  165b6f9ad
v0.40.0-rc3     MATCH  7b8c68e0a  ==  7b8c68e0a
v0.40.0-rc4     MATCH  119974cee  ==  119974cee
v0.40.0-rc5     MATCH  04ceff8d7  ==  04ceff8d7
v0.40.0-rc6     MATCH  9e67aa5b9  ==  9e67aa5b9
v0.40.0-rc7     MATCH  ab5bec803  ==  ab5bec803
v0.40.0-rc8     MATCH  b71ec999c  ==  b71ec999c
v0.41.0-rc1     MATCH  4a1a97502  ==  4a1a97502
v0.42.0-rc1     MATCH  cfa83f197  ==  cfa83f197
v0.43.0         MATCH  396b836f1  ==  396b836f1
v0.43.0-rc1     MATCH  396b836f1  ==  396b836f1
v0.44.0         MATCH  57cb05caf  ==  57cb05caf
v0.44.0-rc1     MATCH  34c08ad93  ==  34c08ad93
v0.44.1         MATCH  754ddda0b  ==  754ddda0b
v0.45.0         MATCH  a277d67ac  ==  a277d67ac
v0.46.0         MATCH  63ef3220e  ==  63ef3220e
v0.47.0         MATCH  3ec99a4cb  ==  3ec99a4cb
v0.47.0-rc1     MATCH  3a7058ec6  ==  3a7058ec6
v0.47.0-rc2     MATCH  5ea4137d8  ==  5ea4137d8
v0.47.0-rc3     MATCH  3ec99a4cb  ==  3ec99a4cb
v0.48.0-rc1     MATCH  6e8e86bd2  ==  6e8e86bd2
```

**Resultado real: 23 MATCH, 0 DIFF.** No existe deuda residual por divergencia de tags.
La sección anterior queda como muestra del bug de medición y se sustituye por esta
verificación correcta. No hay acción pendiente sobre tags.

## Estado del gate

WIP-1: **CERRADO**. Cumple su objetivo (resolver el conflicto de tag que bloqueaba fetch).
No abre, no cierra, ni modifica bytes durables de producto.

## Próximo paso

WIP-2: §1.1 Reconciliación de Git y producto — (a) diff completo `v0.48.0-rc1..HEAD`
(ya hecho: 134 commits, mix docs/test/fix/refactor/perf/style/chore/feat); (b) auditoría
de los 8 docs no trackeados en `docs/v2/07-uat/` y `docs/`; (c) verificación de que
Progressive Console/OBS está integrada en main, no solo en una rama de feature. No hay
deuda pendiente por divergencia de tags (verificación ampliada arriba).