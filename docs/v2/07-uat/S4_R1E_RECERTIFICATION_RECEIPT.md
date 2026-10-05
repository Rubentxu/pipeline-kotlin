# S4 R1-E — Recertificación sobre el árbol vivo

**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` (RP7-SEM S4)
**Estado anterior:** `IMPLEMENTED` · **Base del recibo previo:** `92151de835dba25b05cc77a5cf7dd0aa58eb638b`
**Certificado sobre:** `c32fa1e13100916f6cdf7729b09784d1bde5b877`
**ADR:** `ADR-0103` RPL-2 / RPL-3 / D6 · **Recibo previo:** `S4_R1_E_RECOVERY_AND_IDENTITY_EPOCH.md`

> Por la Certification Law §10 un recibo es evidencia de su propio SHA y hereda nada. El recibo de
> R1-E declaraba una base 79 commits atrás, con 17 commits tocando el árbol de replay/recovery
> después. Este documento no reutiliza sus cifras: las vuelve a medir sobre `c32fa1e1`.

## 1. Por qué hacía falta recertificar, y no sólo re-ejecutar

Dos razones, y la segunda es la que no aparece en el recibo anterior.

**La deuda de SHA.** `92151de8` → `c32fa1e1` son 79 commits, 17 de ellos sobre
`application/durable/` o `sdk/runtime/durable/`. La evidencia de R1-E era cierta cuando se escribió
y dejó de serlo.

**Un bloqueo de auditabilidad que ningún recibo registraba.** `BodyStructureDigest.kt` —la defensa de
WU-RP-035 contra *silent reuse*— contenía cinco bytes NUL crudos y `InMemoryOperationJournal.kt`
uno más. `read` se negaba a abrir el primero ("NUL bytes detected") y `grep` reportaba
"coincidencia en fichero binario" suprimiendo las líneas. Un fichero que sostiene los exit criteria
1, 2, 6 y 8 **no se podía leer con tooling normal**, y por tanto no se podía auditar. Corregido en
`c32fa1e1` con escapes, y la corrección es demostrablemente neutra: los `.class` salen con el mismo
SHA-256 que antes (`fcb8544b…`, `e289f3e8…`), y el `distZip` de 91 MB conserva exactamente el mismo
hash (`d1945889…`).

## 2. Gate sobre el SHA certificado

```text
ARGV      cd v2 && ./gradlew check --rerun-tasks --continue --console=plain
SHA       c32fa1e13100916f6cdf7729b09784d1bde5b877
START     2026-10-05T08:35:31Z
BUILD     SUCCESSFUL in 28m 15s
TAREAS    329 actionable tasks: 329 executed
GATE_EXIT 0
LOG       /var/home/rubentxu/.local/state/pipelinek-gates/gate7.log
```

```text
27 módulos · 732 suites · 4873 tests · 0 fallos · 0 errores · 140 skipped
0 ocurrencias de "uses this output" / "implicit dependency"
:pipeline-architecture-tests:test ejecutada (1 vez en el log)
732 XML, CERO anteriores al arranque del gate
```

Los conteos son **idénticos** a los de gate6 (`5a5a56e9`): el cambio de escapes no movió un solo
test. El recuento usa glob **recursivo**: uno plano desde `v2/` no baja a `pipeline-step-sdk/*` y
subcuenta 555 tests.

**T3 — distribución instalada**

```text
ARGV      ./examples/run.sh
BIN       sha256 225a61dbdf61a20a58292803688a1444f85337eae8147f2938db118b7dc97976
DISTZIP   sha256 d19458894c4f183f0befd0f297b9b7f794dd463eab426662d068f44c3c1754fb
LOG       /var/home/rubentxu/.local/state/pipelinek-gates/t3-c32fa1e1.log
T3_EXIT   0    10/10 fixtures
```

**Consumer externo**

```text
ARGV      v2/gradlew -p examples/fabric-contract-consumer --console=plain \
            -PsdkRepo=v2/build/sdk-repo -PsdkVersion=0.47.0 check --rerun-tasks
14 tests · 0F · 0E · 0S · ts 2026-10-05T09:06:47Z
```

## 3. Los ocho exit criteria, uno a uno

| # | Criterio | Veredicto | Evidencia mecánica |
|---|---|---|---|
| 1 | `ExternalSubprocess+RUNNING` inobservable nunca alcanza `Execute` | **CUMPLE** | `S4RecoveryRequiredNeverExecutesTest` · fila `no required recovery ever resolves to Execute, whatever the observer says` |
| 2 | root null ≠ op dir ausente; `LOST` no para falta de observabilidad | **CUMPLE** | spike `row 4` (exclusiva de M-REC-2) + `Unavailable` es un `data class` con `UnobservableCause` cerrado, no un sentinel |
| 3 | una sola autoridad de reconciliación | **CUMPLE** | `StepReconcilerL1`; 4 consumidores. Fitness `only the reconciliation authority ever asks for a replay decision` |
| 4 | no se duplica `DefaultEffectReplayPolicy` | **CUMPLE** | una sola definición (`EffectReplayPolicy.kt:77`); el resto son instanciaciones. Fitness `SingleDurableAuthorityFitnessTest:135` |
| 5 | sin tercer protocolo scripted ni segunda tabla de statuses | **CUMPLE** | cero `ScriptedProtocol`/`*Status` en producción. Fitness `the scripted surface never reads a durable status to decide` |
| 6 | el fingerprint usa políticas declaradas, no `MEMOIZED` fijo | **CUMPLE** | los `MEMOIZED` en producción son declaraciones en descriptores. Fitness `no scripted surface hardcodes a replay policy`. `BodyStructureDigest` ahora auditable |
| 7 | M-REC-1/2/3 matan independientemente | **CUMPLE** | §4, re-ejecutado sobre este árbol |
| 8 | handler/execution/process launch = 0 con recovery no concluyente | **CUMPLE** | `S4RecoveryUnobservableFailsClosedTest`: `a required recovery with no substrate runs nothing, moves no cursor, and leaves the row RUNNING` |

## 4. Criterio 7 re-ejecutado: las tres mutaciones y su atribución

Todas sobre `RunningSubprocessRecovery.observe`, que es donde vive la decisión del observador.
Conjunto testigo: `S4RecoveryUnobservableFailsClosedTest` + `S4RecoveryRequiredNeverExecutesTest` +
`S4RRecIndeterminateEffectSpikeTest` = 17 filas.

```text
baseline  sha256 8e569a9e1aa1a1fc66b6a5a7e213ae83de9ce0efa0a2948290ca67aa03822705
          BUILD SUCCESSFUL in 1m 11s · 0/17 RED

M-REC-1  sha256 b9866421cce3f29f4c476f55a0c5b6fdb4c544eb9be829938b9e886fb7179c40
         BUILD FAILED in 1m 11s · 3 RED
M-REC-2  sha256 0a46b111db43f551057877f1e9013552e582129c31a0d41c5aaf785d7fa88ef9
         BUILD FAILED in 1m 10s · 1 RED
M-REC-3  sha256 9088adb5b2c5f662d32b79384df74f6b4ec9bdb5b851df0e2b5a281d9088f632
         BUILD FAILED in 1m 12s · 3 RED

restauración verificada tras las tres: sha256 = 8e569a9e…  ·  git diff vacío
```

| # | Mutación | Filas que vira | Exclusivas |
|---|---|---|---|
| M-REC-1 | sin control root ⇒ `Observed(DurableTaskTerminal.Lost)`: la fila 3 pierde su carrier y quema la fila del journal | spike `row 3`, `row 3b`, `no-substrate` | 0 |
| M-REC-2 | `Classification.Lost` ⇒ `DurableTaskTerminal.LaunchFailed`: "miré y no había nada" se vuelve "el lanzamiento falló" | spike **`row 4`** | **1** |
| M-REC-3 | sin control root ⇒ `Observed(Exited(0))`: el observador que no pudo mirar responde como si hubiera visto un éxito | spike `row 3`, `row 3b`, `no-substrate` | 0 |

**M-REC-2 es la que prueba la independencia, y la prueba.** Vira `row 4` y **sólo** `row 4`: las
filas 3 y 3b, que la mutación no toca, siguen verdes. Eso es exactamente lo que el recibo de
S4-R-REC declaraba ("Testigos: 1, atribución 1:1") y lo que un control que cubriera parte de lo que
dice cubrir no podría producir.

M-REC-1 y M-REC-3 comparten atribución, y es lo correcto: **atacan la misma rama** (el ancla
`controlDirRoot == null`) con dos respuestas equivocadas distintas —terminalizar como perdido, o
declarar éxito— y las mismas tres filas las detectan. Que dos mutaciones del mismo brazo coincidan
no es un colapso de cobertura; es que el brazo tiene un solo carrier y dos formas de mentir.

13 de las 17 filas no las vira ninguna mutación válida. Son filas de caracterización, y que lo sean
es información: el criterio 7 afirma que estas **tres** decisiones están protegidas, no que cada una
de las diez filas del spike esté atada a una mutación.

## 5. La corrección que este bloque tuvo que hacerse a sí mismo

La primera ejecución del arnés reportó que las tres mutaciones viraban **las mismas** filas y que el
criterio 7 no se cumplía. **Era falso, y el defecto estaba en el arnés.**

M-REC-2 usaba `DurableTaskTerminal.Failed`, que no existe: los casos reales son `Exited`,
`LaunchFailed`, `Lost` y `Cancelled`. La mutación no compilaba, `BUILD FAILED in 17s`,
`:pipeline-application:test` no arrancó, y el arnés leyó el **XML de la mutación anterior** de
disco. Tres mutaciones idénticas en el informe eran un artefacto de mi propio lector.

Es la misma clase que los tres `UP-TO-DATE` del recibo B2 y que la lectura de XML rancios que ya
documenté dos veces en esta sesión, vista desde el lado del arnés: **una evidencia que no se
comprueba lee como una evidencia correcta.** El arnés ahora exige que `:test` haya arrancado y que
no haya líneas `e: ` antes de atribuir una sola fila, y marca `INVALID` en vez de atribuir cuando
no puede. La mutación se corrigió a `LaunchFailed`, que es el análogo real de "LOST pasa a FAILED"
en el vocabulario vigente.

## 6. Veredicto

`IMPLEMENTED` → **`CERTIFIED` sobre `c32fa1e1`**, con los ocho exit criteria verificados
mecánicamente y el criterio 7 respaldado por mutaciones re-ejecutadas y atribuidas 1:1.

**Lo que este recibo NO dice.** No dice que el árbol no pueda volver a derivar: `c32fa1e1` certifica
lo que contiene hoy, y un commit posterior sobre `application/durable/` devuelve R1-E a
`CERTIFIED_AT_OLD_SHA` hasta que se re-ejecute. No dice nada del PRODUCT-GATE, que sigue
`BLOCKED_EXTERNAL` desde `754ddda0`. Y no sustituye al gate: el gate es `gate7.log`, este recibo lo
cita y no lo reproduce.
