# Design: policy-guardrails

## Shadow path

```text
EventHistory/EventTail -> PolicyRequestProjector -> Cedar -> PolicyAssessment
```

Detached/post-run. Never execution authority.

## Enforcement path (future)

```text
Prepared protected intent -> PolicyAdmission -> Cedar -> Allow/Deny -> effect
```

A separate API and lifecycle. Do not implement by intercepting emitted events.

## Identity

ResourceRef maps to Cedar entity IDs. Request context contains request-specific data only; stable resource
attributes belong to Cedar entities/resource catalog.

## Trust

Platform/org policies cannot be disabled by pipeline source. Pipeline-local policies are advisory or
additional restrictions unless a centrally governed configuration says otherwise.
