# Test Matrix delta

| Concern | T0 | T1 | T2 | T3 | T4 | T5 | T6 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ADTs/value types | MUST | - | - | - | - | - | - |
| codecs/schema | MUST | SHOULD | - | - | - | - | - |
| DSL compile | MUST | SHOULD | - | - | - | - | - |
| canonical IR | MUST | MUST | SHOULD | - | - | - | - |
| atomic handler | SHOULD | MUST | selected | selected | - | - | - |
| plugin loading | - | smoke | MUST | SHOULD | selected | - | - |
| process tree | - | selected | MUST | SHOULD | MUST | - | - |
| replay/resume | pure policy | SHOULD | SHOULD | MUST | selected | - | - |
| block restore | pure patch | MUST | SHOULD | selected | selected | - | - |
| credentials | pure codec | MUST | MUST | SHOULD | MUST | selected | - |
| Git/HTTP/DB | - | fake | selected | - | selected | MUST | optional |
| external OSS | - | - | - | - | - | - | MUST |

## Minimum by type

### echo-like
T0 + T1 + one T2 proof.

### sh-like
T0 + T1 + T2; T3 replay; T4 cancellation/security.

### block Step
T0 body contract + T1 nested behavior + selected T2; T3 if durable control; T4 if security.

### external plugin
independent build + T2 real loading is mandatory.

## CI lanes

- PR-fast: T0/T1 + targeted T2.
- PR-integration: impacted T2/T3.
- sandbox: T4/T5 on Linux Podman runner.
- nightly: broad corpus + T5/T6.
- release: all certifications + supported examples.
