# CLI UX specification summary

## Design

The CLI is the composition root and the public local product. It should feel closer to `git`, `act`, `cargo` or `gradle` than to an administrative Jenkins CLI.

## Commands

```text
pipeline init
pipeline validate [file]
pipeline run [stage|pipeline] [--param K=V] [--resume <run-id>]
pipeline plan
pipeline inspect <run-id>
pipeline logs <run-id> [--step <id>] [--stream stdout|stderr|system] [--follow]
pipeline artifacts <run-id>
pipeline graph <run-id> [--format json|dot|mermaid]
pipeline plugins list|verify|update
pipeline credentials list|set|remove
pipeline doctor
pipeline version
```

## Exit codes

- `0`: pipeline/command success;
- `1`: user pipeline failure;
- `2`: validation/compilation/configuration error;
- `3`: missing capability/credential/plugin;
- `4`: infrastructure/runtime failure;
- `130`: cancellation/interrupt where platform conventions allow.

Exit codes are public API and require compatibility tests.

## CI behavior

When `CI=true` or `--ci` is present:

- no interactive prompts;
- stable machine-readable error codes;
- ANSI controlled by `--color=auto|always|never`;
- logs stream to terminal while also being persisted;
- final summary prints artifact/run locations;
- optional `--report json` emits a machine-readable run summary.
