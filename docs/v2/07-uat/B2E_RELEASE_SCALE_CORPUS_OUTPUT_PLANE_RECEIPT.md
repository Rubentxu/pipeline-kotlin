# B2e — El corpus release-scale vuelve a mirar donde están los bytes

**Rama:** `s4-a1b-scripted-shell-spine` · **Base:** `4f82fd3a` · **Estado:** CERRADO (commit atómico)
**Alcance:** 2 ficheros de test + 1 helper + el vocabulario de retención de `pipeline-output`

---

## Por qué este bloque existe

El gate de release (el que **no** lleva `-PexcludeSlowTests`) encontró **14 REDs** que ningún gate
diario había visto jamás. No eran un defecto nuevo: eran deuda que M1 dejó al entrar y que el gate
diario, por construcción, no podía encontrar.

Las tres clases marcadas `@Tag("release-scale")` están filtradas cuando
`excludeSlowTests` está **presente** por presencia de propiedad
(`v2/pipeline-application/build.gradle.kts:130-138`). El gate diario la pasa. El gate de release no.
Son **59 tests E2E sobre el binario instalado** que sólo corren cuando alguien se acuerda.

> El gate no estaba mal: el gate *anterior* no cubría lo que el siguienteIBA a cubrir. Eso se
> arregla cerrando los REDs, no reescribiendo la política de tags.

## La causa raíz, medida

Los 14 leían la salida de proceso de `sh` desde `EchoOutputCaptured`. ADR-M1 D2 deja de permitirlo:
en la ruta durable, esos bytes tienen una sola autoridad, el **Output Plane**. El canal de eventos
lleva hechos semánticos.

Verificado **empíricamente sobre la distribución instalada**, no leyendo código:

| fixture | exit | `EchoOutputCaptured` | bytes en el Output Plane |
|---|---|---|---|
| 24 | 0 | **0** | `{"name":"alice","age":30}` |
| 25 | 0 | **0** | `name: pipelinek` … `- unzip` |
| 26 | 0 | **0** | `build/utils/scan/a.txt`, `…/sub/c.txt` |
| 27 | 0 | **0** | `one` / `two` / `three` |
| 29 | 0 | **0** | `alpha` / `beta` |

Los cinco pipelines **salían con 0**. Los pipelines no estaban rotos; los lectores apuntaban a un
canal que ya no lleva salida. Y `pipeline console --control-dir … <runId> <opId>` devolvía los 25
bytes exactos del fixture 24: la superficie productiva respondía.

## Los 14 = 5 + 9, y el 9 tenía una segunda causa

Correspondencia 1:1, verificada por grep: los 5 usos de `EchoOutputCaptured` en
`CompatibilityCorpusTest` y los 11 `stdout.contains(...)` de `UatLocal008CredentialsTest` con sus
9 tests.

El primer parche (re-apuntar todo al Output Plane) compiló y arregló 2 de 14. Los otros 12
descubrieron **dos** mecanismos distintos:

### (a) `sh` legítimamente silencioso — fixtures 26, 27, 29

Un pipeline real ejecuta `sh` cuyo propósito es el efecto: `rm -rf`, `mkdir -p … && echo x > f`.
Esos **no abren stream** (medido en B2a). El accessor estricto lanzaba, culpando al lector de un
paso que se comportó exactamente como se escribió.

Solución: un accessor **nuevo**, `transcriptsOfWrittenSteps`, que omite lo que no escribió. El
estricto queda intacto para quien afirma por paso. No se abre paso-por-cero: un plano vacío o mal
apuntado da agregado vacío, y `contains("marcador")` sigue fallando.

### (b) Paso anidado: el `opId` no viaja en el evento — los 9 de credenciales

Cada `sh` de esa clase corre dentro de `withCredentials`, luego su operación se indexa como
`run-s0-0-bp1-0:core.sh`. **Medido**: `StepStarted` publica `stageIndex`/`stepIndex`/`stepName`/
`stepType` y nada que nombre la operación. El reader reconstruía `run-s0-0` → refusal.

**Medido también**: el journal **sí** registra ese `op_id` literal, con body path, indexado por
`run_id`. Es decir: la identidad existe y es consultable, y **ningún schema necesitó cambiar**.

```
sqlite> SELECT op_id, run_id FROM operation_journal;
1bc1cb8a-…-s0-0-bp1-0:core.sh  |  1bc1cb8a-abef-4dcc-9083-60bd437e25b1
```

Solución: `transcriptsOfRun` **pregunta** al journal en vez de **reconstruir** la convención. La
alternativa (componer el `OpId` desde el evento) era una segunda copia privada de la convención de
body-path del motor: habría que conocer anidamiento, índices de rama y plugin step id, y sería
incorrecta la primera vez que cualquiera de esos cambiara.

Enumerar el directorio del plano no era opción: el nombre en disco es
`safe(runId)_safe(opId)_transcript` y `safe()` pliega `/` sobre `_` de forma **irreversible** — el
mismo argumento por el que B2b retiró `streamsOf`.

## El bug que costó encontrar: WAL

Con la consulta ya correcta, los 9 seguían rojos con *"the journal records no operation for run
'X'"* — sobre un journal que el propio diagnóstico demonstraba que **sí** tenía la fila, con el
`run_id` exacto.

Causa: el journal corre en `journal_mode = WAL` (`SqliteConnectionFactory.kt:40`). Una conexión
abierta con `DriverManager` a secas lee **otra vista** del fichero que la que el writer commiteó.
No era un problema de SQL ni de binding de `?` (verificado en Python contra la misma BD: ambos
paths devuelven la fila).

Corrección: usar `SqliteConnectionFactory`, que es **la autoridad** de cómo se abre esa base, en
lugar de ensamblar una conexión propia y heredar un snapshot distinto. Un test aislado pasó en 12 s
con ese único cambio, lo que aísla la causa.

Éste es exactamente el fallo que un merge mecánico habría discovered *después*, como 9 REDs sin
explicación.

## Corrección propia: nada de `!!`

`requirePage` pasó a devolver `String?`, y el accessor estricto necesitaba un `!!` para compilar.
Eso fabrica el valor que la ley prohíbe. Se sustituyó por un ADT sellado
`OperationRead.Bytes | Absent`: añadir un caso futuro convierte el `when` en error de compilación
en vez de dejar que alguien escriba un `!!` que invente un transcript.

## La autoridad duplicada que sí era de la rama

La auditoría de una-autoridad-por-concepto encontró que el vocabulario de retención introducido en
B2b llevaba `outcome: String` en `RunLifecycle.Terminal` y en
`OutputPruneIntent.RunReachedTerminalState`.

`RunOutcome` ya tiene un dueño, en `:pipeline-domain`, módulo del que `pipeline-output`
deliberadamente **no** depende — así que ningún fitness habría pillado la deriva. Una autorización
de poda **no necesita** el outcome: `RunTerminalPlus` pregunta una sola cosa, "¿terminó el run?", y
la respuesta es igual para `Success`, `Unstable` y `Failure`. Se eliminó el campo; el ciclo de vida
dice lo que la retención necesita y nada más. 9 sitios de test actualizados.

## Evidencia

| gate | resultado |
|---|---|
| `CompatibilityCorpusTest` | **30/30**, 0F/0E/0S, XML 18:49 |
| `UatLocal008CredentialsTest` | **27/27**, 0F/0E, 1 skip preexistente, XML 18:55 |
| `:pipeline-output:test detekt` | **46/46**, 0F/0E, XML 18:57, `BUILD SUCCESSFUL`, `^e: 0` |
| gate de release previo | `BUILD FAILED in 28m 38s` — 14 REDs, único fallo `:pipeline-application:test` |

**Corrección de un recuento previo mío**: el log de release contiene 19 líneas `FAILED`, pero 8
son tests **PASSED** cuyo nombre contiene la palabra ("… plus FAILED journal outcome returns
RERUN() PASSED"). Los REDs reales fueron **14**, no 19.

## Lo que NO se cambió

Los otros tres hallazgos de la auditoría —dos tipos `StageOutcome` con vocabularios que se
contradicen, `Unstable` con tres status durables distintos, y `scm.git` emitiendo stdout a un
evento semántico— son **preexistentes en `main`** (blame `429c6088`, `a531dd95`, `d011b4bec`,
`5fcaf2f16`). El merge no los empeora y no pertenecen a este bloque: se registran como deuda
documentada, no se "arreglan" de paso.

**Ningún aserción se relajó, ningún test se desactivó, ningún módulo se excluyó del gate.**
