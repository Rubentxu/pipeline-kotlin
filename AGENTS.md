# AGENTS.md

## V2 DEVELOPMENT PRIME DIRECTIVE

1. Authority: docs/v2/ (ROADMAP, ADRs, MIGRATION_PLAN, FITNESS, CURRENT_STATE).
2. Scope firewall: no implementation change without
   Milestone → Backlog → Exit criterion → Gate/UAT traceability.
3. No V1 repair on the V2 critical path; classify + quarantine instead.
4. No V2 dependency on :pipeline-steps-system:compiler-plugin.

### Exceptions (require explicit human approval + new Milestone)

A. Critical security fix on V1 with no V2 equivalent.
B. INC reclassification promoting a QUARANTINED component.
C. Compatibility shim required by an in-flight UAT.
D. Backlog item with documented Exit criterion + Gate owner.
