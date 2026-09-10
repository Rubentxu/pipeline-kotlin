## EVT — Event Spine evolution + executable verification

> Intercalated after the current local/LFC-2 execution-model consolidation and before resuming E5-02..E5-10.
> EVT does not reopen M4 and does not implement Jenkins. It evolves E1 into a transport-agnostic,
> locally useful event foundation and proves the live semantics that M4/M6 will consume.

### Objective

Make structured execution history a first-class local feature and use it to verify real examples, while
proving that live detached consumers can later drive controller/Jenkins UI without becoming an execution dependency.

### Slices

- EVT-0 grounding/contract freeze.
- EVT-1 ResourceRef + PipelineEventEnvelope.
- EVT-2 Event history ports + existing local durable adapter behind them.
- EVT-3 POST_RUN Event Harness + real examples.
- EVT-4 detached live relay + crash/reconnect/resource-isolation proof.
- EVT-5 CloudEvents mapping + measured transport spike.
- EVT-6 handoff to M4/M6.

### Exit/UAT

- real installDist run leaves queryable historical events;
- same history verifies real examples post-run;
- StageStarted is observable live before RunFinished;
- subscriber process crash never cancels/fails the run;
- reconnect from cursor catches up without semantic lifecycle duplication;
- no remote transport/observer knowledge in canonical coordinator;
- stdout/stderr remain outside DomainEvent history;
- Rule-16 delta clean.

## POL — Policy guardrails learning path

> POL-0..POL-3 may run after EVT foundation to learn safely in audit/shadow mode. M9 keeps ownership of
> production enforcement/hardening.

### Objective

Use Cedar only where it answers real authorization questions: credentials, privileged capabilities,
deployment and artifact promotion. Measure impact against historical runs before any rule can block effects.

### Exit before enforcement is even considered

- Cedar schema validated against typed pipeline entities;
- shadow evaluation cannot change PipelineOutcome;
- historical policy simulation/diff works;
- real guardrail scenarios prove useful findings with acceptable false positives;
- platform/org trust hierarchy cannot be disabled from `.pipeline.kts`.
