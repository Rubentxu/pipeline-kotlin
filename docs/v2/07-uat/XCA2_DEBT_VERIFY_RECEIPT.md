# XCA-2 — Debt-Verify Receipt (2026-09-17, cycle/wu-g5b)

**Rol**: debt-verify adversarial (modelo distinto al autor M3).
**HEAD auditado**: `6d4cb17c` (árbol limpio al inicio y al final; ninguna mutación de esta
verificación permanece en el árbol).
**Alcance**: 9 commits desde el checkpoint (5 de apply, 4 de verify). Misión: cazar deuda
que apply y verify dejaron pasar. NO reimplementar, NO aprobar.

**Distinción de evidencia usada en este receipt**:

```text
OBSERVED     — comando ejecutado en vivo en esta sesión, salida capturada (XML/exit/stdout)
STRUCTURAL   — propiedad estática del árbol/commits (git show, grep, conteo de ficheros)
DERIVED      — inferencia lógica a partir de evidencia OBSERVED/STRUCTURAL
DOCUMENTED   — afirmación que solo existe en un doc, sin evidencia de ejecución
```

---

## Resumen ejecutivo

El par RED→GREEN (eb40d5cb → 7d725aca) y los canaries B.5/B.6 (ca4f299c) son **reales,
re-verificables y falsificables** (probados en vivo con mutaciones propias). La democión
de core.echo (cf5dda04) está bien motivada y es honesta. Sin embargo, hay **9 hallazgos
de deuda**, uno de ellos bloqueante para el cierre de XCA-2 tal como está escrito:

```text
H1  Dual-ledger step-certification.yaml con vocabularios incompatibles   [BLOCK]
H2  Falsificación RED/GREEN: VÁLIDA (re-verificada en vivo)             [OK]
H3  B.5/B.6 falsificables: VÁLIDOS (mutados en vivo, fallan por causa)  [OK con notas]
H4  Cadena de custodia correcta, pero la democión deja dualidad         [DEFER]
H5  ADT reconcile correcto; un guard débil (no falsificable)            [FIX]
H6  Ledger v2: vocabulario fuera de la progresión declarada             [FIX]
H7  E: el conteo "20 fixtures / 18 evidencia" es FALSO en OBSERVED      [FIX]
H8  Guards residualmente débiles: regex greedy, exit-code en WARN       [FIX]
H9  Falta el test que falsifique la inyección del mutante               [FIX]
```

---

## Hallazgos

### H1 — Dual-ledger `step-certification.yaml`: conflicto de autoridad NO resuelto [BLOCK]

**Clase**: STRUCTURAL + OBSERVED. **Recomendación**: BLOCK para cierre de XCA-2; requiere
decisión del orquestador (el brief lo prohíbe arreglar unilateralmente).

Archivo y SHA:

```text
docs/v2/status/step-certification.yaml       (canónica; 34 step_key; 65.641 bytes)
v2/docs/v2/status/step-certification.yaml    (XCA-2; 14 entradas; 14.113 bytes)
Ambos tracked en HEAD 6d4cb17c; la canónica SIN tocar desde antes de este ciclo,
la v2/ creada/modificada por 81e49112 + cf5dda04.
```

Reproducir:

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5b
diff docs/v2/status/step-certification.yaml v2/docs/v2/status/step-certification.yaml
```

Causa (three-layer problem, no uno):

1. **Copias divergentes del mismo pathname lógico.** El mismo nombre relativo
   `docs/v2/status/step-certification.yaml` existe bajo la raíz del repo y bajo `v2/`.
   La resolución relativa decide cuál se lee: desde `v2/pipeline-application/`, la ruta
   `../docs/v2/status/step-certification.yaml` apunta a la **copia v2**, NO a la canónica
   (OBSERVED: `XcaCliCanaryTest.kt:288`). Dos lectores del "mismo" ledger leen ficheros
   distintos.
2. **Vocabularios incompatibles.** La canónica usa
   `legacy_state: CERTIFIED_AND_LEGACY_REMOVED` + `certification_state` (33 entradas
   `CERTIFIED_AND_LEGACY_REMOVED`, OBSERVED por grep). La v2 usa `verification_status`
   con una progresión declarada en su cabecera
   (`CURRENT_STEP/STATIC_CANDIDATE/EVIDENCE_READY/CERTIFIED/QUARANTINED/RETIRED`) que
   ni siquiera es la que luego usa el propio fichero (ver H6).
3. **Cobertura de claves disjunta (STRUCTURAL, derivada por script):**

```text
canonica: 34 keys; v2: 14 keys
En canónica y NO en v2 (26): core.archiveArtifacts, core.deleteDir, core.emit.event,
  core.file.writeFile, core.junit, core.load, core.publishHTML, core.pwd.tmp,
  core.waitUntil, example.uppercase, utilities.* (17 claves)
En v2 y NO en canónica (6): core.catchError, core.dir, core.retry, core.timeout,
  core.withEnv, core.writeFile
```

   Las 6 claves de v2-ausentes-en-canónica existen en producción
   (`StepDescriptorRegistry.kt` las registra: `core.catchError:80, core.warnError:100,
   core.withEnv:115, core.dir:128, core.timeout:154, core.retry:187`, OBSERVED). El
   invariante "every production StepKey has a terminal state in the YAML matrix"
   (`Lfc2E0GlobalClosureFitnessTest.every production StepKey has a terminal state...`,
   que lee SOLO la canónica, OBSERVED línea 96) **no puede verlas** porque mira el
   otro fichero. La matrix canónica está, por tanto, incompleta respecto a producción,
   y el fitness que la protege es ciego a esa incompletitud.

Acción recomendada: decidir (a) eliminar la copia v2 y mover su evidencia a un pathname
no-colisionante (p.ej. `docs/v2/status/xca2-evidence-ledger.yaml`), o (b) declarar la v2
como única y migrar la canónica. Mientras coexistan con el mismo pathname relativo,
cualquier consumidor (incluido el regex de B.5) es ambiguo por construcción.

---

### H2 — Falsificación RED→GREEN (eb40d5cb → 7d725aca): VÁLIDA [OK]

**Clase**: OBSERVED (re-ejecutada en vivo en esta sesión). **Recomendación**: ninguna;
es el estándar que el resto del ciclo debería imitar.

Re-verificación independiente (no me fié del receipt, la repetí):

```bash
git checkout eb40d5cb
./v2/gradlew -p v2 :pipeline-events:test --tests '...JournalRunExecutionEvidenceReaderInMemoryTest'
# exit=1; XML: tests="7" failures="1" errors="0"
# message="RUNNING proves execution started — must be observed ==> expected: <true> but was: <false>"

git checkout 7d725aca   # mismo comando
# exit=0; BUILD SUCCESSFUL; XML: tests="7" failures="0" errors="0"
```

Causa EXACTA confirmada: `git show eb40d5cb:...RunExecutionEvidence.kt` línea 46 =
`val isObserved: Boolean get() = status.isTerminal`; `7d725aca` línea 46 =
`status != OperationStatus.PENDING`. El fallo del RED es exactamente el caso
`RUNNING.isTerminal == false` (no un timeout ni un error de compilación). La causa
esperada del brief ("status.isTerminal short-cut regression") es la observada.

Adicionalmente (STRUCTURAL): el diff de producción entre `ca4f299c..6d4cb17c` en
`v2/pipeline-domain/` es vacío (`git diff ca4f299c HEAD -- v2/pipeline-domain/` = sin
salida) — el par RED→GREEN es net-zero; el mutante no vive en HEAD.

---

### H3 — Canaries B.5/B.6 (ca4f299c): falsificables, probados por mutación propia [OK con notas]

**Clase**: OBSERVED. **Recomendación**: conservar; las notas H8 aplican.

Falsifiqué `runCli` (mutación mía, en árbol de trabajo, revertida después; nunca
commiteada) cambiando el nombre del journal (`journal.db` → `journal-*.db`) y ejecuté:

```text
B6 mutante: exit=1; XML message="B.6 canary: journal file MUST exist after a CLI run...
            ==> expected: <true> but was: <false>"   → falla por ESA causa (fichero no está)
B6 revertido: exit=0; XML tests="3" failures="0" errors="0"

B5 mutante: exit=1; XML message="B.5 falsification: STOPPED_G7 steps (core.pwd,
            core.pwd.tmp) MUST be executed... Observed: []. If observed is empty due to
            a dbPath bug..."  → falla por ESA causa
B5 revertido: exit=0; XML tests="3" failures="0" errors="0"
```

Los asserts que ca4f299c introdujo NO son vacuos: cada uno tiene una causa de fallo
distinta y observable. El `assertTrue(true, ...)` original está erradicado de los ficheros
XCA (grep `assertTrue(true|` en tests XCA: solo menciones en comentarios, OBSERVED).

Nota residual (ver H8): el canary 3 de B.5 es un regex sobre el ledger, con debilidades
propias documentadas abajo.

---

### H4 — Cadena de custodia RED→GREEN→demote: correcta; la democión cubre UNA copia [DEFER]

**Clase**: STRUCTURAL. **Recomendación**: DEFER ligado a H1; la democión es honesta
dentro de su copia, pero la dualidad sigue viva.

```text
eb40d5cb  RED   (1 fichero: RunExecutionEvidence.kt, +1/-1)
7d725aca  GREEN (mismo fichero, revert exacto; net-zero vs ca4f299c — OBSERVED)
cf5dda04  demote (SOLO v2/docs/v2/status/step-certification.yaml, 1 fichero, +36/-3)
```

Tres commits separados, SHAs distintos: la cadena de custodia exigida existe. La demote
cf5dda04 cubre únicamente la copia `v2/`. La canónica sigue en
`CERTIFIED_AND_LEGACY_REMOVED` para core.echo con receipts G0..G8 (S3 burn-down). Esto es
**deuda abierta declarada**, no defecto nuevo: el propio mensaje de commit de cf5dda04
reconoce las dos autoridades y exige un certificador XCA-2 distinto antes de tocar la
canónica. El brief del orquestador lo clasificó como deuda a detectar, no a arreglar:
así queda registrado. La resolución correcta pasa por H1 (un solo ledger, o dos con
pathnames no ambiguos y semánticas nombradas distinto).

---

### H5 — Workstream C: ADT correcto; un guard débil; sin `when(stepKey)` [FIX menor]

**Clase**: STRUCTURAL + OBSERVED. **Recomendación**: FIX (fortalecer el guard).

Lo verificado:

- `FixtureEvidence.kt` (pipeline-domain, sin dependencias de proyecto — hexagonal OK):
  dos ejes separados y ortogonales: `FixtureExecutionState` {HasEvidence, NoEvidence,
  ExecutionFailed(reason)} y `StepExpectationRelation` {ExpectedAndExecuted,
  ExpectedButNotExecuted, ExecutedSupporting, ExecutedUnknown}. El conjunto
  {matched, missing, extra} del brief queda cubierto y ampliado con un cuarto caso
  justificado (ExecutedUnknown).
- `reconcile()` es **total y pura**: intersección/diferencia de conjuntos, sin `else`,
  sin I/O, sin ramas ocultas (leído completo, STRUCTURAL).
- Sin `when(stepKey)` ni branch por StepKey en producción ni en el reader (grep en
  pipeline-domain/events/application main sources: cero ocurrencias; la única mención de
  `when (stepKey)` es un comentario que PROHIBE el patrón en `StructuralStepFamily.kt:15`,
  OBSERVED). El `when (result)` del reader es exhaustivo sobre el ADT cerrado
  `RunEvidenceReadResult` — permitido.
- `FixtureReconciliationTest`: 9 tests presentes (grep `@Test` = 9, coincide con lo
  declarado en TESTING-STATE).

Guard débil cazado: el test
`the shortcut status-isTerminal would drop RUNNING from observed`
(`JournalRunExecutionEvidenceReaderInMemoryTest.kt:211-229`) computa
`lawResult`/`shortcutResult` **localmente** (líneas 219-220): compara dos booleanos
calculados en el propio test. No invoca `ExecutedInvocationEvidence.isObserved`, así que
**no puede fallar si la producción muta** — es documentación, no falsificación. El
comentario del test lo admite ("documentation-of-the-falsification"). La ley metodológica
exige que cada guard sea falsificable: este no lo es contra producción. No es grave
porque el test de arriba (línea 191) SÍ ejerce el campo real y fue el que enrojeció en
eb40d5cb; pero o se elimina o se reescribe para instanciar `ExecutedInvocationEvidence`
y asertar sobre su `isObserved`.

---

### H6 — Workstream D: schema con vocabulario inconsistente [FIX]

**Clase**: STRUCTURAL. **Recomendación**: FIX (corregir la progresión declarada o el
valor usado).

Verificaciones que PASAN:

- Tras la demote, **no queda ningún `verification_status: CERTIFIED` auto-declarado** en
  el ledger v2 (grep = 0 ocurrencias, OBSERVED). El único CERTIFIED del ciclo fue
  rebajado. Cero "CERTIFIED sin evidencia" en v2.
- En la canónica, cada `g8_receipt: docs/...` listado corresponde a un fichero existente
  (22 receipts comprobados con `[ -f ]`, 22/22 OK, OBSERVED).
- Los contadores del bloque `counters` de v2 (certified:0, implemented_uncertified:1,
  evidence_ready:1, static_candidate:12, total:14) cuadran con las 14 entradas
  (STRUCTURAL, contado por grep).

Defecto encontrado: la cabecera del ledger v2 declara la progresión de estados como

```text
CURRENT_STEP | STATIC_CANDIDATE | EVIDENCE_READY | CERTIFIED | QUARANTINED | RETIRED
```

y el propio fichero usa `verification_status: IMPLEMENTED_UNCERTIFIED` (línea 51), un
valor **fuera de la progresión declarada**. Además el bloque de contadores usa claves
(`implemented_uncertified`, `gate_status`, `next_action*`) que no pertenecen al schema
de evidencia declarado — mezclados a la altura de las entradas de step. Un parser estricto
del schema declarado rechazaría el estado de core.echo; un parser laxo acepta cualquier
string, con lo que el schema no protege nada. Elegir: o `IMPLEMENTED_UNCERTIFIED` entra
en la progresión documentada (y se documenta por qué, puenteando con ADR-0074), o la
entrada usa `EVIDENCE_READY` + nota de democión. También: los contadores deberían vivir
en un bloque anidado propio (`counters:`), no al mismo nivel que `steps:`.

Defecto adicional (deuda viva dentro del propio D): `core.sh` está en
`EVIDENCE_READY` con `run_id: null` y nota "populated by E corpus run" (líneas 114-133,
STRUCTURAL en HEAD). Pero E YA corrió (2ebd1b35) y el corpus ejecutó 04-sh/09-sh-then-echo
con `core.sh` observado (OBSERVED en el receipt del corpus, ver H7). El ledger v2 prometió
poblarse con la evidencia de E y sigue vacío para core.sh: la evidencia existe en el XML
del test y no ha sido reconciliada al ledger. El "status per XCA-2 closure gate" del
verify receipt afirma `unknown evidence provenance: 0` y `static_only_certified...: 0`;
lo segundo es verdad, pero la fila `core.sh EVIDENCE_READY` con `run_id: null` después
de la corrida de E contradice el espíritu de `execution_evidence_complete` (DERIVED).

---

### H7 — Workstream E: el conteo "20 fixtures / 18 evidencia" es FALSO en OBSERVED [FIX]

**Clase**: OBSERVED (corrida en vivo de XcaCorpusRunTest en HEAD 6d4cb17c). **Recomendación**: FIX.

El commit 2ebd1b35, TESTING-STATE y el brief afirman: "20 fixtures, 18 con runId,
2 expected failures". La realidad observada hoy:

```text
Corpus receipt (XML system-out de TEST-...XcaCorpusRunTest.xml, corrida en vivo):
  Fixtures total:   21
  Runs OK:          19
  Runs FAIL:        2       (12-error-handling, 21-milestone — SKIP)
  Fully covered:     9
```

Hallazgos derivados:

1. **El corpus tiene 21 fixtures, no 20** (`ls v2/compatibility/*.pipeline.kts | wc -l`
   = 21, OBSERVED). `22-wait-until` existe en disco y se ejecuta (OK, extra=1), pero
   **NO está declarado en `fixtureExpectations`** (grep del nombre en el mapa: ausente,
   STRUCTURAL). Consecuencia: corre con `expected = emptySet()` y su único step observado
   queda clasificado `extra` en vez de validado contra expectativa. El conteo "20" del
   commit era falso EL DÍA del commit (el fixture ya estaba en el árbol; STRUCTURAL:
   `22-wait-until.pipeline.kts` existe desde antes de 2ebd1b35). Nadie contó el directorio;
   se copió un número. La ley del mandato de campaña dice textualmente: "Do NOT hardcode
   30; derive from the authority... if reality differs, investigate" — se violó el
   espíritu con el 20.
2. **Los 2 expected failures tienen causa REAL y distinta (verificada ejecutando cada
   fixture con el CLI instalado, OBSERVED):**
   - `12-error-handling`: `exit=1` con
     `EngineInvariantViolation: registry step 'core.milestone' reached execute without
     declared capabilities available: milestone.operations` (stderr capturado). El motivo
     documentado ("fail intencional por catchError") es **incompleto**: el fixture
     combina catchError + milestone, y lo que mata el run en CLI es el milestone sin
     capability, no el fallo intencional capturado. Mismo origen real que 21-milestone.
   - `21-milestone`: `exit=1`, misma `EngineInvariantViolation` sobre
     `core.milestone`/`milestone.operations` (stderr capturado). Motivo documentado
     correcto.
   No hay un TERCER motivo oculto (no aparece ninguna otra clase de excepción en los dos
   runs). Pero el comentario del código (líneas 197-200 del test) atribuye a
   12-error-handling un motivo equivocado: "exercises catchError with intentional
   failure". La causa real de que no haya runId es la capability de milestone. Corregir
   el comentario o separar el fixture (catchError puro debería producir runId).
3. **El ground truth del test es débil respecto a su propio receipt**: solo afirma
   `unexpectedFails == 0` y `uniqueObservedSteps >= 1`. No valida `isFullyCovered` por
   fixture, no compara contra nada persistido, y permite el estado actual donde 12 de 21
   fixtures corren PARTIAL (missing>0) sin que el test se entere. El receipt impreso
   (matched/missing/extra por fixture) no se assertiona: la brecha 9/21 fully-covered es
   deuda de cobertura silenciosa. No bloquea (los missing son mayormente steps
   orquestación con capability, coherent con H1: esas claves ni siquiera están en la
   canónica), pero el test actual no puede detectar un bajón de cobertura.
4. Ojo con `assert(unexpectedFails == 0)`: usa `assert()` de Kotlin (desactivable con
   `-ea` off) en vez de JUnit Assertions. En la práctica Gradle test corre con assertions
   activadas por defecto, pero la convención del resto del fichero es
   `org.junit.jupiter.api.Assertions`. Homogeneizar.

---

### H8 — Guards residualmente no falsificables [FIX]

**Clase**: STRUCTURAL. **Recomendación**: FIX.

1. **Regex greedy del canary B.5** (`XcaCliCanaryTest.kt:292-299`):
   `^  core\.pwd(?:[^\n]*\n)*?    verification_status:\s*CERTIFIED`. El grupo
   `(?:[^\n]*\n)*?` cruza líneas arbitrarias, incluidas fronteras entre entradas YAML:
   `core.pwd` en línea N con `verification_status` de la entrada siguiente (o de cualquier
   entrada posterior con indent 4) haría match. Hoy NO dispara falso-positivo porque el
   ledger v2 usa indent 4 solo dentro de entradas y no hay CERTIFIED tras la demote
   (verificado: el test pasa, OBSERVED), pero es un guard frágil: detectaría "algún
   CERTIFIED después de core.pwd", no "core.pwd CERTIFIED". Falsificable débilmente.
   Fix: parsear el YAML en bloques por entrada (o al menos acotar el gap con
   `[^:\n]*` y un número acotado de líneas).
2. **El canary del ledger B.5 solo mira la copia v2** (`../docs/...` resuelto desde
   `v2/pipeline-application/` = copia v2, OBSERVED). La canónica tiene
   `certification_state: STOPPED_G7` para pwd/pwd.tmp (no `verification_status`), así que
   el regex de B.5 no podría leerla ni siquiera si quisiera. Estrechamente ligado a H1:
   hoy el canary "protege" el ledger menos autoritativo.
3. **`runFixture` (E) imprime el exit code como WARN** (`[WARN] Fixture exited non-zero`)
   y sigue. La ley "exit code is NEVER evidence" está respetada como evidencia, correcto,
   pero para 12-error/21-milestone ni siquiera se registra la clase de error en el
   resultado estructurado: `FixtureRunResult` no tiene campo de error; los motivos
   equivocados del comentario (H7.2) sobreviven porque nada obliga a contrastarlos.
   Recomendación: capturar al menos el nombre de la excepción en `FixtureRunResult`.
4. `assertTrue(true, ...)` fuera del alcance XCA sigue existiendo en el repo
   (`OperationJournalRunIdContractTest:57`, `BodyExecutionContextDerivationTest:440`,
   `GitCheckoutExecutorAdversarialTest:191` — el último es `exception != null || true`,
   siempre-true por construcción, OBSERVED por grep). Fuera de mi alcance de ciclo, pero
   se registran como deuda de falsificabilidad general.

---

### H9 — El mutante del brief nunca quedó falsificado como test [FIX]

**Clase**: DERIVED. **Recomendación**: FIX.

La falsificación eb40d5cb→7d725aca existe como **historia git**, pero no hay ningún test
que impida que alguien vuelva a cometer `isObserved = status.isTerminal`: el guard es el
test de RUNNING (bien), pero la pareja RED/GREEN no es reproducible desde HEAD sin
checkout manual (yo lo hice; funcionó; es O(seguidos)). Aceptable como evidencia de
custodia; mejorable injertando en `JournalRunExecutionEvidenceReaderInMemoryTest` un
comentario-apuntador al par de SHAs (ya lo tiene parcialmente en el KDoc). No bloquea;
queda como práctica: la falsificación por par de commits es la moneda, y aquí está
sana.

---

## Verificación deliberadamente NO ejecutada

```text
Round gate completo (`./gradlew -p v2 check`)        — 56 fallos pre-existentes documentados
  en XCA2_VERIFY_RECEIPT.md con evidencia base-vs-head (git diff vacío en src/main para
  el alcance verify). Re-ejecutarlo costaría ~20 min y no aportaría evidencia nueva
  sobre los hallazgos de deuda: mis mutaciones fueron quirúrgicas y revertidas con XML
  GREEN 3/3 por clase.
Lfc2E0GlobalClosureFitnessTest ejecutado             — leído estructuralmente; corre contra
  la canónica. Su ceguera a las 6 claves de v2 está demostrada por lectura del código
  (repoRoot canónico), no requiere ejecución para el hallazgo H1.
Corpus completo re-ejecutado 2 veces                 — 1 corrida en vivo (1m50s, GREEN,
  receipt extraído de XML). Suficiente para el conteo OBSERVED.
```

---

## Estado del árbol al cierre

```text
HEAD: 6d4cb17c (cycle/wu-g5b), árbol limpio
Mutaciones aplicadas durante la auditoría: TODAS revertidas (verificadas con
  git status vacío y XML GREEN 3/3 de XcaCliCanaryTest tras cada revert)
Ningún commit hecho por debt-verify (misión: detectar, no arreglar)
Nada pusheado
```

---

## Veredicto final

```text
pass/fail/blocked: BLOCKED (para cierre de XCA-2 tal como está)

Causa del bloqueo: H1 (dual-ledger con dos semánticas de CERTIFIED y cobertura de
claves disjunta) + H7 (el conteo 20/18 del workstream E es falso en OBSERVED: 21/19,
y 22-wait-until corre sin expectativas declaradas).

Lo que SÍ está probado y cerrado:
  - Falsificación RED→GREEN con dos SHAs, causa exacta, net-zero, re-verificable. OK.
  - Canaries B.4/B.5/B.6 reales, cada uno con causa de fallo observable. OK.
  - Demote de core.echo honesta y motivada dentro de su copia. OK (deuda H4 ligada a H1).
  - ADT de reconciliación bien formado, reconcile() total y puro, cero when(stepKey). OK.
  - Cero CERTIFIED sin evidencia en el ledger v2 tras la demote. OK.
```

Acciones recomendadas en orden:

```text
1. (H1) Decidir autoridad única para step-certification; renombrar el ledger v2 a un
   pathname no-colisionante; reconciliar las 6 claves huérfanas en la canónica.
   Requiere confirmación del orquestador (el brief prohíbe tocar docs/v2/status sin él).
2. (H7) Corregir conteo y comentario de 12-error-handling; declarar expectativas de
   22-wait-until; enrichment de FixtureRunResult con error class; reemplazar assert() por
   Assertions.
3. (H6) Alinear vocabulario de la progresión en el ledger v2; anidar contadores;
   reconciliar la fila core.sh con la evidencia ya producida por E.
4. (H5/H8) Fortalecer regex de B.5; eliminar o reescribir el guard local no falsificable.
```
