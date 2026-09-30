# Manifest e instrucciones de integración

## Archivos

```text
00-README.md
01-context/
  CURRENT-STATE-AND-GUARDRAILS.md
02-specifications/
  PRODUCT-SPECIFICATION.md
  UNIFIED-INVOCATION-ARCHITECTURE.md
  CREDENTIAL-CAPABILITY-MODEL.md
  PIPELINE-KTS-DSL.md
  INLINE-AGENT-CLI.md
  PROVIDERS-ADAPTERS-PROJECTIONS.md
  OUTPUT-POLICY-AND-FILTERING.md
  PLUGIN-TRUST-MODEL.md
  COMPATIBILITY-CONTRACT.md
  EXECUTION-PROFILES.md
03-adrs/
  ADR-PKAS-001..010
04-roadmap/
  ROADMAP-INTEGRATION.md
  WORK-UNITS.md
  ROADMAP-PATCH.md
05-uat/
  UAT-MASTER-PLAN.md
  UAT-MATRIX.md
  GATE-TEETH.md
  RISK-REGISTER.md
06-adoption/
  MIGRATION-GUIDE.md
  AGENT-ADOPTION-PROMPT.md
  SPIKES-AND-OPEN-QUESTIONS.md
```

## Destino sugerido

No copiar ciegamente. Tras disposition y renumeración:

```text
docs/v2/00-context/           → current-state/disposition

docs/v2/03-specifications/    → specs aceptadas

docs/v2/04-adrs/              → ADRs renumerados

docs/v2/05-roadmap/           → sólo patch/plan que complemente ROADMAP

docs/v2/07-uat/               → UAT/gate teeth aceptados
```

Una alternativa más limpia durante proposal stage es conservar temporalmente el pack en:

```text
docs/proposals/pipelinek-agent-secretless/
```

hasta que cada pieza tenga disposition. Una vez aceptada, mover sólo documentos normativos a su ubicación canónica y archivar el pack de propuesta para evitar doble autoridad.

## Verificación documental previa a commit

- links internos;
- IDs ADR/WU libres;
- no contradicción con ADRs vigentes;
- ROADMAP único sigue siendo autoridad;
- referencias a HEAD actualizadas al SHA de integración;
- ningún receipt histórico modificado;
- `ACTIVE_DOCUMENTS.md` actualizado sólo cuando las specs sean realmente aceptadas.



## Inventario generado del pack

- `00-README.md`
- `01-context/CURRENT-STATE-AND-GUARDRAILS.md`
- `01-context/SOURCE-BASELINE.md`
- `02-specifications/COMPATIBILITY-CONTRACT.md`
- `02-specifications/CREDENTIAL-CAPABILITY-MODEL.md`
- `02-specifications/EXECUTION-PROFILES.md`
- `02-specifications/INLINE-AGENT-CLI.md`
- `02-specifications/OUTPUT-POLICY-AND-FILTERING.md`
- `02-specifications/PIPELINE-KTS-DSL.md`
- `02-specifications/PLUGIN-TRUST-MODEL.md`
- `02-specifications/PRODUCT-SPECIFICATION.md`
- `02-specifications/PROVIDERS-ADAPTERS-PROJECTIONS.md`
- `02-specifications/UNIFIED-INVOCATION-ARCHITECTURE.md`
- `03-adrs/ADR-PKAS-001-SINGLE-PROCESS-EXECUTION-SPINE.md`
- `03-adrs/ADR-PKAS-002-CREDENTIAL-USE-NOT-SECRET-RETRIEVAL.md`
- `03-adrs/ADR-PKAS-003-PROVIDER-ADAPTER-PROJECTION-SEPARATION.md`
- `03-adrs/ADR-PKAS-004-EXPLICIT-SECURITY-POSTURE.md`
- `03-adrs/ADR-PKAS-005-FREEZE-LEGACY-WITHCREDENTIALS.md`
- `03-adrs/ADR-PKAS-006-EPHEMERAL-CONFIG-OVERLAYS.md`
- `03-adrs/ADR-PKAS-007-SENSITIVE-EXTENSIONS-OUT-OF-PROCESS.md`
- `03-adrs/ADR-PKAS-008-REDACTION-BEFORE-FILTERING.md`
- `03-adrs/ADR-PKAS-009-PROFILES-AS-COMPOSABLE-POLICY.md`
- `03-adrs/ADR-PKAS-010-NO-EBPF-SECRET-MEMORY-PATCHING.md`
- `04-roadmap/ROADMAP-INTEGRATION.md`
- `04-roadmap/ROADMAP-PATCH.md`
- `04-roadmap/WORK-UNITS.md`
- `05-uat/GATE-TEETH.md`
- `05-uat/RISK-REGISTER.md`
- `05-uat/UAT-MASTER-PLAN.md`
- `05-uat/UAT-MATRIX.md`
- `06-adoption/AGENT-ADOPTION-PROMPT.md`
- `06-adoption/AGENT-SKILL-GUIDANCE.md`
- `06-adoption/MIGRATION-GUIDE.md`
- `06-adoption/SPIKES-AND-OPEN-QUESTIONS.md`
