# Specification — Local CI/CD Product Profile v1

Status: PROPOSED

## Scope

Define the exact product subset required before a public/local-production-ready distribution.

## Required user capabilities

A software repository can express and execute:

```text
prepare/checkout
    ↓
build
    ↓
test
    ↓
package
    ↓
archive/report outcome
```

with workspace/environment/credentials/retry/timeout/parallel as needed.

## Project convention

Default discovery:

```text
repo/
├── pipeline.kts
├── ...project files...
└── .pipelinek/       # local generated state; normally gitignored
```

Suggested state layout:

```text
.pipelinek/
├── runs/
├── journal/
├── events/
├── transcripts/
├── artifacts/
└── cache/
```

Exact on-disk schema remains versioned and internal unless declared public.

## CLI minimum

- `pipelinek run [pipeline.kts]`
- `pipelinek validate [pipeline.kts]`
- `pipelinek doctor [--format text|json]`
- `pipelinek version [--format text|json]`
- `pipelinek events ...`
- `pipelinek inspect ...`
- credentials commands already supported, normalized under the final CLI naming.

## Exit codes

Stable V1 contract:

- `0`: requested operation completed successfully / pipeline success;
- `1`: pipeline/acceptance outcome failure;
- `2`: invocation/configuration/compilation/admission error before a valid run could execute.

Any additional category must be introduced compatibly and documented; scripts can always depend on nonzero as failure.

## Supported/experimental/rejected

Every public DSL/CLI surface in a release has one product state:

```text
SUPPORTED
EXPERIMENTAL
REJECTED/UNAVAILABLE
```

No method may present stable-looking behavior while returning a placeholder/no-op.

## Real project gate

At minimum three externally shaped project fixtures are maintained and executed through the installed distribution:

- Gradle JVM project using wrapper;
- Maven JVM project using wrapper;
- Node/npm project with lockfile.

A fourth Python/Poetry or Go fixture is recommended after Gate-1, not required to block the first release.

Fixtures must exercise actual subprocesses, artifact output and failure paths; mocks alone do not satisfy product certification.
