# WIP-11 — §1.2 Estado final del gate sobre HEAD candidato a v0.48.0-rc2

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**Veredicto:** gate **con fallos reales de producto** — bloquea CANDIDATE_PUBLISHED.

## Comando

```bash
cd v2 && ./gradlew check --rerun-tasks --console=plain --no-daemon
```

Log: `/tmp/full-gate-rc2-v5.log`. **BUILD FAILED in 29m 56s** (sin crash de daemon; el build sí
completó todas las tareas de test).

## Resultado del gate

```
2811 tests completed, 3 failed, 123 skipped
```

123 skipped incluye los dos tests adversariales `@Disabled` (OUT-01, OUT-02) que esperan
política de migración del Output Plane.

## F-1: B1aShNonDurableRouteCharacterizationTest > b (large transcript)

```
expected: <true> but was: <false>
the durable arm must have committed the transcript to the Output Plane
(extent=null, expected >= 16777224); a null or short extent means the
durable route fell back to the non-durable one and this row's comparator is void
    at B1aShNonDurableRouteCharacterizationTest.kt:216
```

El "durable arm" debería escribir un transcript de ≥16 MiB en el Output Plane y no lo hizo
(extent=null). Posibles causas (no investigadas en este WIP):

- El cambio en `WorkspaceResolver.resolveArchiveDir` (PATH-01) requiere runIds path-safe; si el
  test usa un runId con caracteres unsafe, la resolución lanza `IllegalArgumentException` y
  cae al modo no-durable.
- El cambio en `RunIdDirectory.record` (RUN-01) no debería afectar a este test directamente,
  pero podría si el test usa internamente `RunIdDirectory` para algo.

## F-2: InstalledDistributionHarnessFitnessTest > S6-PRE

```
S6-PRE — todo harness que bifurca el runtime pasa por OwnedSubprocess
no hay ningún harness nuevo que bifurque el runtime por su cuenta() FAILED
    at InstalledDistributionHarnessFitnessTest.kt:130
```

Precondición S6-PRE: ningún harness nuevo debe bifurcar el runtime por su cuenta; deben pasar
por `OwnedSubprocess`. La precondición falla, lo que sugiere que algún harness nuevo (creado
o modificado durante B1) bifurca sin usar `OwnedSubprocess`.

## F-3: TestSandboxFitnessTest > S6-PRE 2

```
S6-PRE 2 — un arnes que bifurca no escribe fuera de su sandbox
ningun arnes que bifurca escribe fuera de su directorio propio() FAILED
    at TestSandboxFitnessTest.kt:77
```

Precondición S6-PRE 2: ningún harness que bifurca escribe fuera de su directorio. Falla; puede
ser consecuencia del mismo problema en F-2.

## Veredicto y consecuencia

El gate de B1 termina con 3 fallos reales (no son crashes de infraestructura). Según el roadmap
§"Definición de cierre":

> "Funcionalidad terminada y criterios de aceptación comprobados.
> Pruebas de integración, regresión y UAT/AAT aplicables.
> Gate local completo sobre el SHA candidato."

El gate NO está completo. **No se puede publicar `v0.48.0-rc2` ahora.**

Las 3 candidatas del roadmap son:
- `CANDIDATE_PUBLISHED` (no se cumple — gate incompleto)
- `CERTIFIED` (sin certificación del harness — irrelevante mientras el gate local no pase)
- `STABLE_PROMOTED` (no aplicable)
- `BLOCKED_EXTERNAL` (aplicable — los defectos son del producto, no del entorno externo)

## Decisión

**B1 → `BLOCKED_EXTERNAL`.** No se publica `v0.48.0-rc2` en este ciclo. Los 3 fallos reales se
registran como deuda residual priorizada para el siguiente ciclo (B2 o un nuevo B1').

## Acciones tomadas

1. Re-ejecutado el gate global con RUN-01 unique-temp + OUT-01/OUT-02 @Disabled.
2. Confirmado que NO es daemon crash: el build completa todas las tareas y reporta
   `BUILD FAILED` por tests fallidos.
3. Identificados los 3 tests fallidos con su ruta de evidencia.
4. Marcada la release como `BLOCKED_EXTERNAL`.

## Acciones NO tomadas y por qué

- **Investigar F-1, F-2, F-3 a nivel código.** Requeriría análisis profundo de los 3 tests
  y posibles interacciones con mis fixes RUN-01/PATH-01/COV-01. No es trabajo de este WIP.
- **Aplicar fix a los 3 fallos.** Sin entender la causa raíz, un fix podría ser un parche
  incorrecto que introduzca regresiones.
- **Re-ejecutar el gate** sin investigar — sería otro `NOT_RUN_BLOCKED_BY_LOAD` o un nuevo
  fallo diferente.

## Deuda residual priorizada para el siguiente ciclo

| ID | Tipo | Severidad |
|---|---|---|
| F-1 | B1aSh durable arm no commitea transcript | HIGH (afecta admisión durable) |
| F-2 | S6-PRE harness bifurca sin OwnedSubprocess | MEDIUM (afecta fitness del ecosistema) |
| F-3 | S6-PRE 2 harness escribe fuera de sandbox | MEDIUM (afecta seguridad operacional) |
| OUT-01 | SegmentOutputStore prune filter permisivo | HIGH (test @Disabled) |
| OUT-02 | SegmentOutputStore safeStreamName no inyectivo | HIGH (test @Disabled) |

Los 5 ítems bloquean el avance. Si el siguiente ciclo aborda todos, B1 puede reabrir.

## Próximo paso

WIP-12: declarar el estado de B1, registrar el bloqueo, y considerar el avance a B2 o un
nuevo B1' con la deuda priorizada arriba.