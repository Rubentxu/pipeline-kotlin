# PipelineK — Upgrading and rollback

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Latest published release**: `0.47.0`, confirmed 2026-10-06 (`releases/latest` → `releases/tag/v0.47.0`).

> **Documentation divergence.** This page previously carried the header *"Release verified against:
> pipelinek 0.39.0"*. That header is withdrawn: the upgrade path described here was read from the
> development branch, not from a published binary. `0.47.0` is the newest release, but the newest
> release *with an executed receipt* is still `0.39.0`.
> Recorded 2026-10-06. See `docs/user/README.md` → "Known divergences".

> **Authority.** This repository has **no remote CI since 2026-09-30**: `.github/workflows/` does not
> exist. Commit `754ddda0` removed the CI workflows. **No claim on this page rests on a green pipeline, and nothing here says the
> product is production ready.** Upgrade verification is local and manual — that is the whole point of
> this page.

## By the end of this page you can

- [ ] Install a new version **next to** the old one, so nothing breaks while you check.
- [ ] Switch versions with one command, and switch back the same way.
- [ ] Understand why a certification receipt from an older commit does not certify your new version.
- [ ] Run a local verification checklist that replaces the remote CI this project does not have.

## Words you will meet

| Word | Everyday meaning | Here |
|---|---|---|
| Rollback | Going back to the previous setting | Re-pointing the active version |
| Receipt | The signed note saying "we checked *this exact file*" | Bound to one commit SHA; never inherited |
| Certification | A promise that a build passed some checks | A receipt for one SHA proves nothing about the next SHA |

## What upgrading does and does not give you

| | |
|---|---|
| ✅ You get | More than one version on disk, a one-command switch, and a one-command return |
| ✅ You get | Digests you can check yourself, and a local checklist you can run |
| ❌ You do not get | A green CI signal — there is no remote CI to signal with |
| ❌ You do not get | Inherited certification — a receipt covers its own commit only |
| ❌ You do not get | A guarantee that an old `--db` journal still opens in a new version |

## The channels, honestly

| Channel | Status | Use it? |
|---|---|---|
| `scripts/install-pipelinek.sh` | **Available** | Yes. This is the recommended path |
| GitHub Releases ZIP | **Available** | Yes, but you manage the layout yourself |
| Homebrew (`brew install`) | **Not available** | No tap exists for PipelineK |

## Upgrade with the multi-version installer

**Analogy**: a good technician never throws away the old wrench before the new one has proved
itself. Keep both, test the new one, and only then let go.

### 1. See what you have now

```bash
scripts/install-pipelinek.sh list
```

The active version is marked `*` (`scripts/install-pipelinek.sh:406`).

### 2. Install the new version beside the old one

```bash
scripts/install-pipelinek.sh install <new-version>
```

This does **not** change what is active. It downloads, verifies the digest against `SHA256SUMS`,
extracts into a temporary directory, checks that the binary reports exactly `<new-version>`, and
only then creates `versions/<new-version>` (`scripts/install-pipelinek.sh:225-335`).

### 3. Try it before you commit to it

```bash
scripts/install-pipelinek.sh use <new-version>
pipelinek version
pipelinek doctor
```

`pipelinek version` must print exactly the version you asked for. The installer applies the same
rule at install time and refuses a mismatch (`scripts/install-pipelinek.sh:310-335`).

### 4. If it is good, keep it

Nothing to do — `use` is the switch.

### Roll back

```bash
scripts/install-pipelinek.sh list
scripts/install-pipelinek.sh use <previous-version>   # switch back
pipelinek version
```

Then, only once you are sure you are not going back:

```bash
scripts/install-pipelinek.sh uninstall <new-version>
```

`uninstall` refuses to remove the **active** version
(`scripts/install-pipelinek.sh:430-433`): switch first, then remove.

## Upgrading with the ZIP

If you installed manually, the same shape applies: install alongside, then switch.

```bash
VERSION=<new-version>
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
curl -fsSL -o "pipelinek-${VERSION}.zip" "${URL}"

# Verify the digest from the release's SHA256SUMS BEFORE unpacking.
# For 0.47.0 it is 2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c
shasum -a 256 "pipelinek-${VERSION}.zip"    # macOS
sha256sum "pipelinek-${VERSION}.zip"        # Linux

ROOT="$HOME/.local/share/pipelinek"
unzip -q "pipelinek-${VERSION}.zip" -d "${ROOT}/versions"
export PATH="${ROOT}/versions/pipelinek-${VERSION}/bin:${PATH}"
pipelinek version
```

Roll back by exporting the `PATH` line of the previous version directory.

| Release | ZIP SHA-256 | Binary SHA-256 | Certified commit | Digest provenance |
|---|---|---|---|---|
| `0.47.0` | `2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c` | **NO VERIFICADO** | **NO VERIFICADO** | Release `SHA256SUMS` |
| `0.39.0` | `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` | `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee` | `951b3cb5695ecc46c877776e330266e4bd44aa9e` | Executed receipt |

## Why an old receipt does not certify your new version

A receipt is evidence **about one commit**. It records what was checked, on which bytes, under which
conditions. It is not a property that travels forward.

| Commit | What exists | What a receipt for it says |
|---|---|---|
| `951b3cb5695ecc46c877776e330266e4bd44aa9e` | The `0.39.0` release — published, and the newest one with an executed receipt | Evidence about the `0.39.0` bytes only |
| `b08fa948` | The development branch documented here | A different, later state — no inherited evidence |

**What this means when you upgrade:**

- Upgrading does not carry certification forward. The new bytes are unverified until you verify them.
- A receipt for an old SHA is not evidence for the SHA you just installed.
- Absence of a receipt is not a defect signal, and presence of an old one is not a green light.
- The only certification you can honestly claim is the one you ran yourself, on the machine you will
  actually use.

## Your local verification checklist

There is no remote CI to lean on, so this replaces it. Run it after every upgrade.

| # | Check | Pass condition |
|---|---|---|
| 1 | Digest of the artifact | Matches the value published in the release notes |
| 2 | `pipelinek version` | Prints `pipeline <version>` — exactly what you asked for (`Main.kt:83`) |
| 3 | `pipelinek doctor` | Exit `0`; three lines `jdk:`, `os:`, `workdir:` (`Main.kt:91-110`) |
| 4 | `scripts/install-pipelinek.sh doctor` | Reports the active binary and warns if `PATH` resolves `pipelinek` somewhere else (`scripts/install-pipelinek.sh:465-478`) |
| 5 | One real pipeline | Run the repository examples: `examples/run.sh` asserts the exit codes and the event contracts |
| 6 | Your own pipeline | Your script, on your machine, with your data |

> `validate` is **not** a rehearsal of `run`. It can print `VALIDATION SUCCESSFUL` for a script that
> `run` rejects with exit `2` (`Main.kt:191`, `:228`). Only step 5 and step 6 count as a real run.

## What an upgrade does not settle

| Question | Status |
|---|---|
| Does the new version accept an old `--db` journal? | **NO VERIFICADO.** No cross-version journal compatibility test is recorded in this repository. Keep the old `--db` file backed up |
| Is the new version production ready? | **No claim.** The product gate is blocked on external conditions, not on your machine |
| Does `sdk` manage this now? | **No.** See the channels table above |

## Traps and edges

| Trap | What happens | What to do |
|---|---|---|
| You installed over the only copy | There is no way back | Install beside, switch, verify, then remove the old one |
| `use <version>` on something not installed | The script stops with `Version <version> is not installed` | Run `install <version>` first (`scripts/install-pipelinek.sh:362`) |
| `uninstall` on the active version | Refused, on purpose | `use` another version first (`scripts/install-pipelinek.sh:430-433`) |
| An older `pipelinek` earlier on `PATH` | `version` prints the wrong number | `scripts/install-pipelinek.sh doctor` names both paths (`scripts/install-pipelinek.sh:474-478`) |
| `--resume` without `--db` | Exit `2` | `--resume` and `--rerun` both require `--db` (`CliParser.kt:196`, `:202`, `Main.kt:239`) |
| `--resume` together with `--rerun` | Exit `1` | They are mutually exclusive (`CliParser.kt:196`, `:202`) |
| Flags **after** the script path | Silently ignored — no error | Flags must come first: `--db x --resume pipeline.kts`. The parser stops at the first non-`--` token (`CliParser.kt:144-151`) |

## Next

- [`quickstart.md`](quickstart.md) — run a pipeline end to end on the version you just installed.
- [`installation.md`](installation.md) — if you have not installed yet.
- Hub: [`docs/user/README.md`](README.md).