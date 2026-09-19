# PipelineK — Upgrading

**Release verified against**: `pipelinek 0.39.0`. The upgrade story is
SDKMAN-first; manual install upgrades are documented as a fallback.

## Upgrade via SDKMAN

```bash
# See what's available
sdk list pipelinek

# Install the newer version explicitly (does not switch the default yet)
sdk install pipelinek <new-version>

# Switch the default
sdk default pipelinek <new-version>

# Verify
pipelinek version
```

If you use `.sdkmanrc` per-project, change the `pipelinek=` line to the
new version and re-run `sdk env` to activate it.

## Roll back

If the new version misbehaves, switch back via SDKMAN:

```bash
sdk default pipelinek <previous-version>
```

If the new version broke your project's `--db` (e.g. an incompatible
schema change), keep the old version as your default and only use the
new version in a fresh `--db` path. The `--db` schema is **not** part
of the stable contract; do not assume forward or backward
compatibility across major versions.

## Upgrade via direct download (no SDKMAN)

If you installed from GitHub Releases:

```bash
VERSION=<new-version>
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
curl -fsSL -o "pipelinek-${VERSION}.zip" "${URL}"
curl -fsSL "${URL}.sha256" | sha256sum -c -
unzip -q "pipelinek-${VERSION}.zip" -d /opt/pipelinek

# Swap the symlink
sudo ln -sf /opt/pipelinek/pipelinek-${VERSION}/bin/pipelinek \
              /usr/local/bin/pipelinek

pipelinek version
```

To roll back, repoint the symlink to the older version directory and
remove the newer directory.

## What you can expect across upgrades

- **Patch versions (`0.39.x`)**: bug fixes only. CLI flags and exit
  codes are stable. The DSL may grow new StepSpecs (plugins).
- **Minor versions (`0.X.0`)**: new StepSpecs may be added; existing
  ones do not change semantics. New CLI flags may be added.
- **Anything else**: no promises. Read the release notes and re-run the
  relevant fixtures before adopting.
