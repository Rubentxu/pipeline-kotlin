# PipelineK — Installation

**Release verified against**: `pipelinek 0.39.0` (GitHub Release ZIP SHA-256
`385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`).

## Requirements

- **Java 21 or newer** (the certified binary is built and tested with
  Temurin 21.0.8 / 24.0.2). The ZIP does **not** include a JDK.
- **Bash or any POSIX shell** for SDKMAN.
- **~200 MB free disk** for the distribution plus workspace data.
- **Operating system**: Linux, macOS, or Windows via WSL. The distribution
  is `UNIVERSAL` per SDKMAN; it ships both `bin/pipelinek` (UNIX) and
  `bin/pipelinek.bat` (Windows).

## Install with SDKMAN (recommended)

SDKMAN is the version manager PipelineK is published through.

```bash
# 1. Install SDKMAN if you don't have it (one-time)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. Install PipelineK
sdk install pipelinek 0.39.0

# 3. Verify
pipelinek version     # → pipeline 0.39.0
pipelinek doctor      # → jdk / os / workdir / writable
```

> SDKMAN registration of the `pipelinek` candidate is currently
> **WAITING_EXTERNAL** — see
> [`docs/v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md`](../../v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md).
> Until SDKMAN confirms the candidate, install via GitHub Releases below.

## Install from GitHub Releases (fallback)

```bash
# Download the canonical ZIP for the version you want
VERSION=0.39.0
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
curl -fsSL -o "pipelinek-${VERSION}.zip" "${URL}"

# Verify SHA-256 (compare with the value in the GitHub Release notes
# and in the .sha256 sidecar)
curl -fsSL "${URL}.sha256" | sha256sum -c -

# Unpack into a stable location, e.g. /opt/pipelinek
sudo unzip -q "pipelinek-${VERSION}.zip" -d /opt/pipelinek
sudo ln -sf /opt/pipelinek/pipelinek-${VERSION}/bin/pipelinek /usr/local/bin/pipelinek

# Verify
pipelinek version
pipelinek doctor
```

> The ZIP does not install itself; you decide where it lives. If you don't
> want to use `/usr/local/bin`, just add `<unpack>/pipelinek-${VERSION}/bin`
> to your `PATH`.

## Windows (WSL)

SDKMAN does not run natively on Windows. Use WSL:

```powershell
wsl --install          # one-time
# Then inside WSL (Ubuntu):
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install pipelinek 0.39.0
```

## Pinning a version per project (`.sdkmanrc`)

SDKMAN supports per-directory version selection via `.sdkmanrc`. Drop
this in your project root to lock the version for anyone using SDKMAN:

```ini
# .sdkmanrc
sdkman_auto_use=true
sdkman_auto_install=true
pipelinek=0.39.0
```

Then `cd` into the project and run `sdk env` to activate the pinned
version in the current shell.

## Verify the install is the canonical bytes

Whatever install path you used, you can confirm the binary matches the
canonical ZIP by checking `pipelinek version` reports exactly the version
you installed (e.g. `pipeline 0.39.0`).
