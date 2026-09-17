# XCA — Checkpoint auditado (2026-09-17)

**Tipo:** checkpoint auditado. **NO es un cierre de campaña.**

Este tag publica trabajo revisable que hasta ahora solo existía en local. No afirma que
XCA-2 esté cerrado, ni que XCA-3 o P3.0.1 estén hechos.

## Estado real del gate de campaña

```text
XCA-2 runtime evidence   CERRADO      NO  (B.2 -> E pendientes)
XCA-3 permanent fitness  CERRADO      NO  (ni empezado)
P3.0.1 strengthened RED  READY        NO  (ni empezado)
```

Legado de esta sesión: XCA-0/1A/1B/1C/GOV/YAML/1D cerrados, stack review corregido,
XCA-2A completo (A1-A5) y XCA-2B probado solo hasta el primer vertical runtime.

## Qué contiene este checkpoint

```text
XCA-0   auditor con guards falsables (assert imposible eliminada)
XCA-1A  honestidad del ledger (13 claims falsos eliminados)
XCA-1B  reconciliación de inventario (excepciones tipadas, sin listas mágicas)
XCA-1C  recon + 1C.1 (5 fixtures) + 1C.2 (overloads String)
XCA-GOV autoridad duplicada eliminada; el ledger no era YAML válido
XCA-YAML autoridad parseable + guards de schema
XCA-1D  decisión D: reclasificar, ADR-0050 intacto
XCA-2A A1 fitness anti-bypass (falsificado), A2/A3/A4/A5 (compilan),
       caracterización de la frontera de ejecución (falsificada)
XCA-2B primer vertical runtime real (CLI instalado -> RunId -> journal)
```

## Evidencia clave verificada

```text
CLI instalada    v2/pipeline-application/build/install/.../bin/pipeline-application
RunId real       006df865-a1d4-4bcf-ac8c-895c0864ea60
operation_journal 2 filas
  006df865-...-s0-0  SUCCEEDED  input.stepId=core.echo
  006df865-...-s1-0  SUCCEEDED  input.stepId=core.sh
```

Confirmaciones empíricas (no asumidas):

1. `DurableOperation.input.stepId` ES el StepKey en datos reales.
2. El mapeo del reader es exacto: `op_id -> OperationId`, `input.stepId -> PluginStepId`,
   `status -> OperationStatus`.
3. `events` es un almacén SEPARADO (14 filas) del `operation_journal` (2 filas): los
   eventos son observabilidad, no autoridad de ejecución.
4. `replay_cursor` es un tercer almacén independiente.

## Lo que este checkpoint NO prueba

```text
el reader Kotlin no se ha ejecutado contra ese journal
solo se observó SUCCEEDED (RUNNING/FAILED/PENDING/LOST sin producir)
RunNotFound no se intentó
canarios execute-once-per-fixture, shared-fixture y STOPPED_G7 sin ejercitar
B/C/D/E, XCA-3 y P3.0.1 pendientes
```

## Siguiente paso concreto

```text
1. B.2  test de contrato del reader contra InMemoryOperationJournal,
        assert observed == {core.echo, core.sh}, y FALSIFICARLO
2. B.2b misma aserción contra el run.db real vía SqliteOperationJournalImpl
3. B.3  un Step FAILED debe seguir contando como observado
```

Contexto completo en: `CAMPAIGN_XCA_MANDATE.md`, `XCA2A_JOURNAL_CHARACTERIZATION.md`,
`XCA2B_FIRST_RUNTIME_VERTICAL.md`.
