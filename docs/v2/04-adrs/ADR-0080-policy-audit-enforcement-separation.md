# ADR-0080: Policy audit and enforcement are separate planes

Status: PROPOSED / FUTURE  
Date: 2026-09-10

## Context

Cedar can add strong authorization/guardrail value for credentials, capabilities, deployments and artifact
promotion. An event consumer can audit only after an occurrence; it cannot prevent an already-started effect.
Mixing audit and enforcement would either weaken security or put arbitrary observer failures on the execution path.

## Decision

1. Cedar `AUDIT/SHADOW` is an event/history consumer and produces PolicyAssessments.
2. Cedar `ENFORCE` (future) uses a dedicated pre-effect `PolicyAdmission` seam.
3. New policies start in shadow and are tested against historical requests before enforcement.
4. Policies target typed effects/capabilities/resources, not concrete Step names where avoidable.
5. Platform/org mandatory policy is injected externally; pipeline-local configuration cannot remove it.
6. ResourceRef is the common identity projection for Cedar entities.
7. Audit failure never fails/cancels pipeline execution. Enforcement denial may intentionally prevent the protected effect.

## Deferred

- Cedar binding/runtime selection;
- organization distribution/control-plane APIs;
- enforcement implementation;
- multi-policy-engine support.
