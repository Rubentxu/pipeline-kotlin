# Tasks: policy-guardrails (future)

## POL-0
- [ ] Cedar library/binding spike on JVM/Kotlin
- [ ] typed schema for PipelineRun/Credential/Environment/Artifact/Capability actions
- [ ] ResourceRef -> Cedar UID codec
- [ ] policy validation tests

## POL-1
- [ ] post-run shadow audit from historical events
- [ ] optional LIVE shadow via EventTail
- [ ] PolicyAssessment model/store
- [ ] failure isolation tests

## POL-2
- [ ] historical request corpus projector
- [ ] policy N vs N+1 semantic diff
- [ ] Allow->Deny and Deny->Allow reports

## POL-3
- [ ] real guardrail examples/UAT for credential, capability, deploy, artifact promotion

## POL-4 (future)
- [ ] pre-effect PolicyAdmission seam
- [ ] latency/fail-closed/fail-open policy per protected action
- [ ] mandatory platform/org policy injection
- [ ] enforcement UAT and security review
