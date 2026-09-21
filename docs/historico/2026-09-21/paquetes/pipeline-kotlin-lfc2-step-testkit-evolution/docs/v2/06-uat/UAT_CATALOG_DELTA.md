# UAT Catalog delta

## Scenario corpus

- **UAT-SCEN-001:** ScenarioRunner ejecuta todos los examples soportados y obtiene expected status de manifest, no switch por filename.
- **UAT-SCEN-002:** todo example soportado del README tiene evidencia CI.
- **UAT-SCEN-003:** mismo Scenario se ejecuta desde JUnit, Just y CLI.
- **UAT-SCEN-NEG-001:** receiver ilegal falla con source position.
- **UAT-SCEN-NEG-002:** runtime-valued call declarative no está disponible.
- **UAT-SCEN-NEG-003:** body shape inválido falla before effects.
- **UAT-SCEN-NEG-004:** unknown/incompatible plugin falla before effects.

## Step certification

- **UAT-STEP-CERT-001:** core.echo = CERTIFIED.
- **UAT-STEP-CERT-002:** core.sh = CERTIFIED.
- **UAT-STEP-CERT-003:** external reference Step = CERTIFIED.
- **UAT-STEP-CERT-004:** external Step requiere cero core source edits.
- **UAT-STEP-CERT-005:** missing capability => handler side-effect counter = 0.
- **UAT-STEP-CERT-006:** duplicate StepKey rejected deterministically.

## TestKit

- **UAT-TK-001:** PipelineExtension aísla workspace/stores y no deja recursos.
- **UAT-TK-002:** RealPipelineExtension ejecuta distribución en otro proceso y no hereda test-only plugin classes.
- **UAT-TK-003:** StepContractSuite produce certification receipt.
- **UAT-TK-004:** PipelineSessionExtension kill/resume no repite side effect reusable ya completado.
- **UAT-TK-005:** SandboxPipelineExtension rootless teardown = zero owned containers/processes.

## Generic bodies

- **UAT-BODY-001:** dir cambia workspace sólo en body y restaura.
- **UAT-BODY-002:** nested withEnv restaura exactamente parent.
- **UAT-BODY-003:** timestamps usa generic body route.
- **UAT-BODY-004:** retry fail/fail/success = 3 AttemptIds, un StepId lógico.
- **UAT-BODY-005:** timeout mata process tree.
- **UAT-BODY-006:** parallel con siblings seriales + overlap real + deterministic join.
- **UAT-BODY-007:** child re-resuelve por StepRegistry.

## Scripting

- **UAT-SCRIPTING-001:** dedicated @KotlinScript compila y mapea diagnostics.
- **UAT-SCRIPTING-002:** `$VAR`, `${'$'}VAR`, multiline y source lines preservados sin global rewrite.

## Security

- **UAT-SEC-001:** no descendant process after cancel/timeout.
- **UAT-SEC-002:** secret absent from events/output/TestKit diagnostics.
- **UAT-SEC-003:** temp credential file absent after success/failure/cancel.
- **UAT-SEC-004:** denied capability fails before adapter invocation.

## Closure rule

Si cualquier UAT obligatorio de LFC-2 está disabled/quarantined, LFC-2 permanece OPEN.
