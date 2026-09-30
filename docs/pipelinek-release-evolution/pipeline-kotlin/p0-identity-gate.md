# P0.1 / P0.3 — Product identity model and identity probe

Status: **P0.1 + P0.3b landed.** Pure decision model + real-artifact probe, both proven
by tests. Build wiring (running the gate at candidate materialization) still pending.

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

## 4. P0.3b — the effectful probe (landed)

`DistributionIdentityProbe.kt` reads a real distribution ZIP and returns the three
ZIP-derived surfaces. It **never decides**; it hands facts to the pure function. The
split is the point: decision pure, interpretation at the boundary.

- `assetVersion` — from the filename `pipelinek-<V>.zip`.
- `archiveRootVersion` — the single top-level directory inside the archive. Zero or
  several roots is malformed for our shape, so it returns null rather than a guess.
- `jarImplementationVersion` — `Implementation-Version` read through the JAR's own
  manifest API, not by assuming the manifest is the first entry (ZIP order is not a
  contract).

Every surface may come back **null**, and null means *unobserved*, never *assumed
correct*. A default here is precisely how a missing manifest turns into a green gate.

`DistributionIdentityProbeTest` builds genuine ZIP/JAR bytes in a temp dir — no mocks —
and covers: each surface read, a non-distribution filename, a missing file, a ZIP with
no application JAR, a ZIP with two roots, and the outer-GA/inner-rc laundering canary.

### Verified against the real artifacts in this checkout

`distributions/` holds `pipelinek-0.42.0-rc1.zip`, `0.43.0-rc1.zip`, `0.44.0-rc1.zip`.
The probe was run against them (these tests **executed**, not skipped):

- **`pipelinek-0.43.0-rc1.zip` is internally consistent** at `0.43.0-rc1` across
  filename, archive root, and JAR manifest. This confirms §2: the divergence was
  introduced downstream at promotion, not by the build.
- Every checked-out distribution ZIP has internally agreeing ZIP surfaces.

### A verdict-precedence finding worth recording

The first end-to-end test asserted `Divergent` and got `Incomplete`, because
`RUNTIME_VERSION` and `MANIFEST_VERSION` are not ZIP-derived and no distribution
manifest is generated yet. **The model was right and the test was wrong.** You cannot
compare a surface that does not exist, and for a build with no manifest at all, naming
what is missing is the actionable message.

The precedence is now pinned by its own test, `a missing surface outranks a conflict`.
RED was proven by mutation: inverting the precedence to let conflicts win made exactly
that one test fail (`expected Incomplete to take precedence; got identity DIVERGENT`) and
no other. The mutation was reverted and the file restored byte-identical to `HEAD`.

One honest consequence recorded rather than papered over: because only three of six
surfaces exist in the artifact today, the *honest* end-to-end verdict for a real ZIP
right now is `Incomplete` — a rejection, but for the reason "no manifest exists yet",
not "the bytes disagree". The laundering canary test therefore supplies the two
non-ZIP surfaces explicitly, to isolate ZIP-surface laundering and nothing else.

## 5. What is not done yet

- Build wiring so the gate runs at candidate materialization. Until this exists the
  probe is exercised only by tests; nothing enforces it during a real build.
- The `distribution-manifest.json` that would supply `MANIFEST_VERSION` (P1.x), which
  is also what would move the real end-to-end verdict from `Incomplete` to a genuine
  six-surface comparison.
- The 0.44 version decision: `version = "0.44.0-rc1"` in `v2/build.gradle.kts` is
  currently a candidate-suffixed identity, which this model rejects. Cutting to
  `0.44.0` is an operator decision because it changes the published identity, and it
  has NOT been made. Nothing has been pushed, tagged, or published.

## 6. Evidence

```text
identity tests      26 tests, 0 skipped, 0 failures, 0 errors
  (13 pure model in DistributionIdentityVerdictTest
   + 13 probe in DistributionIdentityProbeTest)
architecture module 83 XML classes, 370 tests, 0 failures, 0 errors
mutation RED        precedence test only, correct reason, reverted
```

## 7. Pre-existing defect found while reading (not fixed here)

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
