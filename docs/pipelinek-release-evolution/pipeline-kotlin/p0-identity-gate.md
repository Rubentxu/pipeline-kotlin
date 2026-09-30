# P0.1 / P0.3 — Product identity model and cheap identity gate

Status: **IN PROGRESS** — decision model + unit proof landed; real-artifact probe
and build wiring pending.

Authority: `docs/pipelinek-release-evolution/shared/01-cross-repo-contract.md` §4, §10, §11;
`docs/pipelinek-release-evolution/pipeline-kotlin/01-responsibilities-and-spec.md` §4, §6;
`docs/pipelinek-release-evolution/pipeline-kotlin/04-uat.md` P-UAT-01, P-UAT-08.

## 1. The law being implemented

```text
ProductVersion == AssetVersion == ArchiveRootVersion
                == ImplementationVersion == RuntimeVersion == ManifestVersion
```

A candidate whose surfaces disagree is a build defect and MUST NOT be handed off.
Candidate status lives **outside** the binary identity, in the candidate descriptor.

## 2. Correction to the incident description (measured, not assumed)

The v0.43.0 incident is often described as "the build shipped GA identity outside with
rc1 inside". Measured against the real artifact on disk, that is **not what happened
here**:

```text
$ unzip -l pipeline-application/build/distributions/pipelinek-0.43.0-rc1.zip
  pipelinek-0.43.0-rc1/lib/pipeline-application-0.43.0-rc1.jar

$ unzip -p <that jar> META-INF/MANIFEST.MF
  Implementation-Version: 0.43.0-rc1
```

Every surface produced by the build agreed on `0.43.0-rc1`. The build was
**internally consistent**.

The divergence was therefore introduced **downstream**, at promotion, when the ZIP was
presented under a GA identity (`pipelinek-0.43.0.zip`). That is exactly the act the
protocol bans in cross-repo contract §10: the harness renames a candidate's identity.

**Why this matters for where the gate goes.** An upstream gate that only compared the
build's own six surfaces would have been GREEN on this artifact and would not have
prevented the incident. A gate that passes on internally-consistent-but-candidate
identity is not a gate. So the model has two obligations:

1. **Internal equality** — the six surfaces must agree (P-UAT-01).
2. **Identity shape** — a candidate may NOT be built under a candidate-suffixed
   identity at all (P0.1 / P-UAT-08). `0.44.0-rc1` is not a `ProductVersion`.
   Removing the suffix is what makes downstream renaming meaningless: there is no
   second identity to launder, so §10 has nothing to violate.

Obligation 2 is the one that actually closes the incident. Obligation 1 alone would
have been a green light on the very bytes that caused it.

## 3. What is landed

`DistributionIdentity.kt` (pure, no I/O):

- `ProductVersion` — a value class over final SemVer. A candidate suffix does not
  parse. `parseOrThrow` explains that candidate state belongs to the descriptor.
- `IdentitySurface` — the six surfaces, as a closed enum.
- `DistributionIdentityVerdict` — a sealed result: `Consistent` / `Divergent` /
  `Incomplete`. Divergent and Incomplete are deliberately different cases so that
  "reported nothing" can never read as "reported correctly".
- `evaluateDistributionIdentity(facts)` — pure decision, no clock/fs/process.
- `render()` — the operator diagnostic, kept out of the decision.

`DistributionIdentityVerdictTest` — 13 tests, all green:

- P-UAT-01 happy path across all six surfaces.
- P-UAT-08 canary: outer `0.43.0` with inner `0.43.0-rc1` is rejected, and the
  diagnostic names **both** lying surfaces.
- Divergence reports every offender, not just the first.
- Incomplete is distinct from Divergent; a missing manifest is never a pass.
- A build where every surface agrees on `0.44.0-rc1` is still **Divergent**, because
  candidate state must not live inside the binary identity.

## 4. What is not done yet

- The effectful probe that reads a real ZIP (asset, archive root, JAR manifest,
  installed `pipelinek version`) and feeds the pure decision.
- Build wiring so the gate runs at candidate materialization.
- The 0.44 version decision: `version = "0.44.0-rc1"` in `v2/build.gradle.kts` is
  currently a candidate-suffixed identity, which this model rejects. Cutting to
  `0.44.0` is an operator decision because it changes the published identity.

## 5. Pre-existing defect found while reading (not fixed here)

`scripts/install-pipelinek.sh` validates versions with:

```sh
readonly VERSION_REGEX='^[0-9]+\.[0-9]+\.[0-9]+$'
```

It **rejects** any candidate-suffixed version. So the installer cannot install a
`0.44.0-rc1` artifact at all. Under the new model this becomes correct by
construction (candidates are installed by final `ProductVersion`), which is one more
reason the P0.1 cut is the right order of work rather than a cosmetic change.

The installer also depends on a `${asset}.sha256` sidecar rather than a
`SHA256SUMS` file, which P1.1 replaces.
