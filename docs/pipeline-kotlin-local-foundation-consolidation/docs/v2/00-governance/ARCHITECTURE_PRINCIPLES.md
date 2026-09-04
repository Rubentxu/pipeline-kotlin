# Architecture principles

1. **One authority per concept.** Pipeline model, step descriptor, effect taxonomy, replay policy, environment resolution and outcome taxonomy each have exactly one owner.
2. **DSL is syntax, not runtime.** Declarative builders construct the canonical IR; they do not perform effects.
3. **Runtime effects go through capabilities.** Step handlers cannot construct process/filesystem/credential/output adapters.
4. **Compile-time structure before runtime dynamism.** Prefer typed builders and generated metadata; dynamic lookup is a compatibility boundary, not the default design.
5. **Fail closed for credentials and security-sensitive capabilities.** Missing credentials never cause a block to run without injection.
6. **Output is a stream, not an event blob.** Large stdout/stderr does not live in the event log or heap.
7. **Durability is explicit.** Every effect has replay/idempotency semantics and a durable operation identity.
8. **Graph is a projection.** Execution never depends transactionally on the visualization graph.
9. **Local-first before distributed.** A remote future must reuse local contracts instead of dictating them prematurely.
10. **Delete after migration.** A replacement is not complete while the old execution/model path remains active.
11. **Jenkins familiarity is a contract.** Compatibility claims are tested, not marketing labels.
12. **Extensibility is typed and bounded.** Plugins extend defined extension points and capabilities, not arbitrary internals.
13. **Architecture is evolutionary, but ambiguity is not.** Decisions may change through ADRs/spikes; at any instant there is one current rule.
