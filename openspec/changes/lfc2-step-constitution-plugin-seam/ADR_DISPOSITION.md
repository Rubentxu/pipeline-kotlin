# ADR disposition — AMEND vs NEW (canonical numbering)

Canonical ADR series tops out at **ADR-0069** (Step Semantics Policy, accepted). Next available IDs
are **ADR-0070+**. Disposition follows the rule: extend an existing ADR when the decision is already
partly taken; create a NEW canonical ADR only for a genuinely new architectural decision. Avoid
duplicating decisions already covered.

## AMEND (extend existing canonical ADR)

| Canonical ADR | Amendment | Reason |
|---|---|---|
| ADR-0069 (Step Semantics Policy, accepted) | Cross-reference the reconstitution ADRs; restate the fail-closed registry mechanism as **registry-driven admission** (open StepRegistry) while preserving the invariant (unregistered family rejected before effects on every run path). | 0069 fixes the invariant via a closed `ALL_PLUGIN_IDS` registry; opening the registry changes the mechanism, not the invariant. |
| ADR-0054 (block-step nesting; status: **proposed**) | Superseded/refined by ADR-0073 for the child re-entry model (BodyInvoker/BranchInvoker instead of block-specific dispatch). Flattening taxonomy may remain. | ADR-0054 is not accepted; its re-entry model conflicts with criterion 15. |
| ADR-0048 (sandbox-profile-local), ADR-0053 (smoke-e2e-sandbox) | Tag as HF levels (ADR-0072) so there is one harness-fidelity taxonomy. | Avoid a second harness taxonomy. |
| `openspec/specs/pipeline-test-rule/spec.md` | Tag the capability as HF1 (In-Process). | pipeline-test-rule is the in-process harness; naming it HF1 keeps one owner. |
| root `AGENTS.md` | Append Step-constitution + fitness rules (operative translation only). | ADR/spec remain architectural authority. |

## NEW canonical ADR candidates (proposed; created only when each decision is accepted)

| Candidate ID | Working title | Source (pack) | Architectural substance |
|---|---|---|---|
| **ADR-0070** | Closed execution structure, open Step registry; core and external plugins share one path | ADR-LFC-018 + 019 | `ExecutionNode` sealed structure (`Invoke`/bodies) + open `StepRegistry` resolving `StepKey → StepDefinition → StepHandler`; core = standard bundled plugin set, same path as external; fail-closed on unknown/incompatible; no central per-Step switch, no KSP `when(stepName)`, no `Map<String,Any?>` public contract. |
| **ADR-0071** | Executable scenarios are first-class product specifications | ADR-LFC-020 | The shown `.pipeline.kts` IS the executed file; `examples/`, `v2/compatibility/`, `test-fixtures/`, `plugin-fixtures/` share ScenarioRunner; invalid programs are first-class fixtures; positive + negative corpus. |
| **ADR-0072** | Layered Test Harness fidelity (HF0..HF6) | ADR-LFC-021 | HF ladder: HF0 Pure Contract, HF1 In-Process, HF2 Forked Real Distribution, HF3 Restart/Resume, HF4 Rootless Sandbox, HF5 Service Sandbox, HF6 Online Smoke. Use the minimum faithful level; deterministic substitution at HF0/HF1; sandbox mapping to ADR-0048/0053; isolation/teardown hygiene. |
| **ADR-0073** | Block Steps re-enter the engine through BodyInvoker / BranchInvoker | ADR-LFC-022 | A block Step never runs child handlers directly; engine injects `BodyInvoker.invoke(body, patch)` / `BranchInvoker.invokeAll(branches, policy, patch)`; each child re-enters Invoke → Registry → capability admission → handler → journal/events. Steers E-EM-11 away from one-off `dispatchRetryBlock`/`dispatchTimeoutBlock`. `parallel` = composable Named Bodies, not permanent stage-terminal. |
| **ADR-0074** | A Step is done only when CERTIFIED | ADR-LFC-023 | Formal states DESIGNED / IMPLEMENTED_UNCERTIFIED / CERTIFIED / QUARANTINED / RETIRED; `DONE/PASS` never used for an uncertified Step; common certification dimensions (contract, codecs, positive/negative DSL, IR, registry, capabilities, handler, failure, observability, cancellation, replay, bodies, security, real distribution, Jenkins compatibility, executable scenario) for core and plugins alike. |

## Numbering / conflict rule

- IDs `0070..0074` are the next available in the canonical series. If the apply phase assigns them
  to other ADRs first, re-map on acceptance (never reuse an in-use ID).
- No canonical LFC-2 `T0..T4` item is renamed by this change (HF applies only to harness fidelity).
