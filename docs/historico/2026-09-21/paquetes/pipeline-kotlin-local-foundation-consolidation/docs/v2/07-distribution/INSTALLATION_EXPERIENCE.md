# Installation and version-management UX

## Supported first-class methods

### SDKMAN
Best for JVM-oriented developer environments and CI images already using SDKMAN.

```bash
sdk install pipeline
sdk use pipeline 1.0.0
```

### Homebrew
Best for macOS and Linux developer workstations.

```bash
brew install rubentxu/tap/pipeline
brew upgrade pipeline
```

### mise
Best for per-project version pinning across many tool ecosystems.

```toml
# mise.toml
[tools]
pipeline = "1.0.0"
```

### asdf
Keep official support for teams already standardized on asdf.

```text
.tool-versions:
pipeline 1.0.0
```

## Bootstrap fallback

GitHub Releases remain the immutable origin. Provide a small documented manual archive install, but avoid a curl-pipe-shell installer as the primary recommendation when version managers already solve verification/update concerns.

## Version contract

`pipeline version --json` returns runtime version, DSL API version, plugin API version, Kotlin compiler version and distribution/platform ID for support diagnostics.
