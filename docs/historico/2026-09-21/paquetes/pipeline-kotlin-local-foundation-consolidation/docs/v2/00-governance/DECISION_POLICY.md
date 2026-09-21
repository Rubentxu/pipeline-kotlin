# Decision policy for evolutionary architecture

## Purpose

Allow architecture to evolve without accumulating parallel half-solutions.

## Decision classes

### Class A — reversible implementation choice
Examples: internal serializer, a CLI rendering library. Decide in code review; no ADR unless it affects a public contract.

### Class B — architectural contract
Examples: canonical IR, plugin loading lifecycle, capability API, replay semantics. Requires ADR + specification + UAT gate.

### Class C — high-cost/uncertain technology
Examples: strong Linux sandbox backend, native compilation strategy. Requires a time-boxed spike with measurable success criteria before ADR acceptance.

## WIP policy

At most:

- one active consolidation migration that changes a canonical path;
- one architecture spike for the next milestone.

Do not start the next canonical migration until the previous legacy path is deleted or explicitly quarantined with an expiry milestone.

## Deprecation budget

Internal deprecated-for-removal paths may survive **at most two milestones**. Public plugin/DSL compatibility follows semantic versioning and a documented deprecation policy.

## Spike output

Every spike must end with:

- hypothesis;
- prototype or evidence;
- measured result;
- recommendation;
- rejected alternatives;
- deletion of throwaway production hooks;
- ADR action: accept/amend/reject.
