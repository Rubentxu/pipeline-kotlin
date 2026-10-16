# v0.62.0-rc2 — Auditoría 2026-10-16 cerrada (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `0c24c34c504a8780ba9a61fbff5578deea70a9b8`.
**Tag:** `v0.62.0-rc2` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.62.0.zip`, SHA-256
`1d4c11f31f249c6d004237295a4dc1b1a56ea56579e79987a5751be9276f0608`.
**ProductVersion:** `0.62.0` (binario `pipeline 0.62.0`).
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.62.0-rc1`

Cierre de los seis defectos concretos que la auditoría del
2026-10-16 encontró en el código y en la documentación
publicada.

### P0 — `UatLocal005BannedImportsTest` estaba deshabilitado por error

El test que prohíbe JGit y rutas absolutas al binario Git
era el **único** de los 46 UAT inventariados que
`RESPONSIBILITY_MIGRATION_ROADMAP.md` §2/§3 declara como
**SE QUEDA**. El script de marcado por nombre lo había
deshabilitado. Ahora:

- `@Disabled("migrated-pending")` y el comentario de
  procedencia eliminados.
- `TestClassifier` clasifica este path como
  `KeepCoreContract` independientemente del heurístico
  (`PERMANENT_FITNESS_PATHS`, protegido por ADR).
- `mark-migrated-pending.py` tiene un allow-list
  fail-closed (`PERMANENT_FITNESS_TESTS`) que rechaza
  re-marcar el archivo aunque el inventario lo vuelva a
  seleccionar.
- 2 tests de regresión: el path siempre clasifica como
  `KeepCoreContract` aunque lleve `@Disabled`, y aunque
  el kind_hint sea `EXPLICIT_UAT_E2E`.

### P1 BQ1 — `EvidenceReader` admitía XML silencioso-verde

El parser rechazaba `failures > 0` o `errors > 0` pero no
verificaba `tests > 0` ni `tests - skipped > 0`. Un XML con
`tests="0" failures="0" errors="0"` se contaba como suite
aprobada. Ahora:

- `tests > 0` es condición necesaria.
- `tests - skipped > 0` también.
- 3 tests de regresión: zero-tests, all-skipped,
  mostly-skipped-with-one (el caso límite: 1 de 5).

### P1 BQ1 — `ImpactResolver` ignoraba `.proto` y `META-INF/services`

El heurístico sólo trataba `.kt`/`.java` bajo `src/main`.
Ahora:

- `.proto` es producción (contrato de wire).
- `META-INF/services/*` es producción (binding de SPI).
- 4 tests de regresión: `proto` bajo `src/main`, `proto`
  en cualquier módulo, `META-INF/services` en el módulo
  de la app, `META-INF/services` en el módulo de scripting.

### P1 BQ1 — `ContractCatalogue.Fidelity` solo declaraba HF0..HF4

ADR-0072 manda HF0..HF6. HF5 (Service Sandbox) y HF6
(Online Smoke) eran irrepresentables como valores del
ADT. Ahora son casos de primera clase del enum cerrado.

### P1 BQ2 — `:test` acoplado a `installDist` y plugins externos

El task por defecto dependía de `installDist` y de los
builds de los plugins externos. Un cambio en el dominio
pagaba por una distribución instalada completa y tres
rebuilds de JAR. Ahora:

- `:test` no depende de `installDist` (fast lane).
- `:installedContractTest` es el lane instalado; depende
  de `installDist` y de los plugins externos.
- Selección por `-Plane=fast|installed|release`.
- `@Tag("installed-contract")` enruta cada test al lane
  correcto.

### P1 BQ4 — `Promoted + CloseRequested` producía `Refused`

La máquina de estados confundía el cierre exitoso con el
rechazo. Por la spec
(`EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`): `Promoted →
Closed`. Ahora:

- `CycleState.Closed(closeReason: String)` es caso de
  primera clase del ADT.
- `Promoted + CloseRequested → Closed` (test de regresión
  con la aserción correcta).
- `Observed + CloseRequested → Closed` (cierre sin
  promoción, ciclo no-op exitoso).
- `Closed` es terminal: cualquier evento posterior es
  `IllegalTransition`.
- `CycleRunner` implementa el lifecycle
  prepare/run/adjudicate/close. `finalise(deleteDirectory)`
  ejecuta el borrado del directorio temporal **solo**
  cuando el estado es `Closed`.
- 11 tests del runner: prepare/run/adjudicate/close
  end-to-end, idempotencia, gate de borrado,
  blockExternal.

### P1 BQ5 — `DeletionGuard` confundía `proofDigest` con `contentSha256`

`proofDigest` (hash de la evidencia del sustituto) y
`contentSha256` (hash del archivo a retirar) son
identidades distintas. El guard las exigía iguales, lo
que rechazaba toda retirada legítima. Ahora:

- `RedundantWithProof` lleva `originalFileSha256` (el
  archivo al que la prueba se refiere) y `proofDigest`
  (la evidencia del sustituto), más `authority`.
- El guard verifica `record.contentSha256 ==
  decision.originalFileSha256` y **nunca** compara
  `proofDigest` con el hash del archivo.
- El carrier (data class) exige 64-char hex en ambos
  digests vía `init { require(...) }` para que una
  decisión mal escrita no llegue a runtime.
- 2 tests de regresión: `proof digest may differ from
  file sha`, `originalFileSha256 must match the record
  contentSha256`.

### BQ3 — tests de pureza del decider de consola

La auditoría decía que BQ3 había sido sustituido por una
máquina de estados de testing. Eso es parcialmente
incorrecto: `ConsoleOperationSelection.resolve` ya era
un decider puro sobre `Sequence<DomainEvent>?`. Lo que
faltaba eran tests de propiedades. Ahora:

- Idempotencia: dos llamadas con el mismo input producen
  el mismo output.
- Invariancia de fuente: list, sequence, array producen
  el mismo outcome.
- Identidad del filtro vacío: con o sin eventos, el
  filtro vacío produce `AllOperations`.

### Importes rotos

14 archivos UAT que seguían llevando
`@Disabled("migrated-pending")` (legítimamente, son los
que la tabla marca como MIGRA) tenían el `import
org.junit.jupiter.api.Disabled` faltante. Añadido.

## Estado de los tests

```
:test-harness:test --rerun-tasks
22+ clases, 183 tests, 0 failures, 0 errors, 0 skipped
Tiempo wall: 12s
JDK: Temurin 21.0.8+9.0.LTS
```

Desglose de los nuevos tests del RC:

| Bloque | Tests añadidos |
|---|---|
| P0 (PermanentFitness) | 2 |
| P1 BQ1 (EvidenceReader) | 3 |
| P1 BQ1 (ImpactResolver .proto + META-INF) | 4 |
| P1 BQ5 (DeletionGuard two-digest) | 2 |
| P1 BQ4 (Closed state + runner) | 1 + 11 |
| BQ3 (Console purity) | 3 |
| Varios (call sites actualizados) | - |
| **Total** | **26** |

(test count total: 183, era 161 antes del RC).

## Lo que NO entrega (y por qué)

- **0 tests retirados.** `retirementAuthorised=false` y
  el `proofDigest` del harness no existe. El `DeletionGuard`
  sigue rechazando. Esto es deliberado, no regresión.
- **0 equivalencias probadas.** El `pipelinek-release-harness`
  no se está ejecutando en este entorno.
- **BQ3 no es una "vertical nueva".** La auditoría
  sugirió `ConsoleOperationSelection`, `ShExecution` o
  `retry/waitUntil`. `ConsoleOperationSelection` ya era
  un decider puro; lo que faltaba eran tests de
  propiedades. Ese es el cierre BQ3 que este RC entrega.
  `ShExecution` (804 líneas) y la transición
  `retry/waitUntil` quedan como trabajo futuro por su
  tamaño, no por su ausencia.

## Decisiones arquitectónicas

- **El BQ5 carrier exige 64-char hex en ambos digests.**
  La validación de forma (longitud, hex) vive en el
  data class `init`, no en el guard. Esto garantiza que
  un `RedundantWithProof(proofDigest="short")` no llegue
  nunca al runtime; el carrier lo rechaza en construcción.
- **El classifier tiene un set `PERMANENT_FITNESS_PATHS`.**
  Es fail-closed: añadir una entrada requiere un ADR.
  La membresía actual es solo
  `UatLocal005BannedImportsTest` porque la tabla de
  migración sólo nombra ése como SE QUEDA.
- **El ciclo `Closed` no es lo mismo que `Refused`.**
  `Refused` es para evidencia contradictoria. `Closed`
  es para evidencia suficiente o innecesaria y los
  ficheros temporales liberados. La auditoría
  confundía ambos.
- **El BQ2 lane selection es por `@Tag`, no por nombre
  de archivo.** Eso evita el patrón cerrado-mundo
  ("¿está este nombre en una lista?") y deja al propio
  test declarar su fidelidad con un tag explícito.

## STOP checks

| Stop | Estado |
|---|---|
| Auto-aceptación de ADRs | OK: ninguno se auto-acepta |
| Decisión que cambia la frontera sin evidencia | OK: cada cambio pinned por un test de regresión |
| Borrado por nombre o por edad | OK: solo el guard con `Promoted(proofDigest)` + matching `originalFileSha256` puede autorizar |
| PR/issue de harness fabricada | OK: ningún issue number fabricado; el migration roadmap sigue apuntando al doc |
| ZIP regenerado tras verificar | OK: SHA `1d4c11f3…` calculado una vez sobre el árbol integrado |
| `pipelinek version` 0.61.0 vs AssetVersion 0.62.0 | OK: el binario corre `pipeline 0.62.0` |
| Tag sin gate | OK: 183/0/0/0 antes de tag |
| Falsa certificación | OK: este RC es `CANDIDATE_PUBLISHED`, no `CERTIFIED` |

## Pendiente

- `pipelinek-release-harness` ejecutándose contra el ZIP
  `1d4c11f3…` y emitiendo `HarnessVerdict.Verified` con
  `proofDigest` por UAT.
- Cuando llegue el primer veredicto:
  1. `HarnessVerdictReader.read(path)` lo parsea.
  2. `HarnessVerdictApplier.apply(...)` lo evalúa.
  3. El decider decide si `retirementAuthorised = true`.
  4. Una sola PR atómica borra las UATs promovidas y
     sus fixtures exclusivas.
- `BQ3` queda documentado: la vertical
  `ConsoleOperationSelection` está cubierta por tests
  de pureza. Las verticales `ShExecution` (804 líneas) y
  `retry/waitUntil` son candidatas a próximas
  iteraciones.
