# Run #34 — `ObsPcReadRecoverySeamFitnessTest` in-VM ancla §1.4 ADR-OBS-002 read/write recovery seam fitness (Bloque E · OBS-R1 / OBS-Pc / ADR-OBS-002)

| | |
|---|---|
| **Run** | #34 |
| **Objetivo** | Anclar **ADR-OBS-002** (read path may not reach an opening that recovers) vía SOURCE-SCAN fitness — variante estructural HF5 de Run #30 aplicada a un ADR distinto. Run #34 cierra el tercer lado del "teorema de separación" entre **read / write / channel**: nadie confunde los canales (Run #30), nadie confunde query/producer (Run #33), **nadie confunde reader/writer en recovery** (Run #34). |
| **SHA verificado** | `7bcca7dd89550f058dcb386f2a7331ee04499c1b` (HEAD post Run #33, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsPcReadRecoverySeamFitnessTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~10,68 (al inicio, ventana favorable). Suite 0.958 s. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #34 cierra

El KDoc del test class —`ObsPcReadRecoverySeamFitnessTest.kt:18-57`— declara el invariante y la disciplina del ADR-OBS-002:

> *"ADR-OBS-002 — no read path may reach an opening that recovers.*
>
> *The seven OBS-1 rows are behavioural and they all pass through the real store. What none of them can say is whether the shape that made the defect possible is still **available**, because the defect was an availability, not an event:*
>
> ```kotlin
> fun storeFor(controlDirRoot: Path): SegmentOutputStore = SegmentOutputStore(root.resolve(OUTPUT_DIR))
> // ...later, inside read():
> if (!recovered && recoveryPermitted) recover()
> ```
>
> *One opener, used by writers and by `observe`/`console` alike, whose `read` reconciled on the way in. Every reader therefore had the power to truncate a live writer's reservation, and OBS-G measured exactly that: a writer confirmed 8192 bytes and 4096 survived.*
>
> *The fix splits the opening (`storeForReading` / `storeForWriting`) and adds a kernel-held ownership lock. Both halves are only worth anything if they cannot be quietly undone, and the undo is cheap: swap one identifier in one line and every behavioural row stays green, because with the ownership lock in place the reverted call is merely **destructive again**, not visibly wrong to a reader that happens to look. This file is what makes that revert red."*

**Misma disciplina HF5 fuente-scan de Run #30 (OBS-C2.3):** cuando el defecto es una **disponibilidad estructural** (no un evento), los behavioural tests no pueden distinguir "fixed" de "silently reverted". El único discriminador es nombrar la llamada prohibida y escanear el código fuente.

Run #30 ancló el source-scan para `redirectErrorStream(true)` (transcript path). Run #34 ancla el **mismo patrón** para `storeFor(... outputPlane ...)` (read/write recovery separation). La discipline es replicable.

Las tres firmas ejecutadas:

| Test | Cubre ADR-OBS-002 | Tipo | Resultado medido |
|---|---|---|---|
| `the reading opening is constructed without permission to recover` | `storeForReading(...)` no lleva `recoveryPermitted = true` por defecto; o equivalentemente, la reading-opening no reconcile | Source-scan anchored por signature + default | verde, 0.085s |
| `only the write side and retention may name a recovering opening` | Scan whole-source-tree (excluyendo `src/test/`) buscando la **recovering-opening call**; offenders por archivo con trace | Source-scan whole-tree | verde, 0.611s |
| `the Output Plane store is constructed in exactly one file` | `SegmentOutputStore(...)` aparece como constructor en exactamente 1 archivo (single constructor site) | Source-scan single-site | verde, 0.259s |

> **Disciplina HF5 del test-sources exclusion:** *"Test sources are EXCLUDED. `ObsGInterferenceProducer` legitimately opens a writing store to play the part of a crashed writer; a fitness that failed on it would train people to route around the law rather than obey it."* Es la misma auto-defensa que Run #30 (comment exclusion en OBS-C2.3). La fitness **no se testea a sí misma**, lo que evita el bug clásico de detector que se reporta como violación.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.ObsPcReadRecoverySeamFitnessTest.xml` (934 bytes, mtime 2026-10-10 02:54:54):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.durable.ObsPcReadRecoverySeamFitnessTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:54:53.365Z" hostname="bazzite-rubentxu" time="0.958">
  <testcase name="the reading opening is constructed without permission to recover()" time="0.085"/>
  <testcase name="only the write side and retention may name a recovering opening()" time="0.611"/>
  <testcase name="the Output Plane store is constructed in exactly one file()" time="0.259"/>
```

**3 tests / 0 fallos / 0 errores / 0 skipped.** Suite **0.958 s** — más lento que Run #30 (0.4 s) porque el test 2 hace un walk whole-tree buscando la `storeForWriting(...)`/`recoveryPermitted=true` call y reporta offenders por archivo. Esta es la fila cara, y es la que verdaderamente "defiende la separación read-write" — un código futuro que añada un segundo reader recuperarable lo cazaría al instante con el nombre del archivo ofensor.

`xmllint --noout` sobre el XML pasa sin advertencias. **No es un PASS por compilación:** la mutación M-OWN-4 ("drop `recoveryPermitted = false` from `storeForReading`") mataría la fila 1 con un cambio de una línea en código de producto; el fitness detecta ese cambio sin ejecutar el flujo runtime.

BUILD SUCCESSFUL in 1m 36s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

## 3. Lo que Run #34 SÍ cierra

- **§1.4 ADR-OBS-002 read/write recovery seam anclado en fitness test.** Run #30 ancló la separación channel×fusion. Run #34 ancla la separación **read×write×recovery** vía `storeForReading` / `storeForWriting` + kernel-owned ownership lock. Una sola línea de revert (cambiar el identifier en una construct call) **mataría el invariante** sin que los tests behavioral lo noten — Run #34 cierra ese gap.
- **Hexagonal-architecture-guard equivalente para recovery separation:** el argumento del KDoc ("the set of files permitted to name a recovering opening is closed, and the store has exactly one constructor site") es la **misma forma** que la hexagonal guard de Run #30 ("SDK runtime may not depend on Output Plane"). Las dos son **límites nombrados a un set de archivos**, no convenciones que los humanos recuerdan. Run #34 añade la dimensión "recovery al scope recovery-only" al HF5-estructural catalog.
- **Constructor-site scan (test 3) cierra el círculo:** "`SegmentOutputStore` se construye en exactamente 1 archivo" complementa "el reading-opening no llama recover". Las dos invariantes son **independientes** (un cambio de constructor location las puede romper por separado), pero juntas cubren el domain del ADR-OBS-002.
- **Three-way ADR-OBS-002 close en Bloque E:** Run #30 (no `redirectErrorStream(true)`) + Run #33 (channel grammar read-side, complementa) + Run #34 (no read-reach-recovery). Las tres cierran **separations** del output plane: por canal (Run #30), por query/producer (Run #33), por read/write (Run #34). El teorema Run #30+Run #33 sigue siendo válido; Run #34 ancla el tercer lado.
- **Tests-source self-exclusion self-defensiva:** del KDoc literal: *"a fitness that failed on it would train people to route around the law rather than obey it"*. Run #34 no innova sobre Run #30 — replica la disciplina, lo que confirma que el patrón es replicable sin fisuras.

## 4. Lo que Run #34 NO cierra

- **No cubre process-side ADR-OBS-002.** El test es in-VM source-scan; un binario `pipelinek console --recovery-permitted=true` o similar contra fork-pool queda pendiente si se requiere process-side.
- **No cubre el constructor-site para `SegmentOutputStore` con `:Unit` constructors** o cualquier constructor que reciba parámetros no-`Path` (la firma podría cambiar). El scan funciona porque el constructor canónico toma `(Path)`. Si se añadiera un constructor `(Path, Boolean)` con `Boolean = true` permitido, este test pasa pero la garantía de ADR-OBS-002 podría debilitarse. El KDoc declara esta limitación: *"The scan does not claim to prove `SegmentOutputStore` cannot be reached indirectly through a value passed around as a parameter."*
- **No cubre `ObsPcReadRecoveryOwnershipUatTest`** (la UAT behaviourally anchored en OBS-G run complementario). Run #34 ancla el fitness; el UAT ancla el comportamiento de `recoveryPermitted` default y `check(recoveryPermitted)` inside `recover()`. Ambos paths son **independientes y se complementan** (Run #34 = law, UAT = enforcement).
- **No cubre cross-process Gradle pool.** Test in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone.

## 5. Cierre acumulado del Bloque E tras Run #34

- **Verde acumulado in-VM**: 1619 (post Run #33) + 3 (Run #34) = **1622 tests verde sobre `7bcca7dd`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #34 añade 1, total 33 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 19 + este (Run #34) = 20.

## 6. Anchors del ADR-OBS-002 en CI

| Firma ADR-OBS-002 | Anchor | Tipo | Run | Path |
|---|---|---|---|---|
| `storeForReading(...)` se construye sin `recoveryPermitted = true` | `the reading opening is constructed without permission to recover` | Signature + default scan | #34 | in-VM (source-scan) |
| Sólo write-side y retention pueden nombrar la recovering-opening call | `only the write side and retention may name a recovering opening` | Whole-tree source-scan | #34 | in-VM (source-scan) |
| `SegmentOutputStore(...)` se construye en exactamente 1 archivo | `the Output Plane store is constructed in exactly one file` | Single-constructor-site scan | #34 | in-VM (source-scan) |

**3 nuevos anchors ADR-OBS-002 in-VM sobre `7bcca7dd`** — segunda aplicación de la HF5 variante **estructural** (tras Run #30 OBS-C2.3), esta vez sobre un ADR nombrado.

## 7. Trinidad de HF5-estructural anclada en Bloque E

| Aplicación | Anchor | Run |
|---|---|---|
| Hexagonal architecture: SDK runtime may not depend on Output Plane | OBS-C2.3 | #30 |
| Recovery seam: read path may not reach recovering opening | **ADR-OBS-002 / OBS-Pc** | **#34** |
| (potentially future: directives vs commands separation, etc.) | — | — |

Run #30 + Run #34 confirman que la **HF5-estructural variante es replicable** sobre ADRs nombrados: cada ADR que codifica un "may not X" invariante puede encontrar su fitness test en el mismo patrón (named forbidden call + scoped source-scan).

## 8. Cierre del teorema "tres separaciones"

| Separación | Anclaje | Run |
|---|---|---|
| **Channel × fusion** (no `redirectErrorStream(true)` en transcript path) | OBS-C2.3 source-scan | #30 |
| **Query × producer** (channel grammar en query sin colapsar) | OBS-D1 pure-contract | #33 |
| **Read × write × recovery** (no read-reach-recovering-opening) | **OBS-Pc / ADR-OBS-002 source-scan** | **#34** |

Run #34 cierra la trinidad Run #30 + Run #33 + Run #34. Cada Run ancla una separación distinta — juntas cubren **el set completo de invariantes de no-confusión del Output Plane** que el proyecto necesita defender mediante fitness (no behavioural). Los tres siguen el mismo patrón replicable.

## 9. Limitaciones operativas

- **Load 1m ~10 al run**: ventana favorable; suite 0.958s aguantó sin wedge.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10 para tests que fork-ean subprocess.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 10. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: 12 cross-process §6.2 siguen pendientes (load 1m ~10 favorable pero no load<5 sostenido).
2. **Run #35 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada tras #34:
   - `ObservationOperationIdShapeTest` (OBS-E3 op-id);
   - `ObservationJsonLinesTest` (OBS-E1 machine format);
   - `ObservationOutputFollowerTest` (OBS-E2 follower);
   - `ObservationWakeupTest` (OBS-D3 wakeups);
   - `ObservationOutputReaderTest` (OBS-D2);
   - `ObservationQueryTest` (HF0 read-side query);
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1622 verde sobre `7bcca7dd` es la mejor frontera defendible de sesión sin release**.
