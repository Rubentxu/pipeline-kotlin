# S2-A — `when` condicional tipado: recibo de slice

- **Ciclo**: TRAIN S2 (directivas de stage → semántica real)
- **Predecesor**: S1 (kernel de directivas, seam abierto, sin semántica de gate)
- **Estado**: implementado y verificado en unidad, intérprete y binario instalado
- **Base**: `0e3bc36a` (S2-R0, run ownership)

## Qué se certifica

S1 construyó el kernel de directivas pero ningún `DirectiveExecutionPolicy.Gate` era
interpretado. El resultado observado: una etapa declarada condicional **se ejecutaba
igual** y el run reportaba éxito. S1 cerró ese hueco de la única forma honesta posible
—rechazando `whenCondition(String)` en construcción— pero dejó el producto sin
condicionales.

S2-A entrega el predicado como **valor**, no como texto, y lo interpreta en el
coordinator real.

## Tres decisiones de diseño

### 1. Predicado de tres valores, no booleano

```
Satisfied | NotSatisfied | Unverifiable
```

La distinción que un `Boolean` no puede expresar, y que decide el comportamiento
observable:

| Situación | Veredicto | Efecto |
|---|---|---|
| Variable ausente | `NotSatisfied` | stage omitido, run **exit 0** |
| Variable con valor distinto | `NotSatisfied` | stage omitido, run **exit 0** |
| Ninguna fuente puede resolverla | `Unverifiable` | run **falla cerrado** |

`Unverifiable` **no** se degrada a "no": reportar éxito por trabajo que no se ejecutó
es exactamente la mentira que S1 condena. Y `Not` **preserva** la
inverificabilidad: `not(X == prod)` donde X es ilegible no satisface la puerta; la
cegaría ahí abriría el stage por el combinador más inocente que existe.

### 2. Codec estructural, no texto

Primer intento: bloque multilínea legible + regex. **Defecto real**: un combinador
anidado (`all { … any { … } … }`) fallaba el decode, devolvía `Malformed`, y la puerta
se **descartaba en silencio** — fail-OPEN. Lo detectó `S2A-DSL-005`.

Reemplazado por notación de prefijo con cadenas de longitud declarada:

```
T | F | E <len>:<name> <len>:<val> | P <len>:<name> | A <n> <child>… | O <n> <child>… | N <child>
```

Propiedades **estructurales**, no esperadas: una línea (sin emparejar llaves), aridad
explícita (un payload truncado es `Malformed`, nunca medio predicado), y prefijo de
longitud (todo carácter es legal dentro de un valor, sin escapes que fallar).

La longitud se lee como **número exacto de caracteres**, no hasta el siguiente
espacio: `feature/my branch` se truncaría a `feature/my` y la puerta compararía
distinto. Lo detectó `S2A-CODEC-004`.

### 3. El engine lee la POLÍTICA, nunca la clave

`core.when` no tiene caso propio en el engine. El coordinator recibe un **port**
`gateDecoder: (definition, encodedArguments) -> WhenPredicate?` y decide leyendo
`definition.policy is DirectiveExecutionPolicy.Gate`. Un gate vendor se interpreta por
el mismo camino con **cero cambios en el engine**, demostrado por
`S2A-INT-007`.

## Defectos encontrados y corregidos durante el slice

Todos detectados por test o por el binario real, ninguno por inspección:

| # | Defecto | Detectado por | Dirección |
|---|---|---|---|
| 1 | `directives { }` **asignaba** `stageDirectives`, borrando un `when` declarado antes | `S2A-DSL-003` | fail-OPEN |
| 2 | Regex de códec perdía combinadores anidados | `S2A-DSL-005` | fail-OPEN |
| 3 | Lectura por tokens truncaba valores con espacios | `S2A-CODEC-004` | fail-OPEN silencioso |
| 4 | `gateContext` leía `pipeline.environment`, que el DSL **nunca puebla** | binario instalado | **skip universal** |
| 5 | Invariante de jerarquía sella de eventos sin actualizar | suite completa `pipeline-events` | — |

El defecto 4 es el más instructive: **todos los tests unitarios estaban verdes y el
producto estaba roto**. Los tests del coordinator inyectan un contexto falso, así que
no podían verlo. Solo ejecutar el binario contra un `.pipeline.kts` real lo
expuso —con la gate viendo un mundo vacío y omitiéndose siempre. Por eso la
suite UAT existe y no es opcional.

El defecto 6 lo contradecía el propio comentario que lo acompaña. La precedencia
se extrajo al dominio como función pura y total
(`GateEnvironmentPrecedence.DeclarationWins.resolve`), de modo que la **decisión**
es testeable sin proceso, filesystem ni coordinator, y el adaptador solo captura
el efecto ambiental (`System.getenv()`). El orden corregido es `host + declared`,
coherente con la semántica ya establecida en `EnvironmentComposer`
(base → overlay de stage → `withEnv` → credenciales).

## Observabilidad

Nuevo evento `StageSkipped(stageIndex, stageName, reason)`. Sin él, una etapa
omitida es indistinguible de una que corrió sin hacer nada, y la ley de
observabilidad por step queda incumplida. `reason` hace la pregunta "¿por qué no
corrió mi stage?" respondible desde el stream de eventos.

Un gate **inverificable** NO emite `StageSkipped`: emite `DirectiveDenied` y falla el
run. Reportar un skip para un predicado que nadie pudo leer sería la misma mentira en
otra forma.

El compilador Kotlin obligó a los cinco sitios exhaustivos a manejar el evento nuevo
(`EventJsonWriter`, `InMemoryEventStore`, `SqliteEventStore`, `EnvelopeProjector`,
`SequenceAssigner`) — no se coló ningún `else` que ocultara el caso.

## Evidencia

### Gate Teeth (mutación de la capa cuya autoridad se certifica)

| Mutación | Capa | REDs |
|---|---|---|
| La puerta nunca bloquea | intérprete | 3 |
| `Unverifiable` deja de fallar cerrado | intérprete | 2 |
| La disyunción se vuelve ilegible | códec | 4 (+1 fuera del módulo) |
| La precedencia de entorno se invierte (`declared + host`) | dominio | 2 |

Restaurado: cero residuo de mutación, 49/49 verdes.

### Suites

| Módulo | Tests | Fallos |
|---|---|---|
| `pipeline-domain` | 611 | 0 |
| `pipeline-events` | 212 | 0 |
| `pipeline-scripting-api` | 82 | 0 |
| `pipeline-application` | 1845 (121 skipped) | 0 |
| gate de ronda `check` | 3489 (todo el repo) | 2 (preexistentes, probadas) |

### Gates estáticos que el gate de ronda sí ejecuta

Las corridas dirigidas (`:módulo:test`) no ejecutan `detekt` ni `apiCheck`. El
gate de ronda `check` los ejecuta, y ambos fallaron con defectos REALES
de este slice, no de la infra:

| Gate | Defecto | Corrección |
|---|---|---|
| `detekt` (scripting-api) | `StageScope` con 27 funciones, límite 25 | se extrajeron `whenAll/whenAny/whenNot` a `WhenGateDsl.kt`; superficie DSL idéntica |
| `detekt` (application) | `S2A_WhenGateInterpreterTest`, `UatS2A_WhenGateInstalledBinaryTest` con `_` | renombrados a `S2AWhenGateInterpreterTest` / `UatS2AWhenGateInstalledBinaryTest` |
| `detekt` (application) | `private val TRUE_SCRIPT` en screaming snake | convertidos a lowerCamelCase |
| `detekt` (application) | `runIdOf(tmp, controlRoot, db)` con 2 parámetros sin uso (preexistente de S2-R0) | firma reducida a `runIdOf(controlRoot)` |
| `apiCheck` (domain) | API pública nueva sin registrar en el dump BCV | `apiDump` regenerado (+203 líneas) |
| `apiCheck` (events) | ídem por `StageSkipped` | `apiDump` regenerado (+235 líneas) |

`pipeline-credentials-api` se verificó y su dump ya estaba al día.

### Fallas de arquitectura PREEEXISTENTES, no regresión de S2-A

Dos fitness tests siguen rojos y NO son de este slice. La clasificación se
sostiene con evidencia base-vs-head, no con inspección:

```text
base = 0e3bc36a (origin/main, HEAD antes de S2-A)
git diff --stat 0e3bc36a -- v2/pipeline-release/   ->  VACÍO (byte-idéntico)
```

- `FArchM3CanonicalTaskRuntimeTest` — `ProcessBuilder` en
  `SourceProvenanceProbe.kt:68`
- `Lfc0GlobalStateFitnessTest` — `System.getProperty("user.dir")` en
  `SourceProvenanceProbe.kt:87`

Ambas líneas vienen del commit `555a2818` (candidata P3), y `pipeline-release`
no aparece en el diff de S2-A. Un módulo byte-idéntico al base no puede cambiar
el veredicto de un scanner de fuentes, así que estos rojos existían antes de
S2-A. **No se registran como PASS ni como regresión propia; quedan
declarados como deuda preexistente a corregir en su propio slice.**

### Budget de la suite, no un hang (corrección de un diagnóstico previo)

Una corrida anterior de `:pipeline-application:test` se abortedó por el timeout de
1270s y se particularizó en un supuesto "hang" de
`UatCompat001CorpusSmokeRunTest` y `UatRunConcurrencyCharacterisationTest`.

Diagnóstico correcto, con corrida limpia completa (`timeout 1800`, sin
intervención, log capturado):

```text
BUILD SUCCESSFUL in 21m 29s
pipeline-application: 234 clases, 1845 tests, 0 failures, 0 errors, 121 skipped
UatCompat001CorpusSmokeRunTest          tests=2  failures=0 errors=0
UatRunConcurrencyCharacterisationTest   tests=1  failures=0 errors=0
```

**No hay ningún hang.** La suite es genuinamente lenta y excede el presupuesto de
1270s. El primer fallo fue **presupuesto insuficiente, no defecto de código**. Se
re-deriva el presupuesto de la ronda: 1289s (21m29s) × 1.3 = 1676s, suelo 1800s.

Los dos tests son legítimamente lentos: la clase de concurrencia hace
`await(owner, 180)` y `await(owner, 180)` — hasta 6 minutos por diseño.

### Binario instalado (`UatS2A_WhenGateInstalledBinaryTest`, 4/4)

- `S2A-UAT-001` puerta satisfecha → body corre, sin skip.
- `S2A-UAT-002` puerta negada → **`SHOULD-NOT-RUN` no aparece**, `StageSkipped`
  emitido con `"variable 'DEPLOY_ENV' is 'staging', not 'prod'"`, el stage
  siguiente corre, exit 0.
- `S2A-UAT-003` variable ausente → `"variable 'A_VARIABLE_NOBODY_SET' is not set"`,
  exit 0.
- `S2A-UAT-004` predicado anidado → evaluado, no descartado.

## Cierre de WU (ley de implementación de referencia)

```
Reference implementation consulted:  Jenkins Declarative Pipeline `when` (stage
                                    conditionals) — semántica de absent-vs-false
                                    y de que un skip no es un fallo del run.
Behaviour adopted:                   absent → omitir (exit 0); no verificable →
                                    fallar cerrado; skip observable y con motivo.
Intentional deviations:              sin evaluación de expresiones arbitrarias; el
                                    predicado es un ADT cerrado. La expression-string
                                    de Jenkins requeriría un intérprete que no
                                    existe, que es justo lo que S1 rechazó.
Security implications reviewed:      la puerta solo lee el entorno que el stage
                                    DECLARA, no el ambiente arbitrario del proceso;
                                    si no, el mismo script se comportaría distinto
                                    en máquinas distintas por razones no escritas.
Tests demonstrating the contract:     WhenPredicateTest, WhenDirectiveDefinitionTest,
                                      WhenDirectiveDslTest, S2A_WhenGateInterpreterTest,
                                      UatS2A_WhenGateInstalledBinaryTest
```

## Alcance de este recibo

S2-B (`post`), S2-C (composición de directivas) y S2-D (prueba de directiva externa)
**no** están cubiertos aquí. La política de gate y su seam están certificadas; el
resto de políticas sigue pendiente.
