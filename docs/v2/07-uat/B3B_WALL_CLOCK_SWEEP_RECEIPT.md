# B3b — barrido de dependencias de reloj: completado y clasificado

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08 · **Base:** `24641376`
**Origen:** B3.5 del plan, y el residuo que B3a dejó nombrado ("queda un barrido sin hacer").
**Alcance:** sólo tests. Cero cambios de producción.

---

## 1. Método

Dos barridos sobre `v2/*/src/test` y `v2/*/*/src/test`:

```bash
grep -rn --include=*.kt -E 'assert(True|That|Equals)\([^)]*(elapsed|duration|Millis|Ms\b|took)'
grep -rn --include=*.kt -E 'assert.*(elapsed|took|duration|Millis|Ms)[A-Za-z]*\s*[<>]=?\s*[0-9]'
```

y después se clasifica CADA coincidencia. La distinción que importa no es "menciona un tiempo" sino
"afirma una propiedad de la máquina".

## 2. Resultado: 6 cotas superiores, ninguna ajustada

```text
PipelineRuleParityTest:68                     elapsedMs < 5_000      EXCEPCIÓN DECLARADA (B3a)
                                              la velocidad es el propósito del arnés: rápido y
                                              determinista frente a una UAT de subproceso de 300s.
                                              Se queda, con su riesgo de flake nombrado.
ExternalBodyDeadlineCancellationProofTest:212 elapsedMs < 25_000     guarda de cuelgue ("no debe
                                              colgarse del hijo muerto"); 25s para eso es holgado
Lpr011r2SecretRedactionAtRestUatTest:318      elapsed < 60_000       guarda de atasco del bombeo
ProcessDurableTaskRuntimeTest:151             elapsed < 15_000       "el timeout debe dispararse
                                              pronto": guarda holgada
AhoCorasickSwitchTest:46                      elapsed < 200          MEDIDO: el bucle de 25
RedactingEventSinkTest:250                    elapsed < 200          patrones corre en el orden de
                                                                     milisegundos bajos (la clase
                                                                     entera tarda 0,447 s con
                                                                     arranque del JVM dentro), o sea
                                                                     un margen de ~40x, no una cota
                                                                     ajustada
```

**Corrección de un juicio mío previo.** En el análisis del barrido escribí que las dos de 200 ms eran
"las más afiladas" y el candidato más probable a flake. Medirlas antes de tocarlas me desmintió: el
trabajo es de microsegundos a pocos milisegundos, así que el presupuesto sobra ~40x. **No las he
tocado**, y dejo escrito que mi sospecha inicial no se sostuvo: cambiar un número sin medirlo habría
sido exactamente el vicio que B3.5 persigue, sólo que en la dirección contraria.

## 3. Las cotas inferiores son la forma robusta, y se quedan

```text
CoreSleepCoordinatorCharacterizationTest:135  elapsed >= 1900       "un sleep(2) DEBE bloquear al
UatStep004SleepTimingTest:48                  sleepDurationMs >= 950  menos 1900ms"
```

Una cota inferior afirma "el sueño ocurrió de verdad", que es una propiedad del producto, y **una
máquina cargada no puede hacerla fallar** (sólo la haría tardar más). Es la dirección segura de
escribir un presupuesto, y por eso no se toca.

## 4. Lo que NO son dependencias de reloj, aunque el grep las traiga

Clasificadas como correctas y dejadas intactas:

```text
CoreWaitUntilStepUnitTest:94, CoreWaitUntilDifferentialContractTest:108,122
G7_CoreShellOutputCodecRoundTripTest:224        round-trip de un VALOR construido por el test
FileBasedWaitUntilControlJournalTest:79,116,118 la POLÍTICA de backoff (1000/2000 ms), no tiempo
                                                medido
StepOutcomeEncodedOutputDistinctionTest:221     igualdad de un data class decodificado
F5_2_JUnitStepContractTest:399                  parseo de un fixture de informe JUnit
CorePwdTmpStepUnitTest:416                      afirma que un nombre generado NO contiene
                                                "currentTimeMillis": determinismo, no tiempo
```

## 5. Lo que este barrido NO prueba

```text
- No prueba que no existan dependencias de tiempo fuera de los patrones buscados: un test puede
  depender del reloj sin la palabra "elapsed" (por ejemplo esperando por evento con `Thread.sleep`,
  que las reglas del proyecto ya limitan a posicionamiento de estado).
- No convierte en verdes las dos excepciones: la de PipelineRuleParityTest sigue con su riesgo de
  flake bajo carga, declarado.
- No toca producción ni el gate.
```

## 6. Verificación ejecutada

```text
cd v2 && timeout 600 ./gradlew :pipeline-credentials-api:test \
        --tests '*AhoCorasickSwitchTest*' --tests '*RedactingEventSinkTest*' --rerun-tasks
EXIT 0    AhoCorasickSwitchTest  tests=1  fail=0
          RedactingEventSinkTest tests=12 fail=0
```

Canario aplicado: XML borrados antes de la corrida y regenerados. Ninguna de las dos cotas de 200 ms
se modificó, así que no hay cambio de código en esta rebanada.
