# RP-3 EXIT REVIEW — Fortalecer arquitectura, DSL y ejecución durable

**Fecha:** 2026-09-22. **SHA auditado:** 6e1d30f4 (código WU-RP-033 = 554672aa; CI 35756206083 SUCCESS 7/7).
**Nota de honestidad:** este review AUDITA el criterio; las WU del roadmap ya
registradas como cerradas (030/031/032/033) se re-verifican aquí contra su
evidencia, no se da por bueno el marcado DONE de la WU.

## Criterio de salida (ROADMAP §5)

> Un Step externo con y sin cuerpo accede al mismo registro genérico (si forma
> parte del SDK soportado), con admission/replay/typed errors comprobados y cero
> nueva ramificación concreta en el coordinator. Los recorridos soportados tienen
> paridad demostrada.

## Veredicto por cláusula

### 1. Step externo SIN cuerpo por el registro genérico — CUMPLE (OBSERVED)
- `example.uppercase` (examples/example-uppercase-plugin, CERTIFIED, receipt LB-02):
  su DSL baja a `registryStep(...)` → `RegistryStepSpec` → lowering genérico →
  spine canónico. Cero cambios en core al añadir el plugin.
- Evidencia: certification del plugin + UAT-RP-003 (ADR-0069).

### 2. Step externo CON cuerpo por el registro genérico — CUMPLE (OBSERVED, esta ronda)
- WU-RP-033 (554672aa): `RegistryBlockSpec` + `registryBlock(...)`; lowering
  genérico a `BlockStepNode`; coordinator compone autoridad de body-policy
  (registro abierto primero, tabla canónica fallback en `UnknownStep`).
- Proof: `ExternalStepWithBodyRegistryProofTest` 3/3 (with-body vía CANONICAL_ENGINE,
  fail-closed 0 filas journal, paridad atómica) + `RegistryBlockDslLoweringTest` 3/3.
- Regresión completa local + CI 7/7 en el SHA.

### 3. Admission/replay/typed errors comprobados — CUMPLE (OBSERVED)
- Admission fail-closed: proof (b) — clave de bloque desconocida → Failure tipado,
  CERO filas de journal (antes de efectos). Capability admission ya cubierta por
  ADR-0069/UAT-RP-003 (missing capability → typed rejection).
- Replay: `ReplayPolicy` sigue siendo única autoridad (`OUTPUT_EXISTS != REPLAY_REUSE`,
  ley E-EM-11); WU-RP-033 no añade superficie de replay nueva y las suites
  `EffectReplayPolicy*` siguen verdes en el gate.
- Typed errors: DSL exhaustivo (sin else), `BodyPolicyRejection` ADT, fail-closed
  de claves desconocidas/incoherentes.

### 4. Cero nueva ramificación concreta en el coordinator — CUMPLE (OBSERVED)
- Dif: ningún `when(stepKey)`/`when(stepName)` añadido. La composición resuelve la
  política desde `StepDescriptor` (metadatos declarados), no desde la clave.
- Fitness `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest` actualizado como
  allow-list documentada (RegistryBlockSpec = forma genérica con lowering propio,
  mismo destino estructural `BlockStepNode`), no como excepción de semántica.

### 5. Paridad de recorridos soportados demostrada — CUMPLE (OBSERVED con nota)
- Paridad atómica: proof (c) (opaque node Success).
- Paridad cuerpo: hijo del Step externo se ejecuta por el MISMO motor canónico que
  los Block Steps core (proof a: eventos/outcomes del hijo vía engine canónico).
- Nota residual: el espacio de políticas de cuerpo admitido vía registro abierto es
  el declarado como soportado (`SCOPED_SEQUENTIAL_RETRYING`); políticas más ricas son
  extensión futura DETRÁS del mismo seam (no hay camino privilegiado core).

## Cobertura de las WU de RP-3 (re-verificación contra evidencia)

| WU | Criterio | Estado re-verificado |
|---|---|---|
| WU-RP-030 | fitness hexagonales/connascence/contrato capacidades | CUMPLE — arch fitness 313/313 en SHA actual (incl. Lfc2RegistryFamilyFitness). |
| WU-RP-031 | extracciones StructuralPreparation→…→StepExecutor con golden journal/replay + kill/resume | CUMPLE — receipts WU_RP_031_E3/E4 + suites UAT kill/resume verdes (UAT-RP-012/013 COVERED). |
| WU-RP-032 | semánticas DSL: declaración vs ejecución, fail-closed superficie no soportada | CUMPLE — receipts R1/R2 + UatDsl008 + post{} fail-closed; CI verde. |
| WU-RP-033 | Step externo con cuerpo por registro genérico | CUMPLE — receipt propio + proof + CI verde. |

## Deuda técnica clasificada (abierta, NO bloquea salida RP-3; candidata a RP-4/5)

1. **Cancelación de hijos de cuerpo de Steps externos** — semántica de cancelación
   para cuerpos de `RegistryBlockSpec` en vuelo. Mismo contrato que Block Steps core
   (UAT-RP-014). Prioridad alta dentro de RP-4 (aislamiento runner, WU-RP-041).
2. **Espacio de body-policies del registro abierto** — hoy `SCOPED_SEQUENTIAL_RETRYING`;
   ampliar (parallel/retrying declarados por plugin) detrás del mismo seam cuando
   exista demanda real de plugin.
3. **Auditoría declaración-vs-ejecución completa del DSL** — la cláusula RP-3/032 la
   cubre por muestreo (fail-closed post{}, options, unknown keys); el barrido
   exhaustivo variante-a-variante es deuda documentada de bajo riesgo (el compilador
   ya es exhaustivo por sealed when).
4. **UAT-RP-018 PARTIAL** (sandbox 'os' → RP-4/5, ADR-0016) y **UAT-RP-005 inv 3**
   (MANIFEST.json → WU-RP-042, ADR-0095): ya diferidas formalmente con ADR.

## Salida RP-3

**DECLARADA CUMPLIDA** sobre SHA 6e1d30f4 con las deudas 1-4 registradas y
trazadas a RP-4/RP-5. El gate formal de production-ready sigue siendo RP-5;
este review no reemplaza la certificación de candidato.
