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
