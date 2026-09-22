# WU-RP-031 · Extracción 1 — CanonicalStructuralDecisions

**Fecha:** 2026-09-22 · **Base:** d7324c05 (WU-RP-030)
**Patrón:** ROADMAP §5 WU-RP-031 — "pequeñas extracciones StructuralPreparation →
DurableResolution → TypedInputDecode → StepExecutor; cada extracción pasa golden
journal/event/replay y UAT kill/resume antes de retirar su predecesor".

## Cambio

Extracción mecánica pura (zero-semantic-change) del bloque de **decisiones
estructurales** del coordinator a `CanonicalStructuralDecisions.kt` (mismo paquete
`application.durable`):

- ADTs de decisión cerrados: `CanonicalContinuation`, `InvocationReconciliation`,
  `RunningCanonicalShellRecovery`, `BlockShellScope`, `BodyExecutionProjection`,
  `StageTimeoutProjection`, `NonCanonicalStep`.
- Funciones de preparación puras: `analyzeCanonicalDurableExecution`,
  `supportsCanonicalDurableExecution`, `checkCanonicalExecution`,
  `projectShellOptions`, `timeoutProjection`, `projectBodyExecution*`,
  `decodeDeadline`, `decodeCredentialBindings`, `applyPatchToContext`,
  `toOperationStatus`, `deriveOverlay` helpers.
- Raíces de elegibilidad: `canonicalStepIds`, `canonicalBodyStepIds`
  (derivadas del descriptor, sin lista hardcodeada de StepKeys).

`private` → `internal` (misma visibilidad efectiva: solo el módulo
pipeline-application consume estos símbolos; Main.kt usa las 2 funciones
públicas de análisis). Coordinator: 2346 → 1943 líneas.

## Oráculo (obligación del WU)

- L0/L3 golden/replay: ExecutionPathsCharacterisation (8/8), WULpr302Phase1b (puro),
  B11ContextBlocks (7/7), WULpr011ResumeLifecycleUAT (kill/resume, 1/1). 27/27.
- L4 paquete durable completo (incluye UATs de kill/resume, retry, parallel,
  replay): **316/316 GREEN**.

## Estado de la escalera WU-RP-031

```text
[x] E1 StructuralPreparation/decisions  (esta extracción)
[ ] E2 DurableResolution (reconcileInvocation/deterministicGate/replayResolution/recoverRunningShell)
[ ] E3 TypedInputDecode (rejectSchema + decoders)
[ ] E4 StepExecutor (dispatch loop)
```

Cada extracción siguiente repite el mismo oráculo antes de tocar wiring.
