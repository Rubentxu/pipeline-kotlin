# CDH-P0-CORPUS-01 — La fusión de los dos barridos del corpus, medida antes de aplicarse

| Field | Value |
| --- | --- |
| Work unit | `CDH-P0-CORPUS-01` |
| Base SHA | `fc556966` |
| Classification | **CHARACTERISATION** — la premisa del plan queda refutada antes de tocar código |
| Cambio en producción | **ninguno**. No se fusionó nada. |
| Evidence | `docs/v2/07-uat/evidence/cdh-p0-corpus/corpus-differential-matrix.json` |

## 1. Lo que el plan afirma

> Fusionar los dos barridos del corpus. `UatCompat001CorpusSmokeRunTest` y
> `CompatibilityCorpusTest` ejecutan las mismas 31 fixtures. Se conservarán las
> comprobaciones exclusivas de ambos, pero no dos ejecuciones completas de cada
> escenario.

La condición de retirada es: *«Las 31 fixtures conservan todas sus aserciones y
casos negativos»*. El plan trata la cobertura como idéntica y el trabajo de
conservación como accesorio.

## 2. Lo que la inspección dice

`v2/compatibility/` contiene **31 fixtures** (01–32, falta el 07). Sobre eso:

| Medición | Valor |
| --- | --- |
| Fixtures en disco | **31** |
| Nombradas explícitamente en `CompatibilityCorpusTest` | **28** |
| Métodos `@Test` reales en `CompatibilityCorpusTest` | **25** |
| Métodos `@Test` reales en `UatCompat001CorpusSmokeRunTest` | **1** |

Las tres fixtures en disco que `CompatibilityCorpusTest` **no nombra**:

```text
21-milestone.pipeline.kts
31-stash-unstash.pipeline.kts
32-publish-html.pipeline.kts
```

`UatCompat001` sí las ejecuta, porque descubre el directorio entero en vez de
nombrar fixtures. Su cobertura de conjunto es completa; la de
`CompatibilityCorpusTest` no lo es.

### Mecánicas exclusivas de cada barrido

Una fusión que conserve «las comprobaciones exclusivas de ambos» no es una
simplificación: es la mayor parte del trabajo, porque **ninguno de los dos
barridos es redundante**.

| Mecánica | `UatCompat001` | `CompatibilityCorpusTest` |
| --- | --- | --- |
| Siembra `LocalSecretStore` con 7 tipos de credencial | **exclusiva** | — |
| Exporta `PIPELINE_STORE_PASSPHRASE` al proceso | **exclusiva** | — |
| Fixture 14 con almacén sembrado | — | **exclusiva** |
| Fixture 14 **sin** almacén, fallo tipado | — | **exclusiva** |
| Zip-slip fixture 28, contención de `ZipEntry` | — | **exclusiva** |
| `FileRead` con sha256 y tamaño, contenido nunca en el canal | — | **exclusiva** |
| Tres `StepStarted`/`StepFinished` `core-utils` con SHA-256 del fichero | — | **exclusiva** |
| Transcripción leída del Output Plane (`ConsolePlaneProbe`) | — | **exclusiva** |
| Fixtures 15 y 28 declaradas como fallo deliberado | **exclusiva** | — |
| Una única aserción sobre las 31 | **exclusiva** | — |
| Un método por fixture, seleccionable de forma independiente | — | **exclusiva** |

Esto último es la condición de retrievación que el propio paquete describe en otro
sitio: el grano por fixture existe para que un cambio en una fixture no obligue a
re-ejecutar las otras. Un barrido monolítico la revierte.

### El punto que decide

Las dos aserciones negativas que sólo `CompatibilityCorpusTest` hace —fixture 14
sin almacén y fixture 28 zip-slip— **no son provoked por nada**. La fixture 28
existe para demostrar que un `ZipEntry` malicioso no escapa del directorio de
destino; eso es una propiedad del Step, no del barrido. Una fusión que la suprima
retira la única cobertura de CVE-2023-32981 que existe en el repositorio, y lo
haría después de un gate verde, que es exactamente la forma de no poder distinguir
un harness arreglado de uno silenciado.

## 3. La cifra que casi publico mal

Durante esta medición obtuve dos cifras contradictorias:

| Método | Resultado | Real |
| --- | --- | --- |
| regex `fun\s+(\w+)` tras newline | 11 | — |
| `grep -c '@Test'` | **30** | **25** |
| anotaciones reales tras eliminar comentarios | 25 | 25 |

`grep` reporta 30 porque cinco ocurrencias de `@Test` viven dentro de un bloque
KDoc que cita código de ejemplo. La cifra autoritativa es 25. Un conteo por
subcadena no es un conteo de lo que se quiere contar, y sólo se detectó porque dos
métodos independientes discreparon.

## 4. Conclusión: la P0 no es ejecutable como está redactada

**No fusiono los barridos.** La premisa es falsa y las condiciones que el propio
plan declara para la retirada no pueden cumplirse tal como están escritas.

Lo que sí es cierto y accionable: los 28 fixtures que `CompatibilityCorpusTest`
cubre se ejecutan **dos veces** por `:pipeline-application:test`, una en cada
barrido. Ahí hay ahorro real.

La forma correcta de hacerlo es la inversa a la propuesta: no «fusionar dos
barridos», sino **extraer el trabajo compartido** —el descubrimiento de fixtures, el
staging, el almacén sembrado, la ejecución del CLI— a un único apoyo, y dejar que
cada barrido conserve sus aserciones. Eso elimina la doble ejecución sin retirar
ni una aserción, y es compatible con el grano por fixture.

### Condición de retirada, reescrita a partir de lo medido

```text
RETRACTADO:  "las 31 fixtures se ejecutan en ambos barridos"

VÁLIDO:      cada barrido conserva TODAS sus aserciones actuales,
             incluidas las seis mecánicas exclusivas de la tabla,
             y el grano por fixture sigue siendo seleccionable
             de forma independiente con --tests.

             Condición mecánica verificable:
               - los métodos @Test reales de CompatibilityCorpusTest
                 siguen siendo >= 25 y cada uno sigue siendo direccionable
               - fixture14WithoutStoreFailsTyped sigue presente
               - fixture28ZipSlipDefense sigue presente
               - UatCompat001 sigue sembrando el almacén y exportando
                 PIPELINE_STORE_PASSPHRASE
               - 21/31/32 siguen ejecutándose por algún barrido
               - ningún barrido baja de 31 fixtures cubiertas
```

## 5. Lo que sigue abierto

```text
medición de wall time, barrido UatCompat001       351 s, rc=0  (1 test)
medición de wall time, barrido CompatibilityCorpus 311 s, rc=1  (30 tests, 5 fallos)
fusión de barridos                                NO — premisa refutada
extracción del apoyo compartido                   autorizada, sin empezar
arreglo de los 5 fallos de ConsolePlaneProbe      NOT_RUN — slice propio
extracción de la mecánica compartida de procesos  NOT_RUN (BLOQUE 3)
```

`CliRun`, `StrictCliRun`, `Subprocess`, `OwnedSubprocess` y `SubprocessOutcome`
siguen coexistiendo con 18, 2, 26, 20 y 2 consumidores respectivamente. La
consolidación es trabajo real, pero pertenece al BLOQUE 3 y exige migrar
consumidores uno a uno; no es un cambio de un día.

## 6. Mediciones de reloj

Ejecutadas el 2026-10-14 sobre `fc556966`, máquina cálida, con límite de 600 s
según la regla 4 de AGENTS.md. Cada una con `argv`, código de salida y segundos
observados.

| Barrido | argv | rc | wall | XML |
| --- | --- | --- | --- | --- |
| `UatCompat001CorpusSmokeRunTest` | `./gradlew :pipeline-application:test --tests 'UatCompat001CorpusSmokeRunTest'` | **0** | **351 s** | 1 test, 0 fallos, `time=328.965` |
| `CompatibilityCorpusTest` | `./gradlew :pipeline-application:test --tests 'CompatibilityCorpusTest'` | **1** | **311 s** | 30 tests, **5 fallos**, `time=302.824` |

```text
wall antes de cualquier extracción   = 351 + 311 = 662 s
presupuesto declarado por el plan     = 900 s
consumo actual de SOLO el corpus      = 662 s = 74% del presupuesto
```

**Eso es el hallazgo con consecuencias.** Los dos barridos solos se comen las
tres cuartas partes de los 900 s que el plan fija para la validación completa de
una candidata. El presupuesto no está en problemas por el resto del gate: está
en problemas por el corpus.

### Los 5 fallos no son culpa de este slice, y tampoco son ruido

```text
fixture26FindFiles           findFiles + core.sh find must surface both .txt
fixture24UtilitiesRoundtrip  must have written the cat output to the Output Plane
fixture27ZipUnzip            Unzipped content must echo one/two/three
fixture29MixedUtilities      Unzipped content must echo alpha/beta
fixture25YamlRoundtrip       YAML must contain name=pipelinek
```

Los cinco leen la transcripción con `ConsolePlaneProbe.transcriptsOfWrittenSteps`
y la encontraron **vacía**. Evidencia de que son preexistentes: este slice no toca
nada bajo `v2/` (`git diff --name-only` devuelve únicamente
`scripts/gen-disabled-inventory.py` y el inventario generado).

No los he arreglado, y la razón importa: son exactamente el tipo de fallo que
`BLOCKED_BUDGET` no cubre. Un barrido que falla no se poda, se arregla. Y
arreglarlos es un slice propio, con su propio before/after.

Esto también significa que las dos cifras no son comparables entre sí como
coste de cobertura: `UatCompat001` (**351 s**, rc=0) ejecutó 1 test y pasó;
`CompatibilityCorpusTest` (**311 s**, rc=1) ejecutó 30 tests y falló 5. Comparar
un barrido que pasa con uno que falla no produce un ahorro defendible, así que no
se afirma ninguno.

```text
wall_saved           NO AFIRMADO. Requiere el after de una extracción real.
processes_saved      NO AFIRMADO. 31 + 30 = 61 forks hoy; el after no existe.
%tests_union_preserved  NO MEDIDO. Es la condición de retirada de la propia P0.
```

### El ahorro defendible, enunciado sin exagerarlo

Los 28 fixtures que ambos barridos comparten se ejecutan hoy **dos veces**: 31
forks en `UatCompat001` más 30 en `CompatibilityCorpusTest`. Ahí hay 28 ejecuciones
redundantes, pero **no puedo cifrarlas en segundos sin un after medido**, y un
porcentaje estimado es exactamente lo que el plan prohíbe.

## 7. Clasificación del slice

```text
Reference implementation consulted: ninguno. La equivalencia entre dos
                                   barridos de un corpus propio se mide
                                   contra el propio código; no hay referente
                                   externo al que adherirse.
Behaviour adopted:                ninguna todavía. Se midió antes de cambiar,
                                   y la medición refutó la premisa.
Intentional deviations:           no fusionar. Es una desviación respecto del
                                   plan, y es la razón por la que este recibo
                                   existe.
Security implications reviewed:   la fixture 28 (zip-slip, CVE-2023-32981)
                                   tiene cobertura ÚNICA en
                                   CompatibilityCorpusTest. Retirarla sería una
                                   pérdida de seguridad; por eso figura como
                                   condición de retirada explícita.
Tests demonstrating the contract: el harness de matriz es la evidencia de
                                   caracterización; no certifica nada por sí
                                   mismo. La certificación de cualquier
                                   extracción compartida requerirá su propio
                                   before/after de aserciones.
```