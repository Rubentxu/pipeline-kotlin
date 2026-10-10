# Preservación del tag anotado local v0.47.0-rc1

**Fecha de preservación:** 2026-10-10
**Trabajo previo:** WIP-1 de B1 (v0.48.0-rc2) — resolución de conflicto de tag
**Autoridad:** este fichero NO es autoridad de producto; preserva metadatos extraídos de un tag
anotado local antes de su eliminación. La candidata v0.47.0-rc1 sigue identificada por su
CandidateId, no por este tag.

## 1. Por qué se preserva antes de borrar

`git fetch origin --tags` falla desde este clon porque el tag local `v0.47.0-rc1` apunta a un
tag object propio (`58670c9b...`, annotated) mientras que origin lo expone como lightweight
sobre el commit `3a7058ec...`. Ambos acaban en el mismo commit, pero el ref diverge.

El tag anotado local contiene metadatos que NO existen en ningún otro archivo del repo:
CandidateId, ZIP size, SHA256, resultado del gate. Si se borra sin preservar, esa evidencia
se pierde. Este recibo captura esa información de manera verificable.

## 2. Identificadores preservados (verbatim del tag anotado)

```
tag object     58670c9b2e3e8750994c07e5215dda3388f514e9
tagger         Rubentxu <rubentxu74@gmail.com>
tagger date    epoch 1791135298 +0200
source commit  3a7058ec610b9b6bcab1facfe0e7fe62431cf345
                chore(release): abrir el tren 0.47.0 sin tag y sin fingir certificacion
CandidateId    sha256:dde11f3526d44b5fd3ee2ca164fbb610d8f3fde6d53d1ebdaf3ee36725ddf20e
ProductVersion 0.47.0
ReleaseTrain   0.47.0
CandidateSequence 1
archive_root   pipelinek-0.47.0
ZIP            pipelinek-0.47.0.zip, 91811317 bytes
SBOM digest    f6d158af...
manifest       2d225b28...
```

## 3. Resultado del gate preservado (verbatim del tag anotado)

```
BUILD SUCCESSFUL in 27m 5s
713 clases
4743 tests, 0 fallos, 0 errores, 140 skipped
apiCheck x4, detekt x25, koverVerify x26
0 lineas ^e:
```

## 4. Procedencia del tag anotado local

El tag anotado fue creado localmente con la intención de documentar la candidata; no fue
publicado en `origin`. El tag que origin expone es lightweight y termina en el mismo commit
`3a7058ec...`. La divergencia es solo de tipo (annotated vs lightweight), no de contenido.

## 5. Acción aplicada

1. Este recibo commiteado (este commit).
2. `git tag -d v0.47.0-rc1` ejecutado: el ref local annotated `58670c9b...` se elimina;
   el commit `3a7058ec...` permanece accesible por ancestry.
3. `git fetch origin --tags --prune` ejecutado: ref local ahora lightweight sobre
   `3a7058ec...`, idéntico al de origin.
4. Verificación posterior: `git rev-parse v0.47.0-rc1^{}` y `git ls-remote origin
   refs/tags/v0.47.0-rc1` retornan el mismo SHA.

## 6. Garantía de no duplicación

Antes de crear este fichero se buscó `dde11f35...` y `91811317` en todo `docs/` y no se
encontraron. El contenido preservado no existía en otro receipt.

## 7. Estado del conflicto

| Tag | Antes | Después |
|---|---|---|
| local v0.47.0-rc1 | annotated `58670c9b` → commit `3a7058ec` | lightweight `3a7058ec` |
| origin v0.47.0-rc1 | lightweight `3a7058ec` | lightweight `3a7058ec` (sin cambio) |

Tras la acción, `git fetch origin --tags --prune` debe completar sin rechazos. El estado
se confirma en el recibo de cierre de WIP-1.