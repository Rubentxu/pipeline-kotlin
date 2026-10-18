# `quiet` — cierre: el último parámetro semántico muerto de `core.waitUntil`

> status: cerrado sobre el árbol de trabajo de este recibo (base `37c9cb41`).
> alcance: **un parámetro**, no la deuda madre. La deuda madre sigue siendo
> `bl-bl-01M3YGH3FE000387X13PG607G0` (`DEBT-WIRE-AUTHORITY`), P2, Triaged.
> ley: `SEMANTIC CONSTITUTION` §2 (metadata accepted but never interpreted) y §8
> (los eventos son semántica, no log).

## 1. Qué se cierra

`quiet` desaparece de **diez** ficheros de producción. Antes:

```text
StageScope.waitUntil(..., quiet: Boolean = false, ...)
  → StepSpec.WaitUntilBlock.quiet
  → CoreWaitUntilInput.quiet
  → CoreWaitUntilWireCodec: put("quiet", …) / obj["quiet"]
  → WaitUntilShape.quiet                (pipeline-domain)
  → BlockShellScope.WaitUntilScope.quiet (pipeline-application)
  → BlockShellScopeDescriptor.WaitUntil.quiet (BodyInterpreter)
  → CanonicalBlockShellScope.WaitUntilScope.quiet
  → Wire del carrier de registro: CoreWaitUntilStep / WaitUntilInput.quiet

  lectores de scope.quiet:  CERO
```

Medido, no supuesto: `CanonicalStructuralDecisions` construía el scope con
`quiet = shape.quiet` y nadie leía `scope.quiet`. Un `waitUntil(quiet = true)` se
codificaba, viajaba por el payload, sobrevivía a la proyección, y se descartaba.

## 2. Por qué quitarlo y no cablearlo

La respuesta sale de una medición, no de una preferencia:

```text
ConsoleFamilyLines.controlNote:  is WaitUntilPolled     -> Line(Family.NOTE, …)
                                is WaitUntilCompleted  -> Line(Family.NOTE, …)
HumanConsoleRenderer.visible:    NORMAL -> family != NOTE       // NOTE ya está oculto
```

En la vista por defecto la narración completa de `waitUntil` **ya es silenciosa**.
`quiet = true` no tenía nada que silenciar. Cablearlo exigía una de dos cosas, y
las dos contraten una ley que ya existe:

1. `HumanConsoleRenderer.line()` pasa de `Line` a `Line?`. Eso contradice
   `RendererRoutePartitionTest`, cuya aserción exige que *todo evento enrutado lo
   renderice la familia a la que se enruta*. Anular una fila de ese guard para
   admitir un parámetro que nadie pidió es comprar `quiet` con una ley.
2. Añadir un miembro al enum cerrado `Family`. Eso hace crecer un tipo cerrado
   para expresar un caso que la vista por defecto no puede mostrar.

El coste de la firma, por cierto, **no era el coste**: `line()` tiene exactamente
**2** puntos de llamada, ambos dentro de `render()`, y **0** en test. Una versión
anterior de esta nota daba el cambio de firma como el precio del arreglo, y
estaba equivocada; se corrigió antes de cometerse.

Lo que sí cambia para quien migra desde Jenkins: `waitUntil(quiet = true)` pasa de
**compilar sin hacer nada** a **no compilar**. Es la respuesta honesta para un
parámetro que este motor nunca honró.

## 3. El censo falló dos veces antes de acertar

La fila nueva del guard enumera sus sitios por nombre, y la primera versión de
esa lista decía **8**. Eran **10**. Los dos que faltaban los encontró el
compilador, no el censo:

| fichero omitido | qué era |
| --- | --- |
| `pipeline-domain/.../step/BodyInterpreter.kt` | `BlockShellScopeDescriptor.WaitUntil`, el gemelo del descriptor del scope. Estaba archivado como «quiet no relacionado» porque el fichero tiene otros hits de `quiet` que sí lo son. |
| `pipeline-application/.../DslCompiledPipelineCompiler.kt` | la proyección `StepSpec.WaitUntilBlock -> CoreWaitUntilInput` que reenviaba el campo. |

Es la cuarta vez este ciclo que un censo clasificado por «este hit parece del
sujeto?» produce una cifra confidentemente equivocada. Un censo que **nombra** sus
sitios no puede equivocarse así: la reaparición se nombra por sitio en vez de
contarse.

## 4. Cambio de formato durable, declarado (DR-10)

El sobre del carrier de registro pierde una clave:

```text
antes   {"kind":"waitUntil","initialRecurrencePeriod":1000,"quiet":false}
ahora   {"kind":"waitUntil","initialRecurrencePeriod":1000}
```

`DR-10` exige que un cambio de material de identidad durable pase por revisión, no
que se reinterprete en silencio. Lo que hace que esto sea seguro y no una
reinterpretación es una fila nueva de contrato:

```text
codec input — a payload written by an older build still decodes, and its quiet key is ignored
```

Un payload antiguo con `"quiet":true` decodifica al **mismo** `WaitUntilInput`. La
historia previa se lee, no se reescribe. Y la fila
`canonical envelope — the input codec envelope is exactly the two dsl-v1 waitUntil keys`
declara el sobre exacto, que es donde un cambio así se anuncia en vez de
absorbirse.

El sobre del carrier de bloque pierde la misma clave; `CoreWaitUntilWireCodec`
comprueba `kind` al decodificar y rechaza un sobre del otro carrier, así que el
discriminador — que sí tiene lector — no se tocó.

## 5. Evidencia

```text
RED   fila `the waitUntil block carries no dead semantic parameter`
      FAILED nombrando los 8 sitios del censo (todavía eran 8)
      sin error de compilación: el fallo es la aserción, no `^e:`

GREEN :pipeline-architecture-tests:test
       Lfc2WaitUntilWireAuthorityFitnessTest         7  0 fallos  0 errores
       FArchS0SurfaceManifestTest                    13  0 fallos  0 errores
       Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest  (incluido en el mismo run)

L0    :pipeline-application:compileKotlin :pipeline-domain:compileKotlin
       :pipeline-scripting-api:compileKotlin         exit 0
L0    compileTestKotlin de los tres módulos + architecture-tests  exit 0
```

### 5.1 Los cinco fallos del L2 son pre-existentes, MEDIDOS

Regla 16: sin evidencia fresca base-vs-head, «pre-existente» no se afirma. Se
levantó un worktree en `37c9cb41` y se corrieron ahí:

```text
run                                    base 37c9cb41        head (este recibo)
B1WURp053rContextRuntimeClosureTest      FAILED (Path)       FAILED (Path)
CanonicalInMemoryCliTest  (2 filas)      2 de 2 FAILED       2 de 2 FAILED
CredentialsStorePathIsNotRecomputed…     FAILED              FAILED
DirScopeEndToEndTest (writeFile in dir)  FAILED              FAILED
```

Los cinco reproducen idénticos en la base. **Cero regresiones** de este cambio.

## 6. Lo que NO se cierra

- Las ramas de `blockPayload` sin autoridad nombrada (`Dir`, `Timestamps`, `WithEnv`,
  `TimeoutBlock`, `RetryBlock`, y la lectura disputada de `WithCredentialsBlock`).
- La conversión de unidad de `TimeoutBlock` dentro del encoder (`AGENTS.md` §7).
- `BlockShellScope.TimestampsScope(runId = "")`: mismo defecto que `quiet`, un nivel
  más abajo — el productor pone `""` a mano, el motor usa el `runId` del cierre, el
  intérprete pasa el scope opaco, y **cero** lectores del campo. Medido en tres
  declaraciones de tipo y un adaptador de paso. No se mezcló aquí.
- `ProductVersion` / PRODUCT-GATE: `BLOCKED_EXTERNAL` sin CI remota.

## 7. Cierre de unidad de trabajo

```text
Implementación de referencia consultada: Jenkins pipeline-basic-steps `waitUntil`
  (intervalo inicial 1000ms; `quiet` silencia el log, no el stream de eventos)
Comportamiento adoptado:                   intervalo inicial 1000ms, verbatim.
                                          `quiet` NO se adopta: suprimir sería sobre
                                          un canal que aquí ya está oculto por familia.
Desviaciones deliberadas:                 el techo de backoff se expone como input del DSL,
                                          que Jenkins no hace; `quiet` se rechaza en
                                          compilación en vez de aceptarse sin efecto.
Implicaciones de seguridad revisadas:     n/a — no se tocó superficie de credenciales,
                                          procesos ni sistema de ficheros.
Pruebas que demuestran el contrato:       Lfc2WaitUntilWireAuthorityFitnessTest (fila nueva),
                                          WaitUntilStepContractSuiteTest (fila nueva de
                                          decodificación de historia previa),
                                          CoreWaitUntilWireCodecContractTest,
                                          CoreWaitUntilStepUnitTest,
                                          CoreWaitUntilDifferentialContractTest,
                                          FArchS0SurfaceManifestTest
```