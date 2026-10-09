# RP-5 Production Ready Gate

This document defines the executable gate derived from the audit.

## Gate identity

A PASS applies to exactly:

`{repo, source SHA, build inputs, JDK/OS, artifact digest, supported profile, UAT set}`

## Mandatory conditions

### G1 — Repository truth

- generated current-state view matches candidate SHA;
- no blocking PR ambiguity;
- no stale debt item is being used as a blocker.

### G2 — Functional

- full suite on exact candidate;
- 0 mandatory failures;
- HAR-007 PASS;
- supported DSL and compatibility corpus PASS.

### G3 — Durable semantics

- fresh execution PASS;
- replay PASS;
- divergence fail-closed;
- kill/resume PASS where contract requires;
- no memoized side-effect duplication.

### G4 — Security

- SAST current;
- gitleaks current;
- dependency/SCA current;
- SBOM current;
- workspace/path containment tests PASS.

### G5 — Quality

- candidate Kover generated;
- critical package regression reviewed;
- targeted mutation survivors classified.

### G6 — Performance

- previously accepted throughput SLOs PASS;
- memory SLO defined and PASS;
- performance measurement is not coverage-instrumented.

### G7 — Distribution

- clean build;
- reproducible distZip;
- exact artifact SHA256;
- clean install;
- success and intentional-failure execution.

### G8 — Dogfooding

- at least 2 external repositories;
- distinct project characteristics if available;
- one intentional failure;
- replay/recovery observable.

### G9 — External harness

- verdict exists;
- verdict binds exact artifact digest and candidate SHA;
- mandatory scenarios PASS.

### G10 — Admission publication

- immutable receipt written;
- mandatory GitHub admission check published;
- branch/release protection observes that check.

**Status: `BLOCKED_EXTERNAL` (ADR-0105, 2026-10-08).** The first bullet is
local and this repository owns it. The second and third are published by
`pipelinek-release-harness` against a candidate, and neither this repository nor
a local agent can satisfy them: `.github/workflows/` does not exist, and
publishing a check run requires write credentials over this repository.

The condition is evaluated against the harness's verdict, never assumed:

```text
no admission check for this candidate SHA  ->  G10 = NOT_RUN
                                             ->  RP-5 PRODUCT_GATE_STOP
```

Absence is `NOT_RUN`, which is a `STOP`. It is not `PASS`, and there is no
third value. A candidate without an externally published admission check cannot
appear as `PRODUCT_GATE_GO`.

## Final decision

Only two valid outcomes:

- `RP-5 PRODUCT_GATE_GO`
- `RP-5 PRODUCT_GATE_STOP`

No `GREEN_WITH_ASSUMPTIONS` state is permitted for mandatory conditions.

Known limitations may coexist with GO only if they are explicitly outside the advertised supported profile and backed by a normative decision.
