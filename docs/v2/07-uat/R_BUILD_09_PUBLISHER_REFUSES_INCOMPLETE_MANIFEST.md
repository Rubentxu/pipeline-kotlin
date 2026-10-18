# R-BUILD-09 — the publisher's post-publish check was circular

**Defect class:** a check that could not fail, because it verified the set that had already
survived the very filter it was meant to police. Same family as R-BUILD-07/08, one step further
out: those closed the producer and the materializer; this one is in the code that publishes.
**Commits:** `1f8e2400` (the refusal), `0f460ceb` (the harness that never reached it).
**Fidelity:** the product change is a shell guard, verified STRUCTURALLY. The harness fix is
BEHAVIOURAL and mutation-tested. Both labels are stated rather than blended; §7 says why the
product change is not behavioural here.

---

## 1. The defect

`stage_manifest_asset` treated a null manifest field as "nothing to do":

```bash
    fname=$(read_manifest_field "$field" "")
    if [ -z "$fname" ]; then
        echo "release-pipelinek: manifest field '$field' is null; skipping."
        return 0
    fi
```

On its own that is defensible for an optional asset. It was not defensible here, because of
what happens downstream:

```bash
STAGED_PATHS=()                       # populated only by non-null fields
...
stage_manifest_asset "sdk.name" ...    # skipped when sdk.name is null
...
gh release create "${PIPELINEK_RELEASE_TAG}" ... "${STAGED_PATHS[@]}"
...
for staged in "${STAGED_PATHS[@]}"; do
    required=$(basename "$staged")
    if ! printf '%s\n' "$PUBLISHED" | grep -Fxq "$required"; then
        echo "release-pipelinek: published release is missing required asset '${required}'."
```

The verification iterates **what was staged**. A field that was null was never staged, so it was
never required. A manifest missing the SDK produced a release missing the SDK, and a verification
loop with no reason to notice — and the failure message it would have printed cites AGENTS.md §8,
which fixes the asset set at five. **The check cited the authority it was not enforcing.**

Worth stating why this survived review of R-BUILD-07: the tolerance was *unreachable in practice*.
`b078bb63` made the materializer refuse to emit a manifest whose `sha256sums` is null next to a
non-empty asset set, so the current producer never produces an incomplete manifest. The publisher's
skip was therefore dead code by the time it was read — which is precisely why its removal changed
nothing observable and nobody had a reason to look.

## 2. The fix

A null field now refuses, before the tag is created, so no release exists to unpick:

```bash
    if [ -z "$fname" ]; then
        echo "release-pipelinek: manifest field '$field' is null; §8 fixes the asset set" >&2
        echo "    exactly, so an incomplete manifest cannot be published. Refusing before the" >&2
        echo "    tag is created, so no release exists to unpick." >&2
        rm -rf "$EVIDENCE_DIR"
        exit 1
    fi
```

`stage_manifest_asset` is called for exactly five fields — `asset.name`, `sbom.name`, `sdk.name`,
`bom.name`, `sha256sums.name` — all mandatory under §8. There is no optional field left to skip,
so the branch had no legitimate caller. Nothing else changed: the manifest stays the authority for
*names*, and the upload list stays `${STAGED_PATHS[@]}`. The change adds a completeness invariant;
it does not reintroduce a hardcoded asset list that `test_release_publisher_assets.sh` tests 3 and
7 explicitly forbid.

## 3. The harness was not testing any of this

Writing the guard surfaced something bigger. `release-pipelinek.sh:51`:

```bash
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
```

`scripts/test_release_publisher.sh` built a sandbox git repo per test and then ran the script
**from outside it**. The script resolved `REPO_ROOT` from its own path and immediately left the
sandbox, so every `git` command acted on the real repository. Measured, not inferred:

- All three tests refused with `working tree is dirty` — the *real* tree's state. Test 1 passed for
  an accidental reason; tests 2 and 3 were **vacuous**, and they passed because refusing with a
  non-zero exit was the only thing asserted.
- The test named *"tag already exists on origin"* was one clean working tree away from
  `git tag -a` + `git push origin` against this repository. That is a live hazard, not a theory.

Two independent defects, both in the harness:

1. **The sandbox was decorative.** Fixed by copying the script into the sandbox so `REPO_ROOT`
   resolves there, and by asserting that the refusal names the test's *own* pre-condition.
   Refusing is necessary but not sufficient — any earlier refusal satisfied the old check.
2. **`setup_remote_tag_exists` deleted the remote it had just created.** It built a bare repo in
   its own `mktemp -d` and `rm -rf`'d it before returning, so `git ls-remote --tags origin` found
   nothing and that pre-condition never fired. Masked until the harness became honest about which
   refusal it was looking at; it then surfaced as `exit=127` at the gradle call.

## 4. Verification

RED for the product change, from the XML-free shell harness reading its own counters:

```text
FAIL  a null manifest field is SKIPPED, so an incomplete manifest publishes a
      release whose missing asset is also missing from the verification loop
FAIL  the null-field branch has no fail-closed exit of its own:
        if [ -z "$fname" ]; then
            echo "release-pipelinek: manifest field '$field' is null; skipping."
            return 0
        fi
summary: pass=11 fail=2
```

That second row is scoped to the null branch by `awk`, not to the function. The first version of
it asserted only that the function contains `exit 1`, and it **passed** — on the unrelated
"file does not exist" exit three lines below. A row green for a reason other than the claim under
test is worse than a red one, because it certifies something nobody checked.

GREEN:

```text
summary: pass=13 fail=0      # test_release_publisher_assets.sh
summary: pass=3  fail=0      # test_release_publisher.sh
```

Each of the three now refuses on its own pre-condition, which is what the harness now asserts:

```text
PASS  dirty working tree is refused (refused with exit=1 on its own pre-condition: working tree is dirty)
PASS  tag already exists locally   (refused with exit=1 on its own pre-condition: already exists locally)
PASS  tag already exists on origin (refused with exit=1 on its own pre-condition: already exists on origin)
```

Non-contamination was checked, not assumed — the harness now really does run `git push`, so this
is the assertion that matters:

```text
tag v0.56.0-rc999 local  -> ausente
tag v0.56.0-rc999 origin -> ausente
HEAD == origin/main      -> 0f460ceb
```

`bash -n` clean on both scripts.

## 5. Mutation (HARNESS FIDELITY §5)

Claim: *each negative test refuses on its own pre-condition.* Pointing the remote-tag check at a
tag that does not exist must kill row 3 and leave rows 1 and 2 green — 1:1 attribution, not a
blanket red.

```text
mutated: git ls-remote --tags origin "refs/tags/MUTANT-never-exists"
  PASS  dirty working tree is refused
  PASS  tag already exists locally
  FAIL  tag already exists on origin  (exit=127, stopped earlier, at the gradle call)
summary: pass=2 fail=1
restored, sha256 identical before and after: dce329a70f14501cc5650f7a861296bf459a7b0072d390c2c4c4c3216b7cee6f
```

Rows 1 and 2 staying green is the point: it shows row 3 fails because *its* pre-condition stopped
firing, not because the harness happened to be red.

## 6. Residual risk

- **No real publish has ever been run against the hardened script**, before or after this change.
  The structural guard proves the branch exists and refuses; it does not prove a release succeeds.
  This is the `NOT_RUN` the roadmap's L294 box still carries, and it is a UAT, not a code change.
- **`scripts/test_release_publisher_assets.sh` is static throughout.** It asserts properties of
  text, and Test 8 says so in its own header. It cannot be promoted to behavioural without a full
  `releaseGate` per run, which is the harness-cost-over-value trade this loop is meant to avoid.
- **`pk-rc2/scripts/release-pipelinek.sh` is a second copy of this script** and was not touched.
  Whether it is live was not established. It is recorded, not fixed: fixing a second copy of a
  publisher is a distribution decision, not a bug fix.
- **`scripts/publish_rc_release.py` in the harness is the path AGENTS.md §8 names**, not this
  script. Its tolerant/strict reading was not examined and is not claimed here.

## 7. Why the product change is not behavioural

HARNESS FIDELITY §1 requires a behavioural harness to cross the productive authority. The staging
block sits after `releaseGate` (`distZip + cyclonedxBom + candidateAdmission + test`), so driving it
in-process would cost a full distribution build per run — the exact "more effort on the harness than
on the product" shape. The guard is therefore declared STRUCTURAL in its own header, and the
behavioural claim is scoped to what was actually proved: the branch refuses, and it refuses before
the tag. It is **not** claimed that a real publish now succeeds.

## 8. Reference implementation consulted

- The two existing fail-closed checks in the same script (dirty tree, existing tag), which use the
  same `rm -rf "$EVIDENCE_DIR"; exit 1` shape. The new branch copies theirs rather than inventing a
  third error convention.
- `MaterializationResult.Refused` (R-BUILD-08) for the same shape one layer up: refuse before
  anything is written, and name what was missing.

## 9. Verification report (delivery-first-testing)

```text
Changed:
- scripts/release-pipelinek.sh                    — null field refuses instead of skipping
- scripts/test_release_publisher_assets.sh        — Test 8, scoped to the null branch
- scripts/test_release_publisher.sh              — script runs inside the sandbox; refusal must
                                                  name the test's own pre-condition; setup no
                                                  longer deletes the remote it created

Affected SUT:
- the publish boundary: what asset set a release may carry
- the negative harness for that boundary

Verification executed:
- RED, assets harness   — pass=11 fail=2, null branch printed `return 0`
- GREEN, both harnesses — 13/13 and 3/3, each refusal naming its own pre-condition
- non-contamination     — no test tag locally or on origin, HEAD == origin/main
- mutation              — 1:1, kills row 3 only, restored with identical sha256
- bash -n on both scripts

Evidence reused:
- none. Both the product script and the harness changed.

Verification deliberately not executed:
- a real publish — external authority, and the roadmap's open UAT, not a code change.
- `scripts/publish_rc_release.py` — a different publisher, in another repository.

Unknown impact:
- whether the hardened script still publishes successfully (NOT_RUN).
- whether `pk-rc2/scripts/release-pipelinek.sh` is live.

Result: PASS
Full verification required now: NO — bash-level change outside any Gradle module.
```