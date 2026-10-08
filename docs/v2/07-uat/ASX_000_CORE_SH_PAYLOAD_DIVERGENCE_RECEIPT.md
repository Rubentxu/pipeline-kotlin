# ASX-000 (B5) — Segunda rebanada: `core.sh` tiene DOS productores de su payload

**Fecha:** 2026-10-08 · **SHA base:** `f8cea05c` · **Cambio en producción:** **ninguno**
**Entregable:** `v2/pipeline-application/src/test/kotlin/.../CoreShellStepPayloadDivergenceTest.kt`

---

## 1. El hallazgo

`core.sh` tiene **dos productores del payload que decide su identidad durable**, y no coinciden:

```text
autorado   sh("echo hi")   ->  DslCompiledPipelineCompiler.shellPayload
   {"kind":"sh","command":"echo hi","isScriptBlock":false,"returnStdout":false}

códec      CoreShellStep.definition.contract.inputCodec.encode(CoreShellInput(ShellCommand("echo hi")))
   {"kind":"shell","script":"echo hi","returnMode":"NONE"}
```

Y el `encode` del códec **está en ruta de producción**, no sólo en tests:
`ScriptedRegistryInvoker.kt:333` hace `encodedInput = definition.contract.inputCodec.encode(input)`.
El payload es material de huella, así que **el mismo `sh` lógico tiene dos identidades según quién
construyó el nodo**.

Lo que hace esto un defecto y no un estilo lo dice el propio repositorio. Para `cleanWs`,
`milestone`, `stash`, `unstash` y `publishHtml`, `StageScope.kt` documenta que la fachada DSL emite un
envoltorio *"byte-for-byte identical to `CoreXStep.inputCodec.encode()` so the durable fingerprint
round-trips through the G5 registry path"*. Esa ley es lo que mantiene la identidad única, y
**`core.sh` es el único Step que no la cumple**.

La tolerancia del `decode` (acepta `"sh"` o `"shell"`, `command` o `script`, `returnStdout` o
`returnMode`) **no es el defecto**: es lo que permite que un `.pipeline.kts` real enrute por la ruta
de registro. El defecto es que **ningún productor es dueño de los bytes**.

## 2. Por qué se fija y no se arregla

Unificarlos significa cambiar el payload de una de las dos rutas, y por tanto **cambiar la identidad
durable de historia que ya existe**. Eso es exactamente lo que DR-10 prohíbe hacer en silencio y lo
que DR-12 exige resolver con un ADR. Así que la salida correcta de ASX-000 es una **medición
registrada**, no una reparación callada.

Por eso la fila central del test no afirma una forma: afirma **la divergencia**.

```kotlin
assertEquals(codec.decode(authoredShape), codec.decode(codecOwnShape))   // misma operación
assertNotEquals(authoredShape, codecOwnShape, "...RECORDED DEFECT...")   // dos identidades
```

Esa fila **fallará el día que alguien las unifique**, forzando que la transición sea explícita. El
KDoc lo dice en esos términos: cuando la autoridad única aterrice, la fila y las de forma se
reescriben juntas, en el mismo commit, citando el ADR que autorizó el cambio de identidad.

## 3. Verificación

```text
mutacion: renombrar el kind del codec de "shell" a "sh"
  -> ROJO exactamente 2 filas de 9: las dos que fijan la forma PROPIA del codec.
     Las 7 restantes (tolerancia de decode, divergencia, fallo cerrado) siguen verdes,
     que es lo correcto: renombrar no toca la tolerancia.
sha256 de CoreShellStep.kt restaurado e identico:  d9eb1f902dfe4507...   produccion intacta en git

estado restaurado con consumidores:
  CoreShellStepPayloadDivergenceTest 9/0 · G7_CoreShellOutputCodecRoundTripTest 34/0
  CoreShellStepTest 12/0 · A4_3TypedShellOutputIntegrationTest 2/0 · A4_2ShellOperationsCapabilityTest 6/0
  TOTAL 63 tests, 0 fallos, EXIT 0
```

Las dos formas predichas se derivaron **leyendo** el compilador y el códec, y coincidieron con la
observación a la primera.

## 4. Qué queda de ASX-000

```text
HECHO      payload de credenciales (1.ª rebanada) · payload de core.sh (esta rebanada)
           replay: KDoc de ReplayPolicy alineado con su autoridad (3.ª rebanada)
MEDIDO     eventos de core.sh: YA CUBIERTO, no se anaden tests. 50 ficheros de test referencian
           EchoOutputCaptured, la familia que el sustrato ShExecution emite por EventSink, y la
           parte de transcript durable + redaccion la caracterice yo en B1a. El sustrato documenta
           que sustituyo a "the pair of emitters that used to exist here", que eran "the SECOND
           rendering of the same bytes": eso es la ley de emision unica (§8.3) ya aplicada, no un
           hueco de ASX-000.
```

ASX-000 queda **agotado en lo ejecutable sin ADR**: lo que resta del bloque no es caracterizacion.

**Lo que este negativo NO autoriza a concluir:** que la familia de eventos este *ejercitada* no
demuestra que el contrato observable del Step este *declarado* como tal (Semantic Constitution §8.2:
"every supported Step/directive declares its observable event contract"). Esa pregunta es distinta, no
la he medido, y no la doy por buena por este negativo.

## 5. Lo que este hallazgo NO es

No es una regresión introducida aquí: la divergencia existía y su comentario ("A4 flip: accept BOTH
canonical kind spellings") muestra que fue una decisión consciente de compatibilidad. Lo que faltaba
era alguien que midiera la consecuencia —dos identidades para una operación— y la dejara escrita.
Tampoco es un fallo de seguridad ni de corrección en caliente: ambos payloads **decodifican** al
mismo input, así que la ejecución es correcta en ambos caminos. Lo que queda partido es la
**identidad durable**, que es lo que decide si un replay reutiliza o vuelve a ejecutar.

---

## Cierre — protocolo de investigación de referencia

```text
Implementacion de referencia consultada: el propio CoreShellStep (CERTIFIED) y la ley que
                                         StageScope.kt ya documenta para otros cinco Steps
Comportamiento adoptado:                 fijar la divergencia observada en lugar de elegir una forma
Desviaciones intencionadas:              NO se unifica: exige ADR por DR-10/DR-12
Implicaciones de seguridad revisadas:    ninguna; ambos caminos decodifican al mismo input
Tests que demuestran el contrato:        CoreShellStepPayloadDivergenceTest (9),
                                         G7_CoreShellOutputCodecRoundTripTest, CoreShellStepTest
```
