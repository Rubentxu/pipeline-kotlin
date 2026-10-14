# WU-CDH-H0-EVIDENCE-01 — el reporter de evidencia deja de convertir ilegible en verde

**Ciclo:** `p-733fb505b5a6bd2d/release-0-56-consolidation` (OPEN, `build`, path `B-direct`).
**WorkItem:** `93158a45-2997-49ff-9a85-7906c56344c6` (active), tramo **H1** — «harden releaseGate
so an absent or SKIPPED test fails explicitly».
**Backlog:** `bl-bl-01M4XGBH3X000389V0QGXH12C0` (captured 148, triaged P0 149, promoted al ciclo 150).
**Trazabilidad:** `docs/v2/05-roadmap/ROADMAP.md` RP-0 («restaurar CI y verdad del repositorio») y §0.6
(«prioridad obligatoria: corregir la línea de verificación y los defectos de integridad antes de iniciar
expansión funcional»).
**Base SHA:** `e3a6b9fefbba67036598e315b74c50099037f7ee` (rama `worktree/test-harnes-evolutivo`).

## 1. Defecto reproducido antes de tocar código

`scripts/run-scoped-junit-report.py` es la herramienta que convierte XML de JUnit en el veredicto del
gate. Reproducido por su CLI real en `fdcadcfd`, evidencia inmutable en
`evidence/cdh-h0-evidence-01/negative-characterization-prefx-fdcadcfd.json`
(digest `42fa130751b61c870613e770111b7679e87958f86873942b156bb11bf89c8941`):

| Caso | Esperado | Observado en `fdcadcfd` |
| --- | --- | --- |
| `<testsuite tests="1"` (XML truncado) | rechazar evidencia inválida, exit ≠ 0 | **PASS, exit 0** |
| `failures="not-a-number"` | rechazar, exit ≠ 0 | **PASS, exit 0** |

Dos mecanismos, un solo efecto. `read_xml` devolvía `None` para lo que no podía leer y `totals` lo
contaba aparte, así que un fichero ilegible desaparecía de la aritmética y `ok` se computaba sobre un
subtotal. Un contador no numérico caía en `except ValueError: out[k] = 0`, que es exactamente el valor
que informa un run limpio. En ambos casos el número seguía cuadrando: sólo era menor de lo debido, y
nada aguas abajo podía distinguir un rojo de un verde.

El programa de este bloque (`docs/pipelinek_cd_harness_package/.../SPEC-06-EVIDENCE-AND-GATE.md`,
**PROPOSED**) lista ambos defectos; se corrigen aquí por pertenecer a H1 del ciclo vigente, sin
activar ninguno de sus ADR propuestos.

## 2. Cambio

Un solo fichero productivo, `scripts/run-scoped-junit-report.py`:

1. **La evidencia es un resultado cerrado, no un centinela.** `read_xml` devuelve
   `SuiteCounts | InvalidEvidence`, decidido una vez al parsear. Desaparecen el `None` y el entero
   mágico `unreadable_files`.
2. **Los contadores se validan donde se parsean.** No entero → fichero inválido. Negativo → fichero
   inválido. Ausente → 0 (Gradle siempre emite los cuatro, y un atributo ausente no es una
   contradicción). XML sin `<testsuite>` → inválido.
3. **Decidir antes de interpretar.** `decide()` es pura (`Totals -> (verdict, exit)`) y evalúa el caso
   inválido **primero**, de modo que no es alcanzable por un total de cero. El veredicto pasa a tres
   casos: `PASS` / `FAIL` / `INVALID_EVIDENCE`.
4. **Fresco vs carried-over.** Un fichero fresco ilegible invalida el run (es uno de los resultados
   que el run afirma haber producido). Un carried-over ilegible sólo se avisa: está excluido
   y no puede certificar ni refutar nada. Tratarlo como fatal dejaría que bytes de otro run bloquearan
   un veredicto legítimo, que es la imagen especular del defecto corregido.
5. **El digest identifica el conjunto.** `fresh_set_digest` combina ruta relativa a la raíz de
   resultados, SHA-256 de los bytes del fichero, contadores validados y cuál de los dos casos es. El
   digest anterior usaba basename + contadores, que colisiona entre módulos homónimos y no distingue
   un fichero corrupto de uno con cero tests.
6. **El digest no miente sobre su propia forma.** Si los bytes de un resultado fresco no se pueden
   leer, la función no devuelve algo que parezca un SHA-256. Antes devolvía `unreadable-bytes:<rel>:<errno>`
   desde un valor cuyo nombre y campo son `fresh_set_sha256`: un string en el sitio de una prueba, la
   misma clase de defecto que el centinela `None`, una capa más abajo. Ahora es
   `INVALID_DIGEST_NO_BYTES:<rel>:errno=<n>`, que se declara. M7 mide el otro extremo: hashear la
   cadena vacía devuelve un digest plausible sobre menos ficheros de los que el run afirma, y esa fila
   también muere.
7. **El contrato máquina nombra el motivo.** `fresh_invalid_evidence` y
   `carried_over_invalid_evidence` son listas de `{path, reason}`; `verdict` y `exit_code` viajan en el
   JSON. Los recibos que leían `verdict` siguen válidos; los que leían `unreadable_files` deben leer la
   lista. **Cambio de contrato observable**, registrado aquí.

## 3. Verificación ejecutada

Toda la evidencia es de `scripts/`, ejecutada sobre el árbol de trabajo que contiene el cambio. **No
se ejecutó Gradle, `check`, `releaseGate`, ni ninguna UAT**: el cambio no toca Kotlin, ni el grafo de
módulos, ni `:pipeline-release`.

### 3.1 Suite directa del reporter

```bash
timeout 600 python3 scripts/test_run_scoped_junit_report.py   # argv: 22 filas -> 25 filas
```

`exit 0`, `Ran 25 tests`, `OK`. Evidencia: `evidence/cdh-h0-evidence-01/suite-run.log`, SHA-256
`d7c171e44a31f44dbf4d74b911ec0c83962d09e00c355093b017fdd580443cee` (última ejecución, sobre el árbol
exacto que se commitea; los dos ficheros conservan los SHA del bloque de estado, luego el resultado es
del código commiteado y no de un estado intermedio).

### 3.2 Mutaciones atribuidas 1:1

Arnés fuera del repositorio (`mutation_sweep.py`, no se distribuye). Cada mutación se aplica, se
ejecuta la suite, se restaura y se **verifica el SHA restaurado** antes de la siguiente.

| Mutación | Filas muertas | Atribución |
| --- | --- | --- |
| M1 veredicto sobre el subtotal (comportamiento pre-fix) | 8 | todas las de evidencia inválida, incluida la del digest |
| M2 contador no numérico reparado a 0 | 1 | `test_invalid_counter_invalidates_the_run…` |
| M3 contador negativo aceptado | 1 | `test_negative_counter_is_invalid` |
| M4 carried-over inválido tratado como fatal | 1 | `test_unreadable_carried_over_is_warned_not_fatal` |
| M5 digest por basename | 2 | colisión entre módulos + bytes |
| M6 digest sin bytes del fichero | 1 | `test_digest_changes_when_only_the_file_bytes_change` |
| M7 digest hasheando la cadena vacía (falso digest plausible) | 1 | `test_a_digest_that_is_not_a_digest_is_marked_as_such` |

`pre_sha256 = f7f629aac1bebdecbb0e2e0cb8067a4bc5794b732e273c4a3ede66753e1988d6`; las siete
restauraciones coinciden con ese valor. Registro: `evidence/cdh-h0-evidence-01/mutations.json`,
SHA-256 `ef4d0cc581eb5e731b254fcfb0ffeb86e11557bb1434f49629f1c9ffb2399b33`.

**Dos filas que casi se colaban.** La primera versión de
`test_digest_separates_same_named_suites_in_different_modules` comparaba el dict completo del informe
contra el string del digest: nunca son iguales, así que la fila no podía fallar y M5 no murió bajo
ella. El defecto estaba en la fila, no en la herramienta. Corregida para comparar `["fresh_set_sha256"]`
de ambos lados; ahora M5 mata 2 filas.

La primera versión de `test_a_digest_that_is_not_a_digest_is_marked_as_such` afirmaba `exit 0` con la
premisa de que un fichero puede parsear y luego fallar al hashearse. No puede: ambas lecturas abren los
mismos bytes, así que un fichero ilegible es evidencia inválida por las dos vías y el veredicto es 2.
La premisa era mía, la observación es del código. La fila afirma ahora lo que se observó, y la
distinción importa: una fila que verifica su premisa en vez de su tema no distingue el producto de mi
idea sobre él.

### 3.3 Reproducción por la CLI productiva, con control limpio

`reproduce_false_greens_postfix.py` entra por el binario real, cambia sólo el XML y no reimplementa
ninguna decisión del reporter:

| Caso | Exit | Veredicto |
| --- | --- | --- |
| `corrupt-xml` (mismo fixture que `fdcadcfd`) | 2 | `INVALID_EVIDENCE` |
| `invalid-failure-counter` (mismo fixture) | 2 | `INVALID_EVIDENCE` |
| `clean-control` (3 tests, 0 f, 0 e) | 0 | `PASS` |

`exit 0`, `rejected=True control_green=True`. Registro:
`evidence/cdh-h0-evidence-01/postfix-reproduction.json`, SHA-256
`c6625910876b9f7ec89444b6173ad3bde5ae44cc27570829c14345a920f61001`.
La caracterización de `fdcadcfd` **no se sobrescribió**.

## 4. Fidelidad del arnés

Clase HF0 (contrato puro), no HF2. Los tests invocan la herramienta como subproceso y leen sus
contadores; no reimplementan el algoritmo. Las tres aserciones que dependen de Gradle (`installDist`,
ejemplos externos) no aplican a este script. El `mtime` sigue siendo la frontera del freshness porque
un `runId` con directorio aislado pertenece a Bloques 1/4 del programa CDH, no a esta unidad.

## 5. Lo que esta unidad NO hace

- **No** toca `:pipeline-release`, `releaseGate`, `candidateAdmission`, ni `settings.gradle.kts`.
- **No** toca el paquete `docs/pipelinek_cd_harness_package/` ni lo sobrepone a `v2/test-harness/`.
- **No** toca ninguna UAT, ni `@Disabled`, ni el harness externo, ni G10.
- **No** ratifica ADR-CDH-001..004, ni el gate de 900 s. El techo de 900 s sigue siendo una
  **medición pendiente**, no un umbral aplicado.
- **No** es evidencia de nada sobre el candidato o la RC: `Gradle / check / releaseGate / UAT / 900 s =
  NOT_RUN` en este SHA. No hay verde heredado de `e3a6b9fe` para esta unidad.

## 6. Cierre de unidad de trabajo (AGENTS.md)

```text
Reference implementation consulted: Jenkins `xUnit`/JUnit XML consumers (formato de <testsuite>);
  no se copió código. El contrato fail-closed es decisión propia de este bloque.
Behaviour adopted:           evidencia ilegible ⇒ el veredicto no existe (INVALID_EVIDENCE / exit 2),
                             nunca un cero silencioso dentro de un total.
Intentional deviations:      carried-over ilegible avisa en vez de fallar (no puede certificar ni
                             refutar el run fresco); digest pasa a ruta+bytes+contadores.
Security implications reviewed: n/a — no cambia autenticación, credenciales ni superficie expuesta;
                             un gate que antes aceptaba evidencia corrupta era él mismo una debilidad.
Tests demonstrating the contract: scripts/test_run_scoped_junit_report.py (25 filas, 7 mutaciones),
                             evidence/cdh-h0-evidence-01/postfix-reproduction.json (3 casos por
                             la CLI real).
Driver (fuera del repo, no se distribuye): reproduce_false_greens_postfix.py,
                             mutation_sweep.py.
```

## 7. Estado

```text
Status: IMPLEMENTED_AND_VERIFIED_ON_THIS_TREE
Base SHA: e3a6b9fefbba67036598e315b74c50099037f7ee
Tool SHA-256 (fixed):     f7f629aac1bebdecbb0e2e0cb8067a4bc5794b732e273c4a3ede66753e1988d6
Test SHA-256 (fixed):     60f37d314cd306baf8058c87665c5fa014aae83426f3137fe1a035ed7d0360ea
NOT_RUN: Gradle, v2 check, releaseGate, UAT, cobertura, RC 900 s, publicación.
```

Este recibo certifica el contrato del reporter en este árbol. **No** certifica la candidata, ni el
gate de producto, ni el harness externo.