# L378 — UAT efímeras mínimas: promoción, retiro y el defecto que encontró

**Caja:** `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md:378`
**Commits:** `6533b1a2` (defecto de reproducibilidad), `0eff0269` (fila R11 del arnés del instalador)
**Fidelidad:** el E2E y los AAT son efímeros y se executed una vez; la única garantía permanente
que sale de aquí es R11, que sí lleva su mutación 1:1.

---

## 1. Lo que la caja pedía, y lo que se hizo

> UAT de checkout limpio que ejecute el test afectado; E2E único que instale el ZIP y ejecute un
> pipeline pequeño; AAT de asset faltante/extra. Promover fitness de lanes y contrato exacto de
> assets; borrar los scripts/fixtures efímeros después de resultados observados.

Las tres categorías se ejecutaron. La cuarta parte —"borrar los efímeros"— no aplica: no se
escribió ningún script ni fixture efímero en el repositorio. El E2E y los AAT se compusieron
con herramientas ya presentes (`scripts/install-pipelinek.sh`, un servidor loopback real, y el
propio `pipelinek`), y sus artefactos vivieron bajo `/tmp`.

## 2. E2E: los bytes de la candidata como consumidor

Instalado el ZIP de la distribución en un `PIPELINEK_HOME` nuevo, y después usado:

```text
install 0.62.0        -> versions/0.62.0 presente, nada fuera de él
pipelinek version     -> 0.62.0   (identidad exacta exigida por PRODUCT IDENTITY LAW)
validate              -> correcto
run (pipeline de 2 pasos) -> 6 plugins externos descubiertos
                        Pipeline finished with SUCCESS
```

Ese es el `NOT_RUN` que R-BUILD-08 y R-BUILD-09 dejaron abierto — *"probar los mismos bytes como
consumidor"*— y queda ahora ejecutado. **No** queda executed un *publish* real: eso sigue siendo
`NOT_RUN` y es una UAT de autorización externa, no un cambio de código.

## 3. AAT: asset faltante / asset extra, y por qué las dos direcciones importan

Se probaron las tres semánticas opuestas contra el mismo instalador:

| Escenario | Resultado | Fila permanente |
| --- | --- | --- |
| SHA256SUMS nombra el asset con digest **incorrecto** | rechaza, no instala nada | R2 (pre-existente) |
| SHA256SUMS **no nombra** el asset (pero trae otro) | rechaza, no instala nada | **R11 (nueva)** |
| SHA256SUMS trae un asset **extra** no solicitado | instala | R1/R3 (pre-existente) |

La tercera fila es la que hacen relevante la segunda: sin un asset extra presente, *"no hay
entrada para lo que pido"* y *"el manifiesto está vacío"* serían indistinguibles, y una
implementación que sólo mirara `grep -c` pasaría ambas.

**Promoción.** R11 entra al arnés permanente porque la cobertura era genuinamente inexistente y la
mutación es 1:1. **Retiro.** Una fila adicional para "un mirror en loopback se admite" se escribió
y se eliminó antes de commitear: la exención de loopback en `is_loopback_host` la comparten las
quince filas que usan mirror, así que cualquier mutación dirigida a ella mata 15 de 16 en lugar de
una. Una afirmación comportamental que no puede cargar su propia mutación no se promueve
(HARNESS FIDELITY §5); se conserva como característica documentada del instalador, no como fila.

**Mutación de R11** (el `die` por entrada ausente pasa a `expected_digest=""`):

```text
15/16 passed
  FAIL  test_r11_manifest_without_entry_for_asset_refuses
  PASS  test_r2_digest_mismatch_refuses_and_installs_nothing   <- control
```

R2 es el control que demuestra que la mutación apunta a la vía *entrada ausente* y no a la vía
*digest incorrecto* en general. Estado final del arnés: **15/15**.

## 4. El defecto material que encontró el E2E

Al comparar el ZIP instalado contra uno reconstruido en un worktree limpio appeared una diferencia
de digest que no era el commit (el `buildCommit` embebido en `pipelinek-version.properties` cambia
legítimamente entre commits). Controlado en el mismo worktree limpio y el mismo commit, dos builds
—uno cacheado, otro `--rerun-tasks --no-build-cache`, 89/89 tareas— dieron **ZIP byte-idéntico**
(`9d641dc5…`). Así que `distZip` sí es reproducible desde checkout limpio, y la diferencia real
estaba en otro sitio: los JAR de los plugins de ejemplo.

Causa raíz, leída en el código y no inferida: `compute<Name>Release` recorre `resourcesDir` para
hashear, y el manifiesto `META-INF/pipelinek/plugin-manifest.json` vive ahí y **contiene el propio
`releaseDigest`**. Incluirlo es una ecuación de punto fijo sin solución. `example-directive-plugin`
ya lo excluía; **`example-uppercase-plugin` y `example-block-plugin` no**, y los tres se distribuyen
dentro del ZIP.

Medido, revirtiendo cada fichero a su forma previa y construyendo dos veces seguidas sobre el mismo
commit y el mismo worktree:

```text
plugin     pre-fix build 1        pre-fix build 2        deriva
uppercase  ab53b40e9fda52861...   26782dbe54b22f7d0...   SI
block      7639321f309f07b5...    24d2a1cb598b14d9...   SI
```

Con la exclusión aplicada, cada plugin produce un solo SHA entre dos builds (`uppercase
c67d3c87…`, `block 7be81f90…`). Se aplicó la misma forma ya aceptada en el hermano, con el mismo
comentario explicando el porqué.

Esto importa por contrato: AGENTS.md fija la identidad de la candidata en el SHA-256 del material, y
el PRODUCT-GATE exige `distZip` reproducible. Dos de los tres plugins distribuidos tenían un digest
que era función del historial de builds y no del commit.

## 5. Garantía permanente y su mutación

`DirectivePluginShaReproducibilityTest` **conserva su nombre**: cinco referencias vivas apuntan a él
(dos comentarios en build files, un comentario en el build script, un KDoc en el contract suite), y
renombrar habría revuelto cuatro archivos para no comprar garantía adicional. Lo que se amplió es el
alcance: una fila por plugin, resueltas **por nombre** y no por índice de lista, para que reordenar
la lista no pueda reapuntar en silencio una fila a otro plugin.

```text
tests=3 failures=0 errors=0 skipped=0     (70 s, XML fresco)
  PASS  directive plugin JAR SHA is identical across two --rerun-tasks builds
  PASS  uppercase plugin JAR SHA is identical across two --rerun-tasks builds
  PASS  block   plugin JAR SHA is identical across two --rerun-tasks builds
```

**Mutación** (revertir la exclusión de `uppercase`):

```text
tests=3 failures=1
  PASS  directive   <- control
  FAIL  uppercase
  PASS  block       <- control
```

Una sola fila cae. Es la atribución 1:1 que exige HF §5; un blanket rojo sólo habría probado que el
arnés estaba rojo.

## 6. Riesgo residual

- **Un publish real sigue sin ejecutarse.** Endurecer el publicador nunca se ha corrido contra
  GitHub. Es `NOT_RUN` y depende de autorización externa.
- **La reproducibilidad se midió para el mismo commit en el mismo worktree.** No se midió el caso
  "dos worktrees distintos, mismo commit" para estos dos plugins; el mecanismo es el mismo (el
  manifiesto auto-referencial) pero la medición literal es la de dos builds consecutivos.
- **La fila R11 depende del arnés loopback.** La exención de loopback es la que hace hermético el
  arnés completo; si algún día cambia, las quince filas se caen juntas. Es el coste conocido de esa
  decisión y no se ha tocado.
- **`pk-rc2/v2/...` contiene una copia del test**, anterior a esta ampliación. No se tocó: es un
  snapshot de candidata publicada.

## 7. Informe de verificación (delivery-first-testing)

```text
Changed:
- examples/example-uppercase-plugin/build.gradle.kts   — excluye plugin-manifest.json del digest
- examples/example-block-plugin/build.gradle.kts       — ídem
- .../DirectivePluginShaReproducibilityTest.kt         — una fila por plugin, resuelta por nombre
- scripts/test_install_pipelinek.py                    — R11 (manifiesto sin entrada para el asset)

Affected SUT:
- la identidad de la candidata (el digest del ZIP y el de cada plugin que distribuye)
- la frontera de autoridad de digest del instalador

Verification executed:
- RED     uppercase y block, forma previa, dos builds cada uno -> deriva medida en ambos
- GREEN              tres filas del arnés, 3/3, 0 skips, XML fresco (no BUILD SUCCESSFUL)
- mutación           revierte uppercase -> 3 tests, 1 failure; directive y block verdes
- arnés instalador    15/15; mutación de R11 -> 15/16 con R2 verde como control
- E2E                install + version + validate + run de 2 pasos sobre el ZIP -> SUCCESS

Evidence reused:
- la medición de reproducibilidad de dos builds limpios del mismo commit (9d641dc5 dos veces),
  del apartado anterior de este mismo ciclo.

Verification deliberately not executed:
- publish real contra GitHub (autorización externa)
- suite completa `check` — el cambio toca build files de ejemplos y un test; ninguna clase de
  producción cambió, y la escalera dice L2, no L5.

Unknown impact:
- otros consumidores del `releaseDigest` más allá del ZIP y del arnés.

Result: PASS
Full verification required now: NO
```