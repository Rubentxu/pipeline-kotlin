# Recibo — Cobertura del catálogo y el corredor que sólo ejecuta HF0

**Commits:** `7e66a652` (clasificador) y `f9150ed6` (catálogo + constante muerta)
**Caja servida:** `QUALITY_VALUE_ADDENDUM.md` §BQ4 "Selección y evidencia vinculadas" (L241) y
la pré-condición declarada en L246.
**Fecha:** 2026-10-18
**Alcance del cambio:** dos defectos materiales en el camino que decide *qué* se certifica.
Ninguno toca el producto de pipeline; ambos tocan el harness que certifica al producto.

---

## 1. Qué se encontró, medido sobre el diff real

`scripts/validation-gate.py --base a138c467 --commit $(git rev-parse HEAD)` refusaba **seis**
rutas de producción. Ninguna era una ruta sin importancia: incluían los cuatro `build.gradle.kts`
de módulo y tres fuentes `src/main` del propio harness. Es decir, **la puerta no podía certificar
un cambio en lo que decide qué se empaqueta, ni en su propio código de decisión.**

Se dividen en dos clases de rechazo, y confundirlas fue el error de la premisa anterior:

| clase | dónde se decide | antes | después |
|---|---|---|---|
| hueco del **clasificador** | `ChangedInputClassifier.classify` | `scripts/test_*.py\|sh` caían en `UnknownProduction` | regla 6b → `TestSource` (`7e66a652`) |
| hueco del **catálogo** | `ContractCatalogue.contractsFor` | ningún `build.gradle.kts` de módulo lo reclamaba | `Provenance.build.identity` + `Build.composition` (`f9150ed6`) |

La distinción importa porque el remedio del refusal nombra una de las dos cosas: "add a
catalogue row". Para `scripts/test_install_pipelinek.py` ese remedio es **absurdo** —un script
de prueba del repositorio no es un contrato de producto, y atarlo como tal compraría un verde
escribiendo una mentira en el catálogo—. Para un `build.gradle.kts` es exactamente lo correcto.

## 2. El defecto de catálogo era una(split) accidental

`Build.composition` documentaba que los ficheros `.gradle.kts` estaban reclamados por
`Provenance.build.identity`. **Era falso:** ese contrato no tenía patrón para ellos. La premisa
del roadmap —"el catálogo los cubre"— era una afirmación sobre un KDoc, no sobre el código, y
por eso `v2/pipeline-release/build.gradle.kts` —que decide qué entra en el ZIP de distribución—
era incertificable.

La partición se decidió **leyendo**, no nombrando. El `build.gradle.kts` raíz fija
`JavaLanguageVersion.of(21)`, `sourceCompatibility`/`targetCompatibility` y `JvmTarget.JVM_21`
para cada subproyecto: eso es composición del toolchain, no identidad del artefacto. La fila
`the root build file is composition and not identity` existe para que la partición no se
colapse después.

## 3. Constante muerta con KDoc que afirmaba lo contrario

`ImpactResolver.SELF_OWNED_PREFIXES` hacía que toda ruta bajo `v2/test-harness/` fuera
no-producción. **Su consumidor ya no existía** (BQ4 P0 eliminó la exención del clasificador y
la delegación dejó la lista sin lector), pero la constante sobrevivió con un KDoc que seguía
afirmando la exención. Este repositorio ya pagó antes la clase de defecto "un comentario
describe una propiedad que el cableado no tiene"; es la segunda instancia.

Medido sobre `git ls-files`: **36** rutas `src/main` bajo `v2/test-harness` clasifican como
Production y hoy se niegan sin contrato. Esa negativa es **fail-closed y correcta**: el harness
*es* el producto de certificación, y un hueco de cobertura se cierra con un contrato, no
devolviendo una exención. Por eso la fila `a harness production source still refuses for want of
a contract` es un **control**, no evidencia de este cambio: pasa antes y después, y su único
trabajo es impedir que alguien restaure la exención por prefijo.

## 4. Evidencia

Todas las cifras por canario (XML borrado antes, regenerado después). Sin `^e:`: un fallo de
compilación no es un RED.

```
RED   ImpactResolverAdversarialTest: tests=25 skipped=0 failures=2 errors=0
        FAILURE: the root build file is composition and not identity
        FAILURE: changing a module build file selects the provenance identity contract end to end
GREEN ImpactResolverAdversarialTest: tests=25 skipped=0 failures=0 errors=0
GREEN :test-harness:test          : 63 clases, 367 tests, 0 failures, 0 errors, 0 skipped
GATE  base=a138c467 commit=f9150ed6  ->  exit 2, 5 rechazos (antes 6)
```

Las **dos** filas que fallaron son las que describen el cambio. La tercera (negativa del
harness) estaba verde antes y después.

Mutación aplicada a la afirmación central (`Provenance.build.identity` sin los patrones de
módulo): `v2/pipeline-release/build.gradle.kts` vuelve a `UnclassifiedPath` y la fila roja.
Verificada que **se aplicó** antes de leer su resultado —una fila verde que sobrevive a una
mutación no aplicada es vacua.

Censo de cobertura (instrumento efímero, ya borrado): 4117 rutas rastreadas, 942 Production, 926
sin contrato antes, **886** después; 16 → 56 certificables. **Esta cifra no es reproducible por un
tercero** porque el instrumento no quedó; la cifra que sí es reproducible es la del gate
(5 rechazos), y es la que este recibo usa como evidencia.

## 5. El hallazgo que reordena la caja de L246

Al dejar de añadir capacidades se midió el corredor, y el resultado es más grande que el hueco de
cobertura:

```
ProbeExecutor.execute  despacha sobre manifest.expectedFidelity:
    HF0            -> runHF0(...)                       un camino real
    HF1            -> BlockedExternal
    HF2..HF6       -> UnsupportedFidelity
runHF0 (línea 418)  -> Failed si HF0 ∉ plan.minimumFidelities

ContractCatalogue: 8 capacidades, 7 declaran minFidelity = HF2,
                   1 (OutputPlane.uat.fixture) declara HF0
```

**Consecuencia exacta:** la única capacidad que el ciclo puede ejecutar alguna vez es
`OutputPlane.uat.fixture`. Un honesto productor del sidecar no cambiaría esto: el plan exigiría HF2
y nada observa por encima de HF0, así que `InsufficientFidelity` no podría dispararse y el ciclo
seguiría sin poder descargar una garantía real. **Por eso este ciclo NO escribe el productor del
sidecar**, y esa es una razón medida, no una postergación.

Esto **agranda** la pregunta que L246 declara para un ADR. La hoja dice "decidir la capacidad de
observación de `hf` y `source_commit`". La medición añade una tercera, que es la primera:

> **HF0 es la única fidelidad ejecutable, y 7 de 8 contratos exigen HF2.** El ADR debe elegir
> entre (a) un ejecutor HF2 (distribución real bifurcada) o (b) una **fuente nueva de
> observación** de la fidelidad y del commit **del módulo que produjo cada XML**, de modo que
> quien corre la suite sea quien la declara. Sin una de las dos, el sidecar más honesto posible
> no descarga nada.

Una precisión sobre `source_commit`, medida hoy y que reduce el problema: la observación **existe**
y es coherente. El gate imprime `provenance: installed distribution reports commit
f9150ed6…` y coincide con HEAD. Lasourcese `InstalledDistribution.kt:75`, leída por `GateCli`.
Lo que falta no es "observar un commit" sino "observar el commit **del artefacto que produjo cada
XML**": la distribución instalada es otro artefacto. Una distribución obsoleta haría fallar
`StaleBinary` culpando al artefacto equivocado.

## 6. Lo que deliberadamente NO se hizo

- **No se añadió una capacidad para las 36 fuentes del harness.** Haría certificable este
  mismo diff, pero es exactamente el bucle de auto-certificación que `ADR-PK-DF-002` decisión 7
  nombra para retirada. Cerrar un hueco de cobertura por el camino que una ADR aceptada programa
  para borrar es work sobre código condenado. Queda como cobertura pendiente, no como tarea.
- **No se congeló el número 886 en un fitness.** Convertir una cifra de backlog en un contrato la
  convierte en un número falso en seis meses.
- **No se declaran ejecutables las capacidades HF2.** Habría convertido una negativa honesta en
  `UnsupportedFidelity`, que es otra negativa con más filas.

## 7. Estado

```
Verde    :test-harness:test (63 clases, 367 tests) sobre f9150ed6, canario verificado
Verde    la puerta sigue negando lo que no sabe (exit 2, 5 rechazos) — fail-closed correcto
Residual 886 rutas Production sin contrato  -> trabajo futuro, cuantificado
Bloqueo  L246 requiere ADR antes de escribir el productor del sidecar
```

**Próximo bloque desbloqueado:** el ADR de la pregunta de §5. Es una decisión, no código: por
eso no se abre aquí.

---

## Cierre de unidad de trabajo (AGENTS.md)

```
Reference implementation consulted:  ninguna; el defecto es propio del catálogo de este repo.
Behaviour adopted:                   una ruta de producción sin contrato se niega; se cierra el hueco
                                      con una fila de catálogo o con la regla del clasificador que
                                      corresponde, nunca con una exención por prefijo.
Intentional deviations:              ninguna. Las dos filas de evidencia fallan juntas y pasan juntas.
Security implications reviewed:       n/a — ningún cambio de autorización, secreto o frontera.
Tests demonstrating the contract:    v2/test-harness/src/test/kotlin/.../plan/ImpactResolverAdversarialTest.kt
                                      (2 filas de evidencia + 1 control negativo)
```