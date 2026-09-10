# Change: policy-guardrails

Status: FUTURE — do not implement before EVT identity/history foundation is stable.

## Why

Policies add value only when they prevent or detect concrete security/governance risks. Cedar is a
candidate because PipelineRun/effect/resource requests map naturally to principal/action/resource/context.

## Initial value cases

- production credential guardrail;
- privileged capability guardrail;
- production deploy guardrail;
- artifact promotion/provenance guardrail;
- historical simulation and policy semantic diff before enforcement.

## Non-goals

Generic rules engine, policy-on-arbitrary-event JSON, OPA+Cedar multi-engine, marketplace, enforcement before shadow evidence.
