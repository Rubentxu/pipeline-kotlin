# ADR-S4-F2 — `StageBody.Scripted` de primera clase

**Estado:** `ACCEPTED` — decisión de ownership tomada antes de escribir código de producción
**Work item:** `f24f3ac0-b889-407c-9e46-f5e524120818` · **Tram:** S4
**Base de código:** `36bf440748e4c5cfb6d56a091de85cb4782273ec` (`main` tras el cierre de F1)
**Realiza:** `docs/pipelinek-semantic-evolution/03-scripted-runtime.md` §3

> La decisión arquitectónica se toma **antes** del código. `ACCEPTED` afirma que el reparto es el
> correcto; no afirma que esté implementado. Los recibos de F2 demuestran conformidad.

---

## 0. La decisión

```text
StageBody.Scripted  =  una IDENTIDAD de artefacto compilado, en el IR canónico

StageNode           =  el lifecycle: environment · options · whenCondition · input
                                  · post · directives        ← YA EXISTE, no se toca
        ↓
   dispatcher        =  un ÚNICO recorrido, que resuelve la identidad en el borde
```

Y explícitamente **no**:

```text
no  colapso de Steps/NestedStages/Parallel/Matrix en un Declarative(root)
no  cuerpo híbrido (un stage que mezcle llamadas eager y runtime)
no  segundo runner semántico
no  referencia desde el IR a CompiledScriptedEntryPoint (ver §3, dependencia)
```

## 1. Lo que se midió antes de decidir

Ninguna de estas preguntas se respondió desde el spec, y varias lo cambian.

| # | pregunta | medición | consecuencia |
|---|---|---|---|
| M1 | ¿qué dice el objetivo? | `Declarative(root: ExecutionNode)` + `Scripted(artifact: ScriptedArtifactRef)` | ambos nombres **no existen en código** |
| M2 | ¿existe `ExecutionNode`? | 0 hits en todo `--include=*.kt`; sólo en `docs/historico/` | el objetivo literal es una ficción |
| M3 | ¿existe `ScriptedArtifactRef`? | 0 hits en código | hay que nombrar el tipo **real** |
| M4 | ¿dónde vive el lifecycle de un stage? | `StageNode`: `environment · options · whenCondition · input · body · post · directives` | añadir un caso de body **hereda** el lifecycle sin tocarlo |
| M5 | ¿`StageBody` está en un formato durable? | `CompiledPipeline` **nunca** se serializa en `src/main`, con cualquier mecanismo | **no procede STOP (1)** |
| M6 | ¿superficie de producción? | 7 ficheros consumen `StageBody.*`; `Scripted`: 0 | la vía es aditiva |
| M7 | ¿de qué depende `pipeline-domain`? | sólo `kotlinx-serialization-json` y `kotlinx-coroutines-core` | **no** puede referenciar tipos de `scripting-api` |

### 1.1 M5 en detalle, porque es la que desactiva un STOP

`StageBody` es `@Serializable`, luego un colapso **cambiaría la forma serializada**. Pero `StageBody`
no está en ningún formato durable:

```text
CompiledPipeline / StageNode  →  Json.encodeToString  →  NINGUNO en src/main
                              →  .serializer()        →  NINGUNO en src/main
                              →  json.encodeToJson…   →  NINGUNO en src/main
```

La única serialización del IR canónico es el round-trip de
`CompiledPipelineTest.kt:54-55`, un test de contrato. El spine canónico compila el DSL y ejecuta
`CompiledPipeline` **en memoria**, una vez por run. Lo que sí es durable es el artefacto **scripted**,
identidad-digested (`ScriptedArtifactIdentity` + `fingerprintMaterial()`), y ése no cambia.

Por tanto: **reshape del IR canónico ≠ cambio de formato durable.** El STOP (1) no se activa. El
contraste con `cdcf68c6`, donde `outcomeOf` exigía regenerar el dump ABI, es el que hace evidente la
diferencia: allí la superficie era pública y publicada; aquí es in-memory y sin consumidores externos.

## 2. Por qué **no** colapsar a `Declarative(root: ExecutionNode)`

El spec propone un eje de dos casos. Leerlo como «dos, y sólo dos» es un error de lectura, y hay tres
razones medidas para no hacerlo:

1. **El objetivo no existe.** `ExecutionNode` tiene 0 ocurrencias en código (M2). Un ADR que colapsa
   sobre él estaría decidiendo sobre un tipo que nadie implementó, y la primera decisión de
   implementación sería inventarlo — que es exactamente la clase de defecto que este ADR existe para
   evitar.

2. **Los cuatro casos actuales no son del mismo tipo.** `Parallel` lleva identidad de rama, `Matrix`
   lleva una `MatrixSpec`, `NestedStages` lleva estructura, `Steps` lleva una lista plana. colapsarlos
   en un `root: ExecutionNode` exige **codificar** esas tres diferencias dentro de un nodo nuevo: es un
   rediseño del IR canónico, y no es un prerrequisito para que un stage sea scripted. Es trabajo que
   no compra nada que F2 necesite.

3. **Lo que el spec exige sí se cumple con la vía aditiva.** La línea 45 dice, en sus términos, que un
   body scripted «must be a distinct `StageBody` case». Un caso distinto y explícito es exactamente
   eso. La línea 54 — *avoid implicit hybrid bodies* — se cumple con más fuerza por construcción: un
   `StageBody` es o declarativo o scripted, nunca los dos, y el tipo lo hace inexpresable en vez de
   merely discouraged.

La colapsión a `Declarative` queda registrada como **pregunta abierta**, no como deuda: si algún día
se introduce `ExecutionNode`, la decisión se reevalúa con la misma medición, no antes.

## 3. La frontera del IR, y por qué el payload es una **identidad**

**M7 es la restricción dura.** `pipeline-domain` no depende de `pipeline-scripting-api`, y la ley
hexagonal lo prohíbe en una dirección: los contratos internos no dependen de adaptadores. Por tanto
`StageBody.Scripted` **no** puede llevar `CompiledScriptedEntryPoint` (que además es `internal` de
`pipeline-application`) ni `ScriptedArtifactIdentity` (pública de `scripting-api`, módulo publicado).

La forma correcta es la que el repositorio ya usa dos veces:

```text
PluginStepId            en domain;  el Step real vive en el registry, se resuelve en el borde
VersionedStepPayload    en domain;  el codec real vive en el contrato, se resuelve en el borde
ScriptedStageRef        en domain;  el artefacto real vive en el host,  se resuelve en el borde
```

Así que el payload del caso es **una referencia opaca ya canónica**, y el dispatcher la resuelve:

```kotlin
@Serializable
data class ScriptedStageRef(
    /** `ScriptedArtifactIdentity.fingerprintMaterial()`: la misma clave que ya alimenta el
     *  fingerprint durable de replay. No se recalcula aquí ni se duplica ninguno de sus campos. */
    val artifactKey: String,
    val entryPointId: String,
) {
    init {
        require(artifactKey.isNotBlank())
        require(entryPointId.isNotBlank())
    }
}
```

**Por qué no duplicar los seis campos de identidad dentro de domain.** Porque eso serían dos
autoridades para la misma identidad obligadas a stay sincronizadas, y dos autoridades que deciden lo
mismo se eliminan, no se sincronizan. `fingerprintMaterial()` se queda donde vive, que es el módulo
que posee la ley de identidad de artefacto; el IR transporta su resultado.

**Por qué esto no es una pérdida de información en la frontera.** La frontera IR→runtime pierde el
*artefacto*, que es ejecutable y no serializable, y conserva su *identidad*, que es lo único que el
spine necesita para reconciliar. Es la misma regla por la que `classifyShellTerminal` recibe un
terminal en vez de un `OperationStatus`: el hecho viaja, el significado lo aplica su dueño.

## 4. Ownership

| pieza | dueño | por qué |
|---|---|---|
| `StageBody.Scripted` y su payload | `pipeline-domain` | es IR canónico; el spine lo recorre |
| resolución `artifactKey → artefacto` | el borde (application / host) | el host posee el artefacto compilado y su cache |
| `fingerprintMaterial()` | `pipeline-scripting-api` | posee la ley de identidad de artefacto |
| lifecycle (`env`/`options`/`when`/`input`/`post`/`directives`) | `StageNode`, sin cambios | ya existe y no es de F2 |
| `RunOutcome` y el spine durable | sin cambios | F1 los convergió; F2 los consume |

**Ningún consumidor nuevo infiere nada del status.** El stage scripted entra al mismo spine y su
resultado es el mismo `RunOutcome` que el de un stage declarativo.

## 5. Consecuencias

1. **El lifecycle es el mismo por construcción, no por sincronización.** `StageNode` ya lleva
   `environment`, `options`, `whenCondition`, `input`, `post` y `directives`; un body nuevo no puede
   saltárselos porque no tiene dónde declararlos.
2. **El fallo es cerrado por construcción.** Un `StageBody.Scripted` cuya `artifactKey` no resuelve es
   un artefacto ausente, y se falla cerrado nombrando la clave. No hay fallback a declarativo: un
   fallback sería exactamente el «silent no-op» que la constitución semántica prohíbe.
3. **La firma ABI cambia de forma aditiva.** `StageBody` es serializable y público, luego hay que
   regenerar `api/pipeline-domain.api`. Como M5 demuestra que nada en producción serializa el IR, el
   impacto es `CompiledPipelineTest`, que es un contrato justo lo que hay que actualizar.
4. **Cambio de comportamiento para artefactos de terceros**: un `.pipeline.kts` deja de ser un
   programa de primer nivel para pasar a ser el cuerpo de un stage. Es el objetivo declarado, y su
   consecuencia es que el artifact cache pasa a ser re-consultado por el spine, no sólo por el runner.

## 6. Lo que F2 NO hace

| # | por qué no aquí |
|---|---|
| colapsar `StageBody` a `Declarative(root: ExecutionNode)` | §2; el objetivo no existe y no es prerrequisito |
| colapsar `Parallel`/`Matrix` dentro de un `ExecutionNode` | rediseño del IR canónico, no habilitación de scripted |
| `OperationStatus.UNSTABLE` / terminal durable de `Unstable` | D-2/D-3, `DEFERRED`; sigue siendo propiedad de S4 |
| body híbrido | prohibido por el spec y por la constitución semántica |
| cambiar el protocolo de artefacto scripted | §3; la identidad se reusa tal cual |
| migrar artefactos en disco | la clave es la misma, luego nada que migrar |

## 7. Verificación que este ADR exige

F2 no se cierra por compilar. Se cierra con:

```text
C1  un stage scripted comparte lifecycle con uno declarativo
      (env, options, when, input, post, directives) — mismo StageNode, misma ruta
C2  sin cuerpo híbrido: un StageBody es un caso o el otro, y el tipo lo hace inexpresable
C3  un solo recorrido del spine: el resultado es el mismo RunOutcome
C4  artifactKey sin resolver falla cerrado nombrando la clave
C5  API/ABI regenerada y aditiva
C6  UAT con Kotlin runtime real: if · loops · sh NONE/STDOUT/STATUS · pwd · isUnix
      readFile · fileExists · unstable · failure · restart/resume
C7  mutación que mate cada afirmación de comportamiento
C8  fitness que impida reintroducir el segundo runner
```

Y el gate que las sustituye a todas sigue siendo el local: no hay CI remota desde `754ddda0`, y
«CI verde» no es una evidencia disponible en este repositorio.

## 8. Evidencia de la medición

```text
M2  grep -rn 'ExecutionNode' --include=*.kt            → 0
M3  grep -rn 'ScriptedArtifactRef' --include=*.kt      → 0
M5  grep -rnE 'CompiledPipeline…serializer|encodeTo…'  → 0 en src/main
M6  grep -rln 'StageBody\.(Steps|NestedStages|Parallel|Matrix)' → 7 ficheros en src/main
M7  grep -n 'project(' pipeline-domain/build.gradle.kts → ninguno
```

El único round-trip del IR canónico es `CompiledPipelineTest.kt:54-55`. Ninguna conclusión de este
ADR se apoya en el spec sin medirla sobre el código.
