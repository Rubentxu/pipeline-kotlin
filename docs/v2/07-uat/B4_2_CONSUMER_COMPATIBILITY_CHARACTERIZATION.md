# B4.2 — Compatibilidad de consumidores: caracterización por eje

**Fecha:** 2026-10-08 · **SHA:** `f0e2bf72` (rama `s6-plugin-sdk`)
**Alcance de referencia:** `v0.47.0` (último estable publicado) contra HEAD, 116 commits de la línea.

---

## 1. El problema, y por qué no hizo falta producir consumidores

B4.2 pedía "caracterizar consumidores compilados contra versiones anteriores". No hay consumidores
en este repositorio, y el plan lo trataba como el hueco grande: "haría falta producir esos
consumidores". **Esa premisa era falsa y se corrige aquí.**

Los dumps de `.api` están versionados en git (`v2/<módulo>/api/<módulo>.api`), uno por contrato
publicado, congelados por `binary-compatibility-validator` y **obligados por el propio `check`**
(`apiCheck` cuelga de `check` en cada módulo BCV). Un dump versionado es la superficie pública de
esa revisión con la misma autoridad que un consumidor compilado: dice exactamente qué descriptores
JVM existían. La diferencia es que el dump se puede *diffear*, y un consumidor no.

```text
modo de descubrimiento usado: git show <rev>:v2/<m>/api/<m>.api   vs   HEAD, comparado POR CLASE
```

No se construyó ningún consumidor. Se compararon dos revisiones del contrato que ya existían.

---

## 2. Qué módulos cuentan, y por qué sólo cuatro

Dos listas en `v2/build.gradle.kts`, y la distinción es la que decide si algo es ruptura de consumidor:

```text
bcvModules            = 6   ABI INTERNO REVISADO      domain, events, step-sdk:api, credentials-api, output, scripting-api
publishedContractModules = 4   CONTRATO DE CONSUMIDOR    domain, scripting-api, events, output
```

Verificado por medición, no por el comentario que lo afirma:

```text
pipeline-step-sdk/api          maven-publish: 0 coincidencias   -> no resuelve como coordenada Maven
pipeline-credentials-api       maven-publish: 0 coincidencias   -> idem
pipeline-domain/scripting-api/events/output                      -> las cuatro aplican maven-publish
ejemplos que importen dev.rubentxu.pipeline.v2.sdk               -> ninguno
```

Consecuencia: **una ruptura en un módulo sólo-BCV no es ruptura de consumidor.** Un artefacto
compilado contra él no puede existir, porque nunca se publicó.

---

## 3. Eje BINARIO — inventario medido

Comparación por clase (la única granularidad en la que un descriptor significa algo: agrupar por
nombre de método produce pares falsos como `copy` de `CatchErrorOverlay` con `copy` de
`BlockStepNode`, y así lo detecté y descarté).

```text
módulo                    clases            clases eliminadas   miembros ausentes   firmas modificadas
pipeline-domain           647 -> 688              2                    6                    20
pipeline-events           116 -> 144              0                    2                     8
pipeline-scripting-api    101 -> 101              0                    4                    13
pipeline-output            26  ->  26              0                    0                     0
```

**`pipeline-output` tiene delta CERO** en su contrato de lectura publicado. Es una observación, no
un elogio: coincide con su propia rationale ("el más pequeño de los cuatro y el menos consumido").

Miembros genuinamente ausentes, con su destino:

```text
pipeline-domain
  clase  InMemoryStepRegistry                                   eliminada
  clase  StepManifest                                           eliminada
  PluginManifest.<init>(ResourceRef,PluginReleaseRef,String,Set,Delivery,TrustMetadata,List)
  PluginManifest.getStepManifests()                             eliminado
  StepRegistry.register(StepDefinition) / register(StepRegistration)   eliminados de la interfaz
  ContextOverlay$CatchErrorOverlay.<init>(String,String,String,long)
  WaitUntilReconciliationDecision$Aborted.<init>(String,String)

pipeline-scripting-api
  StepSpec$CatchError.<init>(String,String,String,List)  (+ el sintético con DefaultConstructorMarker)
  StepSpec$Error.<init>(String,String)                   (+ el sintético)

pipeline-events
  CatchErrorTriggered.<init>(8 args)
  EventPage.<init>(List,EventCursor,boolean)
```

### Corroboración independiente: el registro de excepciones

`v2/contract/published-contract-exceptions.json` — **9 entradas**, cada una con `module`, `sha`,
`surface`, `change`, `permitting_maturity`, `reasoning` y `removal_boundary`. Las comparé una a una
con mi medición, que se hizo **antes** de leer el registro:

| # | entrada del registro | coincide con lo medido |
|---|---|---|
| 0 | `WaitUntilReconciliationDecision.Aborted` + `attempt: Int` | `Aborted.<init>(String,String)` ausente |
| 1 | `StepSpec.Error.failureKind` `String->FailureKind` | `StepSpec$Error.<init>(String,String)` ausente |
| 2 | `ContextOverlay.CatchErrorOverlay.buildResult/.stageResult` | `<init>` de 4 args ausente |
| 3 | `CatchErrorTriggered.buildResult/.stageResult` | `<init>` de 8 args ausente |
| 4 | `StepSpec.CatchError.buildResult/.stageResult` | `<init>` de 4 args ausente |
| 5 | `EventPage.refusals` | `<init>(List,EventCursor,boolean)` ausente |
| 6 | `PluginManifest`, `PluginManifestValidator.validate`, `StepManifest` | 2 constructores, `getStepManifests()`, clase `StepManifest` |
| 7 | `InMemoryStepRegistry`, `register` x2, `registerContributors` | clase eliminada + 2 `register` ausentes |
| 8 | `EventRegistry.register` movido a `EventRegistry.Builder` | no aparece como ausencia: `register` **sigue** en el ABI, en la clase anidada |

**Nueve de nueve.** Dos métodos independientes — un diff de descriptores por clase, y un registro
escrito a mano — coinciden sobre el mismo conjunto. Eso convierte el registro en evidencia
corroborada en vez de una declaración. La única entrada que mi diff no ve (8) es precisamente una
que **no** elimina el símbolo, y su `reasoning` lo dice: "register stays on the ABI, as
EventRegistry.Builder".

---

## 4. Eje FUENTE

```text
ROTA     error("boom", "USER")  ->  error(String, FailureKind)
         un pipeline autorado contra 0.47.0 no compila. Registrado en la entrada 1.
         El registro nombra los dos incumplimientos por separado en vez de fundirlos:
         "BINARY: descriptor change -> NoSuchMethodError. SOURCE: no longer compiles."

PRESERVADA   catchError(buildResult = "FAILURE", stageResult = "UNSTABLE") { ... }
         la grafía de autor se PRESERVÓ y se deprecó; lo que cambió es la superficie IR.
         La entrada 4 existe justamente para que esa diferencia sea legible.
```

---

## 5. Eje WIRE / ESQUEMA — la razón por la que la ruptura binaria era afrontable

El mecanismo está en el compilador, no en la prosa del registro:

```kotlin
is StepSpec.CatchError -> {
    put("kind", "catchError")
    // `.wireToken`, not the case: this payload feeds the fingerprint, so the
    // exact historical strings are load-bearing. A `name` projection here would
    // have changed every catchError fingerprint in the repository.
    put("buildResult", step.buildResult?.wireToken ?: "")
    put("stageResult", step.stageResult?.wireToken ?: "")
}
```

**La huella consume el token de wire (un string estable), no el caso tipado.** Por eso un cambio de
`Ljava/lang/String;` a `L.../CatchErrorBuildResult;` rompe el enlace binario sin reinterpretar
ninguna historia: el material de huella es byte-idéntico. El registro afirma esto; el compilador lo
implementa; y hay tests dedicados que lo fijan:

```text
CatchErrorResultWireCompatibilityTest    tests=4  fallos=0 errores=0   (pipeline-events-store)
CatchErrorHistoricalDecodingTest         tests=5  fallos=0 errores=0   (pipeline-events-store)
FArchE6CatchErrorResultTypedTest         tests=7  fallos=0 errores=0   (corrió en el último gate)
FArchE6OptionalFieldCodecFitnessTest     tests=3  fallos=0 errores=0   (corrió en el último gate)
```

**Nota de frescura, dicha explícitamente:** los dos primeros corrieron hace ~286m (gate anterior de
esta sesión), no en el último `check`; su módulo no cambió desde entonces, y por la regla de
economía de ejecución no se re-ejecutan sin cambio cubierto.

---

## 6. Eje SEMÁNTICO

```text
StepSpec.Error   default de FailureKind: la entrada 1 declara que un default de 0 se consideró
                 y se rechazó ("0 names no control row"), así que el default es un valor
                 semántico elegido, no un centinela
CatchError.Overlay  el override de overlay (buildResult/stageResult) es precedencia declarada
EventPage        `refusals` es aditivo al modelo de lectura: un reader antiguo ignora el campo,
                 uno nuevo ve una lista que antes no existía -> sin cambio de semántica de lectura
```

---

## 7. Eje DURABLE / REPLAY — verificado negativo, con el mecanismo

Este es el eje que el registro **no** enumera, y AGENTS.md lo hace obligatorio (DR-10: un cambio en
identidad, interpretación de replay o material de huella exige revisión de compatibilidad;
`runtimeCompatibilityVersion` es la dimensión que lo expresa). Tres comprobaciones:

```text
1. runtimeCompatibilityVersion   v0.47.0: "r3-runtime-v1"   HEAD: "r3-runtime-v1"   SIN CAMBIO
2. todas las versiones de esquema durable (CURRENT_SCHEMA_VERSION / SCHEMA_VERSION, incluidas
   las privadas)      v0.47.0: {1,1,1}     HEAD: {1,1,1}      SIN CAMBIO
3. la ruptura que toca lo durable (entrada 0, Aborted) es serializable?  NO
```

```kotlin
sealed interface WaitUntilReconciliationDecision { data class Aborted(operationId, attempt, reason) }
   -> sin @Serializable: es una DECISIÓN en memoria, no un registro
data class WaitUntilControlIdentity(operationId: String, schemaVersion: Int = 1)
   -> la identidad durable son esos dos campos; ningún campo de la decisión participa
el driver: "persists the plan BEFORE launching any child effect" -> persiste el PLAN, no la decisión
```

**Conclusión, y es un negativo deliberado:** `Aborted.attempt` no es material de identidad, ni se
persiste, ni se codifica. Que `runtimeCompatibilityVersion` no cambie es **correcto, no una
omisión**. El eje durable tiene cero cambio visible en 116 commits, y eso se sostiene sobre el
mecanismo del §5 (la huella es el wireToken), no sobre la ausencia de inspección.

---

## 8. Lo que NO es una ruptura, y por qué medirlo importaba

`pipeline-step-sdk/api` perdió **6 tipos públicos** entre el merge-base y HEAD:

```text
JenkinsSurface, Step (anotaciones), LspMetadata + Companion, LspMetadataLoader,
LspParameter, LspLoadDiagnostics          194 -> 111 líneas de dump, +0 añadidas
```

Borrado deliberado, no accidental: `b865bb95 refactor(sdk): el KSP era una segunda autoridad que
ademas mentia` retiró el procesador KSP y sus tipos. Vivían en un módulo **sólo-BCV** (§2): sin
`maven-publish`, sin consumidor posible. No es ruptura de consumidor.

El comentario del build ya lo decía, y además documenta que la frase anterior ("SDK contract for
external plugin authors") era **falsa**, "the same defect this session already paid for once in the
KSP: a written claim that no evidence backs, pointing a future reader at a consumer program that
does not exist". Verifiqué la corrección en vez de creerla: 0 `maven-publish`, 0 ejemplos que
importen el paquete.

---

## 9. Hallazgos de esta caracterización

```text
F1 (info, doc)  El JSON de madurez apunta a `publishedContractExceptions()` "en el mismo test".
                El mecanismo es real pero es OTRO fichero: v2/contract/published-contract-exceptions.json,
                con entries[]. El puntero por nombre de función está obsoleto. No rompe nada;
                desvía a quien lo siga al pie de la letra.
F2 (positivo)   El registro de 9 entradas es corroborado por medición independiente 9/9.
                Un registro que coincide con un diff que no lo leyó es evidencia, no prosa.
F3 (positivo)   El eje durable está protegido por diseño (huella = wireToken) y no por suerte.
                Verificado: versiones idénticas, decisión no serializable, identidad sin la decisión.
F4 (límite)     La medición cubre SOLO contratos publicados. Un módulo sólo-BCV puede romperse sin
                registro, y eso es correcto por política: no hay consumidor que romper.
```

---

## 10. Verificación ejecutada

```text
metodo: git show <rev>:<dump> vs HEAD, comparacion por clase; cruce con
        v2/contract/published-contract-exceptions.json y con v2/build.gradle.kts
arbol:  f0e2bf72, limpio
gate:   cd v2 && timeout 1800 ./gradlew check
        BUILD SUCCESSFUL in 25m 4s · 325 tareas · ninguna fallida
        XML: 444 clases · 3019 tests · 0 fallos · 0 errores · 131 skipped
apiCheck: verde (el dump coincide con el codigo -> los descriptores medidos son los reales)

No se ejecuto ningun test nuevo: esta caracterizacion compara artefactos versionados y lee
un ledger; no cambia codigo de producto. La evidencia de los cuatro tests del eje wire se cita
con su edad real (§5), sin re-ejecutarlos, porque el codigo que cubren no cambio.
```

---

## Cierre — protocolo de investigacion de referencia

```text
Implementacion de referencia consultada: binary-compatibility-validator (Kotlin/BCV) via
                                         gradle task apiCheck/apiDump; y la taxonomia de
                                         madurez de este repositorio
Comportamiento adoptado:                 el dump versionado ES la superficie por revision;
                                         diff por clase para separar ausencia de modificacion
Desviaciones intencionadas:              ninguna
Implicaciones de seguridad revisadas:    n/a (no hay ejecucion ni credenciales en esta WU)
Tests que demuestran el contrato:        CatchErrorResultWireCompatibilityTest,
                                         CatchErrorHistoricalDecodingTest,
                                         FArchE6CatchErrorResultTypedTest,
                                         FArchE6OptionalFieldCodecFitnessTest,
                                         P3EPublishedContractMaturityFitnessTest
```
