# AGENTS.md candidate laws — DO NOT MERGE UNTIL VALIDATED

Only promote these after the corresponding EVT/POL closure receipt proves them.

## Event Spine candidate

- Event history is observable projection, never durable execution authority.
- Remote/live observers MUST NOT be execution cancellation/failure authorities.
- Ordinary event relay/network latency MUST NOT sit on the canonical execution critical path.
- Event output and execution stdout/stderr are separate channels.
- ResourceRef construction is typed/deterministic; no ad-hoc production string IDs for cross-system resources.

## Event Harness candidate

- Real examples are executable specifications: expected execution outcome + observable contract.
- Parallel event contracts use causal/partial-order laws, not accidental global ordering.
- A verification failure changes acceptance, not the already-computed execution outcome.

## Policy candidate

- Event-driven policy is audit/shadow only.
- Enforcement is a distinct pre-effect PolicyAdmission decision.
- Pipeline-local configuration cannot relax mandatory platform/org policy.
