# `v2/test-harness/` — el hogar de gobierno del harness de pruebas

Esta carpeta **no contiene pruebas**. Contiene el catálogo y las políticas que gobiernan
cómo se seleccionan, ejecutan y evidencian las pruebas del producto. Las pruebas siguen
viviendo en su módulo propietario (`v2/<módulo>/src/test/…`) o, para el harness hermético,
en `scripts/` junto a su herramienta.

Mandato: B1.3/B1.4/B1.5 del documento *Evolución del test-harness*. Regla que lo define:
**un catálogo, ninguna política duplicada, ningún segundo motor de selección.**

## Contenido

| Fichero | Naturaleza | Qué responde |
|---|---|---|
| `catalogue.json` | **GENERADO** (`just catalogue`) | qué módulos tienen código productivo y qué plan de impacto los cubre, qué gates existen con qué presupuesto, qué roles tienen autoridad única, **qué superficie crítica** cubre cada módulo, cuántas suites hay |
| `CATALOGUE.md` | **GENERADO** | la misma información en tabla legible |
| `surfaces.json` | escrito a mano | la **declaración** de superficies críticas (CLI, plugins, credenciales, eventos, Output Plane, runtime, scripting, release) y de los módulos auxiliares. Es autoridad compartida: el generador la valida contra el árbol y el planificador de impacto lee **el mismo fichero** para decir qué superficies toca un cambio |
| `README.md` | escrito a mano | esta página: frontera, cómo se regenera y qué se verifica |

No edites los generados a mano: `just fitness` falla si el catálogo commiteado no coincide
con lo que produciría una regeneración sobre el árbol actual (`--check`).

## Autoridades únicas (esto es lo que impide un segundo motor)

El catálogo nombra **una sola** autoridad por rol. Si aparece otro fichero que reclame un
rol por su nombre, el generador se niega y no escribe nada (V2).

| Rol | Autoridad |
|---|---|
| ContractCatalogue | `scripts/gen-test-harness-catalogue.py` |
| ImpactResolver | `scripts/plan-affected-tests.py` |
| SuitePolicy | `scripts/fitness-harness-coverage.py` |
| Dispatch | `justfile` |
| Gate | `scripts/run-integration-gate.py` |
| EvidenceReader | `scripts/run-scoped-junit-report.py` |
| BaselineMeter | `scripts/measure-harness-baseline.py` |
| CandidateReceipt | `scripts/emit-candidate-receipt.py` |
| VerdictConsumer | `scripts/consult-harness-verdict.py` |

`Dispatch` es el único sitio que lanza Gradle. Un gate = **un grafo Gradle por fase**
(la receta puede delegar en una herramienta, y el catálogo registra en cuál).

## Reglas que el generador verifica (fail-closed, no avisos)

```text
V1  todo módulo con build file tiene plan de impacto no vacío (se lo pregunta al
    propio ImpactResolver por su CLI: no hay un segundo mapa de impacto aquí)
V2  cada rol resuelve a exactamente un fichero existente; ninguna otra pieza
    reclama un nombre de rol
V3  cada gate existe como receta `just` y el presupuesto del catálogo coincide con
    el default real de la receta
V4  la frontera UAT (`shard.excludes`) está enlazada a la variable del justfile,
    nunca copiada literal
V5  cada suite de `scripts/` existe y declara al menos un `def test_`
V6  ninguna receta anida Gradle (más de una invocación por fase)
V7  un módulo con `forkEvery`/`maxParallelForks` declara su motivo
V9  cada módulo pertenece a exactamente una superficie crítica declarada (o a
    `auxiliary_modules` con motivo); una superficie declarada que ya no casa con
    ningún módulo es drift, y un auxiliar con `src/main` es un defecto
```

Ejemplo de por qué V1/V9 importan: un módulo nuevo sin plan de impacto haría que un cambio en él
**no planificara nada**; un módulo nuevo fuera del mapa de superficies perdería su cobertura de
regresión sin que nadie lo notara. Hoy ambas cosas fallan el gate. Escribir V9 encontró en el
árbol real un módulo que nadie había declarado (`pipeline-sdk-bom`), una doble declaración
(`pipeline-step-sdk/api`) y una entrada obsoleta.

## Clases de prueba y dónde corre cada una

| Clase | Qué es | Dónde corre |
|---|---|---|
| **pura** | suites herméticas `scripts/test_*.py` (HF0: sin proceso de producto) | `just harness` (todas) y el gate afectado |
| **in-process** | tests Kotlin de módulo (`v2/<módulo>/src/test`) | el gate del módulo, consumidores y `check` |
| **instalada / durable** | batería UAT (CLI instalada, durabilidad, compatibilidad) | **harness externo**; excluida de los gates locales por la frontera `uat_excludes`, cuyos patrones publica el catálogo |

Esta separación es la que evita aplicar `forkEvery=1` (y los costes de arranque de JVM) a
pruebas que no lo necesitan: sólo `pipeline-application` lo declara, con motivo (V7).

## Uso

```bash
just catalogue                 # regenera catalogue.json + CATALOGUE.md
just catalogue-check           # verifica frescura (lo mismo que hace `just fitness`)
python3 scripts/gen-test-harness-catalogue.py --check
python3 scripts/test_gen_test_harness_catalogue.py   # 18 tests del generador
```

## Políticas (índice, no copia)

Las políticas de prueba **no** se copian aquí; su autoridad es única y vive donde le
corresponde. El catálogo las indexa:

- redacción de pruebas, protocolo de cuelgues, presupuestos → `AGENTS.md` §V2 TESTING RULES
- escalera de gates y qué certifica cada nivel → `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md` §4
- estados de certificación de Steps → `docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md`
- niveles HF0..HF6 (propuesta) → `docs/v2/04-adrs/ADR-0072-layered-test-harness-fidelity.md`
- frontera candidata/release con el harness externo → `docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md` §5.4
- catálogo de migración de responsabilidad (éxodo de UAT) → `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`

## Lo que este catálogo NO es

- No es un runner: no ejecuta nada. Ejecutar es `just <gate>`.
- No es una lista de allowlists para ocultar fallos: los `OPEN_GAP` los reporta
  `just fitness`, y `--strict` falla con ellos.
- No sustituye a los recibos: la evidencia de una ejecución vive en `docs/v2/07-uat/`.
