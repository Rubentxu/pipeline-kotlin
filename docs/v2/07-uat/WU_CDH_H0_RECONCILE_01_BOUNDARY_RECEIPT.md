# CDH-H0-RECONCILE-01 — qué del paquete CDH es contrato interno y qué no lo es

**Ciclo:** `p-733fb505b5a6bd2d/release-0-56-consolidation` (OPEN, `build`, path `B-direct`).
**WorkItem:** `93158a45-2997-49ff-9a85-7906c56344c6` (active). Tramo H1 / RP-0: integridad de la línea
de verificación antes de expansión funcional.
**Base SHA:** `f1a830cc72ed31e63faf084ae31d1a3d36c90822` (rama `worktree/test-harnes-evolutivo`).
**Paquete inspeccionado:** `docs/pipelinek_cd_harness_package/` — **sin trackear, intacto, no aplicado a
`v2/test-harness/`** (esa ruta sigue sin existir).

## 0. Qué es y qué no es este documento

Es una **tabla de límites derivada**, no una ratificación. Cada elemento del paquete queda en una de
tres categorías, y sólo la primera tiene fuerza de contrato aquí:

| Categoría | Significado |
|---|---|
| `CONTRATO INTERNO APLICADO` | El contenido coincide con un documento vigente del repo o con una ley de AGENTS.md. Se sigue porque el documento vigente lo manda, no porque el paquete lo proponga. |
| `PROPOSED SIN RATIFICAR` | Es una decisión nueva. No se aplica hasta que exista ADR ratificado y reconciliado. |
| `FUERA DE FRONTERA` | Corresponde al harness certificador externo, o es una medición que este repo aún no tiene. |

Ninguna fila de este documento ratifica un `ADR-CDH-*`, ni convierte el paquete en roadmap, ni abre una
segunda autoridad. `docs/v2/05-roadmap/ROADMAP.md` sigue siendo la única secuencia operativa.

## 1. Los cuatro ADR propuestos, uno por uno

Los cuatro se declaran `Estado: PROPOSED` en su propia línea 3. Ninguno está ratificado.

### ADR-CDH-001 — Núcleo interno del harness

| Parte | Categoría | Base |
|---|---|---|
| `pipeline-release` conserva `CandidateAdmission` | `CONTRATO INTERNO APLICADO` | ADR-0105, AGENTS.md §Release candidates |
| `pipeline-event-harness` es producción, no test-infra; no se borra | `CONTRATO INTERNO APLICADO` | Verificado: `MainEventsVerifyCli.kt` importa `dev.rubentxu.pipeline.v2.harness.verify.EventHarness` y lo llama en runtime |
| No crear subbuild ni motor de test nuevo | `CONTRATO INTERNO APLICADO` | ADR-0099 |
| Centralizar en `v2/test-harness/` | `PROPOSED SIN RATIFICAR` | Requiere `include(":test-harness")`; la ruta no existe |
| Reconciliar con ADR-0072/0099/0105 antes de aceptar | `PROPOSED SIN RATIFICAR` (y es la razón de este documento) | El propio ADR lo pide |

**Verificación:** las 17 referencias de `REFERENCES.md` existen todas en este árbol (verificado por
existencia, no por lectura completa). `v2/pipeline-testkit` y `v2/pipeline-event-harness` existen;
`v2/test-harness` no.

### ADR-CDH-002 — Selección contextual y RC bajo 900 s

| Parte | Categoría | Base |
|---|---|---|
| `Unknown` impact nunca permite saltarse tests: elegir full o bloquear | `CONTRATO INTERNO APLICADO` | AGENTS.md §Intelligent Change-Scoped Testing §9 |
| `NOT_RUN` / `SKIPPED_UNEXPECTED` / `INVALID_EVIDENCE` impiden `CANDIDATE_VERIFIED` | `CONTRATO INTERNO APLICADO` | `CERTIFICATION_PROTOCOL.md`; el nombre `INVALID_EVIDENCE` ya existe en el reporter desde `8d08fda7` |
| No reusar receipts de otra SHA | `CONTRATO INTERNO APLICADO` | AGENTS.md §Certificación / §Release candidates |
| **Presupuesto hard de 900 s para el gate de RC** | `PROPOSED SIN RATIFICAR` | Ver §3 |
| No mover suites obligatorias al certificador para que el productor parezca más rápido | `CONTRATO INTERNO APLICADO` | Frontera de responsabilidad, AGENTS.md |

### ADR-CDH-003 — Poda por propiedad probada

| Parte | Categoría | Base |
|---|---|---|
| No borrar en masa; no usar `@Disabled` para obtener verde | `CONTRATO INTERNO APLICADO` | AGENTS.md; `INTEGRATION.md` §Prohibiciones |
| Sustituir una prueba sólo si la nueva ejecuta la misma propiedad con fidelidad no menor **y** una mutación negativa la hace fallar | `CONTRATO INTERNO APLICADO` | AGENTS.md HARNESS FIDELITY LAW §5 |
| Conservar recibos históricos sin alteración | `CONTRATO INTERNO APLICADO` | AGENTS.md |
| `GuaranteeRow` como artefacto obligatorio de cada poda | `PROPOSED SIN RATIFICAR` | Nombre y formato propios del paquete |
| "Retirar `PipelineFixture.kt` sólo tras cero consumidores" | `CONTRATO INTERNO APLICADO`, ya cumplido como observación | Verificado: la clase está vacía (3 líneas) y **sólo su propio fichero la menciona**; los otros tres hits del término son `HelloPipelineFixture` y otra clase |

### ADR-CDH-004 — Una identidad de ejecución

| Parte | Categoría | Base |
|---|---|---|
| `:pipeline-release:releaseGate` mantiene `CandidateAdmission`; el harness agrega evidencia, no reimplementa admisión | `CONTRATO INTERNO APLICADO` | ADR-0105 |
| `CERTIFIED` / `STABLE_PROMOTED` pertenecen al certifier externo; nunca local | `CONTRATO INTERNO APLICADO` | AGENTS.md §Frontera de responsabilidad punto 5 |
| Un fallo de parser o un módulo obligatorio no descubierto invalidan la evidencia | `CONTRATO INTERNO APLICADO` | Corregido en `8d08fda7` |
| Directorio por run (`build/harness/runs/<runId>/`) en vez de `mtime` | `PROPOSED SIN RATIFICAR` | Requiere cambio de layout de Gradle y del script |
| Estados `DEV_VERIFIED` / `PREFLIGHT_VERIFIED` / `CANDIDATE_VERIFIED` / `CANDIDATE_PUBLISHED` / `BLOCKED_LOCAL` | `PROPOSED SIN RATIFICAR` | Nomenclatura nueva; el vocabulario vigente es otro |

## 2. Los defectos que el paquete describe, y su estado real

`SPEC-06` lista seis defectos de `run-scoped-junit-report.py`. Verificados uno a uno en `f1a830cc`:

| # | Defecto declarado | Estado en `f1a830cc` |
|---|---|---|
| 1 | XML ilegible cuenta como advertencia | **CORREGIDO** en `8d08fda7`: `INVALID_EVIDENCE` / exit 2 |
| 2 | Atributo no numérico cae a cero | **CORREGIDO** en `8d08fda7`: contador inválido invalida el fichero |
| 3 | `mtime` no distingue dos carreras | **ABIERTO**: sigue siendo la frontera del freshness |
| 4 | Digest sobre basename+counts colisiona | **CORREGIDO** en `8d08fda7`: ruta relativa + bytes + contadores |
| 5 | `UP-TO-DATE` no puede pasar por ejecución fresca | **ABIERTO**: no modelado |
| 6 | Catálogo disparado y tests recibidos de menos no tienen veredicto | **ABIERTO**: el reporter no tiene catálogo esperado |

Que cuatro de seis estén corregidos **no ratifica SPEC-06**: se corrigieron porque pertenecen a H1 del
ciclo vigente, no porque el paquete tenga autoridad. Un paquete que acierta seis diagnósticos no
hereda la autoridad de sus ocho SPEC.

Dos hallazgos adicionales del paquete que sí son ciertos y no estaban en SPEC-06:

- `gen-disabled-inventory.py` `classify()` cae en `HISTORICAL` por defecto (línea 59). Verificado.
- `justfile` `app-fast` (línea 60) usa dos filtros negativos `--tests '!...'` y declara `budget="900"`.
  Verificado. Ojo al dato: ese `900` es un **timeout de shell de ese recipe**, no el techo de RC propuesto,
  y no es evidencia de que el techo haya sido aceptado.

## 3. El techo de 900 s: por qué NO es un gate

Tres razones, y la tercera es la que importa:

1. Ningún `ADR-CDH-002` está ratificado, así que la cifra no tiene autoridad normativa.
2. `SPEC-03` **ella misma** dice: «no está demostrado que el repositorio actual pueda cumplirla» y «el
   primer hito mide baseline, después se aprueba o se rehace el SLO ante límites físicos medidos». O sea,
   el paquete se autolimita.
3. Un umbral duro sin medir produce `BLOCKED_BUDGET` antes de haber hecho el trabajo, y en un gate
   bloqueado la reacción del equipo es subir el umbral. El techo se vuelve entonces una cifra que
   describe el esfuerzo, no un límite.

**Estado:** `MEDICIÓN PENDIENTE`. No es un gate. No se aplica. No se cita como SLO vigente. Si se
quiere un SLO, primero: medir p50/p95 de cinco ejecuciones en la misma clase de máquina con al menos
una limpia, y entonces escribir el ADR que lo fije.

## 4. Desajustes factuales entre el paquete y este árbol

Ninguno es grave; los tres importan porque un plan que cuenta mal induces trabajo mal.

| Afirmación del paquete | Observado en `f1a830cc` |
|---|---|
| `892 *.kt` test files | `818` ficheros `.kt` bajo `*/src/test/*`; `778` con `Test` en el nombre; `1431` `.kt` en total bajo `v2/`. El propio ROADMAP del paquete pide no equiparar ficheros con clases activas, así que la cifra no debe usarse como denominador de nada. |
| `31 fixtures solapadas` entre `CompatibilityCorpusTest` y `UatCompat001CorpusSmokeRunTest` | Ambos ficheros existen. El recuento de 31 no se ha re-verificado aquí; es la cifra histórica de `C13_CORPUS_TIMEOUT_BUDGET_RECEIPT.md`. |
| `PipelineFixture.kt`: "clase vacía", retirar tras cero consumidores | Confirmado, y el prerequisite ya se cumple: sólo su propio fichero nombra la clase. |

## 5. Límites que este documento fija

```text
APLICADO    por este slice, por pertenecer a H1/RP-0 del ciclo vigente:
              - evidencia ilegible -> INVALID_EVIDENCE / exit 2 (8d08fda7)
              - contadores validados al parsear; digest liga ruta+bytes+contadores
              - NO_MUTADO: nada más de scripts/ se toca

NO APLICADO  esperando decisión del operador:
              - include(":test-harness") y el scaffold del paquete
              - :pipeline-testkit retirement, :pipeline-event-harness intacto
              - directorio por run en lugar de mtime
              - UP-TO-DATE != ejecución fresca
              - catálogo esperado con "tests de menos" -> sin veredicto
              - GuaranteeRow, estados DEV/PREFLIGHT_VERIFIED, presupuesto 900 s
              - la tabla CSV REQUIREMENTS.csv como inventario runtime

FUERA DE FRONTERA  pertenece al harness certificador externo:
              - certificacion de candidatos reales, veredicto, promocion estable
              - G10 y la bateria UAT como gate del productor
              - CERTIFIED, STABLE_PROMOTED, BLOCKED_EXTERNAL
```

## 6. Regla operativa que sale de aquí

Un paquete propuesto puede **describir** un defecto real y ser acertado en el diagnóstico. Eso le da
crédito como **entrada**, no como **autoridad**. La autoridad sigue siendo: ADR aceptado > spec/contrato
publicado > ROADMAP > estado SDDK > recibos. Aplicar una corrección porque pertenece a la WU activa es
legítimo; aplicarla porque el paquete la propuso habría sido ratificar por el camino de atrás.

## 7. Lo que este slice NO hace

- **No** añade `include(":test-harness")`, **no** copia `v2/test-harness/`, **no** toca
  `settings.gradle.kts`, `build.gradle.kts`, `justfile`, ni ningún `.kt`.
- **No** modifica el paquete: sigue sin trackear y byte-idéntico.
- **No** ratifica `ADR-CDH-001..004` ni ninguna SPEC.
- **No** desactiva, migra ni borra ninguna UAT.
- **No** aplica el techo de 900 s.
- **No** es evidencia de nada sobre un candidato o una RC. Para `f1a830cc` y este commit:
  `Gradle / v2 check / releaseGate / UAT / cobertura / 900 s = NOT_RUN`. No hay verde heredado.

## 8. Cierre de unidad de trabajo (AGENTS.md)

```text
Reference implementation consulted: no aplica — este slice NO cambia comportamiento de producto.
  Se confrontó el paquete contra ADR-0099, ADR-0105, ADR-0072, ADR-0074,
  CERTIFICATION_PROTOCOL, PIPELINE_TEST_HARNESS y AGENTS.md, que son la autoridad real.
Behaviour adopted:           tabla de límites derivada; sólo CONTRATO INTERNO APLICADO obliga.
Intentional deviations:      SPEC-03 y el README declaran 900 s como límite duro; aquí se registra
                             como MEDICIÓN PENDIENTE porque el propio paquete se autolimita y
                             ningún ADR lo ratifica.
Security implications reviewed: n/a — sin cambio de código ni de superficie expuesta.
Tests demonstrating the contract: ninguno; es un slice documental. Las afirmaciones técnicas de §2
                             y §4 se comprobaron por inspección del árbol en f1a830cc (comandos
                             y resultados en §9), y eso es verificación por observación, no una suite.
```

## 9. Cómo se verificó cada afirmación técnica

Comandos de solo lectura ejecutados en `f1a830cc`, árbol limpio salvo el paquete sin trackear:

```bash
rg -n 'HISTORICAL' scripts/gen-disabled-inventory.py          # classify() -> "HISTORICAL" en :59
rg -n "app-fast|'!" justfile                                   # :60 app-fast, :63-64 negaciones, budget="900"
cat v2/pipeline-testkit/.../PipelineFixture.kt                 # 3 lineas, class vacia
rg -n '\bPipelineFixture\b' --glob '*.kt' v2/                 # 1 solo hit: su propio fichero
rg -n 'EventHarness' v2/pipeline-application/src/main/kotlin    # MainEventsVerifyCli lo usa en runtime
ls -d v2/test-harness                                          # ABSENT
find v2 -path '*/src/test/*' -name '*.kt' | wc -l             # 818
find v2 -name '*Test*.kt' -not -path '*/build/*' | wc -l       # 778
sed -n '33,70p' v2/settings.gradle.kts                         # include(...) sin ":test-harness"
# existencia de las 17 REF-xx de REFERENCES.md                # 17/17 OK
# lectura de los 4 ADR-CDH-* y SPEC-03/SPEC-06/ROADMAP/INTEGRATION/AGENT-BRIEF
```

Estado final: `8d08fda7` + `f1a830cc` en la rama; este documento es el tercer commit y no cambia
código. Gradle, `check`, `releaseGate`, UAT, cobertura y el techo de 900 s siguen `NOT_RUN`.