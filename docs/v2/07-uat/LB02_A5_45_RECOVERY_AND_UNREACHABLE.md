# LB-02 / A5.4 + A5.5 — core.sh recovery provenance and LEGACY_UNREACHABLE

## A5.4 — recovery is a proven registry property

Proof test: `A5_CoreShLegacyUnreachableProofTest` (6 tests, committed `766d269a`),
plus the coordinator durable Sh laws migrated to the registry family in `dfc8dbbd`
and `f5ec0e52`.

### A5.4.1 metadata provenance (by divergence)

`RegistryStepMetadataResolver.composite(registry).resolve(core.sh)` follows the
registered `StepDescriptor.recoveryPolicy`, never `CanonicalCoreStepMetadata`.

Proof of provenance, not value-equality: the legacy row still declares
`ExternalSubprocess`; a custom `core.sh` registered with `RecoveryPolicy.None`
resolves to **None**. Only the registry descriptor could produce `None`; a legacy
row fallback would have returned `ExternalSubprocess`. The proof therefore
distinguishes the two authorities even though the production descriptor and the
legacy row currently carry the same value.

### A5.4.2 reconciler path is generic

Recovery reaches the existing `StepReconcilerL1` via the descriptor-declared
`RecoveryPolicy.ExternalSubprocess`. There is no `if (stepKey == "core.sh")`
recovery branch and no `RegistryShReconciler`. The coordinator consumes only
`StepMetadata` and never names a decoded Sh command; the reconciler classifies
RUNNING entries from control-dir/journal state.

### A5.4.3 running recovery behaviour (frozen)

A journaled RUNNING `core.sh` with an exit-0 result file, on the registry
composition, is reconciled to `Success`. The operation identity, control-dir
state, reconciler decision, and terminal journal state are preserved exactly.

### A5.4.4 no fresh handler/process launch on recovery

The same scenario, instrumented at the common seam, records
`freshExecutions == 0`: recovery does NOT dispatch a fresh handler or launch a
fresh process; the decision passes through the reconciler. Recovery is not
confused with a fresh rerun.

## A5.5 — LEGACY_UNREACHABLE

### A5.5.1 mechanical proof

The proof class makes the property machine-checkable:

```text
core.sh normal execution surfaces ∩ legacy Sh execution = ∅
```

- `core.sh` is not in `LEGACY_PLUGIN_IDS`.
- `StructuralFamilyResolver.classify(core.sh, production registry) == Registry`.
- The structural switch never yields `LegacyCore` for `core.sh` with a registry,
  so the legacy Sh decoder/dispatcher are unreachable on every registry-wired
  surface.
- Coordinator durable Sh laws (recovery, timeout, dir-block cwd, typed script
  failure, StepFinished failure count) pass on the registry family.
- Running recovery dispatches zero fresh handler launches.

### A5.5.2 distinguishing compatibility from execution

The only remaining `core.sh` legacy references are:

| Reference | Classification |
| --- | --- |
| `@515` in `CanonicalDurableRunCoordinatorTest` (legacy decoder SCHEMA error) | **obsolete legacy-Sh decode characterization**, removed at burn-down |
| `CanonicalShellNodeDispatcherTest`, Sh cases in `CanonicalCoreStepDecoderTest` | **obsolete legacy-family characterization**, removed at burn-down |
| `CanonicalCoreStepCommandRegistryTest` Shell subtype / `CoreLegacyStepMetadataResolverTest` row | **obsolete legacy-model description**, retired at burn-down |
| Bare coordinator echo/milestone/withCredentials tests (14 red) | unrelated pre-existing Echo/block/credential debt, NOT Sh execution |

None is a normal Sh **execution** surface that blocks `LEGACY_UNREACHABLE`.

### A5.5.3 explicit state transition

```text
core.sh:
    REGISTRY_PRIMARY
        ↓
    LEGACY_UNREACHABLE
```

Still NOT `LEGACY_REMOVED` / `CERTIFIED`.

## Gate A5.4 / A5.5 evidence

```text
registry Sh recovery            green (766d269a, dfc8dbbd)
descriptor metadata authority   proven (divergence)
ExternalSubprocess              proven (registry descriptor)
existing reconciler             proven (no relaunch, generic)
legacy Sh decoder = 0           proven (structural switch)
legacy Sh dispatcher = 0        proven (structural switch + authority counter)
coordinator core.sh knowledge = 0  (coordinator names only StepMetadata)
new A5 failures = 0             confirmed (A5 closure: proof 6/0, coordinator 24/14 baseline unchanged)
```

Baseline red did not increase: `CanonicalDurableRunCoordinatorTest` stays `24/14`.
The 14 red are the documented Echo/block/withCredentials/legacy-decode debt that
is not part of the Sh execution closure.

## Burn-down is next (S6.1 → S6.5)

Removal order, each commit green, using the compiler as the remaining-legacy
dependency detector:

```text
S6.1 remove CanonicalCoreStepCommand.Sh
→ S6.2 remove legacy Sh decoder/dispatcher cases
→ S6.3 remove legacy metadata/catalogue entry
→ S6.4 irreversible architecture fitness
→ S6.5 StepContractSuite → core.sh CERTIFIED
```
