# Puntos de entrada para la primera integración OBS

- **Fecha de fijación**: 2026-10-09 19:04Z
- **Condición**: BLOQUEADA por [`software-development-decision-kernel#12`](https://github.com/Rubentxu/software-development-decision-kernel/issues/12)
- **Autoridad de integración**: `main` (ADR-0099)

Este documento fija los SHA de referencia **antes** de integrar, para que el corte se certifique
contra bytes identificados y no contra una referencia móvil. Cualquier cambio en estas referencias
invalida el corte y obliga a re-fijarlas.

## SHAs fijados

| Ref | SHA | Descripción |
|---|---|---|
| `main` (base) | `57774c9250a0da146a4d9eeb71648e85f48c2553` | `test(c8): the installed binary, and --workspace is never a scratch root` |
| OBS (fuente) | `37df79612779d278fb0a3bcaa41eef41e051b732` | `fix(credentials): read devolvia lo que ya sabia, no lo que le pedian` |
| Merge base | `2e9b4824b4bb18b4c909b37c19c3ab0ee7a875f4` | ancestro común |

### Aclaración sobre `19d85f04`

El commit `19d85f04` (*"refactor(observation): la consola enruta, y cada familia construye su línea"*)
**ya está contenido en `37df796`** como ancestro:

```text
$ git merge-base --is-ancestor 19d85f04 par/cli-observation
SI: 19d85f04 ya esta en par/cli-observation
```

No es una cabeza pendiente de publicar. Existe además una rama local `integrate/main-obs` que
apunta al mismo `37df796` (fast-forward, sin commits propios). `origin/par/cli-observation` y la
rama local coinciden en `37df796` tras `git fetch`.

## Divergencia con este corte

| Métrica | Valor |
|---|---|
| Commits solo-OBS | 68 |
| Commits solo-main | 113 |
| Ficheros tocados por OBS | 167 |
| Ficheros tocados por main | 216 |
| Ficheros solapados | 18 |
| Conflictos textuales | 10 |
| Ficheros Kotlin nuevos en OBS | 78 (66 en `pipeline-application`) |

## Reglas de ejecución (de la decisión del operador)

1. Worktree basado en `origin/main` **vigente**, nunca sobre `main` del checkout principal.
2. SHA de OBS **fijado por corte** (`37df796` aquí), no una referencia móvil.
3. Una **vertical funcional mínima**, con su código y sus contratos. Nunca los 78 ficheros.
4. Compilar y probar sobre el árbol realmente integrado, no sobre el de OBS.
5. Verificar durabilidad, ausencia de pérdida de bytes, seguridad de secretos y compatibilidad.
6. Evidencia atada al SHA exacto.
7. Publicar según ADR-0099 y ADR-0105.

## Estado del gate al fijar estos SHAs

```text
gate_receipts:  6 filas en c8-reconciliation (la última, #6, a las 19:01:30Z)
events_v1:      842 eventos, last_hash sin cambio
cycle status:   OPEN / explore / updated_at 18:13:18 (congelado) / artifacts 0
```

`evaluate-gate` emite y persiste el recibo; `cycle transition` responde
`ENGINE_MISSING_GATE_RECEIPT` un segundo después, con identidades correctas. Detalles y criterios de
aceptación en [`SDDK_CYCLE_PERSISTENCE_BLOCKER.md`](SDDK_CYCLE_PERSISTENCE_BLOCKER.md).

## Condición de reanudación

Este corte **no arranca** hasta que #12 esté corregida y `evaluate-gate → transition` funcione con el
mismo conjunto de identidades. La prueba de reanudación es observable y no admite interpretación:

```text
sddk cycle transition --cycle p-733fb505b5a6bd2d/c8-reconciliation \
  --transition phase.explore.complete --actor rubentxu --artifact "exploration-report=<ruta>"
# debe devolver la transición aplicada, NO ENGINE_MISSING_GATE_RECEIPT
```

y, como consecuencia, `updated_at` debe dejar de reportar `18:13:18` y `artifacts` debe pasar de `0`.
