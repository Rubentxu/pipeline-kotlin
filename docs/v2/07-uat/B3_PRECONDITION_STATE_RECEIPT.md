# B3 — estado medido de las precondiciones (S7) antes de construir el arnés

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08 · **Base:** `b30584e5`
**Método:** para cada fila de B3 que ya tiene dueño en el árbol, **ejecutar** al dueño y registrar el resultado. Donde no hay dueño, decirlo. Ninguna fila se marca verde por existir un fichero con el nombre adecuado.

---

## 1. B3.1 — testigos semánticos: el mecanismo existe, y es discriminante

No hay que inventarlo. `S0SemanticWitnessMatrixTest` es el índice y `S3EnvironmentSemanticWitnessTest` el ejemplo trabajado, y su diseño es el correcto: el testigo **discrimina**, no describe.

```text
S0SemanticWitnessMatrixTest      17 tests  0 fail  0 err  0 skip   (12:42:19Z)
S3EnvironmentSemanticWitnessTest  7 tests  0 fail  0 err  0 skip   (12:42:20Z)
```

El KDoc de S3 lo dice mejor de lo que yo podría: un run con una stage que declara `environment` seguida de otra que no; una implementación PATCH imprime el valor solo en la primera, una AMBIENT en las dos, y *«no hay configuración de "correcto" que haga correcta la respuesta ambiente»*. Eso es un testigo: falla en la dirección que importa.

Constructos hoy con testigo en la matriz: `sh`/`echo` (positivo y negativo), `environment`, `timeout` (bloque y directiva), `retry` (positivo y negativo), `dir`, `withEnv`, `catchError`, `warnError`, `git` (**hallazgo de superficie: rechazado fail-closed por el puente canónico**), `milestone`, `stash`/`unstash`, `parallel`, `waitUntil`, `timestamps`.

## 2. B3.4 — kill/restart/recovery: verificado con sus dueños

```text
UatLocal001KillDuringShTest        1 test  0 fail  0 skip
UatLocal002ResumeAfterKillTest     1 test  0 fail  0 skip
S54ExternalVerticalRestartUatTest  2 tests 0 fail  0 skip
DestructiveSafetyOwnershipTest    11 tests 0 fail  0 skip   (ownership, disco, proyecto de
                                                            usuario, subdirectorio permitido)
TOTAL                             15 tests  0 fail  0 err  0 skip
```

Los dos primeros son kill y resume **reales**, con sus propios procesos. El de seguridad destructiva cubre `deleteDir`/`cleanWs`, que era mi candidato a hueco y no lo es: tiene dueño.

## 3. Lo que B3 sigue necesitando, y por qué no lo he hecho aquí

```text
B3.7  El vocabulario de veredicto NO existe como tipo en este repositorio. Es un diseño, no un
      arreglo: un tipo de veredicto mal delimitado se convierte en una SEGUNDA AUTORIDAD sobre la
      admisión, que es exactamente lo que ADR-0105 prohíbe (G10 es del harness; la ausencia de
      check es NOT_RUN y STOP). Diseñarlo requiere decidir antes su frontera con STEP-CERT y con
      el harness, y eso merece una sesión con la cabeza despejada, no el final de una larga.
B3.1  El índice de testigos nombra ~15 constructos; el manifiesto de superficie tiene 50 en las
      cinco categorías más 3 UNSUPPORTED_FAIL_CLOSED. Extenderlo es trabajo real, pero cada
      extensión necesita su propiedad discriminante: una fila que sólo afirme "compila" no
      certifica semántica, y añadir filas así empeoraría el índice en vez de mejorarlo.
B3.5  Queda un barrido de dependencias de reloj sin hacer (B3a corrigió las dos donde la propiedad
      del producto ya estaba afirmada, y dejó nombrada la de PipelineRuleParityTest).
B3.3  Certificación de plugin externo con manipulación adversarial: los materiales existen
      (fixture de artefacto, admisión, cross-check) pero su combinación como escenarios S7 no.
```

## 4. Lo que este recibo NO hace

```text
- No declara B3 cerrado: declara sus precondiciones medidas, que es distinto.
- No convierte en PASS lo que no ejecuté: sólo aparecen arriba las corridas con XML fresco mío.
- No introduce ningún tipo de veredicto, precisamente para no crear una segunda autoridad.
- No toca producción: cero ficheros de `src/main` modificados en esta rebanada.
```

## 5. Verificación ejecutada

```text
cd v2 && timeout 900  ./gradlew :pipeline-application:test --tests '*SemanticWitness*' \
                                  --tests '*S3EnvironmentSemanticWitness*' --rerun-tasks
cd v2 && timeout 1200 ./gradlew :pipeline-application:test --tests '*UatLocal001KillDuringSh*' \
                                  --tests '*UatLocal002ResumeAfterKill*' \
                                  --tests '*S54ExternalVerticalRestart*' \
                                  --tests '*DestructiveSafetyOwnership*' --rerun-tasks
EXIT 0 en ambos. Canario: XML borrados antes de cada corrida y regenerados con timestamp fresco.
```
