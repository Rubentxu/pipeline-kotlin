# Distribution strategy

## User experience target

```bash
sdk install pipeline
# or
brew install rubentxu/tap/pipeline
# or
asdf plugin add pipeline <official-plugin-url>
asdf install pipeline 1.0.0
# or
mise use -g pipeline@1.0.0
```

The first three may use different packaging metadata, but all install the same signed/checksummed release artifacts.

## Primary artifact: Jlink image

Use a platform-specific Jlink distribution so `pipeline` includes its Java runtime. This avoids "works only with the right JDK" failures in local CI and is supported as a JReleaser distribution type and by SDKMAN/Homebrew/asdf packagers.

## Release channels

- `nightly`/snapshot: GitHub artifacts only; never SDKMAN/Homebrew stable feeds.
- `rc`: GitHub prerelease, optional tap/version-manager test channel.
- `stable`: GitHub Release + SDKMAN + Homebrew tap + asdf + mise registry/backend.

## Homebrew

Start with an official project tap to control update cadence. Consider `homebrew/core` only after stable releases, usage and acceptance requirements justify it. Formula/cask tests must execute functional behavior, not just `--version`.

## SDKMAN

Complete vendor onboarding, obtain release credentials and publish multi-platform archives with checksums. Stable/default promotion is a separate release decision.

## mise

Prefer the Aqua backend/registry because it avoids maintaining a mise-specific plugin and supports checksummed/security-oriented package metadata. A GitHub backend is a fallback if Aqua registry onboarding lags.

## asdf

JReleaser can generate/publish asdf packaging. Keep the plugin minimal and portable; test on Linux and macOS.
