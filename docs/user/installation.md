# PipelineK — Installation

**Release verified against**: `pipelinek 0.39.0` (GitHub Release ZIP SHA-256
`385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`).

## Requirements

- **Java 21 or newer** (the certified binary is built and tested with
  Temurin 21.0.8 / 24.0.2). The ZIP does **not** include a JDK.
- **Bash or any POSIX shell**.
- **~200 MB free disk** for the distribution plus workspace data.
- **Operating system**: Linux, macOS, or Windows via WSL. The distribution
  ships both `bin/pipelinek` (UNIX) and `bin/pipelinek.bat` (Windows).

## Install from GitHub Releases

This is the only officially supported install path today.

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
pipelinek doctor     # jdk / os / workdir / writable
```

> The ZIP does not install itself; you decide where it lives. If you don't
> want to use `/usr/local/bin`, just add `<unpack>/pipelinek-${VERSION}/bin`
> to your `PATH`.

## Install via SDKMAN (waiting external)

> SDKMAN registration of the `pipelinek` candidate is currently
> **WAITING_EXTERNAL** — vendor onboarding in progress. See
> [`docs/v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md`](../v2/07-uat/WU_LPR_080_SDKMAN_PUBLICATION_RECEIPT.md).
> Until SDKMAN confirms the candidate, install via GitHub Releases above.

If and when the SDKMAN candidate becomes available, the install will be:

```bash
# 1. Install SDKMAN if you don't have it (one-time)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. Install PipelineK
sdk install pipelinek 0.39.0

# 3. Verify
pipelinek version
pipelinek doctor
```

## Windows (WSL)

Use WSL (Ubuntu recommended). Once inside WSL, follow the GitHub Releases
install above. SDKMAN does not run natively on Windows.

```powershell
wsl --install          # one-time
# Then inside WSL (Ubuntu): follow the GitHub Releases block.
```

## Pinning a version per project (`.sdkmanrc`)

When SDKMAN becomes available, you can pin a per-directory version via
`.sdkmanrc`:

```ini
# .sdkmanrc
sdkman_auto_use=true
sdkman_auto_install=true
pipelinek=0.39.0
```

Then `cd` into the project and run `sdk env` to activate the pinned
version in the current shell. Until SDKMAN is confirmed available, pin
versions via your shell manager (mise, asdf) or by shipping the ZIP
URL + SHA-256 in your repo.

## Verify the install is the canonical bytes

Whatever install path you used, you can confirm the binary matches the
canonical ZIP by checking `pipelinek version` reports exactly the version
you installed (e.g. `pipeline 0.39.0`).
