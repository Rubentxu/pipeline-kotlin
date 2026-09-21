# Local-first product contract

## Problem

Developers need a CI pipeline that can be run, inspected and debugged locally with the same pipeline definition used by CI, without first deploying a Jenkins controller, runner fleet or auxiliary service.

## Primary workflows

```bash
pipeline validate
pipeline run
pipeline run Build
pipeline logs <run-id>
pipeline inspect <run-id>
pipeline graph <run-id>
pipeline artifacts <run-id>
pipeline credentials list
pipeline doctor
```

## Project convention

```text
project/
├── pipeline.kts
├── pipeline.lock
└── .pipeline/
    ├── config.toml        # optional project-local config
    ├── runs/              # ignored, durable local state
    ├── cache/             # ignored
    └── credentials.enc    # optional, ignored
```

User-global state lives under an OS-appropriate data/config directory, never inside the repository unless explicitly configured.

## Product guarantees for 1.0

- deterministic compilation of a pipeline against its plugin lock;
- familiar Jenkins declarative structure;
- correct stdout/stderr streaming and exit semantics;
- cancellation and timeout terminate process trees;
- credentials are scoped and redacted;
- rerun/resume semantics are documented and tested;
- `pipeline run` works without a pre-installed JDK when installed from the recommended platform distribution;
- plugin API compatibility is versioned;
- local run data can be inspected without a running daemon.
