# LB-02 / G3-A4.1 — Descriptor Recovery Property

## Scope

A4.1 establishes `StepDescriptor.recoveryPolicy` as the single source of truth for the
recovery semantics declared by a Step. The composite metadata resolver propagates that
property through to the durable layer without per-Step branches. `core.sh` declares
`RecoveryPolicy.ExternalSubprocess` directly on its descriptor; legacy and registry
metadata for `core.sh` remain parity-equivalent during the dual phase.

## Change set (atomic)

| File | Purpose |
|---|---|
| `v2/pipeline-domain/.../StepDescriptor.kt` | Adds `recoveryPolicy: RecoveryPolicy = RecoveryPolicy.None`. |
| `v2/pipeline-application/.../RegistryStepMetadataResolver.kt` | Propagates `descriptor.recoveryPolicy` into the resolved `StepMetadata`. |
| `v2/pipeline-application/.../CoreShellStep.kt` | Declares `recoveryPolicy = RecoveryPolicy.ExternalSubprocess` on its descriptor. |
| `v2/pipeline-application/.../A4_1DescriptorRecoveryCharacterizationTest.kt` | 7 tests covering A4.1.1–5. |

## Why this slice exists

A4.0 (Sh grounding) showed that the legacy `CanonicalCoreStepMetadata["core.sh"]` row
exposes `recoveryPolicy = ExternalSubprocess`, but the registry-resolved
`StepMetadata` carried `recoveryPolicy = None` (default) — meaning the durable
recovery path was unobservable for registry-routed `core.sh` and the gating fact for
A4.10 could not hold. A4.1 closes the gap declaratively:

> Recovery is a declared property of the Step, not a `core.sh` branch in the coordinator.

The composite resolver stays Step-agnostic. `CoreShellStep` declares the property on
its descriptor. The legacy metadata row carries the same value. After A4.8 flips the
routing, the durable protocol reads `recoveryPolicy == ExternalSubprocess` from the
registry-resolved metadata — no new code path.

## Laws captured (frozen by tests)

1. **Default invariance** — `StepDescriptor(stepId, name, configRef).recoveryPolicy == RecoveryPolicy.None`.
   `core.echo` keeps `recoveryPolicy == None` because no call site specifies it.
2. **Step declaration** — `CoreShellStep.definition.contract.descriptor.recoveryPolicy == ExternalSubprocess`.
3. **Propagation** — for any registered non-core Step, `composite.resolve(key).recoveryPolicy == definition.contract.descriptor.recoveryPolicy`.
4. **Legacy parity (during dual phase)** — `CanonicalCoreStepMetadata.metadata("core.sh").recoveryPolicy == RecoveryPolicy.ExternalSubprocess`.
5. **Registry vs legacy byte-equality (A4.1.5)** — for `core.sh`, `legacy.replayPolicy == descriptor.replayPolicy`, `legacy.recoveryPolicy == descriptor.recoveryPolicy`, and `legacy.effects == descriptor.effects.toSet()`.
6. **No per-Step branching** — adding a recoverable external plugin requires only a new descriptor; the resolver needs no change.

## Evidence

### A4.1 L0 / L1 — green path tests

```
A4_1DescriptorRecoveryCharacterizationTest:        tests=7  failures=0 errors=0
RegistryStepMetadataResolverTest:                  tests=3  failures=0 errors=0
CoreShellStepTest:                                 tests=10 failures=0 errors=0
EchoStepContractSuiteTest:                         tests=17 failures=0 errors=0
TOTAL:                                             37/37   failures=0 errors=0
```

XML SHA-256 (fresh after this slice):

```
1ff87a4a2ac6a2687a1a9cfce98e69a7078e84b951b274fbeee57140b57f6534  A4_1DescriptorRecoveryCharacterizationTest.xml
096e1b7d6b34054bd37cdc09f5c256103b7365ccb186d0228035fea498f20d24  RegistryStepMetadataResolverTest.xml
fc62908f5ddf8556429c0c392b10080a3a7d2b7cf43c352b671786fa2d283b95  CoreShellStepTest.xml
fc2c12469bded2abc3f022df77104924a2867744ca3bda6aaf0ab5679968d967  EchoStepContractSuiteTest.xml
```

### A4.1 L2 — full `:pipeline-application:test`

Pre-existing failure count vs base (`f4d0ea90` G3-A3):

| Branch | Unique failures | Difference |
|---|---|---|
| base `f4d0ea90` | 38 | (reference) |
| A4.1 (this slice) | 36 | **−2** (`SB-S-008` did not run with the explicit `--tests` filter on L2; mechanically absent) |

**Pre-existing failure count did NOT increase.**

The 36 pre-existing failures are all CLI/UAT subprocess tests (`UatDsl*`, `UatEvt*`,
`UatCompat*`, `ErrorHandling*`, `CompatibilityCorpus*`, `UatLocal*`, etc.) that fork
`appBin` and assert on event-stream output. They were already failing before A4.1
(verified by `git stash + test + git stash pop` at base `f4d0ea90`). The exact root
cause is documented in `docs/v2/00-context/LB02_G3_A4_0_SH_GROUNDING.md` (UAT family
pre-existing on `53d096b2`).

## Gating facts for A4.10 (unchanged)

`StepReconcilerL1` is parameterized only by `(clock, controlDirRoot, opId)`. The
recovery branch in `coordinator.recoverRunningShell` (lines 750–848) gates on
`metadata.recoveryPolicy == ExternalSubprocess`, **NOT** on `core.sh`. With A4.1.3
landed, the recovery path is reachable through the registry metadata resolver with no
per-Step branch and no code change in the coordinator.

## Stop condition check

After A4.1, the recovery path no longer depends on `CanonicalCoreStepCommand.Sh` at
the metadata layer. The coordinator still has the `recoverRunningShell` branch, but
its gate is `metadata.recoveryPolicy == ExternalSubprocess`. The stop condition
("recovery irreducibly depends on `CanonicalCoreStepCommand.Sh`") is **not** met.

## Next

A4.2 wires the inert quarantined `ShellOperations`, `ShellOperationsCapabilityKey`,
and `ShOperationsAdapter` into the registry path and demonstrates
`SHELL_OPERATIONS_CAPABILITY` delegation. A4.10 (recovery reachable through registry
metadata) becomes mechanically achievable.
