## Program EVT — Event Spine evolution & executable verification
> Extiende E1 y prepara E5/M4 + M6 sin reabrir todavía controller/Jenkins.

- **EVT-00** Grounding inventory: producers, ordering, consumers, assertions, authorities.
- **EVT-01** `ResourceRef` typed identity for Pipeline/Run/Stage/Step/Operation.
- **EVT-02** `PipelineEventEnvelope` + EventRef/causation/correlation contract.
- **EVT-03** Split EventPublisher/EventHistory/EventTail ports; preserve current adapters through compatibility layer.
- **EVT-04** Adapt existing SQLite event history behind ports; add cursor/filter/query path.
- **EVT-05** ObservationStatus (`COMPLETE/DEGRADED/INCOMPLETE`) and acceptance separation.
- **EVT-06** Universal Event Protocol Grammar.
- **EVT-07** Scenario contract ADTs + YAML/TOML codec + minimal counterexamples.
- **EVT-08** Real examples 01..10 acceptance gate (catchError/parallel/retry/timeout included).
- **EVT-09** Event Harness mutation canaries.
- **EVT-10** Event↔Journal consistency verifier (after core harness proves useful).
- **EVT-11** Replay metamorphic verification.
- **EVT-12** Detached live relay process + cursor reconnect + crash isolation.
- **EVT-13** Resource budget/lag benchmark and optional Linux cgroup proof.
- **EVT-14** CloudEvents mapping adapter/contract tests.
- **EVT-15** Transport spike: HTTP CloudEvents vs NATS JetStream; Kafka only if controller-scale requirement justifies comparison.
- **EVT-16** M4/M6 handoff mapping: E5 event protocol + Jenkins event→FlowNode requirements.

## Program POL — policy guardrails (future, evidence-gated)
> Pulls shadow/simulation learning earlier; M9 remains enforcement/hardening owner.

- **POL-00** Cedar JVM/Kotlin feasibility + schema spike.
- **POL-01** ResourceRef/Cedar entity model for Run/Credential/Environment/Artifact/Capability.
- **POL-02** Post-run Cedar shadow audit -> PolicyAssessment.
- **POL-03** Optional LIVE shadow audit through EventTail.
- **POL-04** Historical policy simulation.
- **POL-05** Policy semantic diff (Allow→Deny / Deny→Allow).
- **POL-06** Credential/capability/deploy/artifact-promotion real guardrail UATs.
- **POL-07** Trust hierarchy: platform/org/project/local packs.
- **POL-08** Expiring waiver model (only if real operational need appears).
- **POL-09** Pre-effect PolicyAdmission ENFORCE seam — future/M9; blocked until shadow evidence + security review.
