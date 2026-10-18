---
type: adr
id: ADR-PK-REG-001
title: "El registro de estado de las ADR es decidible: un campo, tres valores, tres formas de superficie"
status: accepted
date: 2026-10-18
ratified: 2026-10-18
deciders: "Rubentxu (product owner): APROBADO 2026-10-18 (gate humano concedido explícitamente)"
resolves: CONFLICT-ADR-STATUS-REGISTRY
supersedes: null
superseded_by: null
related:
  - ADR-0070
  - ADR-0071
  - ADR-0072
  - ADR-0073
  - ADR-0074
  - ADR-PK-DF-002
---

# ADR-PK-REG-001 — El registro de estado de las ADR es decidible

**ACEPTADA 2026-10-18.** Resuelve [`CONFLICT-ADR-STATUS-REGISTRY.md`](CONFLICT-ADR-STATUS-REGISTRY.md),
que se había registrado como conflicto normativo abierto. La resolución no elige un ganador entre
dos posiciones: **demuestra que el conflicto medido no existía**, porque se midió el *parseo*, no
el corpus. Ver §Conteo corregido.

> Gate humano concedido explícitamente por el product owner. El alcance es **el registro de
> estado**, no el contenido de ninguna de las 20 ADR afectadas: §4 corrige un *campo*, no una
> decisión de arquitectura, y por eso no requiere releer las 20.

## Contexto — lo que se midió, y lo que no

El documento de conflicto afirmó tres cosas. Dos se corrigen, y la corrección es el contenido de
esta ADR:

| Afirmación del CONFLICT | Medido 2026-10-18 | Veredicto |
| --- | --- | --- |
| «61 de 111 no tienen campo `status`» | **4** de 111 | El parser del CONFLICT sólo aceptaba la forma `- **Status:** v` en línea. Las otras dos formas existen y son legales. |
| «8 grafías para 4 estados» | **3** estados, 0 grafías distintas | Casing, negrita y backticks son *presentación*, no valores. `PROPOSED`, `**Status:**` y `` `accepted` `` son el mismo valor. No existe un 4.º estado. |
| «16 ADR del rango 0070..0099 declaran `proposed`» | **20** | El subrange estaba mal acotado; el número era una cota inferior de un mismo error. |

Lo que **sobrevive** del CONFLICT, y es real: **20 de los 30 ADR presentes del rango 0070..0099
declaran `proposed`**, entre ellos `ADR-0070`, `0071`, `0072`, `0073` y `0074` — los cinco que
`AGENTS.md` invoca como autoridad MANDATORY y sobre los que está construida toda la STEP
CONSTITUTION. Eso sí es una contradicción real entre dos peldaños de la jerarquía de autoridad.

## Decisiones

### 1. El registro de verdad es el campo del propio fichero

`status:` dentro de `docs/v2/04-adrs/ADR-*.md` es el **único** registro del estado de ciclo de vida
de una ADR. `ACTIVE_DOCUMENTS.md §5` deja de afirmar que *un rango* está aceptado y **cita el
registro**. Ningún índice de rango puede competir con el campo: un rango que se declara aceptado
es una afirmación sobre 30 ficheros que ninguno de ellos puede desmentir.

### 2. El conjunto de valores es cerrado, y tiene exactamente tres

```text
proposed     decisión redactada, no ratificada
accepted     ratificada por su decider, vigente
superseded   sustituida; el enlace va en `superseded_by:`
```

**No existe un cuarto estado, y `deprecated` no lo es.** La retirada se expresa con
`status: superseded` + `superseded_by:` + `supersedes:`, que es un hecho estructural ya presente
en el frontmatter del corpus. Un ADR que se sustituye *también* estuvo aceptada, y el estado
actual no es «retirada»: es «sustituida por otra». Aceptar `deprecated` como cuarto valor sería
añadir un synonym para un caso que la estructura ya cubre, y un synonym en un campo de estado es
una fila más que dos agentes pueden interpretar distinto.

### 3. Hay tres formas de superficie legales, y todas se leen igual

Las tres existen **medidas** en el corpus hoy. Ninguna se inventa aquí:

```text
1.  status: accepted                          frontmatter o viñeta, ADR-0090
2.  - **Estado:** ACCEPTED (…)                sinónimo español, 5 ficheros
3.  ## Status            Accepted · 2026-08-24    encabezado y valor debajo, 4 ficheros
```

Las tres normalizan al mismo valor. **Casing, negrita, backticks y el paréntesis explicativo que
sigue al valor no forman parte del valor** — el valor es el primer token alfabético. Cualquier
otra cosa —ausencia, token fuera del conjunto cerrado, o una forma de superficie no listada— es
**fallo cerrado**.

**Y un fichero declara su estado UNA vez, no dos.** Medido el 2026-10-18: **9 de las 111** tienen
más de una declaración —frontmatter y encabezado `## Status` a la vez— y **2 de esas 9 discrepan**
entre sí. Es la misma clase de defecto que esta ADR viene a cerrar, que se produce dentro del propio
fichero: dos autoridades sobre un valor. La guarda falla cerrado ante la discrepancia y no ante la
redundancia, porque un valor repetido idéntico no admite dos lecturas y uno repetido distinto sí.
Resueltas por evidencia, no por simetría: `ADR-0075` era el campo inline el que estaba obsoleto
—el encabezado cita R1–R6 verdes con commits—; `ADR-0051` era el párrafo el que describía un gate
cuyo verificador ya no está instalado, y lo que decide es que cuatro ficheros de producción la
implementan.

**Una cuarta condición gobierna la forma de encabezado, y sin ella la guarda lee prosa.** Bajo el
`## Status` de `ADR-0075`, la línea siguiente empieza por «`status`, so it cannot trigger on
arbitrary rows»: el encabezado da nombre a la sección, no al estado. Aceptar «la primera línea no
vacía» como valor convierte una frase en un estado. La condición es que la línea **empiece por un
token del conjunto cerrado**; la prosa no lo hace, así que esa forma no declara nada y, si es la
única, el fichero queda SIN ESTADO en vez de declarar una frase.

### 4. El campo se corrige; la decisión no se reescribe

`ADR-0070`, `0071`, `0072`, `0073` y `0074` pasan a `status: accepted`. El motivo es de
*jerarquía*, no de contenido: `AGENTS.md` — peldaño 1 de su propia jerarquía — las invoca como
autoridad vinculante, y un campo `proposed` en el peldaño 4 contradice al peldaño 1. Cuando dos
peldaños discrepan, el superior gobierna **y el campo es lo que se corrige**.

Esto **no ratifica arquitectura que nadie haya leído**. La corrección es sobre el estado, no
sobre el contenido: estas cinco ADR ya gobernaban el código antes de esta ADR y después de ella,
porque `AGENTS.md` las invoca y el código las cumple. Lo que estaba mal era el rótulo.

### 5. Las otras 15 del rango se quedan en `proposed`, y eso es la verdad

De los 30 ADR presentes en 0070..0099, 20 decían `proposed`. Cinco quedan corregidas por §4. Las
**quince restantes se quedan en `proposed`**: nada en `AGENTS.md` las invoca como vinculantes,
no he leído su contenido, y ratificarlas en bloque sería una afirmación sin evidencia — la misma
falta que esta ADR viene a cerrar. Su estado truthful es `proposed`.

Por eso §1 también **estrecha** la línea de `ACTIVE_DOCUMENTS.md`: el rango no es una unidad de
estado, y quien necesite saber si una ADR concreta está en vigor, lee su campo.

## Enforcement

`AdrStatusRegistryFitnessTest` (`:pipeline-architecture-tests`):

1. enumera `docs/v2/04-adrs/ADR-*.md` y lee las tres formas de §3;
2. **falla cerrado** ante ausencia o ante un primer token fuera del conjunto cerrado de §2;
3. **anti-vacuo por conteo**: exige encontrar **≥ 100** ficheros, para que borrar ADRs no lo vuelva
   verde;
4. **anti-vacuo por valor**: exige que los tres valores estén presentes, para que la guarda no
   pueda quedar satisfecha por un corpus homogeneizado a un valor.

## Alternativas descartadas

- **Mas-flippet de las 20 a `accepted`.** Ratificaría contenido no leído. Es exactamente la
  confianza sin evidencia que esta ADR prohíbe.
- **`deprecated` como cuarto estado.** Duplica `superseded`, que ya tiene estructura de enlace.
- **Un índice (`status-index.json`) como registro de verdad.** Sería un segundo registro que
  puede desmentir al campo sin que nadie lo note — el mismo defecto que `lane-planner.json`, que
  se publicó como asset y que nadie leía (ver `ADR-PK-DF-002`, decisión 2b corregida).

## Relación con `CONFLICT-ADR-STATUS-REGISTRY.md`

Ese documento queda **cerrado por esta ADR**. Su tabla de censo y sus tres afirmaciones quedan
corregidas arriba; se conserva por trazabilidad, no como advertencia vigente.