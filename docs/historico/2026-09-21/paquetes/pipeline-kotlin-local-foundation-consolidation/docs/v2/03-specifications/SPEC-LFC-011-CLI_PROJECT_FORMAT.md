# SPEC-LFC-011 — Local project and CLI format

**Status:** proposed

## Discovery

Starting at cwd, CLI searches upward for one of:

1. `pipeline.kts` (default);
2. explicitly configured path;
3. project config naming a pipeline file.

## Project state

`.pipeline/` is local runtime state and SHOULD be gitignored. The pipeline source and lockfile are version-controlled.

## Config precedence

```text
CLI flags
> environment variables explicitly supported by CLI
> project .pipeline/config.toml
> user config
> built-in defaults
```

Secrets are never placed in normal config.

## Reproducibility

`pipeline validate --locked` and CI mode fail if the plugin lock is missing/out of date when external plugins are required.
