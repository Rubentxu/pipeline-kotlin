# Policy Guardrails — Cedar where it adds real value

Status: FUTURE / PROPOSED  
Depends on: stable ResourceRef + Event Spine + Event Harness foundation

## Why Cedar

Cedar's model asks whether a `principal` may perform an `action` on a `resource` in a request `context`.
That maps well to pipeline authorization if we use policies for actual authorization/guardrail problems,
not as a generic rules language.

## Two planes that MUST stay separate

### Audit/shadow

Consumes live or historical events and produces assessments:

```text
Domain events -> policy request projection -> Cedar -> PolicyAssessment
```

It never changes execution and may run detached or post-run.

### Enforcement

Runs before a protected effect:

```text
Prepared intent -> PolicyAdmission -> Allow/Deny -> effect
```

This is deliberately on the critical path because its job is to prevent an effect. It is **not** an
EventSink interceptor and must not be smuggled into the observer path.

## Initial high-value policy resources/actions

Prioritize capabilities and security boundaries rather than Step names.

Entities/resources:

- PipelineRun
- Credential
- Environment
- Artifact
- Agent/AgentPool (later)
- Repository/Project (when controller identity exists)

Actions:

- `UseCredential`
- `UseCapability` (`NETWORK`, `PROCESS`, `FILESYSTEM_WRITE`, etc.)
- `Deploy`
- `PublishArtifact`
- `PromoteArtifact`

Avoid brittle policies like `deny core.sh`; authorize capabilities/effects instead.

## Policy examples with real value

- feature/untrusted pipeline cannot use production credentials;
- only trusted/protected source may deploy to production;
- artifact promotion to production requires provenance/test/signature attributes;
- external/untrusted runs may execute local process but not network/credential capabilities;
- temporary exceptions are explicit waivers with identity/reason/expiry, not `disablePolicy=true`.

## Shadow-first rollout

Every new policy pack begins as `AUDIT`/`SHADOW`:

```text
would-allow / would-deny + reason + ResourceRefs
```

Only after false-positive measurement and historical impact analysis can a rule become `ENFORCE`.

## Historical simulation and semantic diff

Because event histories are replayable, evaluate a new policy pack against historical runs before rollout:

```text
policy v3 -> 1000 historical requests
policy v4 -> same 1000 requests

Allow -> Deny : new restriction
Deny  -> Allow: privilege expansion (security review)
```

This is higher value than simply adding a policy engine.

## Trust hierarchy

A pipeline-local file cannot be the sole source of mandatory security policy because the pipeline author
could delete it.

```text
Platform policy       mandatory
Organization policy   mandatory
Project policy        centrally/project governed
Pipeline-local policy optional/additional restriction
```

Local/project guardrail packs should be able to add restrictions but not silently relax higher-level
policy.

## DSL

Audit only:

```kotlin
pipeline {
    observe(cedarAudit())
    stages { ... }
}
```

Enforcement, if/when implemented, must be visually and architecturally distinct, e.g.:

```kotlin
pipeline {
    guardrails {
        cedar()
    }
    stages { ... }
}
```

Mandatory platform/org enforcement is injected externally and cannot depend on the presence of this line.

## Explicitly deferred

- Cedar dependency selection/binding until POL-0 spike;
- enforcement until shadow/historical simulation demonstrates value;
- OPA/Rego dual-engine support;
- policy marketplace;
- generic policy-on-any-JSON rules;
- policy decisions embedded as pipeline DomainEvents (use separate Assessment model unless a later ADR proves otherwise).
