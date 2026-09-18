# UAT — Distribution and SDKMAN

Status: PROPOSED

## DIST-001 — installDist parity

The real `installDist` launcher executes all core CLI/UAT paths; tests do not use a privileged in-JVM execution algorithm.

## DIST-002 — reproducible ZIP

Build `distZip` twice from same source/toolchain in controlled clean workspaces. SHA-256 must match. If current Gradle/tooling has an unavoidable nondeterministic input, classify it and fix before LPR release.

## DIST-003 — GitHub Release smoke

Download uploaded ZIP from release asset, verify checksum, unpack, run `pipelinek version/doctor/validate/run` on a real project.

## DIST-004 — version provenance

`pipelinek version --format json` reports release version and revision consistent with tag/artifact metadata. No `0.1.0-SNAPSHOT` in a stable release artifact.

## DIST-005 — SDKMAN publish

After vendor onboarding, publish the exact GitHub Release ZIP. Store publish response/evidence; no separate build.

## DIST-006 — clean SDKMAN install

In clean CI mode:

```text
install SDKMAN noninteractively
sdk install pipelinek <version>
pipelinek version
pipelinek doctor
pipelinek validate
pipelinek run
```

Verify installed behavior and release version.

## DIST-007 — checksum authority

Checksum used/published by SDKMAN automation equals GitHub asset checksum generated in release workflow.

## DIST-008 — previous version install/upgrade

Install previous supported version, run compatibility fixture, upgrade to current, rerun. Record migration behavior.
