# pipeline-kotlin — UAT / Acceptance for Fast Lane

## P-UAT-01 Exact identity

Given target `V`:

- ZIP filename == `pipelinek-V.zip`;
- root == `pipelinek-V/`;
- app JAR version == V;
- `Implementation-Version` == V;
- installed `pipelinek version` == V;
- manifest.version == V.

## P-UAT-02 Reproducible candidate

Two controlled builds from same SHA/toolchain produce identical ZIP SHA.

## P-UAT-03 Immutable manifest

Changing only promotion metadata cannot alter distribution manifest; promotion metadata is separate.

## P-UAT-04 Candidate handoff completeness

Descriptor contains all mandatory fields and its artifact SHA equals real ZIP SHA.

## P-UAT-05 Manual installer clean install

Stable install from empty home:

- checksum PASS;
- exact identity PASS;
- binary executable;
- no partial directory left on failure.

## P-UAT-06 Manual installer mismatch

Inject mismatched runtime version. Expected:

- non-zero;
- target install removed;
- previous active version untouched.

## P-UAT-07 Continuity

After candidate publication, SDDK permits next independent WorkItem without waiting for harness verdict.

## P-UAT-08 Historical canary

Feed an artifact shaped like `v0.43.0` (outer GA identity, embedded rc1). Local identity gate MUST reject it.

## P-UAT-09 Gate teeth

Derived from a real false green found in the 0.44 train (2026-09-30). It is
listed here rather than buried in a receipt because it is a property of every
gate in this document, not of one release.

**Law.** A test of a critical gate is not evidence unless it can demonstrate
that it fails when that specific gate is disabled.

The failure mode is defence-in-depth. A pipeline may reject an artifact for
several independent reasons, so disabling one gate can leave every test green:

```text
mutation(gate A)  ->  defence B also rejects  ->  suite GREEN
```

That proves nothing about A. It proves B exists. The gate A under test may be
completely inert and the suite would never say so.

**Required evidence, for each critical gate:**

```text
mutation(A)  -> test_A RED      (injected at the layer A owns)
restore(A)   -> test_A GREEN
```

Rules:

1. The mutation is injected **at the layer whose authority is being
   certified**. Disabling the identity decision inside the identity module is a
   valid canary; disabling it in a downstream validator is not a test of it.
2. **No other defence may be weakened to obtain the RED.** Masking a second
   gate so the first one becomes visible is a valid diagnostic technique, but
   the reported result must state which gate was temporarily disabled and that
   it was restored.
3. A mutant that survives is a finding about the **test**, not a pass. The test
   is re-aimed at the layer that owns the decision, and the mutant is re-run.
4. Mutants that are behaviour-equivalent must be recorded as such, not
   silently discarded. `?:` defaulting an unobservable value to the same value
   the caller would supply is equivalent, and proves nothing either way.

**Scope.** Mandatory for: identity, candidate admission, certification,
promotion, credential redaction, replay. Advisory elsewhere.

**Worked example (0.44 train).** Asserting only that `materialize` returned
`Refused` for a ZIP with no application JAR left the whole suite green when
the unobservable `IMPLEMENTATION_VERSION` was defaulted to the expected value,
because the handoff validator independently rejects a null
`implementationVersion`. The test was asserting defence-in-depth
incidentally. Re-aiming the assertion at the identity verdict, and re-injecting
the mutant inside `DistributionIdentityProbe`, produced the required RED.
