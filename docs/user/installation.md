# PipelineK — Installation

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Latest published release**: `0.47.0`. Confirmed 2026-10-06: `releases/latest` redirects to
`releases/tag/v0.47.0`, and the release ships `pipelinek-0.47.0.zip` plus a `SHA256SUMS` file.
**The commands below were read from the source, not from running a downloaded binary.**

> **Documentation divergence.** This page previously carried the header *"Release verified against:
> pipelinek 0.39.0"*. That header is withdrawn: the commands below were read from the development
> branch, not from a published binary. Two different things used to be called "the published
> release", and the page mixed them. They are now separated:
>
> - **`0.47.0`** is the **latest published release**. Its ZIP digest comes from the release's own
>   `SHA256SUMS` — trustworthy provenance, but nobody in this repository ran that ZIP.
> - **`0.39.0`** is the **latest release with an executed receipt** (commit `951b3cb5…`). Its ZIP
>   *and* binary digests are recorded in this repository.
>
> Recorded 2026-10-06. See `docs/user/README.md` → "Known divergences".

> **Authority.** This repository has **no remote CI since 2026-09-30**: `.github/workflows/` does not
> exist. Commit `754ddda0` removed the CI workflows. Nothing on this page is backed by a green pipeline, and nothing here claims
> the product is production ready. What you do get is a digest you can check yourself.

## By the end of this page you can

- [ ] Install the release ZIP and prove the bytes are the canonical ones.
- [ ] Confirm the binary runs on your machine with `pipelinek version` and `pipelinek doctor`.
- [ ] Use the multi-version installer to keep more than one version side by side.
- [ ] Know which install channels **do not exist yet**, so you do not lose time on them.

## Words you will meet

| Word | Everyday meaning | Here |
|---|---|---|
| Binary | The compiled program | `bin/pipelinek` inside the ZIP |
| ZIP | A box of files | The canonical release artifact (`docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md`) |
| SHA-256 digest | A fingerprint, 64 hex characters | Two different files never share one |
| `PATH` | The list of folders your shell searches for commands | If `pipelinek` is not on it, it is "not installed" |
| Install root | The one folder everything lives under | `~/.local/share/pipelinek` |

## Requirements

| Requirement | Value | Notes |
|---|---|---|
| Java | **21 or newer** | Certified on Temurin 21.0.8 and 24.0.2. The ZIP does **not** ship a JDK |
| Operating system | Linux, macOS, or Windows via WSL | WSL is the only supported Windows path |
| Shell | Bash 4+ | Only for the multi-version installer (`scripts/install-pipelinek.sh:23`) |
| Tools for the installer | `curl`, `sha256sum`, `unzip` on `PATH` | `scripts/install-pipelinek.sh:25` |
| Disk | ~200 MB free | Distribution plus workspace data |

> **macOS note.** The installer calls `sha256sum` (`scripts/install-pipelinek.sh:276`), which macOS
> does not ship by default. Install GNU coreutils first (`brew install coreutils`) or use the manual
> ZIP path below. **NO VERIFICADO**: no macOS-specific installer run is recorded in this repository.

## Option A — install the canonical ZIP (works today)

### 1. The digests

Check these before you download anything.

| Item | Value |
|---|---|
| Release to install | `0.47.0` |
| ZIP SHA-256 | `2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c` |
| Where that digest comes from | The release's own `SHA256SUMS` file |
| Binary SHA-256 | **NO VERIFICADO** — no receipt in this repository records the digest of the binary inside the `0.47.0` ZIP |
| ZIP URL | `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.47.0/pipelinek-0.47.0.zip` |

> **Where the digest is *not*.** The release also shows `v0.47.0.zip` and `v0.47.0.tar.gz` in its
> asset list. Those are GitHub's **source archives**, generated from the tag. `SHA256SUMS` does not
> cover them, and they are not the distribution. Downloading `v0.47.0.zip` instead of
> `pipelinek-0.47.0.zip` gets you a tarball of the repository, and the digest above will not match.

<details>
<summary>Previous release with a fully executed receipt: <code>0.39.0</code></summary>

| Item | Value |
|---|---|
| Release | `0.39.0` |
| ZIP SHA-256 | `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` |
| Binary SHA-256 | `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee` |
| Certified commit | `951b3cb5695ecc46c877776e330266e4bd44aa9e` |
| ZIP URL | `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.39.0/pipelinek-0.39.0.zip` |

These are the digests of a release that was actually downloaded, checked and run. If you want the
strongest evidence available rather than the newest code, install this one.

</details>

### 2. Download and verify

```bash
VERSION=0.47.0
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
curl -fsSL -o "pipelinek-${VERSION}.zip" "${URL}"

# Verify the ZIP digest BEFORE unpacking anything.
echo "2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c  pipelinek-0.47.0.zip" | sha256sum -c -
# macOS equivalent:  shasum -a 256 pipelinek-0.47.0.zip
```

Continue only if it printed `pipelinek-0.47.0.zip: OK`. A mismatch means stop.

### 3. Unpack and put it on `PATH`

```bash
ROOT="$HOME/.local/share/pipelinek"
mkdir -p "${ROOT}/versions"
unzip -q "pipelinek-${VERSION}.zip" -d "${ROOT}/versions"
export PATH="${ROOT}/versions/pipelinek-${VERSION}/bin:${PATH}"
```

> The ZIP contains exactly one top-level directory, `pipelinek-<version>/`, and the binary must be at
> `bin/pipelinek` inside it. The installer refuses anything else
> (`scripts/install-pipelinek.sh:294-311`). If your archive looks different, you have the wrong file.

### 4. Make `PATH` permanent

Add the same line to `~/.bashrc` or `~/.zshrc`:

```bash
export PATH="$HOME/.local/share/pipelinek/versions/pipelinek-0.47.0/bin:$PATH"
```

### 5. Verify

```bash
pipelinek version
pipelinek doctor
```

| Command | Expected | Source |
|---|---|---|
| `pipelinek version` | `pipeline 0.47.0` — note the output starts with `pipeline `, **not** `pipelinek` | `Main.kt:83` |
| `pipelinek doctor` | Three lines: `jdk:`, `os:`, `workdir:` | `Main.kt:91-108` |

`doctor` exits `0` when healthy and `2` if the working directory is not writable (`Main.kt:110`).

## Option B — the multi-version installer

`scripts/install-pipelinek.sh` keeps several versions side by side and switches between them with one
symlink. It is the right choice if you upgrade often or work on projects pinned to different versions.

**Analogy**: instead of replacing the only wrench in the toolbox, you keep a labelled drawer per
version and point one handle at the drawer you want today.

### Subcommands

| Command | What it does |
|---|---|
| `install <version>` | Download, verify the digest, extract, verify identity, then publish |
| `use <version>` | Point the `current` symlink at an installed version |
| `list` | Table of installed versions; the active one is marked `*` |
| `uninstall <version>` | Remove a version that is **not** active |
| `doctor` | Report the resolved binary and PATH, then run `pipelinek doctor` |
| `help` | Usage |

Dispatch table: `scripts/install-pipelinek.sh:532-536`.

### Install and activate

```bash
# Run from a checkout of the repository
scripts/install-pipelinek.sh install 0.47.0
scripts/install-pipelinek.sh use 0.47.0
export PATH="$HOME/.local/share/pipelinek/current/bin:${PATH}"
pipelinek version
```

### What it guarantees

| Guarantee | Detail | Source |
|---|---|---|
| URL allowlist | Only `github.com` and `objects.githubusercontent.com`; anything else is refused before any file is written | `scripts/install-pipelinek.sh:54`, `:121` |
| Digest authority | `SHA256SUMS` next to the ZIP. It refuses to install without a valid entry | `scripts/install-pipelinek.sh:225`, `:254-270` |
| Transactional | Download, digest, extract and identity checks all happen in a temp dir; the version directory appears only after every check passed | `scripts/install-pipelinek.sh:40-43` |
| Exact identity | The extracted binary must report exactly the requested version | `scripts/install-pipelinek.sh:310-335` |
| No `sudo` | Never writes to `/usr` or `/opt`, never spawns a daemon | `scripts/install-pipelinek.sh:38` |
| Idempotent | `install` on an existing version is a no-op; `uninstall` on a missing one is a no-op | `scripts/install-pipelinek.sh:37-38` |

### Environment overrides

| Variable | Default | Purpose |
|---|---|---|
| `PIPELINEK_HOME` | `~/.local/share/pipelinek` | Install root |
| `PIPELINEK_RELEASE_BASE_URL` | GitHub Releases | Must satisfy the allowlist |
| `PIPELINEK_MIRROR_BASE_URL` | *(empty)* | Install from a mirror or an air-gapped copy; loopback hosts are always allowed |
| `PIPELINEK_SHA256_<VERSION>` | *(unset)* | Expected digest, e.g. `PIPELINEK_SHA256_0_39_0` (dots become underscores) |

Source: `scripts/install-pipelinek.sh:30-35`, `:228`, `:503`.

## Channels that do not exist today

Two methods work today: **Option A** (canonical ZIP) and **Option B** (the multi-version installer).
Three more are specified but not built, and one is a common pattern that this project does not
support at all:

| Method | Status | Detail |
|---|---|---|
| `mise` (`mise use -g pipelinek@0.47.0`) | **Specified, not built** | Planned as **DIST-4** in [`DISTRIBUTION_ROADMAP.md`](../v2/05-roadmap/DISTRIBUTION_ROADMAP.md): register `pipelinek` on the Aqua or GitHub-release backend. The plugin registry is external to this repository, so the commands do not work yet |
| `asdf` (the `asdf-pipeline` plugin) | **Specified, not built** | Planned as **DIST-7**, an external plugin exposing `bin/install`, `bin/download`, `bin/list-bin`. Also lives in the external harness. `asdf` already reads this repo's `.tool-versions` for `java`, `gradle` and `maven`, but `pipelinek` is not in it, and adding it today would break every `asdf` user with an unresolved plugin |
| `curl \| sh` one-liner | **Exists, with a caveat** | `scripts/install-pipelinek-curl.sh` is a POSIX `sh` bootstrap that resolves the installer, verifies it and delegates to it. It exists because `scripts/install-pipelinek.sh:46` sets `set -Eeuo pipefail` and line 56 reads `BASH_SOURCE[0]`, which is empty on stdin — so `curl … \| sh` against the installer *itself* always fails. **Blocked in practice**: no published release ships the installer as a release asset yet (verified 2026-10-06: `…/releases/download/v0.47.0/install-pipelinek.sh` is HTTP 404), so the bootstrap exits `10` rather than installing. It is correct code against an incomplete release pipeline |

Every channel — the two that exist and the three planned ones — must consume the same canonical ZIP
and verify the same SHA-256. That rule is in
[`ADR-0089`](../v2/04-adrs/ADR-0089-distribution-artifact-authority-sdkman.md) and
[`DISTRIBUTION_RELEASE_SPEC.md`](../v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md): no channel
rebuilds PipelineK.

The main README documents all five methods, including the exact shape `mise` and `asdf` are designed
for: [`README.md` → Installation methods](../../README.md#installation-methods).

| Channel | Status | Detail |
|---|---|---|
| GitHub Releases ZIP | **Available** | Option A above |
| `scripts/install-pipelinek.sh` | **Available** | Option B above |
| Homebrew (`brew install`) | **Not available** | There is no Homebrew tap for PipelineK. `brew install pipelinek` does not work |

## Traps and edges

Read these after a successful install.

| Trap | What happens | What to do |
|---|---|---|
| You skipped the digest check | You trust a file you cannot prove | Always run `sha256sum -c -` first |
| `pipelinek` not found after install | `${ROOT}/.../bin` is not on `PATH` | Add it to `~/.bashrc`, then open a new shell |
| An older `pipelinek` shadows yours | `version` prints the wrong number | `scripts/install-pipelinek.sh doctor` warns about exactly this (`scripts/install-pipelinek.sh:474-478`) |
| `pipelinek version` exits `3` | The artifact has no `Implementation-Version` in its manifest. The CLI refuses to invent one | Rebuild, or re-download: this is a broken artifact, not a broken machine (`Main.kt:80`) |
| Mixing Option A and Option B | The installer names directories `versions/0.47.0`; the manual path unpacks to `versions/pipelinek-0.47.0` | Pick one method per machine |
| You passed a pre-release to the installer | `0.47.0-rc1` is rejected: the version must be `MAJOR.MINOR.PATCH` | Use only published releases (`scripts/install-pipelinek.sh:55`) |

## Next

- [`quickstart.md`](quickstart.md) — write and run your first pipeline.
- [`upgrading.md`](upgrading.md) — move to another version and roll back.
- Hub: [`docs/user/README.md`](README.md).