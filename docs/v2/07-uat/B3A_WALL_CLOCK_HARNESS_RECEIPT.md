# B3a — credibilidad del harness: la dependencia de reloj de pared

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08 · **Base:** `08fe40cb`
**Alcance:** lado test. Cero cambios de producción.
**Origen:** B3.5 del plan — *«Corregir las pruebas dependientes de wall-clock, incluido `CompiledScriptedEntryPointHostTest`»*.

---

## 1. El test que el plan nombra: medido, y su presupuesto es defendible

```text
v2/pipeline-scripting-kotlin24/.../CompiledScriptedEntryPointHostTest.kt   @Timeout(30)
medido con --rerun-tasks, XML fresco:  tests=1 fail=0  time=11.133 s   (total de la invocación: 34 s)
```

11,1 s reales frente a un presupuesto de 30 s es un margen de 2,7× sobre una máquina ociosa, y su
sujeto es un **compilador de Kotlin real** en proceso, cuyo coste crece con la carga. **No lo he
tocado**: subir el número no arregla la clase de problema, sólo la mueve, y bajarlo empeoraría el
riesgo. Queda registrado que su PASS depende de un presupuesto de reloj de pared, con la medición al
lado para que el próximo que lo vea no tenga que volver a medirlo.

## 2. Las dos aserciones de reloj que sí eran sustituibles, y por qué no se pierde cobertura

Una aserción sobre duración afirma una propiedad de la máquina, no del producto. En estos dos casos
la propiedad del PRODUCTO ya estaba afirmada de forma discreta al lado, así que la fila de milisegundos
no añadía cobertura: añadía una forma de que una caja cargada produjese un RED ajeno al sujeto.

```text
UatLocal005CheckoutGitTest   `SC-002 second run with same SHA is classified no-op`
  quitado   assertTrue(elapsedMs < 2000, "SHA-equal no-op must complete in <2s")
  queda     assertEquals("no-op", classification)     <- la propiedad, y es el valor del producto
  se renombra el test: antes decía "no-op under 2s", y tras quitar la fila ese nombre sería mentira

FileInputDecisionsTest       `an expired bound denies with the time actually waited`
  quitado   assertTrue(elapsed < 5_000L, "the bound must actually stop the wait")
  queda     timedOut.waitedMillis >= 100L             <- que la denegación reporte lo esperado,
                                                          no una constante; un retorno inmediato
                                                          cae aquí
```

La guarda de cuelgue no desaparece: las dos clases llevan `@Timeout` de clase (120 s y 60 s), que es
donde corresponde vivir a un presupuesto de tiempo.

## 3. La mutación que prueba que la fila que queda está viva

Quitar una aserción sólo es honesto si la que se queda puede fallar. Medido, con XML fresco
(`2026-10-08T11:50:22Z`):

```text
M  assertEquals("no-op", ...) -> assertEquals("clone", ...)
   RED: tests=13 fail=1  "expected: <clone> but was: <no-op>"
   restaurado: la clase vuelve a 13 tests / 0 fallos / 2 skipped  (11:51:50Z)
```

Es decir: la fila discreta es la que carga la propiedad, y no es vacua.

## 4. La que dejo, dicha en voz alta

```text
PipelineRuleParityTest:68
  assertTrue(result.elapsedMs < 5_000L, "in-process run must be <5s")
```

**No la he tocado, y no por descuido.** El KDoc de la clase declara que el valor de este arnés es ser
el camino **rápido y determinista** frente a la UAT de subproceso de hasta 300 s: la velocidad es el
propósito del harness, no una afirmación sobre el producto. Quitarla borraría esa propiedad; dejarla
mantiene un riesgo de flake bajo carga. Es un compromiso consciente y queda nombrado, con su riesgo,
en vez de resuelto a medias por mi conveniencia.

## 5. Lo que esta rebanada NO hace

```text
- No hace un barrido completo de dependencias de reloj en la suite: he corregido las dos donde la
  propiedad del producto ya estaba afirmada. Otras aserciones de tiempo pueden existir y no se
  declaran revisadas.
- No toca producción, ni ningún contrato, ni el gate: los cambios son dos ficheros de test.
- No convierte en PASS nada: el arnés de veredictos de B3 sigue sin existir.
```

## 6. Verificación ejecutada

```text
cd v2 && timeout 900 ./gradlew :pipeline-application:test --tests '*FileInputDecisionsTest*' \
                              --tests '*UatLocal005CheckoutGitTest*' --rerun-tasks
  EXIT 0   FileInputDecisionsTest  tests=9  fail=0  err=0  skip=0
           UatLocal005CheckoutGitTest tests=13 fail=0 err=0 skip=2
canario: XML borrados antes de cada corrida y regenerados con timestamp fresco
```
