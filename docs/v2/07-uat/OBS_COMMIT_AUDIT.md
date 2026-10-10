# Auditoría commit a commit de OBS — 2026-10-09

Complementa `OBS_RECONCILIATION_INVENTORY.md` (métricas de divergencia). Aquí está el
**contenido** de los 68 commits, no su tamaño. Base de todo: lectura directa de
`git log main..origin/par/cli-observation` y de los documentos que OBS aporta.

## Caracterización

| Tipo | Nº |
|---|---|
| `test` | 20 |
| `feat` | 20 |
| `fix` | 11 |
| `docs` | 7 |
| `refactor` | 5 |
| `perf` | 3 |
| `chore` | 1 |
| merge | 1 |

**La mitad del trabajo es tests** (20 de 68). Y los mensajes de commit describen *el defecto*, no
el cambio mecánico. Ejemplos que citan una verdad concreta del dominio:

- `f4298281` — "stdout y stderr son streams durables separados, no una ejecución fundida"
- `1f619720` — "el formato de máquina, y los bytes que destruía en silencio"
- `1b711b3d` — "un drenaje que sabe distinguir completo de truncado"
- `420c1478` — "el ordinal de un frame es una sección crítica entre procesos"
- `d4086e6d` — "el fin de un run y el fin de sus bytes son dos autoridades"

Esto no es trabajo experimental. Es diseño de sistemas con vocabulario propio y maduro.

## Fases del trabajo (por fecha)

```text
07-oct  mañana  CLI: --view/--format/--channel, y los filtros que se ignoraban
07-oct  mediodía Output Plane: transcript de proceso, índice durable, streams separados
07-oct  tarde    Harness: dueño de cada proceso hijo, kills con outcome propio
07-oct  noche    Read model: posiciones, no observaciones; follower con drenado y stop
08-oct  madrugada Formatos, budgets de --limit, un outcome escrito de tres formas
09-oct  mañana   Rendimiento medido (chunk cost, ventana de fsync) y correcciones
09-oct  mediodía ADR-OBS-003: propuesta y aceptación del propietario
09-oct  tarde    Refactor: launch, aridad por tabla, scope de renderer
09-oct  noche    UAT end-to-end y sección crítica entre procesos
```

## Gobernanza que OBS YA aportó

Esto es lo que más cambia el planteamiento del todo:

| Documento | Estado en OBS |
|---|---|
| `ADR-OBS-001-live-output-authority-and-lpr011r2-supersession.md` | aceptada; supersede *en parte* los claims de `console.log` de `WU_LPR_011` |
| `ADR-OBS-002-read-recovery-ownership.md` | aceptada |
| `ADR-OBS-003-child-output-descriptor-ownership.md` | **propuesta → aceptada** en dos commits consecutivos |
| `ADR-0105-frame-index-ownership.md` | **`status: accepted`, `deciders: "Rubentxu (product owner)"`, 2026-10-09** |

Y seis receipts: `AUD04_CLI_ARGUMENT_STRICTNESS`, `OBS1_READ_RECOVERY_OWNERSHIP`,
`OBS2_LEVEL_B_INGEST_AGENT_PROTOTYPE`, `OBS2_STEP_CERT_ATTEMPT_1`, `OBSF_PUBLISHED_READ_SIDE_AND_CHUNK_COST`,
`OBSG_READER_RECOVERY_INTERFERENCE`.

### ADR-0105 responde a una pregunta abierta del roadmap

El WorkItem `f8fc07e6` (B0 reconciliación) dice textualmente: *"investigar harness real antes de
aceptar ADR-0105"*. OBS **ya lo hizo** y cerró la decisión ayer:

- **Estrategia elegida**: un escritor del índice por run, con exclusividad entre procesos.
- **Mecanismo**: `ReentrantLock` por JVM **primero**, luego `FileLock` exclusivo sobre
  `<root>/frames/<safeRunId>.authority`.
- **Por qué ese orden**: el lock JVM evita `OverlappingFileLockException` entre hilos. Y **no
  puede** degradar a "skip" como hace `SegmentOutputStore`, porque son bytes ya comprometidos:
  declinar convertiría contención en pérdida de salida.
- **Por qué `FileLock` y no heartbeat**: el kernel suelta el lock al morir el proceso. Un PID o un
  mtime son *adivinar* la vivacidad; `FileLock` es la afirmación del propio kernel.
- El ADR descarta explícitamente que un `ReentrantLock` con contador cacheado por JVM demuestre
  exclusividad global.

**Esto es respuesta medida a una pregunta que el roadmap tenía abierta.** No es código sin
gobernanza: es gobernanza que ya se escribió y no está en `main`.

## Hallazgo principal: `main` no está "al día", está **divergido**

El inventario ya decía que `main` lleva 113 commits que OBS no ve. Esta auditoría lo confirma en
la otra dirección:

> OBS no está pidiendo permiso para traducir trabajo viejo. OBS contiene ADRs aceptadas por el
> propietario **ayer**, con verificación que `main` no puede haber visto porque los commits son
> posteriores a la divergencia del 7-oct.

Por tanto el riesgo real **no** es "OBS pisa el trabajo reciente de `main`". `main` y OBS se
modificaron en paralelo, y el trabajo de gobernanza más avanzado vive en OBS.

## Qué NO se ha hecho

- No se ha mergeado, rebaseado ni cherry-picked nada.
- No se ha evaluado si `main` cambió la misma zona que ADR-0105 (eso requiere el análisis
  commit a commit de los 113 de `main`, no está hecho).
- No se ha emitido veredicto de certificabilidad del corte.

## Orden de reconciliación que estos datos exigen

El orden que propuse en el inventario **cambia**. Antes dije "resolver los 2 CLI y auditiar los
67". Con la auditoría en la mano:

1. **Las 4 ADR de OBS deben entrar primero**, no al final. Son decisiones aceptadas por el
   propietario que gobiernan el código de los 20 `feat`. Fusionar el código sin las ADR deja
   23k líneas sin autoridad normativa.
2. **`ADR-OBS-001` supersede *en parte* los claims de `console.log` de `WU_LPR_011`.** El nombre del
   fichero menciona `lpr011r2`, pero el frontmatter del ADR es más preciso: *"Supersedes, in part:
   the `console.log` claims of `WU_LPR_011_SECRET_REDACTION_RECEIPT.md` and its Gate-1 UAT surface"*.
   Hay que comprobar qué queda de esa superficie en `main`. Es una decisión de gobernanza, no un
   conflicto de texto.
3. **Los 2 conflictos de CLI** siguen siendo la decisión de producto pendiente, y ahora se ven
   desde el otro lado: los 20 `feat` de observabilidad dependen de ellos.
4. **Los 7 conflictos de test** son legados que migran; no bloquean.
5. **Los 113 commits de `main`** necesitan el mismo tratamiento commit a commit que acabo de hacer
   con OBS. Hoy son un riesgo abierto de la reconciliación, no un detalle.