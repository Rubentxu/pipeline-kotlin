---
name: evidence-driven-implementation-decision
description: >-
  Método sistemático para decidir cómo implementar cambios de software complejos
  de forma incremental, verificable y reversible. Combina análisis de autoridad,
  trazado end-to-end, characterization testing, contratos diferenciales,
  property-based reasoning, blast-radius analysis, strangler migration,
  arquitectura emergente y gates GO/STOP.
version: 1.0.0
tags:
  - architecture
  - software-design
  - refactoring
  - legacy
  - migration
  - testing
  - differential-testing
  - contracts
  - strangler-pattern
  - evidence-driven-development
  - architecture-emergence
  - go-stop-gates
---

# Evidence-Driven Implementation Decision

## Propósito

Usa esta skill cuando haya que decidir **cómo implementar algo** y existan varias
soluciones posibles, riesgo de regresión, comportamiento legacy, estado durable,
plugins, protocolos, concurrencia o incertidumbre arquitectónica.

La meta no es encontrar la arquitectura más elegante en abstracto.

La meta es:

> encontrar el cambio más pequeño, reversible y verificable que resuelva el
> problema actual, preserve las leyes arquitectónicas y no bloquee objetivos
> futuros ya conocidos.

---

# 1. Algoritmo rector

```text
observed_current_behavior
        +
target_architectural_laws
        +
known_future_requirements
        +
blast_radius
        +
machine_verifiable_evidence
        ↓
smallest_reversible_next_change
```

Nunca decidir sólo por gusto arquitectónico.

---

# 2. Identificar la autoridad real

Antes de modificar código, localizar qué componente decide realmente:

```text
routing
metadata efectiva
validación
replay
retry
recovery
side effects
event emission
state ownership
final outcome
```

Distinguir:

```text
AUTHORITY
CONSUMER
PROJECTION
ADAPTER
CACHE
TEST FIXTURE
HISTORICAL EVIDENCE
```

Ejemplo:

```text
DSL
 ↓
StepNode
 ↓
FamilyResolver        ← autoridad de routing
 ↓
ExecutionBoundary
 ↓
Handler
```

Ley:

> Una implementación nueva no está migrada mientras la autoridad siga enviando
> producción al legacy.

---

# 3. Trazar el camino end-to-end

Construir el flujo completo:

```text
user API
→ compiler/parser
→ canonical model
→ structural preparation
→ routing
→ durable resolution
→ typed decode
→ execution boundary
→ handler
→ side effects
→ outcome
→ events
→ persistence
→ user-visible result
```

Para cada nodo registrar:

```text
INPUT
OUTPUT
AUTHORITY
STATE
SIDE EFFECTS
FAILURE MODE
REPLAY BEHAVIOR
CANCELLATION BEHAVIOR
```

No asumir que una semántica local pertenece sólo al handler.

---

# 4. Separar estructura y semántica

Aplicar:

```text
execution_structure = closed_and_generic
feature_semantics   = open_and_extensible
```

La infraestructura común puede conocer:

```text
lifecycle
routing
retry
replay
journal
capabilities
plugin registration
transport
```

Pero debe evitar:

```text
if step == "foo"
if plugin == "bar"
```

salvo que sea realmente una regla estructural del protocolo.

---

# 5. Characterization first

Antes de cambiar comportamiento:

```text
observe
→ characterize
→ freeze
→ modify
```

Caracterizar:

```text
valid inputs
edge inputs
errors
exceptions
events
effects
replay
retry
cancellation
recovery
concurrency
state
ordering
```

La caracterización responde:

> ¿Qué hace hoy el sistema?

No:

> ¿Qué debería hacer?

---

# 6. Differential Contract Algorithm

Comparar legacy vs candidate:

```text
Dimension
Legacy observation
Candidate observation
Classification
Evidence
```

Clasificaciones válidas:

```text
PARITY
APPROVED_FIX
APPROVED_CONTRACT_DELTA
APPROVED_ARCHITECTURAL_DELTA
CURRENT_SCOPE_DECISION
DEFERRED_IMPROVEMENT
UNKNOWN
```

Gate law:

```text
UNKNOWN_DIFFERENTIALS = 0
```

antes del authority flip.

---

# 7. No-new-validations rule

No endurecer contratos accidentalmente.

Preguntar:

```text
¿legacy rechazaba esto?
¿el contrato lo prohíbe?
¿hay evidencia para cambiarlo?
```

Si no:

```text
PRESERVE
```

Ejemplo:

```text
legacy accepts blank string
candidate accepts blank string
```

hasta que exista un cambio explícito de contrato.

---

# 8. Counterexample-driven abstractions

No crear abstracciones especulativas.

```text
proposed abstraction
 ↓
find concrete counterexample requiring it
 ↓
none?
    defer
exists?
    implement smallest useful seam
```

Ejemplo:

```text
sleep necesita espera cooperativa
→ coroutine delay() ya sirve
→ NO TemporalEngine todavía
```

Reabrir sólo cuando aparezca una necesidad real:

```text
process restart must resume remaining duration
```

---

# 9. Regla 80/20

Priorizar:

```text
present value
+
compatibility with known future goals
```

Evitar:

```text
framework-before-problem
cloud-before-local
distributed-before-single-process
policy-engine-before-policy-need
scheduler-before-temporal-case
```

---

# 10. Blast Radius Analysis

Antes de cambiar:

```text
Who calls this?
Who owns this?
Who persists this?
Who routes this?
Which tests assume this?
Which counters change?
Which code becomes unreachable?
Which code becomes removable?
```

Si el blast radius es alto:

```text
split_change()
```

---

# 11. State-machine migration model

Modelar el progreso explícitamente:

```text
ABSENT
 ↓
REGISTERED
 ↓
IMPLEMENTED_UNCERTIFIED
 ↓
REGISTRY_PRIMARY
 ↓
LEGACY_UNREACHABLE
 ↓
LEGACY_REMOVED
 ↓
CONTRACT_CERTIFIED
 ↓
REAL_SCENARIO_CERTIFIED
```

No colapsar estos estados en un booleano.

---

# 12. Strangler migration por gates

Patrón recomendado:

```text
G0 Characterize
G1 Candidate
G2 Differential contract
G3 Migration readiness
G4 Authority flip
G5 Legacy removal
G6 Contract certification
G7 Real integration evidence
G8 Final certification
```

## G0 — Characterize

Sin cambios de producción.

## G1 — Candidate

Nueva implementación registrada, todavía no primaria.

## G2 — Differential contract

Todas las diferencias clasificadas.

```text
UNKNOWN = 0
```

## G3 — Readiness

Demostrar que el nuevo camino funciona y que no hay dependencia oculta del legacy.

## G4 — Authority flip

Cambiar sólo routing/authority.

## G5 — Legacy removal

Eliminar implementación legacy físicamente.

## G6 — Contract certification

Suite común de contrato.

## G7/G8 — Real scenario certification

Artefacto instalado + escenario real + fresh/replay/rerun/resume según aplique.

---

# 13. GO / STOP protocol

Cada gate termina en:

```text
GO
```

o:

```text
STOP
```

STOP obligatorio si:

```text
UNKNOWN differential exists
unexpected regression appears
authority is ambiguous
new abstraction becomes necessary
baseline widens
real scenario diverges
```

---

# 14. Historical truth rule

No reescribir tests antiguos para fingir que siempre describieron el estado nuevo.

Incorrecto:

```text
G3 expected LegacyCore
G4 arrives
modify G3 test to expect Registry
```

Correcto:

```text
archive/disable G3 historical fitness
add new G4 fitness
```

Los tests de transición son evidencia histórica.

---

# 15. Baseline preservation / Rule-16

Si el baseline ya tiene fallos:

```text
baseline_failure_set
```

un cambio no amplía deuda cuando:

```text
failure_set_after == baseline_failure_set
```

Regresión:

```text
failure_set_after ⊃ baseline_failure_set
```

Verificar en worktree limpio cuando sea necesario.

---

# 16. Property-based reasoning

Derivar leyes verificables:

```text
MEMOIZED + SUCCEEDED
=> handler invocation count <= 1
```

```text
missing capability
=> handler invocation count = 0
```

```text
unknown event kind
=> emitted events = 0
```

```text
parent cancellation
=> boundary must not swallow CancellationException
```

```text
LEGACY_REMOVED
=> command absent
 ∧ decoder absent
 ∧ dispatcher absent
 ∧ metadata absent
```

---

# 17. Negative-space testing

Probar también ausencia:

```text
no duplicate event
no second execution
no hidden fallback
no legacy path
no capability leak
no unexpected state mutation
no extra network call
```

---

# 18. Replay algorithm

Toda operación durable debe responder:

```text
fresh
replay succeeded
replay failed
resume running
rerun
```

Preguntas:

```text
¿se vuelve a ejecutar?
¿se reutiliza resultado?
¿se duplican efectos?
¿se duplican eventos?
¿se conserva outcome?
```

---

# 19. Cancellation ownership

Ley:

```text
parent owns structured cancellation
```

Infraestructura genérica no debe convertir automáticamente:

```text
CancellationException
```

en:

```text
domain failure
```

Si un Step posee un timeout funcional, modelarlo como resultado de dominio explícito.

---

# 20. Capability design

Una capability debe ser:

```text
small
typed
explicit
declared
fail-closed
single-responsibility
```

Evitar:

```text
PipelineContext
RuntimeServices
ServiceLocator
Map<String, Any>
```

Preferir:

```text
EventSink
ShellOperations
StageIdentity
WorkspaceAccess
CredentialAccess
ArtifactStore
```

Ley:

```text
handler needs X
→ contract declares X
→ admission verifies X
→ handler receives only X
```

---

# 21. Authority flip law

El flip debe ser pequeño.

Ideal:

```text
remove key from legacy authority set
```

No mezclar:

```text
flip
+ deletion
+ semantic redesign
+ validation changes
+ new storage
```

---

# 22. Legacy removal law

Distinguir:

```text
UNREACHABLE
REMOVED
```

Definición genérica:

```text
LEGACY_REMOVED(feature) =
    old_routing_absent
 ∧ old_command_absent
 ∧ old_decoder_absent
 ∧ old_dispatcher_absent
 ∧ old_metadata_absent
```

---

# 23. Exact-set fitness tests

Cuando existe un catálogo:

```text
legacy ids
metadata
dispatchers
plugins
capabilities
```

usar:

```kotlin
assertEquals(expectedSet, actualSet)
```

en vez de sólo:

```kotlin
assertFalse(actual.contains("foo"))
```

---

# 24. Counter-based migration evidence

Si hay representaciones paralelas:

```text
routing ids / metadata / dispatchers
```

usar snapshots:

```text
before flip      11 / 11 / 11
after flip       10 / 11 / 11
after removal    10 / 10 / 10
```

Un mismatch transitorio puede ser correcto si es explícito.

---

# 25. Real-scenario certification

Unit tests no bastan.

Certificar con:

```text
real installed distribution
real DSL / config
real persistence
real process invocation
real exit code
```

Según aplique:

```text
fresh
replay
rerun
resume
```

---

# 26. Stable vs volatile evidence

Comparar semántica estable:

```text
event type
runId
payload
outcome
cardinality
ordering
```

Excluir campos volátiles:

```text
UUID
timestamp
temporary path
PID
random port
```

---

# 27. Failure taxonomy

Separar:

```text
USER
SCHEMA
TIMEOUT
CANCELLED
INFRASTRUCTURE
ENGINE
POLICY
EXTERNAL
```

Preguntar:

```text
¿es fallo esperado de dominio?
¿input inválido?
¿bug?
¿infraestructura?
```

No usar excepciones para outcomes normales cuando puede existir un ADT.

---

# 28. ADT-first semantics

Preferir:

```kotlin
sealed interface Result

data object Success : Result
data class Rejected(...) : Result
data object Unstable : Result
```

sobre:

```text
boolean
nullable fields
magic strings
exceptions
```

---

# 29. Decidir si introducir una nueva abstracción

Checklist:

```text
1. ¿Qué problema concreto existe?
2. ¿Las primitivas actuales lo resuelven?
3. ¿La solución violaría una ley arquitectónica?
4. ¿Existe un segundo caso independiente?
5. ¿Reduce complejidad o sólo la desplaza?
6. ¿Se puede testear aisladamente?
7. ¿Es reversible?
```

Regla:

```text
one speculative caller -> defer
two independent cases sharing same law -> consider abstraction
```

---

# 30. Arquitectura emergente

Evolucionar así:

```text
hypothesis
→ spike
→ characterization
→ minimal implementation
→ measured friction
→ refine architecture
```

Cada decisión provisional debe tener:

```text
decision
evidence
tradeoff
revisit trigger
```

---

# 31. Revisit triggers

No escribir "en el futuro quizá".

Escribir:

```text
Introduce distributed transport WHEN:
  more than one machine needs live event delivery

Introduce temporal persistence WHEN:
  process restart must preserve remaining wait

Introduce policy engine WHEN:
  independent external plugins need admission policy

Introduce state capability WHEN:
  a second stateful Step needs per-run durable state
```

---

# 32. Design scoring heuristic

Puntuar 0–5:

```text
Present value
Correctness
Reversibility
Testability
Blast radius
Future compatibility
Operational complexity
Cognitive complexity
```

Preferir la opción más simple salvo que un requisito actual obligue a la más general.

---

# 33. Change budget

Cada gate debería cambiar una dimensión principal:

```text
G1 implementation
G2 semantics
G3 readiness
G4 authority
G5 deletion
G6 certification
```

Si un cambio toca simultáneamente:

```text
routing
protocol
semantics
storage
API
tests
```

dividir salvo necesidad demostrable.

---

# 34. Evidence hierarchy

Orden recomendado:

```text
1. real production-path test
2. installed CLI / UAT
3. integration test
4. contract suite
5. unit test
6. source inspection
7. documentation
8. assumption
```

---

# 35. Decision template

```markdown
## Goal

## Current authority

## Current execution path

## Observed semantics

## Architectural laws

## Candidate designs

### Option A
### Option B
### Option C

## Counterexamples

## Blast radius

## Differential matrix

## Chosen smallest reversible change

## Tests / evidence

## Exit state

## Deferred work

## Revisit triggers

## GO / STOP
```

---

# 36. Gate receipt template

```markdown
# <Feature> / <Gate>

## Base

## Scope

## Production changes

## Behavioral evidence

## Differential matrix

## Counters

## Test results

## Real scenario evidence

## Known baseline failures

## Exit state

REGISTERED =
PRIMARY =
LEGACY_UNREACHABLE =
LEGACY_REMOVED =
CONTRACT_CERTIFIED =
REAL_SCENARIO_CERTIFIED =

## Deferred

## STOP
```

---

# 37. Anti-patterns

Evitar:

```text
Big-bang migration
Framework-first
Fake parity
Happy-path-only certification
Exception-driven domain semantics
Hidden fallback to legacy
Service locator capabilities
Keeping dead production code for old tests
Semantic cleanup during migration
```

---

# 38. Compact decision engine

```text
function decide(change):

    authority = find_current_authority(change)

    path = trace_end_to_end_execution(change)

    baseline = characterize_current_behavior(path)

    laws = collect_architectural_invariants()

    candidate = smallest_solution(
        solves=current_problem,
        preserves=laws,
        enables=known_future_goals,
        avoids=speculative_abstractions
    )

    differential = compare(baseline, candidate)

    classify_every_difference(differential)

    if UNKNOWN exists:
        STOP
        characterize_more()

    blast = calculate_blast_radius(candidate)

    if blast too_large:
        split_change()

    tests = derive_properties(
        positive_behavior,
        negative_space,
        replay,
        cancellation,
        failures,
        state
    )

    implement_candidate_without_authority_flip()

    verify()

    if evidence insufficient:
        STOP

    flip_only_authority()

    verify_real_path()

    remove_legacy_mechanically()

    certify_contract()

    certify_real_scenario()

    mark_complete()
```

---

# 39. Final law

> No diseñar desde lo que imaginamos que el sistema debería ser. Diseñar desde
> lo que el sistema realmente hace, las leyes que queremos garantizar y la
> evidencia mínima necesaria para mover una sola frontera cada vez.
