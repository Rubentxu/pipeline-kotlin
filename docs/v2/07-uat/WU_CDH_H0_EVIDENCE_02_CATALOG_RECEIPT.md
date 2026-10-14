# WU-CDH-H0-EVIDENCE-02 — el reporter declara qué debía ejecutar, o se declara no atestiable

## 0. Qué es y qué no es este documento

Es el recibo de una unidad de trabajo estrecha sobre **un** fichero productivo,
`scripts/run-scoped-junit-report.py`. Cierra el tercer defecto abierto que
`WU_CDH_H0_RECONCILE_01_BOUNDARY_RECEIPT.md` §2 dejó registrado como
`no closed`: la ausencia de catálogo esperado, es decir, la posibilidad de un
verde que certifica menos tests de los despachados.

**Lo que este recibo NO hace:**

- No ratifica ningún `ADR-CDH-*`. El paquete sigue `PROPOSED SIN RATIFICAR`.
- No adoptó `v2/test-harness`. El módulo no existe en el árbol.
- No toca la frontera de certificación externa: ni harness externo, ni G10, ni
  UAT de distribución. Nada de eso se ha ejecutado ni se reclama.
- No mide el techo de 900 s. Sigue `MEDICIÓN PENDIENTE`.

**Autoridad y precedencia.** Este slice se ejecuta bajo la misma lectura de la
reconciliación: `SPEC-06` es material de paquete no ratificado, pero los seis
defectos que enumera son **observables en el código productivo actual** y se
verificaron uno a uno contra él. Cerrar un defecto real del productor no requiere
que la especificación que lo nombra esté ratificada; requiere que la observación
sea cierta, y aquí se midió antes de tocar código.

## 1. Defecto reproducido antes de tocar código

Caracterización con la CLI real, sin `--expected-catalog`, en `6db038fc`:

| Caso | Despachadas | Entregadas | Exit | Veredicto |
| --- | --- | --- | --- | --- |
| `short-by-one` | 3 | 2 | **0** | **PASS** |
| `complete` | 3 | 3 | 0 | PASS |
| `none-delivered` | 1 | 0 | 2 | (sin veredicto) |
| `short-by-four` | 5 | 1 | **0** | **PASS** |

Fuente: `evidence/cdh-h0-evidence-02/pre-fix-characterization.json`.

Las dos filas en negrita son el defecto. El informe reportaba `PASS` sobre dos
de tres clases **no contenía ningún campo** que admitiera que la tercera faltaba:
`missing_from_report` estaba vacío en las cuatro filas, o sea, no existía ni
`expected`, ni `missing`, ni `catalog`, ni `complete`.

Esto no es un caso patológico. Es exactamente el error de la que nació la
herramienta, una capa más arriba: W4 declaró `753 classes / 4712 tests` sobre la
suma de todo el disco. Un verde sobre un subconjunto desconocido es el mismo
error de aritmética, y aquí era alcanzable por el camino normal de uso.

Control: `none-delivered` **ya** se rechazaba (exit 2, "no XML falls inside the
run boundary"). El límite inferior estaba bien; el superior no. Eso delimita el
defecto con precisión: no era "el reporter no detecta runs vacíos", era
"el reporter no sabe qué se le pidió".

## 2. Cambio

Un solo fichero productivo. La tesis de diseño: **completitud es una pregunta
distinta de validez**, y por eso es un ADT aparte con su entrada aparte.

```text
decide(Totals, Completeness) -> (verdict, exit)
                          ↑         ↑
              qué llegó     qué se pidió
```

Si se hubieran colapsado en un solo veredicto, sólo habría dos salidas
aceptables: seguir diciendo `PASS` y dejar que el llamante adivine, o inventar una
afirmación de completitud a partir de los ficheros llegados. Ambas son la misma
mentira con distinto disfraz.

### 2.1 El ADT de completitud, cuatro casos cerrados

```python
Completeness = NotAttestable | CatalogUnreadable | Shortfall | Complete
```

Cada caso lleva su propia carga útil. No hay `Boolean + nullable`, ni
`isComplete` junto a `expected`, ni discriminador de cadena:

- `NotAttestable` — no se declaró catálogo. **No es un fallo.** El run fue verde;
  lo que no se hizo fue una afirmación que nadie pidió.
- `CatalogUnreadable(reason)` — se declaró un catálogo y no se pudo leer. Deliberadamente
  **no** es lo mismo que `NotAttestable`: no declarar es un llamante que nunca
  afirmó completitud; un catálogo ilegible es un llamante que la afirmó y cuya
  afirmación no se puede comprobar.
- `Shortfall(suites)` — clases declaradas sin fichero de resultado fresco. La carga
  útil son los **nombres**, no un contador.
- `Complete` — toda clase declarada produjo fichero fresco.

`decide()` hace `match` exhaustivo sin `else`, y el caso inválido se comprueba
**antes** del recuento, igual que en el slice anterior.

### 2.2 El catálogo se declara desde fuera, y por qué eso no es negociable

`--expected-catalog <json>` acepta `{"suites": ["Foo", "Bar"]}`.

La tentación era derivar el catálogo recorriendo `build/test-results`. Eso es el
conjunto llegado con otro nombre: la comprobación se volvería **infalsable por
construcción**, porque cualquier fichero ausente estaría por definición ausente
del catálogo. Un guard que sólo puede pasar es un guard que no guarda.

Por eso el catálogo se declara **fuera** del árbol de resultados, y por eso
`read_catalog` devuelve `InvalidEvidence` —nunca un `Catalog` vacío— cuando no se
puede leer, está mal formado, o declara cero suites. Con la degradación a vacío,
el déficit sería cero, el veredicto sería `PASS`, y una entrada rota se
convertiría en un gate verde: la aritmética de W4 otra vez, una capa más arriba.

La fila de catálogo vacío merece su propia fila porque su lectura tentadora
—«esperaba cero, faltaron cero, completo»— convierte el vacío en la afirmación
de completitud **más fuerte** alcanzable desde la entrada **más débil** posible.

### 2.3 Identidad de suite, no de ruta

El catálogo nombra *suites*, no ficheros. `suite_identity()` quita el prefijo
`TEST-` y la extensión, y la comparación es sobre el nombre de clase. Comparar
entradas contra rutas haría la comprobación dependiente de qué módulo eligió
Gradle, que no es una propiedad de la evidencia sino del layout.

Un fichero **carried-over** de un run anterior no satisface el catálogo: el
catálogo describe lo que **este** run debía producir, y reportar sobre resultados
de un run previo es el defecto W4 que la herramienta existe para impedir.

### 2.4 Veredicto de tres salidas, y por qué `NOT_ATTESTABLE` existe

```text
INVALID_EVIDENCE / 2   la evidencia no es de fiar, o hay déficit declarado
NOT_ATTESTABLE   / 0   lo que llegó estaba verde; nadie declaró qué debía llegar
PASS | FAIL       / 0|1 lo que llegó, sobre un catálogo cuya completitud se atestigua
```

`NOT_ATTESTABLE` conserva exit 0, y es una decisión, no una conveniencia: el run
**fue** verde, y un gate que lee un exit distinto de cero como «el run falló» no
debe fallar por una propiedad de su propia invocación. Lo que cambia es el
**nombre**, que es lo que impide citar el informe como prueba de que el run
ejecutó todo lo que debía.

### 2.5 Superficie observable

JSON: `expected_catalog`, `expected_files`, `completeness{case, attested, reason,
missing_suites}`, más `verdict` y `exit_code`.

Texto: bloque `COMPLETENESS:` con `attested` / `not attested` / `REFUSED`, y la
lista de suites ausentes. El guard existe en **ambos** modos a propósito: un guard
que sólo vive en el contrato de máquina se puede rodear por quien lea el informe
de operador, que es lo que un humano lee cuando un gate rechaza.

`attested` es `false` en los tres casos no-`Complete`, incluidos los que no son
fallo. Un consumidor que sólo lea ese booleano acierta en todos los casos.

## 3. Verificación ejecutada

Todo a nivel de fichero, sin Gradle. `argv`, exit y conteos en
`evidence/cdh-h0-evidence-02/`.

### 3.1 Suite directa del reporter

```text
cd <worktree> && python3 scripts/test_run_scoped_junit_report.py
→ Ran 36 tests   OK   (exit 0)
```

25 filas preexistentes + 12 nuevas en `CompletenessAttestation`
(log: `suite-run.log`).

**Dos filas preexistentes cambiaron, y no se debilitó ninguna aserción.** Las
dos afirmaban `verdict == "PASS"` mientras su claim real era *exit 0*:

| Fila | Claim | Cambio |
| --- | --- | --- |
| `test_skips_do_not_make_the_verdict_fail` | skips no son gate rojo | `code == 0` intacto; el nombre pasa a `NOT_ATTESTABLE` y se añade `attested is False` |
| `test_unreadable_carried_over_is_warned_not_fatal` | carried-over ilegible no es fatal | `code == 0` intacto; ídem |

Ambas pasaron a afirmar el claim, no la cadena del veredicto. Un skip sigue sin
bloquear el gate; un carried-over ilegible sigue sin invalidar un run válido. Lo
que se fijó además es que el informe **no** afirma completitud. Afirmar el
nombre habría fijado también el vocabulario, que no es lo que esas filas defienden.

### 3.2 Caracterización pre-fix por la CLI real

`pre-fix-characterization.json`, `6db038fc`. Falso verde reproducido (§1).

### 3.3 Mutaciones atribuidas 1:1

`mutations.json`. Control verde; SHA del fichero verificado antes y después.

| ID | Claim | Filas que mata | Discrimina |
| --- | --- | --- | --- |
| M8 | déficit ⇒ `INVALID_EVIDENCE` | 3 | sí |
| M9 | catálogo ilegible ≠ vacío | 3 | no (M11 comparte caso) |
| M10 | sin catálogo no se atestigua | 4 | sí |
| M11 | catálogo vacío se rechaza | 1 | sí |
| M12 | se compara contra *fresh*, no contra el árbol | 1 | sí |
| M13 | el catálogo nombra suites, no rutas | 5 | **no** |
| M14 | rojo sigue rojo con catálogo satisfecho | 1 | sí |
| M15 | el texto anuncia el no-atestado | 1 | sí |
| M16 | el texto rechaza el déficit | 1 | sí |

`verdict: ALL_KILLED`, `restored_identical: true`, 0 filas no atribuidas.

**Dos atribuciones necesitaron corrección, y ambas quedan registradas:**

- **M9** mata también la fila de catálogo vacío, porque un catálogo vacío se
  *clasifica en* `CatalogUnreadable`. Eso es la clasificación funcionando: la fila
  vacío-vacío prueba precisamente que `read_catalog` rechaza en vez de devolver
  `Catalog(frozenset())`, y ese rechazo **es** este caso. Atribución ampliada a
  `may_kill`, no ocultada.
- **M13 no discrimina.** Comparar contra `relpath` no es un cambio local: con la
  ruta relativa ninguna entrada de catálogo puede coincidir ya nunca, porque el
  valor lleva el directorio de módulo que eligió Gradle, así que mueren las cinco
  filas con catálogo. La lectura honesta es que «el catálogo nombra suites, no
  rutas» es una propiedad de la función de identidad **sin mutación estrecha**, y
  su evidencia aislante es la fila, no un kill set. Se declara `discriminating:
  false` en la evidencia en vez de forzar una atribución limpia.

### 3.4 Reproducción post-fix por la CLI real, con control

`postfix-reproduction.json`. Mismos cinco casos, mismos datos.

| Caso | Exit | Veredicto | `attested` | `missing_suites` |
| --- | --- | --- | --- | --- |
| `short-by-one` | **2** | `INVALID_EVIDENCE` | false | `["Gamma"]` |
| `complete` | 0 | `PASS` | **true** | — |
| `short-by-four` | **2** | `INVALID_EVIDENCE` | false | B,C,D,E |
| `none-delivered` | 2 | (sin veredicto) | — | — |
| `no-catalog` | 0 | `NOT_ATTESTABLE` | false | — |

`short-by-one` pasó de `PASS`/0 a `INVALID_EVIDENCE`/2 **con la misma entrada**.
El control completo sigue verde y ahora **atestigua**. Las 7 comprobaciones del
harness son `true`.

## 4. Verificación contra un árbol real de Gradle

Esta sección cierra el hueco que §5 declaraba abierto. Se ejecutó **después** del
commit de implementación, y sobre su mismo SHA.

### 4.1 El árbol

No existía ningún `build/test-results` en el worktree (`find` → 0 XML), así que
hubo que producirlo. Se eligió `:pipeline-events:test` como módulo más barato con
tests reales: depende sólo de `pipeline-domain` y `pipeline-scripting-api`, sin
protobuf ni JDBC.

```text
cd v2 && ./gradlew -p . :pipeline-events:test --console=plain
→ BUILD SUCCESSFUL in 10s   (12 actionable tasks: 8 executed, 4 from cache)
```

Log: `evidence/cdh-h0-evidence-02/gradle-test-run.log`. El boundary se creó
**antes** del build, condición que el propio reporter exige.

**Primer Gradle ejecutado en esta rama.** `v2 check`, `releaseGate`, la batería
UAT y la cobertura siguen `NOT_RUN`; lo que se reclama es exactamente una tarea
de test de un módulo.

### 4.2 El arnés se equivocó primero, y eso es lo más instructivo del slice

La primera ejecución del arnés comparó `suite_identity()` contra
`<testsuite name="...">` y reportó **19 discrepancias sobre 33 ficheros**. Un
número confiado y equivocado.

Leyendo los valores reales se vio la causa:

```text
file   : TEST-...EventRecordReadPortTest$CursorShape.xml
xmlname: cursor encoding                       ← @DisplayName, una frase
xmlname: S5.4 — el rechazo llega a la pagina   ← otra etiqueta humana
```

`name` es la etiqueta de `@DisplayName`, elegida por quien escribe el test. No es
una identidad. Gradle escribe la clase en `<testcase classname="...">`.

Las 19 discrepancias eran una propiedad del supuesto equivocado del arnés, no del
reporter. El arnés se corrigió para comparar contra `classname`, que es la
autoridad real del productor, y **el primer número se conserva en el recibo**
porque un arnés que produce un número confiado y falso es la clase de defecto más
cara de este repositorio.

### 4.3 Resultado sobre el árbol real

`evidence/cdh-h0-evidence-02/real-gradle-tree.json`,
`reporter_sha256 = 2c94019f…` (idéntico al de `4132653`).

```text
ficheros                        33
clases distintas (classname)    33
basenames distintos             33
discrepancias de identidad       0
classname ausente                0
basenames con clase anidada ($)  21
basenames duplicados             0
evidencia inválida en fresh       0
```

Y las dos invocaciones del reporter sobre el árbol real:

| Invocación | Exit | Veredicto | `fresh_files` | `fresh` |
| --- | --- | --- | --- | --- |
| catálogo desde `classname` del productor | 0 | `PASS` | 33 | 148 tests, 0 fallos |
| sin catálogo | 0 | `NOT_ATTESTABLE` | 33 | 148 tests, 0 fallos |

Las **7 comprobaciones** del arnés son `true`, incluidas las dos que importan:
la identidad del reporter coincide con la autoridad del productor, y el catálogo
construido desde esa autoridad atestigua `COMPLETE` sin déficit inventado.

### 4.4 El detalle que hacía falta verificar

21 de los 33 ficheros son **clases anidadas** y su nombre contiene `$`:

```text
TEST-dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPortTest$CursorShape.xml
classname="dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPortTest$CursorShape"
```

El `$` sobrevive intacto en ambas rutas, así que `suite_identity()` casa. Eso no
era deducible del XML sintético de la suite: si Gradle codificara el `$` de otra
forma, la identidad habría discrepado en más de la mitad del árbol real sin que
ninguna fila lo detectara. **Era el riesgo concreto que este slice existía para
descartar.**

## 5. Lo que este slice NO demuestra

Honestidad de alcance, no formato:

- **Un solo módulo.** `:pipeline-events:test` con 33 clases. No se ejecutó
  `v2 check` completo, ni `releaseGate`, ni la batería UAT, ni cobertura: siguen
  `NOT_RUN`. Un árbol real de un módulo es evidencia del productor para *ese*
  módulo; no es el árbol de la RC.
- **Sin `mtime` concurrente.** El árbol se produjo sin ningún escritor
  competidor, así que la ambigüedad entre carreras —el defecto 6— no queda
  tocada por esta sección.
- `NOT_ATTESTABLE` es un **nombre nuevo** en el vocabulario del veredicto. Nadie
  lo consume hoy (§6), pero es un cambio de contrato público para cualquier
  llamante futuro que compare el veredicto por igualdad con `"PASS"`.

## 6. Consumidores

`rg` sobre el árbol: los únicos sitios que nombran esta herramienta son la propia
herramienta, su suite, dos recibos previos, y los documentos del paquete CDH no
ratificado. **No hay ningún consumidor interno**, y `v2/test-harness` no está
incluido en `settings.gradle.kts`.

Eso es lo que permitió endurecer el contrato sin romper nada, y es una
afirmación verificada en este slice, no una suposición heredada de la
reconciliación.

## 7. Estado de los seis defectos de SPEC-06

Actualizado respecto de la reconciliación. `5/6` cerrados, **no ratificado**:

| # | Defecto | Estado | Cierra en |
| --- | --- | --- | --- |
| 1 | XML ilegible tratado como advertencia | **CLOSED** | `8d08fda7` |
| 2 | Contador no numérico / negativo | **CLOSED** | `8d08fda7` |
| 3 | Digest por basename | **CLOSED** | `8d08fda7` |
| 4 | Sin suite (`<testsuite>` ausente) | **CLOSED** | `8d08fda7` |
| 5 | Sin catálogo esperado / verde sobre subconjunto | **CLOSED** | este slice |
| 6 | `mtime` ambiguo; `UP-TO-DATE` indistinguible | **OPEN** | — |

El defecto 6 sigue abierto y **no** se puede cerrar sin un directorio único por
run, que es un cambio estructural, no un parche de este reporter.

## 8. Clasificación del slice

```text
Reference implementation consulted: ninguno. Un gate que certifica su propia
                                   completitud no tiene equivalente Jenkins: el
                                   referente es la práctica de reporting JUnit
                                   que separa el conjunto observado del conjunto
                                   esperado, y no hay un contrato publicado al
                                   que adherirse.
Behaviour adopted:                completitud como entrada declarada, externa al
                                   árbol de resultados, con veredicto
                                   NOT_ATTESTABLE cuando no se declara.
Intentional deviations:           el catálogo nombra suites (basename sin
                                   TEST-/.xml), no rutas ni IDs completos.
Security implications reviewed: n/a. Sin credenciales, sin red, sin ejecución de
                                 procesos; el cambio es de aritmética de recuento
                                 y vocabulario de veredicto.
Tests demonstrating the contract: scripts/test_run_scoped_junit_report.py
                                   (36 filas; 12 en CompletenessAttestation)
Verificación contra el productor: :pipeline-events:test → 33 clases / 148 tests,
                                   0 discrepancias de identidad, 7/7 checks
                                   (evidence/cdh-h0-evidence-02/real-gradle-tree.json)
```

## 9. Cierre

Unidades cerradas hasta aquí, en orden de commit:

| Commit | Unidad |
| --- | --- |
| `8d08fda7` | CDH-H0-EVIDENCE-01, integridad del veredicto |
| `f1a830cc` | recibo de EVIDENCE-01 ligado al SHA de implementación |
| `6db038fc` | CDH-H0-RECONCILE-01, reconciliación del paquete |
| (este) | CDH-H0-EVIDENCE-02, catálogo esperado y veredicto no atestiable |

Trabajo autorizado y disponible a continuación, en este orden:

1. **RETRACTADO por `WU_CDH_H0_EVIDENCE_03_REFUSAL_TYPED_RECEIPT.md`.** Este punto
   recomendaba ejecutar `:pipeline-application:test` porque «tiene módulos con clases
   de nombre repetido», y afirmaba que era lo único que podía producir basenames
   duplicados entre módulos. **Es falso.** Un escaneo estático de los 773 ficheros de
   test del repositorio, en 22 módulos, encontró 773 basenames distintos y **cero
   colisiones, incluidas cero colisiones entre módulos**. Esa verificación no puede
   fallar porque no hay colisión que producir. La rama del catálogo queda sin
   verificar contra un productor con basenames duplicados, y seguirá así salvo que
   aparezca una colisión real; no se debe reejecutar `:pipeline-application:test`
   esperando encontrarla.
2. Defecto 6 de SPEC-06, que exige directorio único por run y no se resuelve
   con más executions: sigue siendo un cambio estructural.
3. Adopción del paquete CDH. **Bloqueada** a la espera de decisión del operador
   sobre los cuatro `ADR-CDH-*` propuestos.