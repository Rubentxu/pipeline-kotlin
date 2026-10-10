# Inventario read-only de OBS (`par/cli-observation`) — 2026-10-09

- **Rama**: `origin/par/cli-observation` @ `05eecc4b`
- **Base**: `main` @ `57774c92` == `origin/main`
- **Merge-base**: `2e9b4824` (2026-10-07, "un constructo nuevo con cero cambios en el core")
- **Estado**: **no se ha tocado nada**. Ni merge, ni checkout, ni escritura en el árbol.
  Verificado al final: `HEAD == origin/main` y el único cambio sigue siendo el blocker staged.

## Por qué no son 62 commits

La premisa de partida ("OBS va 62 commits por delante") no se sostiene. La divergencia es
**bidireccional**:

| Métrica | Valor |
|---|---|
| Commits solo en OBS | **68** |
| Commits solo en `main` | **113** |
| Ficheros tocados por OBS | 167 |
| Ficheros tocados por `main` | 216 |
| Ficheros en solape | **18** |
| Conflictos textuales reales | **10** |

`main` lleva 113 commits que OBS no ve. Esto no es "traer OBS a `main`": es reconciliar dos
líneas que han avanzado en paralelo desde el 7 de octubre. Cualquier plan que trate OBS como
un bloque traíble tiene esa premisa equivocada.

## Los 10 conflictos, clasificados

El tamaño (23.432 líneas insertadas) **no** es el problema. De 167 ficheros, solo 18 se solapan
y solo 10 producen conflicto textual. Se concentran en `pipeline-application` (85% del cambio).

### A) Infraestructura durable — 1 fichero, conflicto aparente

`v2/pipeline-events-store/.../JsonEventLog.kt`

- `main` reescribió el fichero: **+222 / −40** líneas.
- OBS añadió **+11** líneas.
- Las 11 líneas de OBS son un método **aditivo puro**:

  ```kotlin
  fun encodeOne(event: DomainEvent): String = EventJsonWriter.encodeEvent(event)
  ```

- Comprobado: `main` **ya contiene** `encodeOne` (1 ocurrencia) igual que OBS (1 ocurrencia).

**Veredicto: falso positivo de conflicto textual.** No hay contradicción semántica; OBS quedó
atrás y `main` reimplementó el mismo efecto. `git merge-tree` lo marca por proximidad de líneas.
Probablemente se resuelva solo; si no, es un `take` de 11 líneas.

### B) Producción CLI — 2 ficheros

- `MainConsoleCli.kt`
- `MainEventsCli.kt`

Conocidos de antemano: son los que soportan `--view/--format` y `ConsolePrintingEventSink`, el
trabajo característico de OBS. **Son los dos únicos conflictos con decisión de producto
pendiente.** Cada uno requiere decidir qué superficie de CLI conserva `main` y cuál gana OBS.

### C) Tests y corpus UAT — 7 ficheros

`CompatibilityCorpusTest`, `UatCompat001`, `UatDsl001`, `UatDsl003`, `UatDsl005`,
`UatEvt002`, `UatLocal008`.

Los 7 están en `v2/pipeline-application/src/test/`, que `AGENTS.md` clasifica explícitamente
como **legado que migra al harness externo** (`pipelinek-release-harness`). No son contratos de
producto: son UATs que van a morir por diseño.

**Veredicto: no bloquear el corte de OBS por esto.** Se resuelven por decisión de migración, no
por arbitraje semántico. Rechazar el corte por 7 UATs legadas sería exigir al código que
sobreviva a su propia deprecación.

## El riesgo real: reescritura, no tamaño

`git cherry` marca **67 de 68** commits como pendientes por parche. Pero el caso de
`JsonEventLog.encodeOne` demuestra que un parche puede diferir mientras su **efecto ya está
integrado**: `main` reimplementó lo mismo por su cuenta.

Esto significa que **`git cherry` sobreestima el trabajo pendiente**. El recuento fiable de
"qué falta de verdad" requiere comparar semántica por commit, no por parche. Es exactamente el
trabajo de reconciliación que pediste, y confirma que un merge masivo saltaría trabajo ya
absorbido mientras introduce 23k líneas cuyo origen nadie ha auditado.

## Orden propuesto (no ejecutado)

1. **Resolver los 2 conflictos de producción CLI** (`MainConsoleCli`, `MainEventsCli`) — decisión
   de producto, bloqueante para el primer corte certificable.
2. **Aplicar los 11 nombres de `JsonEventLog`** — verificar si el merge los toma solo.
3. **Dejar los 7 de tests** a la decisión de migración del harness; no son gates del corte.
4. **Auditar los 67 commits uno a uno** contra ROADMAP/ADR, dada la reescritura, agrupando los
   que ya estén absorbidos por efecto.

## Lo que este inventario NO autoriza

- No autoriza un merge masivo. El volumen sigue sin auditarse commit a commit.
- No resuelve el bloqueo de gobernanza documentado en `SDDK_CYCLE_PERSISTENCE_BLOCKER.md`; sin
  `evaluate-gate → transition` funcionando, el corte no puede certificarse formalmente aunque el
  código entre limpio.
- No cierra `par/cli-observation: 62 commits ahead` como ítem de backlog. La cifra correcta es
  **68 por delante / 113 por detrás**, bidireccional.