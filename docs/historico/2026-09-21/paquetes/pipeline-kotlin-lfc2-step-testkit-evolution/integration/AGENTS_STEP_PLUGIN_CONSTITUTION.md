# AGENTS.md insertion — STEP & PLUGIN CONSTITUTION

Insertar tras la sección actual `STEP SEMANTICS`.

```markdown
## STEP & PLUGIN CONSTITUTION (MANDATORY)

A Step is NOT complete because a DSL function or handler exists. A Step is complete only when it is CERTIFIED by the common Step Contract Suite.

1. One execution path: typed façade -> canonical Invoke -> StepRegistry -> typed adapter/handler -> capabilities -> durable engine -> result/events.
2. Closed structure, open catalogue: engine exhaustively interprets structural ADTs, never the list of concrete plugin Steps.
3. No privileged core path: an external Step must not require domain/application/compiler/dispatcher/KSP semantic edits.
4. Declarative DSL constructs data only; it never performs effects or fabricates runtime values.
5. Runtime-returning operations belong to the durable scripted API.
6. Builders are pure; a config constructor must not also emit a Step.
7. Decorators must not mutate "the last emitted Step".
8. Public Step contracts use typed Inputs/Outputs and ADTs/value classes, not Any?/stringly state.
9. One StepDefinition owns StepKey, version, codecs, effects, replay, capabilities, body, failure and observability.
10. Process/workspace/output/credentials/network/clock powers are explicit typed capabilities, preferably context parameters.
11. Missing capability fails before side effects.
12. KSP generates plumbing, not semantics; no when(stepName) tables.
13. Runtime must not depend on DSL StepSpec implementation classes.
14. Bodies are structural canonical nodes, not opaque Step-specific JSON.
15. Block handlers invoke children only through BodyInvoker/BranchInvoker.
16. Parallel is composable named bodies, not permanently privileged stage-terminal syntax.
17. Every semantic runtime parameter must survive compilation; configured retry/timeout cannot become empty payload.
18. Unknown/incompatible/unsupported semantics fail closed; no no-op/comment/placeholder/fake success.
19. Expected operational failures use typed failure algebra.
20. Every Step emits typed lifecycle evidence; return value alone is insufficient observability.
21. Every externally observable semantic change adds/updates a real executable .pipeline.kts Scenario.
22. Every new DSL surface adds a negative fixture proving an invalid state is rejected.
23. A Step cannot be marked DONE/PASS/supported before applicable StepContractSuite rows pass.
24. Core Steps use the same certification mechanism as external plugins.
25. Repository retains an independently built external reference plugin as permanent architecture proof.
26. Test fidelity is explicit: pure -> in-process -> forked -> restart -> rootless sandbox -> service sandbox -> online smoke.
27. Plugin classloading/discovery claims require forked real-distribution evidence.
28. T2+ tests use deadlines, capture diagnostics, kill owned process groups/containers and verify zero owned children.
29. A disabled/quarantined mandatory UAT cannot count toward milestone closure.
30. Mechanically checkable architecture rules must have fitness tests, not only reviewer discipline.
```
