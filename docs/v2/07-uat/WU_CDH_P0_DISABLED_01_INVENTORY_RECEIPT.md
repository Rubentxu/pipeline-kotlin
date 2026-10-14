# CDH-P0-DISABLED-01 — Un `@Disabled` sin triage se contabilizaba como historia archivada

| Field | Value |
| --- | --- |
| Work unit | `CDH-P0-DISABLED-01` |
| Base SHA | `fc556966` |
| Clasificación | STEP-CHARACTERISATION → no-regresión (defecto medido primero, luego cerrado) |
| Suite | `scripts/test_gen_disabled_inventory.py`, **0 → 9** filas, verde |
| Evidence | `docs/v2/07-uat/evidence/cdh-p0-disabled/` |

## 1. El defecto, medido antes de tocar código

El roadmap del paquete pide, en BLOQUE 0: *«Endurecer `gen-disabled-inventory.py`:
default UNKNOWN/REFUSED; ningún test nuevo cae a HISTORICAL por defecto»*.

El código no lo hacía. `classify()` terminaba en una rama incondicional:

```python
    # default: archived snapshot evidence
    return "HISTORICAL", "Archived migration/cycle snapshot evidence (verbatim traceability)", "no"
```

Y el comentario inmediatamente anterior afirmaba lo contrario:

```text
# A default would silently mask new misclassified entries, so new non-historical
# @Disabled entries MUST be added to CLASSIFICATION below or the generator
# fails the run.
```

**No había ruta de fallo.** `main()` no devolvía nada, así que el proceso siempre
salía con 0, y `counts[cat] += 1` aceptaba cualquier clasificación sin
comprobarla.

Reproducido con la CLI real sobre una clase de prueba **nueva**, sin historia:

```console
$ python3 -c "...classify(...)..."
entradas extraídas: [('`a brand new contract nobody has triaged`', 'disabled for now')]
  classify() -> category='HISTORICAL'
                blocks_gate_1='no'
                reason='Archived migration/cycle snapshot evidence (verbatim traceability)'
```

Un test que alguien deshabilita al pasar queda archivado como evidencia de un
ciclo cerrado y **deja de bloquear Gate-1**. El generador informaba de un gate
despejado sobre un `@Disabled` que nadie ha triado.

## 2. El coste oculto, medido sobre el árbol real

Después del cambio, el mismo generador sobre el repositorio:

```console
$ python3 scripts/gen-disabled-inventory.py; echo "exit=$?"
FAIL: 72 @Disabled entr(y/ies) not declared in CLASSIFICATION.
...
UNKNOWN                  = 72
mandatory-disabled (Gate-1)    = 72
Gate-1 @Disabled state         = OPEN (mandatory or untriaged disabled > 0)
exit=1
```

**72 entradas** que hasta hoy se contabilizaban como `HISTORICAL` y no bloqueaban
nada. La evidencia completa está en
`evidence/cdh-p0-disabled/untriaged-report.stderr.txt`; las 20 primeras rutas se
imprimen, con el resto resumido.

## 3. El cambio

- `UNKNOWN` es una categoría distinta del resto, no una sexta más. Es el
  resultado fail-closed: es la única que hace salir al generador con ≠ 0.
- El default de `classify()` es `UNKNOWN` con `blocks_gate_1 = "yes"`.
- `UNKNOWN` cuenta en el total mandatory de Gate-1, pero se reporta en línea
  propia para que el documento no confunda «nadie lo trió» con «triado y
  obligatorio».
- `HISTORICAL` sigue existiendo y es alcanzable, pero **sólo por declaración**.
  Afirmar que algo es evidencia archivada de un ciclo cerrado es una decisión
  que pertenece a la tabla, donde es revisable. El silencio no es evidencia de
  archivado.
- El diagnóstico lista rutas, no sólo un número: un gate que falla con «72» sin
 decir dónde manda al lector a buscar.

## 4. Evidencia

```text
suite                    9/9 OK
mutaciones M24..M28      5/5 muertas, control verde
                          SHA restaurado: c6889a78553d86a87ef6dbc59aa3925811c3716ad1f8d076477709d54ffcaa9d
```

| ID | Mutación | Muerta por |
| --- | --- | --- |
| M24 | restaura el default `HISTORICAL` incondicional | `test_untriaged_is_not_historical` |
| M25 | `UNKNOWN` clasifica pero no bloquea Gate-1 | `test_untriaged_blocks_gate_1` |
| M26 | el generador sale con 0 aunque queden entradas sin triar | `test_generator_exits_nonzero_on_the_real_tree` |
| M27 | `UNKNOWN` excluido del total mandatory | `test_inventory_written_marks_unknown_as_blocking` |
| M28 | `classify()` ignora `CLASSIFICATION` y siempre devuelve `UNKNOWN` | `test_declared_entries_still_resolve` |

M28 existe para que «hacer todo `UNKNOWN`» no pase: la tabla debe seguir ganándole
a su default.

## 5. Lo que este slice deja abierto a decisión del operador

El generador ahora **falla con exit 1** sobre el repositorio actual, porque hay 72
entradas sin triagear. Eso es lo correcto, pero significa que **el inventario
generado no es publicable tal cual** y que `WU_LPR_061_DISABLED_INVENTORY.md`
contiene ahora 72 filas `UNKNOWN`.

Triar esas 72 entradas no es una tarea de implementación: exige decidir, entrada
por entrada, si cada `@Disabled` es evidencia histórica de un ciclo concreto, una
deuda conocida, o un olvido. Decidir 72 clasificaciones sin el criterio del
operador es exactamente el tipo de dato inventado que este trabajo evita.

```text
candidatas @Disabled a triar    72
decididas por el agente          0 — requieren criterio humano
estado del gate Gate-1          OPEN, correctamente
inventario publicado             regenerado, refleja UNKNOWN
```

El generador queda en exit 1 y el gate falla hasta que se trie. Es lo que hace
ahora, y es la lectura honesta del defecto: el repositorio tiene 72 disabled sin
triage y eso debe verse como lo que es.

## 6. No ejecutado

```text
`cd v2 && ./gradlew check` completo          NOT_RUN (fuera de alcance: dos scripts Python)
tests de Gradle de :pipeline-application      NOT_RUN salvo los dos barridos del corpus medidos
arreglo de los 5 fallos de ConsolePlaneProbe NOT_RUN (preexistentes; slice propio)
distribución, cobertura, certificación        NOT_RUN
```

## 7. Clasificación del slice

```text
Reference implementation consulted: ninguno. Un clasificador fail-closed de
                                   inventarios es interno; no hay equivalente
                                   Jenkins ni de otro ecosistema.
Behaviour adopted:                default UNKNOWN fail-closed; UNKNOWN cuenta
                                   para Gate-1 y es el único motivo de salida
                                   no-cero.
Intentional deviations:           none
Security implications reviewed:   n/a. Sin credenciales, sin red, sin ejecución
                                  de procesos; es aritmética de clasificación
                                  y vocabulario de veredicto.
Tests demonstrating the contract: scripts/test_gen_disabled_inventory.py
                                  (9 filas; 5 en UnknownIsRefused,
                                   3 en TheGeneratorRefusesToPublish,
                                   1 en ExtractionStillWorks)
```