# S2-C — Composición de directivas: recibo de slice

**Fecha:** 2026-09-30
**Slice:** S2-C (TRAIN S2 directives, tercer work unit de la línea: S1-B kernel → S1-C observabilidad → S2-A gate → S2-B post → **S2-C composición** → S2-D directiva externa)
**Base:** `0561a4b9` (cierre de WU-1) · **HEAD de este slice:** el commit que
añade este recibo. Su SHA se registra en la evidencia del gate SDDK
(`implementation-complete`) y no aquí, para evitar una autorreferencia circular:
un SHA escrito dentro del contenido que ese mismo commit crea no puede ser el
SHA de ese commit.
**Ciclo SDDK:** `p-1f3622e11c093341/train-s2-directives`, fase build
**Ruta:** A-full (explore → specify → design → plan → build)

## 1. Qué resuelve este slice

El kernel de directivas (S1) ya era abierto por clave, fail-closed y observable
(S1-C). S2-A añadió la primera política real (`Gate`) y S2-B los finalizadores
`post`. Faltaba la **composición**: qué significa que un stage declare
VARIAS directivas.

El análisis del código (no inferido) encontró tres defectos reales:

| # | Defecto | Consecuencia |
|---|---------|--------------|
| D1 | El coordinador **re-derivaba** los gates: tras consumir `StageDirectiveDecision.Permitted` para los eventos de admisión, volvía a escanear `stage.directives` + registry para localizar políticas `Gate` | La lista evaluada podía divergir de la lista admitida |
| D2 | **Sin política de composición**: N gates se evaluaban en bucle con AND implícito, sin decisión tipada, sin detección de claves duplicadas | AND silencioso; `when` declarado dos veces se ejecutaba dos veces sin avisar |
| D3 | **Veredicto invisible**: `GateVerdict.Satisfied` no emitía evento; sólo se veía la ausencia de `StageSkipped` | Un gate satisfecho y un stage sin gates eran indistinguibles para un observador externo |

## 2. Diseño implementado (coincide con el artifact de design del ciclo)

- **D1 → `GateCompositionPlanner` + `GateCompositionDecision`** (domain, puro,
  sin I/O, sin reloj, sin eventos). `Composite(AllOf(children))` por orden de
  declaración, `Empty` como elemento neutro, `Conflicting` para clave duplicada.
- **D2 → el intérprete consume la decisión**: se eliminó el segundo escaneo;
  los gates interpretados son exactamente los admitidos en `BEFORE_STAGE` con
  política `Gate`. La composición delega el conflicto al planner (fuente única
  de la decisión; el intérprete no reimplementa la política).
- **D3 → evento `GateEvaluated`**: `satisfied` + `directiveKeys` (orden de
  declaración) + `reason`. Emitido antes de resolver el destino del stage
  (run / skip / fail). `Unverifiable` emite además `DirectiveDenied` y falla
  cerrado, como en S2-A.
- **Bono arquitectónico descubierto durante la implementación**: la producción
  decodificaba los argumentos de un gate con un **switch por clave** en
  `CompositionRoot` (`when (definition.key)`) porque el registro no exponía el
  decodificador. Se añadió `DirectiveDefinitionAny.decodeAny(...)` (el punto
  lawful de borrado de tipos, junto al que ya existía para key/phase/policy) y
  se eliminó el switch. Consecuencia: **cualquier gate de cualquier plugin
  ahora se decodifica con SU propio codec, sin tocar el core** — la ley
  "open by key" deja de tener una excepción escondida.
- **Seam muerto eliminado**: el parámetro inyectado `gateDecoder`
  (coordinador + `CoordinatorCaps` + `CompositionRoot`) quedó sin uso tras
  `decodeAny` y se borró. Menos superficie, menos vías de divergir.
- **Erasure CHECKED, no casteada** (hallazgo de revisión de calidad, D4): la
  primera versión hacía `result.input as WhenPredicate`. Una definition que
  declara policy `Gate` pero cuyo codec devuelve otra cosa habría producido un
  `ClassCastException` escapando del run loop, no un fallo tipado. Se sustituyó
  por el par sellado `DecodedGate { Predicate, Denied }`: una contradicción es un
  valor que el coordinador deniega fail-closed (`FailureKind.USER` +
  `DirectiveDenied`, sin cuerpo, sin veredicto). Cubierto por
  `a gate definition that decodes to a non-predicate is denied fail-closed,
  never cast` (5º test del intérprete).
- **Seam que S2-B había declarado cerrado por imposibilidad** (hallazgo de
  revisión, D5): `S2BPostCoordinatorIntegrationTest` dice literalmente que la
  ruta gate→skip→post no se puede probar "without a gate directive". Con la
  composición ya existente esa directiva existe, así que el test se escribe
  ahora: `a composed negative gate skips the stage and still runs the ALWAYS
  finalizer` (6º test). El skip dispara el finalizador ALWAYS con opciones de
  shell reales y con el entorno proyectado del stage, aunque el stage nunca
  corrió y su workspace nunca existió.

## 3. Archivos tocados

```text
events/    DomainEvent.kt (ADT GateEvaluated), EventJsonWriter.kt,
           JsonEventLog.kt (decode), InMemoryEventStore.kt + SqliteEventStore.kt
           (sequence), identity/{EnvelopeProjector,SequenceAssigner}.kt,
           test/…/GateEvaluatedEventRoundTripTest.kt (nuevo),
           test/…/DomainEventRoundTripTest.kt (pin 55 → 56 con historia)
domain/    directive/DirectiveRegistry.kt (decodeAny),
           directive/GateCompositionPlanner.kt (nuevo),
           test/…/GateCompositionPlannerTest.kt (nuevo)
application/ durable/CanonicalDurableRunCoordinator.kt (intérprete),
           durable/CoordinatorCaps.kt (seam muerto fuera),
           CompositionRoot.kt (switch por clave fuera),
           test/…/S2CGateCompositionInterpreterTest.kt (nuevo),
           test/…/S2AWhenGateInterpreterTest.kt (adaptado al seam decodeAny)
```

`pipeline-scripting-api`: **sin cambios** (la superficie DSL ya producía
listas; la composición es semántica del intérprete, no sintaxis nueva).

## 4. Evidencia ejecutada (SHA-exacta, este slice)

| Nivel | Comando | Resultado |
|---|---|---|
| L1 RED | `:pipeline-events:compileTestKotlin` (antes del evento) | falla por `Unresolved reference 'GateEvaluated'` — RED por la razón esperada |
| L1 RED | `:pipeline-application:test --tests S2CGateCompositionInterpreterTest` | 4/4 fallan por las razones esperadas (sin `GateEvaluated`, duplicado no denegado) |
| L1 GREEN | `:pipeline-events:test` | **219/219** |
| L1 GREEN | `:pipeline-domain:test` | **631/631** |
| L1 GREEN | `:pipeline-application:test --tests S2CGateCompositionInterpreterTest` | **4/4**, luego **6/6** tras los hardings D4 y D5 |
| L2 regresión directivas | S1B + S1C + S2A + S2B + S2C + ContractSuite + CoreWhen | **31/31**, luego **47/47** (11 clases) |
| L0/L2 | `:pipeline-application:compileTestKotlin` tras quitar `gateDecoder` | verde |
| detekt | events + domain + application | verde |
| apiCheck | events + domain (+ apiDump) | verde |
| fitness estático | 0 casts sin comprobar sobre `WhenPredicate`, 0 `when (definition.key)` en producción | verificado por grep |
| L4 delta | domain+events+application+scripting-api `--rerun-tasks` | ver §5 |

Round-trip `GateEvaluated`: 4/4 (incluye razón con comillas y no-ASCII, y lista
de claves vacía). Planner: 6/6 (orden, `AllOf`, duplicado, `Empty`,
idempotencia de predicados iguales bajo claves distintas, determinismo).

## 5. El L4: un fallo de timing NO es una regresión (evidencia base-vs-head)

El primer L4 con mi slice falló en **1** test:

```text
WalkParallelFrameConcurrencyTest.3 branches x 100ms each complete in 150ms total()
  → Expected concurrent execution ≤150ms but took 156ms
```

Clasificación con evidencia, no por inspección (regla 16):

1. `git diff --name-only` del slice: **no toca `application/walk/`** ni nada
   de concurrencia de ramas.
2. Re-ejecución aislada de esa clase con el slice presente: **1/1 verde**.
3. Worktree method: `git stash` del slice → base `0561a4b9` → L4 con
   `--rerun-tasks`, **73/73 tareas ejecutadas realmente**, 18m44s,
   `application` **1851 tests, 0 fallos**, `WalkParallelFrameConcurrencyTest`
   fresco (timestamp 17:27:46Z) y verde.
4. El **mismo L4 del head**, 73/73 tareas, 18m35s: **3445 tests, 0 fallos,
   0 errores**, y `WalkParallelFrameConcurrencyTest` fresco (19:46:42Z) y verde.
5. El run base con 4 módulos en paralelo y `maxParallelForks` es exactamente el
   escenario que produce 156ms: 3 ramas de 100ms concurrentes ≈ 100ms con
   margen de 50ms; 6ms de desviación es ruido de carga, no semántica.

**Veredicto:** fallo de **flakiness de timing bajo carga**, preexistente y
ajeno a este slice, confirmado por comparación base-vs-head con el mismo
comando y el mismo número de tareas. NO se registra como PASS ni como regresión
de S2-C; queda declarado como **deuda observada con criterios vigentes** (el
umbral 150ms no tiene margen suficiente bajo saturación; la regla 11 de
AGENTS.md ya advierte que las suites con semántica de timing se degradan bajo
contención de CPU).
**No se modifica** en este slice: tocar ese umbral sin un análisis de
concurrencia propio sería evidencia débil.

## 6. Cierre de referencia (investigación de implementaciones de referencia)

```text
Reference implementation consulted:  Jenkins Declarative "when { beforeAgent true /
                                       allOf { environment name: X, value: Y } }"
                                       (jenkinsci/pipeline-model-definition, definición
                                       `WhenCondition`/`allOf`), más la ley propia ADR-0069
                                       (admisión fail-closed) y el precedente directo S2-A.
Behaviour adopted:                 composición declarativa por orden de escritura, con
                                    la conjunción ESTRUCTURAL (AllOf) de la decisión pura
                                    en vez de un bucle imperativo; fallo cerrado en
                                    conflicto de declaración.
Intentional deviations:            duplicado de la MISMA clave = Conflicting (fail-closed).
                                    Jenkins combina conditions repetidas sin quejarse;
                                    aquí dos `when` sobre un stage es un error del autor
                                    y esconderse tras un AND sería la immoralidad
                                    silenciosa que el Semantic Honesty Gate elimina.
                                    ALL gates (no sólo `beforeStage`) porque el motor
                                    tiene las tres fases; el DSL de Jenkins no tiene
                                    `afterAgent`, y en un stage tampoco tendría
                                    sentido para una condición de admission.
Security implications reviewed:   el gate sigue leyendo SÓLO el entorno que el stage
                                    DECLARA (sin ambiente arbitrario del proceso):
                                    sin cambio respecto de S2-A. La composición no
                                    introduce ninguna lectura nueva.
Tests demonstrating the contract:  GateCompositionPlannerTest (6),
                                    S2CGateCompositionInterpreterTest (6),
                                    GateEvaluatedEventRoundTripTest (4),
                                    S2AWhenGateInterpreterTest (8, seam decodeAny),
                                    DomainEventRoundTripTest (pin 56)
```

## 7. Criterios de salida del WU-2 (R6..R10 de la especificación del ciclo)

| # | Criterio | Estado | Evidencia |
|---|---|---|---|
| R6 | Varias directivas, todas admitidas e interpretadas en orden de declaración | CUMPLIDO | `two satisfied gates…` (4/4) + planner `AllOf` order test |
| R7 | Políticas declaradas respetadas, sin branching por clave | CUMPLIDO | `decodeAny` elimina el `when (definition.key)` de producción; `LockDirectiveDefinition` de test prueba policy-not-key |
| R8 | Conflicto real = typed failure fail-closed, no orden arbitrario | CUMPLIDO | `a duplicate gate key is denied fail-closed…` (USER + DirectiveDenied + sin StageStarted) |
| R9 | Evento de decisión observable por composición, ceremonia completa | CUMPLIDO | `GateEvaluated` en 7 sitios (ADT, writer, decoder, 2 stores, assigner×2, envelope) + round-trip + pin 56 |
| R10 | Suites delta verdes + gate de ronda | CUMPLIDO | L1/L2 verdes; L4 ver §5 (timing flake ajeno, base y head analizados) |
| R11 (añadido en revisión) | La borradora de tipos de un gate no puede crashear el run | CUMPLIDO | `DecodedGate` sellado + test 5/5; 0 casts sin comprobar |
| R12 (añadido en revisión) | Un gate negativo integrado con los finalizadores se comporta como skip real | CUMPLIDO | test 6/6: StageSkipped observable, ALWAYS se ejecuta, SUCCESS no, cuerpo intacto |

**Corrección de aserción durante la implementación (sin ocultar culpa).** El
test 6 salió RED en su primera versión: afirmaba
`selectedConditions == [ALWAYS, CLEANUP]` y obtuvo `[ALWAYS]`. La aserción era
mía, no del código: `CLEANUP` se selecciona sólo si el autor lo declara, y este
test no lo declara. Se corrigió la expectativa al contrato real (y se dejó el
comentario que lo explica). Regla 21 aplicada: el RED falló por la razón
esperada y la corrección fue del test, no un debilitamiento — se siguió
observando la selección real de `PostPlanner`.

## 8. Lo que NO cubre este recibo

- **S2-D** (directiva externa vía SDK / discovery): sin cambios aquí, fuera del
  WU-2 declarado. `examples/example-directive-plugin` (`acme.lock`, política
  `Evaluate`) sigue compilando y es el siguiente incremento natural.
- Semántica de `Evaluate` y `ProvideContext`: se admiten y se observan
  (`DirectiveAdmitted`), pero su interpretación sigue sin implementar (decisión
  explícita del design, D5).
- Journal/replay y `PostSpec`: sin tocar.
- El flake de `WalkParallelFrameConcurrencyTest`: deuda observada, no reparada
  aquí (§5).

## 9. Cierre del gate sobre el árbol commiteado (corrección post-commit)

La evidencia de §4-§5 se produjo sobre un árbol sin commitear. Al ejecutar el
L5 `check` completo (`--rerun-tasks`) sobre el árbol commiteado, el gate de
cierre destapó dos defectos que el flujo pre-commit no vio, ambos corregidos
en `020c4787`:

1. **`pipeline-domain.api` desfasado**: el apiDump declarado verde en §4 se
   tomó antes del bono arquitectónico (`decodeAny`), así que el dump no
   declaraba el nuevo método público y `apiCheck` falló. Regenerado.
2. **Pin de exhaustividad en `FArchL7DomainEventExhaustivityTest`**: S2-C movió
   el pin de `DomainEventRoundTripTest` (55→56) pero no este, dejando la
   ceremonia R9 a medias. Subido a 56 con su entrada documentada.
3. **Falso rojo ambiental, no corregido aquí**: `DurableShellTerminalAdapterTest`
   falló porque en la sesión de ejecución el `rm` del PATH es un wrapper de
   papelera que imprime a stdout, contaminando la salida capturada por el
   executor de compatibilidad. Re-ejecutado sin el wrapper: 9/9 verdes. En un
   entorno limpio no reproduce.

**L5 final sobre `020c4787` (árbol limpio, wrapper fuera del PATH):**
`BUILD SUCCESSFUL in 18m 46s`, 276/276 tareas ejecutadas realmente,
**3964 tests, 0 fallos, 0 errores, 130 skipped** (application 1857, domain 631,
events 219, architecture 344, step-sdk/runtime 201).

**CI de GitHub Actions: NOT_RUN por migración, no por ausencia de señal.**
El workflow `LPR-0 CI` se disparó en cada push, pero sus 4 runners self-hosted
están offline y 60 runs consecutivos terminaron `cancelled`
(`concurrency: cancel-in-progress` mata el run anterior antes de que ningún
runner lo recoja). El equipo migró la verificación de CI a dogfooding con
PipelineK local; la interacción restante con GitHub Actions debe eliminarse.
Registrado en el backlog SDDK como
`bl-bl-01M3STVZ0Z000387KNMMQ96E00` (origen: este ciclo, fase build).
