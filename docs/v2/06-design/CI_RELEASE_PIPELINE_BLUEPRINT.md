# Design — CI and Release Pipeline Blueprint for LPR

Status: PROPOSED

## 1. Pull request lane

Target: fast enough to be required on every PR while catching architecture regressions.

```text
checkout
  ↓
JDK/toolchain setup
  ↓
compile
  ↓
domain + focused application tests
  ↓
architecture fitness
  ↓
DSL/compiler compatibility corpus
  ↓
small installDist smoke
  ↓
certification-ledger consistency
```

Do not run network-dependent SDKMAN publication or expensive 1 GiB soak per PR.

## 2. Main/nightly lane

Adds:

- full V2 test round;
- HF2 installed distribution suite;
- HF3 restart/resume suites;
- broader real-project matrix;
- 200 MiB observation performance suite;
- dependency/security/SBOM checks as chosen;
- flaky-test detection/second pass only where justified.

## 3. Release-candidate lane

Triggered by controlled tag/workflow:

```text
all required main gates
  ↓
real Gradle/Maven/Node projects
  ↓
secret canaries
  ↓
performance release smoke
  ↓
distZip twice in controlled clean workspaces
  ↓
compare digest
  ↓
SBOM + SHA-256
  ↓
GitHub Release artifact
```

The tested ZIP is uploaded; never rebuild after certification for publication.

## 4. SDKMAN publication lane

Triggered from/pinned to a published GitHub Release:

```text
read release metadata
verify checksum
publish exact ZIP URL via SDKMAN vendor integration
  ↓
clean SDKMAN CI-mode install
  ↓
version + doctor + validate + real project run
```

Publication credentials are protected environment secrets. SDKMAN external availability/onboarding failure does not mutate the GitHub artifact.

## 5. Performance lanes

### PR microbench/smoke

Short deterministic regression canaries, e.g. event burst + 20/50 MiB output.

### nightly

200 MiB + slow consumer + parallel output.

### scheduled/release soak

>=1 GiB output and long-running resource profile.

Performance comparison always uses same durable recording configuration in baseline vs renderer variant, so the test isolates observation consumer overhead instead of comparing “persist output” against “discard output”.

## 6. Required checks

Repository branch protection should require the actual workflow check names once stable. An empty `.github/workflows/build.yml` is not considered CI coverage merely because other manual workflows exist.

## 7. Failure classification

A red release gate is not bypassed by changing expected outcomes without a base-vs-head explanation. Known baseline failures live in a ledger with owner/gate; mandatory disabled/quarantined tests never count green.
