# Release pipeline blueprint

## Trigger

Stable release originates from an immutable semantic-version tag after all required RC gates pass.

## Jobs

```text
verify-source
  -> test-linux
  -> test-macos
  -> assemble-jlink-linux-x64
  -> assemble-jlink-linux-arm64
  -> assemble-jlink-macos-arm64
  -> assemble-jlink-macos-x64 (while supported)
  -> assemble-windows-x64 (when first-class)
  -> distribution-smoke-each-artifact
  -> sbom/checksum/sign/attest
  -> jreleaser release
  -> package-manager publication
  -> clean-install verification
  -> SDKMAN default promotion (stable only)
```

## Publication safety

Prepare/validate package metadata before publishing. Package-manager publication is downstream from immutable release artifacts; it must not rebuild different binaries.

## Rollback

Never overwrite a tagged release artifact. A bad release is superseded by a new patch version. Package managers may be repointed/disabled according to their supported workflows, but artifacts remain immutable for auditability.

## Secrets

SDKMAN/vendor, GitHub and signing credentials live only in release CI secret storage and are never exposed to pipeline output. Release jobs should use minimal permissions and environment protection rules where available.
