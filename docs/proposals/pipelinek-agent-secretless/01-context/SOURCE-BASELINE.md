# Baseline y fuentes inspeccionadas

## PipelineK

Baseline de preparación del paquete:

```text
repo: Rubentxu/pipeline-kotlin
ref : main
sha : e0e32abae1b1dde06c19ab694877250368690671
date: 2026-09-30
head: feat(directives): S2-A - typed three-valued `when` gate on the S1 kernel
```

Documentos/código usados para reconciliar la propuesta:

- `docs/v2/05-roadmap/ROADMAP.md` — autoridad de secuenciación; RP-7 contiene secretos/egress/policy.
- `docs/v2/00-governance/ACTIVE_DOCUMENTS.md` — autoridad documental/SDDK.
- `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`.
- `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`.
- `docs/v2/03-specifications/CREDENTIALS_PROVIDERS.md` — ya anticipa request/context/lease/projection.
- `docs/v2/04-adrs/ADR-0049-credentials-local.md` — SecretHandle, redaction y ProcessBuilder choke point.
- `v2/pipeline-scripting-api/.../StageScopeBuilders.kt` — `sh`, `withCredentials`, `registryStep`, `registryBlock`.
- `v2/pipeline-application/.../ShellOperationsCapabilityKey.kt` — capability canonical de shell.
- `v2/pipeline-domain/.../StepRegistry.kt` — registry/capability admission.
- `docs/v2/00-context/LB02_G3_A4_0_SH_GROUNDING.md` — `ShExecution`/durable shell path.
- `docs/v2/07-uat/WU_RP_011_SECRET_REDACTION_RECEIPT.md` y receipts relacionados de durable shell/streaming.
- `docs/v2/01-product/STEP_REGISTRY_PLAN.md` / plugin certification materials.

## Agent Secretless Vault

Fuente conceptual principal:

```text
repo: Rubentxu/agent-secretless
README-es.md: Agent Secretless Vault (ASV)
```

Invariantes adoptadas conceptualmente:

- API agent-facing sin `getSecret`/`exportSecret`;
- identidad y operación se autorizan por broker;
- preferencia por signer/proxy/session sobre entrega de bytes;
- posture explícita:
  `STRONG_SECRETLESS`, `SHORT_LIVED_EXPOSURE`,
  `ISOLATED_PROCESS_EXPOSURE`, `RAW_PROCESS_EXPOSURE`, `UNSUPPORTED`;
- isolated exec es compatibilidad/degradación, no equivalente a strong-secretless;
- redaction no sustituye confinement;
- mismo UID puede seguir siendo una limitación de memoria si el broker corre con el usuario;
- ASV no debe convertirse en un raw-secret provider de PipelineK.

## Regla al integrar

Este baseline es evidencia de diseño, no estado eterno. El agente integrador debe volver a consultar SDDK/Git/ROADMAP y actualizar cualquier afirmación que haya cambiado antes del commit documental.

