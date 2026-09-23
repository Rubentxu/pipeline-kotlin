# ADR-0094 — Common impact-selection policy shared by local CI, Actions and pipelinek dogfood

**Estado:** proposed (2026-09-23).
**Autor:** pipeline-kotlin (Rubentxu) under auto-run mode, INITIATIVE_LPR_001.
**SHA base:** 74b40a65 (post WU-RP-040 R5..R8 + LPR-0 hardening).
**Aplica a:** V2 desde este SHA, una vez firmada. Cubre el gap que
`docs/v2/05-roadmap/CI_TEST_RELEASE_WORKFLOW.md` §5 declara explícitamente
como **NOT_RUN/BLOCKED**.

## Contexto

`docs/v2/05-roadmap/ROADMAP.md` RP-4 WU-RP-043 define el cierre del
perímetro self-hosted CI con tres niveles:

* N1 bootstrap (compilación + canary, independiente del DSL/registro/ejecutor).
* N2 same-SHA dogfood (`pipelinek` construido desde **este** SHA ejecuta un
  `.pipeline.kts` real del mismo SHA).
* N3 verificación externa (un observador **fuera** del motor verifica
  resultados, logs, informes y artefactos; un fallo intencionado deja CI en
  rojo, un error de DSL no borra el diagnóstico de bootstrap).

El mismo WU-RP-043 declara:

> *El motor de selección por impacto (ADR-0094) será la fuente común de la
> política de testing para local, Actions y pipelinek.*

Es decir, RP-4 da por sentado que **existe** un ADR-0094 que define **una**
política compartida por los tres carriles. Hasta hoy:

1. El ADR-0094 **no existe en el repositorio** (gap observado en
   `git ls-tree HEAD docs/v2/04-adrs/ | grep ADR-0094` →
   `docs/v2/05-roadmap/CI_TEST_RELEASE_WORKFLOW.md` autoridad §5 declara
   `ADR-0094 is missing from the repository. Do not claim that its
   policy is implemented or accepted until the ADR exists and is
   accepted`).
2. Tres carriles ejecutan hoy con políticas **independientes**:
   * `v2/gradlew` local — directives del operador (no compartidas con CI).
   * `.github/workflows/lpr0-ci.yml` LPR-0 — política fixa SHA-pinneada.
   * Dogfood N2/N3 con `v2/compatibility/01-basic.pipeline.kts` y
     `ci/dogfood-fail.pipeline.kts` — política reducida a dos escenarios
     éxito/fallo.

El resultado, observado durante el ciclo de cierre de WU-RP-040 R5..R8 y
LPR-0 hardening (commits `4641fb55` / `4b254cb7` / `74b40a65`), es que
**el mismo cambio** debe justificarse contra tres barómetros distintos. La
canibalización es operativa: cambia la lista de un carril, no cambia la de
los otros dos, y no hay una sola autoridad que diga "este cambio pasa".

## Decisión (propuesta)

Adoptar un **único proceso declarativo** de selección de impacto, instalado
en el repositorio, que produce tres proyecciones a la vez:

```text
impact-policy.adoc            # autoridad única, firmada aquí
  ├── projects/local.md      # proyección para `v2/gradlew :pipeline-application:test --tests '...'` en sesión del operador
  ├── projects/actions.md    # proyección para `.github/workflows/lpr0-ci.yml` (matrix.include + -Pshard.excludes)
  └── projects/dogfood.md    # proyección para `v2/compatibility/*.pipeline.kts` y `ci/dogfood-*.pipeline.kts`
```

La entrada es un único `ImpactArtifact { base_sha, head_sha, changed_paths[],
risk_class }`. La salida es un único `ImpactDecision { local: ..., actions: ...,
dogfood: ... }`. Cada proyección nombra test/escenario/jobs **explícitos**
(por método, por shard, por escenario), nunca `*:test` a ciegas.

El motor **no se inventa** selecciones a partir de heurísticas no firmadas;
cualquier nueva clase de impacto requiere una entrada explícita en este ADR
o en su sucesor.

## Consecuencias (positivas)

* **Una sola decisión humana** por cambio basta para los tres carriles.
* El run dogfood deja de depender de `--tests` como fallback; pasa a
  ejecutar exactamente las mismas selecciones que local y Actions o, cuando
  hay diferencia material entre carriles, la diferencia queda explícita en
  `projects/{local,actions,dogfood}.md`.
* La regla "no broadened capability silently" del bloque normativo
  `CI_TEST_RELEASE_WORKFLOW.md` §1.6 ya no requiere un bypass por carril.

## Consecuencias (negativas / trade-offs)

* Introduce un archivo nuevo `impact-policy.adoc` en la raíz del repo, con
  tres proyecciones derivadas. Aumenta superficie a auditar.
* Si el cálculo de impacto cambia entre local y Actions por motivos no
  firmados (p.ej. caché fría en Actions), la política común exige documentar
  la diferencia o restringir la matriz.

## Alternativas consideradas

1. **Dejar las tres políticas independientes** (status quo). Rechazada:
   el ROADMAP pide expresamente una fuente común.
2. **Elegir Actions como autoridad** y derivar local + dogfood. Rechazada:
   Actions no corre tests locales (herramientas, JDK, OS) y proyecta así una
   cobertura incompleta hacia local.
3. **Elegir local como autoridad** y derivar Actions + dogfood. Similar a
   la anterior pero al revés; misma cobertura incompleta hacia Actions.
4. **Declarar una autoridad compartida sin proceso de proyección** (sólo un
   texto que diga "todos hacen lo mismo"). Rechazada: no es verificable.

## Alcance preciso de esta propuesta

* `impact-policy.adoc` se firma como parte de esta ADR.
* Las proyecciones `projects/{local,actions,dogfood}.md` se generan
  inicialmente **vacías** explícitamente, con la nota "TODO: initial
  projection list from human authority on first real WU". Esto evita
  afirmar `PASS` antes de tener contenido real.
* La primera WU que invoque `impact-policy.adoc` debe crear el primer
  artefacto `DGF_*` en `docs/v2/07-uat/dogfood/<yyyymm>/<sha>-...yaml`
  citando el `ImpactArtifact` que aplicó.

## Pendiente (requiere decisión humana para firmar)

* Firmar esta ADR cambia la forma de evaluar cierres RP-4/RP-5 (los
  criterios quedarían vinculados a `impact-policy.adoc`). Esto entra en
  INITIATIVE_LPR_001 §2.4 ("Change a public certified semantics" → STOP
  y presentar). El estado actual es **proposed**, NO accepted.

## Estado del gap

Esta ADR se publica precisamente para **resolver el gap** que el
`CI_TEST_RELEASE_WORKFLOW.md` §5 declara `NOT_RUN`. Hasta que esté
firmada, el repo continua en el mismo estado:

* `CI_TEST_RELEASE_WORKFLOW.md` §5 nombra el gap, sin proponer fix.
* `ROADMAP.md` RP-4 WU-RP-043 cita el ADR-0094 como "fuente común",
  sin entrar en conflicto con esta propuesta.
* `INITIATIVE_LPR_001` §2.4 protege su firma como human_gate explícito.
