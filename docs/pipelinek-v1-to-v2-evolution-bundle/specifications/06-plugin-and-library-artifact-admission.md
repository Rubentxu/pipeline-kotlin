# Specification — Artifact resolution and pre-classloading admission

## 1. Goal

Move artifact identity and admission ahead of executable plugin class loading while preserving the current Step/plugin runtime model.

## 2. Pipeline

1. Resolve references to the complete byte graph and source facts.
2. Read static metadata and compute actual content digests without contributor initialization.
3. Apply the current artifact admission policy.
4. Reject with typed diagnostics and no contributor initialization, or freeze admitted bytes.
5. Load admitted plugin contributors through the canonical SDK composition and cross-check declarations.
6. Freeze registries and derive compiler/evaluation views from the one dependency plan.

## 3. Static plugin manifest

Reuse the accepted S6 unified plugin manifest and its publishing contract. This spec defines static transport/admission for that authority, not another executable plugin registry. Every V2 distributable plugin SHALL eventually carry:

`META-INF/pipeline/plugin-manifest.json`

It includes at minimum:

- schema version;
- logical plugin identity;
- declared version;
- publisher;
- families;
- delivery classification;
- declared Steps, directives, events, reactors and capabilities from the canonical S6 families;
- supported PipelineK API range.

The resolver calculates the actual artifact digest. The static manifest does not self-certify its bytes.

## 4. Admission result

```kotlin
sealed interface ArtifactAdmissionDecision {
    data class Admitted(val evidence: AdmissionEvidence) : ArtifactAdmissionDecision
    data class Rejected(val reasons: List<AdmissionRejection>) : ArtifactAdmissionDecision
}
```

Initial rejection categories:

- unreadable/malformed artifact;
- missing required manifest;
- API range incompatible;
- digest mismatch against explicit approved digest when such policy is configured;
- duplicate/conflicting artifact identity;
- ambiguous library/plugin kind;
- invalid manifest invariants.

## 5. Trust evidence

Keep `TrustMetadata.Unverified` until a verifier exists.

Future states require actual evidence objects such as:

- verified digest against an operator-owned allowlist;
- verified signature chain;
- verified provenance/attestation.

No `OFFICIAL_PLUGIN => trusted` shortcut.

## 6. Runtime cross-check

After admitted contributor loading, runtime-contributed metadata for all declared S6 families SHALL be cross-checked against the canonical static manifest. Adapt the current Step-only `PluginManifest`/registration during migration; do not let it coexist as a second authority. Divergence fails closed before runtime use.

This prevents the static description and executable contributor from becoming independent truths.

## 7. Side-effect guard

A dedicated AAT fixture plugin SHALL contain a static/class initializer canary. When admission rejects the artifact, the canary must prove the contributor class was never initialized.

## 8. Resolver boundaries

Resolvers know transport/location. Admission knows policy/invariants. Registries know executable definitions. None owns all three responsibilities.

## 9. Existing `BundledPluginClasspathPlan`

Do not delete it in the first slice. Characterize it and incrementally replace its path/string identity assumptions with resolved artifact identities. Remove only after parity is proven.

## 10. Exit criteria

- static manifest read without class initialization;
- tampered or conflicting artifacts fail before ServiceLoader;
- runtime contributor is cross-checked against admitted manifest;
- official/external delivery values do not alter admission unless an explicit policy says so;
- event/audit projections retain immutable release digest.

## 11. Immutable snapshot and hit admission

GR-005/012: hash and admit a stable snapshot, then compile/load those exact bytes. A user JAR replaced after hashing is not reopened as trusted input. Test the race with a deterministic barrier. An existing compilation entry never bypasses current digest/trust/API policy or runtime contributor cross-check. Separate cache corruption (evict/recompile) from artifact rejection (stop before executable loading).

M3 first inventories the accepted S6 manifest/freeze implementation. Any missing canonical seam is work in that owner, not a duplicate implementation here. Kotlin/script class-loading scope is not a security sandbox.
