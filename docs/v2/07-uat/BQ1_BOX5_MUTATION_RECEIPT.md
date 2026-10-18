# BQ1 box 5 — las cuatro mutaciones, medidas

**SHA base del recibo:** `cadf13f03d17ffb3b42991c62127fb76014ce7a3`
**Enmienda:** §8, sobre `1e804c58` (361 tests; cierra el caso `className` de §7).
**Fecha:** 2026-10-18
**Caja:** `QUALITY_VALUE_ADDENDUM.md` BQ1, quinta entrega — *"demostrar mutaciones que detecten reporte 0 tests, all skipped, `buildCommit` erróneo, recurso de servicio no clasificado."*
**Alcance:** `:test-harness:test`, 63 clases, 360 tests al emitir el recibo; 361 tras §8. Verificado verde desde canario limpio (`TEST-*.xml` borrados y regenerados; `failures=0 errors=0 skips=0`; `^e: ` = 0).

Este recibo es la evidencia de la caja. El estado de la caja en el roadmap **no** cambia aquí: marcarla es una decisión de cierre que además exige los otros cuatro TODOs de BQ1.

---

## 1. Método, y por qué está aquí

Cada mutación se aplica al **código productivo**, se ejecuta la fila que la debería detectar, y el fichero se restaura con SHA-256 verificado (`sha256sum -c`). No se muta el test. Un rojo que sobrevive a su mutación es evidencia sobre la mutación, no sobre el producto — y un mutante que sobrevive es un hallazgo, no un trámite: es la fila que no existía.

Las cuatro filas de `BQ_ACCEPTANCE_MATRIX.md` que nombra esta caja (AAT-BQ1-01..04) se reproducen una a una.

| # | AAT | mutación aplicada al código productivo | resultado | filas que la matan |
|---|-----|------------------------------------------|-----------|------------------|
| 1 | AAT-BQ1-01 | desactivar la rama `tests <= 0` de `EvidenceReader.suiteFqcnFrom` | **SOBREVIVE** — las 14 filas de `EvidenceReaderTest` quedan verdes | ninguna, y no puede haberla (ver §2) |
| 2 | AAT-BQ1-01 + AAT-BQ1-02 | neutralizar la rama `tests - skipped <= 0` (`tests < 0`, inalcanzable) | **MUERTA** | 2 filas, ambas de `EvidenceReaderTest` (abajo) |
| 3 | AAT-BQ1-04 | invertir `observedCommit != expectedCommit` en `ImpactResolver.adjudicate` | **MUERTA** | 6 filas / 4 clases |
| 3b | AAT-BQ1-04 | reportar `NoCommitObserved` como `Mismatched(expected, observed = expected)` | **SOBREVIVÍA** → defecto encontrado y cerrado en `cadf13f0` | 1 fila, tras el arreglo (§3) |
| 3c | AAT-BQ1-04 | invertir `m.sourceCommit != expectedCommit` (sidecar) | **MUERTA** | 4 filas / 3 clases |
| 4 | AAT-BQ1-03 | `if (false)` en la rama `META-INF/services/` de `ChangedInputClassifier` | **MUERTA** | 4 filas / 3 clases |

## 2. AAT-BQ1-01 — el guard que no puede ser detectado, porque no es un guard

```kotlin
// forma anterior, medida el 2026-10-18
} else if (tests <= 0) {
    null
} else if (tests - skipped <= 0) {
    null
```

`skipped` no es negativo en ningún XML que Gradle emite, luego `tests <= 0` **implica** `tests - skipped <= 0`. No existe entrada que alcance la segunda rama sin que la primera hubiera devuelto antes. Esto no es un juicio: es subsunción lógica, y por eso desactivar la primera rama no cambia ninguna entrada.

Es decir: el oráculo de AAT-BQ1-01 (`Refused` ante `tests=0`) **se cumple** — la entrada se rechaza — pero lo cumple la misma comparación que AAT-BQ1-02, y no por un segundo guard.

Un guard fail-closed que ninguna entrada alcanza es un guard que no se puede demostrar, y su presencia hace que el lector cuente dos defensas donde hay una. Medido y cerrado en `0bcb5bbd`: colapsado a una comparación, con la prueba escrita en el KDoc para que la próxima persona no lo restaure.

**Lo que NO se hizo:** añadir una fila que "mate" la rama eliminada. Una fila que muere sólo porque el código se rompió de otra manera certifica la fila, no el comportamiento.

### La simetría que cierra el par

La misma medición de AAT-BQ1-02 muestra que la comparación viva sujeta ambas cajas, y que las **dos** filas existen y trabajan:

```
RED  EvidenceReaderTest  a report with zero tests does NOT count as evidence
                           ==> expected: <true> but was: <false>
RED  EvidenceReaderTest  a report with all tests skipped does NOT count as evidence
                           ==> 100% skipped is not a green run; it is the matrix that
                               AGENTS.md calls the silent-green shape
```

AAT-BQ1-01 y AAT-BQ1-02 son, tras el colapso, el mismo guard con dos entradas que lo alcanzan por caminos distintos (`tests=0` y `tests=n, skipped=n`). La matriz los propose como dos probes porque son dos entradas; el código tiene una comparación porque las dos entradas se rechazan juntas. Eso es lo que un guard bien construido se ve, no un defecto: dos entradas, una decisión.

## 3. AAT-BQ1-04b — un caso del ADT que nadie podía nombrar

`SelectionRefusal.StaleEvidence` tiene dos casos, y su KDoc justifica la separación así: *"Distinct from `Mismatched` because the operator action is different: the installed distribution is the problem, not the commit."*

La fila que ejercita el camino `null` afirmaba:

```kotlin
assertTrue(refusal!!.reasons.any { it is SelectionRefusal.StaleEvidence })
```

`StaleEvidence` es el **supertipo compartido** de los dos casos, así que la afirmación concordaba con cualquiera de los dos. Mutación 3b: el brazo `NoCommitObserved` pasa a emitir `Mismatched(expected = expectedCommit, observed = expectedCommit)` — que afirma que la distribución instalada reportó el commit esperado cuando no reportó nada, exactamente la tautología que `InstalledDistribution` existe para impedir. **61 filas verdes en 5 clases.**

Es el mismo patrón que la fila anterior de `NoCommitObserved` no nombrada: una aserción un nivel más gruesa que la propiedad que dice medir. La fila hermana `a distribution built from a different commit refuses` ya usaba la forma precisa (`assertEquals(Mismatched(...), ...)`) — una de las dos knew, la otra no.

Cerrado en `cadf13f0` con la forma precisa. Mutación reaplicada después del arreglo: **muerta por 1 fila**, mensaje nombrando ambos lados:

```
expected: <NoCommitObserved(expected=aaaa...)> but was: <Mismatched(expected=aaaa..., observed=aaaa...)>
```

## 4. AAT-BQ1-03 — el denominador corrige al candidato

La primera medición de esta mutación seleccionó las clases **por lo que el roadmap nombraba** (`ChangedInput*`, `HarnessSelfExclusion*`) y encontró 2 filas rojas. El denominador —enumerar toda fila que toca la rama— encontró 2 más en `ImpactResolverAdversarialTest`, que nunca se habrían ejecutado:

```
RED  ChangedInputClassifierTest$DerivedProductionProjection  isProductionPath returns true for Production ServiceLoader()
RED  ChangedInputClassifierTest$ProductionClassifications     a META-INF services resource is Production ServiceLoader()
RED  ImpactResolverAdversarialTest                            META-INF services resource is a production path()
RED  ImpactResolverAdversarialTest                            META-INF services change in any module is a production path()
```

Las cuatro reds: **1 decisión, 4 filas.** `ChangedInputClassifier.isProductionPath` delega a `classify` (`classify(path) is ChangedInput.Production`), así que las cuatro filas observan la misma rama por dos proyecciones. Reportarlas como 4 guards independientes sería falso.

## 5. Divergencia de nombre con la matriz de aceptación

La matriz propone el oráculo `Refused(UnclassifiedInput)`. El tipo implementado es `SelectionRefusal.UnclassifiedPath` (`ValidationPlan.kt:175`), y el camino se ejercita en `ChangedInputClassifierTest$ResolverIntegration`. La matriz es una propuesta declarativa, no la implementación; se registra la divergencia y **no** se renombra un caso sellado que funciona, porque eso tocaría `GateCli`, el render deOperador y `ImpactResolverAdversarialTest` sin valor operativo.

## 6. Lo que este recibo NO dice

- **No** dice que BQ1 esté cerrado. Faltan las otras cuatro entregas de la caja y la sección STOP.
- **No** es un gate ejecutable. No hay script de mutación en el repo: cada mutación se aplicó y se restauró a mano durante la sesión, con SHA verificado. Un guard reutilizable sería trabajo nuevo, no una observación.
- **No** cubre el lado `binary_sha256` de AAT-BQ1-04, que sí se midió y salió con hallazgo. Ver §7.

## 7. `binary_sha256`: un campo que el gate exige y no interpretan

Medido el 2026-10-18 sobre el denominador completo de lecturas en `src/main`:

```
EvidenceManifest.kt:51   (KDoc)   "binary_sha256": "012345..."
EvidenceManifest.kt:67   (campo)  @SerialName("binary_sha256") val binarySha256: String
EvidenceManifest.kt:83   (require) length == 64
EvidenceManifest.kt:87   (require) all { it in '0'..'9' || it in 'a'..'f' }
```

Cuatro lecturas, **todas dentro del propio `EvidenceManifest`**: su KDoc, su declaración y sus dos `require`. Ni `ImpactResolver.adjudicate` lo compara, ni `GateCli.render` lo imprime. El gate lo exige (un sidecar sin él no construye el manifiesto → `MalformedSidecar` → `Refused`) y después no lo mira.

Esto **no** es un comentario obsoleto. El KDoc del propio campo es honesto y deliberado: distingue `binarySha` ("the binary's own provenance, **not** the source commit") de `sourceCommit` ("**so the gate can detect a stale binary**"), y el párrafo del cierre dice que `adjudicate` une el sidecar con "the plan and the binary's reported commit" — commit, no SHA. La distinción es intencionada y está escrita.

Lo que falta es la decisión, y por eso queda aquí y no en un commit:

- **(a) Declararlo procedencia, no evidencia.** Una línea en el KDoc del gate diciendo que `binary_sha256` se exige por completitud del sidecar y no se compara. Coste cero, riesgo cero, y elimina la lectura de que un `VALIDATION DISCHARGED` dice algo sobre el binario que no dice.
- **(b) Compararlo.** El gate necesitaría el SHA del artefacto que valida — leería la distribución instalada, que es exactamente lo que `InstalledDistribution` hace por el commit. Es una capacidad nueva con un gate nuevo, y pertenece a una decisión de roadmap, no a un recibo.

Por la SEMANTIC CONSTITUTION §2 ("dead semantic parameter — metadata accepted but never interpreted"), (a) es el mínimo correcto hoy y (b) es el único que da valor real. Se recomienda (a) ahora y (b) como ítem propio; no se ejecuta ninguna de las dos sin la decisión, porque (b) cambia el contrato del gate y (a) toca un KDoc que hoy dice la verdad.

Las dos filas existentes que lo mencionan (`EvidenceManifestTest:64` round-trip, `:99` rechazo de un valor mal formado) certifican el **parseo**, no el uso, y no deben contarse como cobertura de este punto.

## 8. Amendamiento del 2026-10-18: `class_name` sí se compara

§7 midió sobre el denominador completo que **cuatro de los seis campos** del sidecar se exigen y no se interpretan, y señaló `className` como el caso más nítido. Ese caso se cerró después de emitido el recibo; esta sección es su evidencia y **modifica §7**, que queda por tanto incompleto tal como está escrito.

### El agujero

`EvidenceManifest.sidecarFor` deriva la ruta del sidecar del **nombre de fichero** del XML:

```kotlin
val className = fileName.removePrefix("TEST-").removeSuffix(".xml")
return xmlPath.resolveSibling("$className.json")
```

Así que `TEST-FooTest.xml` sólo puede emparejarse con `FooTest.json` — y el JSON *dentro* de ese fichero lleva su propio `class_name`, que nada comparaba. El gate indexaba por el `<testsuite name>` del XML y leía el sidecar sólo para `source_commit` y `hf`. Un sidecar que se describiera a sí mismo como `BarTest` **descargaba el plan de `FooTest` sin quejarse**.

Esto implementa literalmente la mitad sin comparar de la caja 3 del roadmap — «validar `tests>0`, `tests-skipped>0`, `errors=failures=0`, y clase/paquete, archivo por invocación» — y es la misma clase de fallo que `EvidenceKeyAgreementTest` cerró un nivel más arriba: dos mitades nombrando lo mismo sin que nadie lo compruebe.

### Medición

```text
RED  (antes de cablear la comparación)
     EvidenceManifestTest  the gate refuses a sidecar that names a different class
                            than its report
                            ==> expected: not <null>
```

Es decir: `adjudicate` devolvía `null` — descargaba — donde la fila exige una negativa. La mutación sobre el código productivo (`if (false && m.className != identity)`) la mata por **1 fila de 361 en 63 clases**, atribuida 1:1.

### Lo que cambió

| sitio | cambio |
|---|---|
| `ValidationPlan.SelectionRefusal` | caso nuevo `SidecarNamesAnotherClass(suite, observed)` |
| `ImpactResolver.adjudicate` | comparación `m.className != identity` en la rama `WithManifest` |
| `GateCli.describe` | frase de operador; el `when` es exhaustivo, así que el compilador la exigía |

El caso lleva `(suite, observed)` y nada más: `suite` **es** el valor esperado, así que un `expected` aparte sería una repetición, y un `field: String` discriminante sería exactamente el *flag* de modo abierto que la constitución prohíbe en un ADT cerrado.

Verde desde canario completo (`*.xml` borrados y regenerados, `^e: ` = 0): **63 clases, 361 tests, `failures=0 errors=0 skipped=0`**.

### Lo que §7 sigue diciendo

`gradlePath`, `invocation` y `binarySha256` **continúan** sin comparación y sin cerrar. La medición de esta sección no los toca; sólo cierra `className`. La decisión (a)/(b) de §7 sigue pendiente para `binary_sha256`.