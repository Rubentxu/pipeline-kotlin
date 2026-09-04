# pipeline-kotlin

`pipeline-kotlin` is consolidating a local-first CI/CD engine with a
Jenkins-familiar Kotlin pipeline DSL. V2 is the active product line.

The project is **not release-ready yet**. The current work follows the Local
Foundation Consolidation (LFC) roadmap: one canonical pipeline model, one
execution path, typed capabilities, durable local execution, and a normal
developer-tool installation experience.

## Quick path for contributors

```bash
# Inspect the active V2 build
./gradlew -p v2 tasks

# Run a focused V2 test while iterating
just t 'FullyQualifiedTestName'

# Run the repository-level V2 gate at an apply/verify boundary
./gradlew check
```

`./gradlew check` forwards to the active V2 composite build. During normal
development, prefer the narrowest relevant V2 test; use the full gate only at a
milestone or verification boundary.

## Active scope

V2 local-first CI/CD covers:

- compiling, validating, and inspecting `.pipeline.kts` pipelines;
- durable local execution and bounded local run data;
- Jenkins-familiar pipeline structure where behavior is verified;
- typed plugin and capability contracts;
- local output, artifacts, events, credentials, and run inspection.

Remote controllers, network protocols, Jenkins runtime integration, remote
scheduling, and a SaaS control plane are deferred. They are not part of the
local product's active build or release path.

## Current programme

The active sequence is [LFC-0 through LFC-10](docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md).
LFC-0 establishes repository truth before the architectural migrations begin.

| Work item | Status |
|---|---|
| LFC0-001 | Document authority and active-scope ADR accepted |
| LFC0-002 | Root/V1/V2 Gradle graph inventoried |
| LFC0-003 | Protocol module removed from the active V2 build |
| LFC0-004 | V1 build and release entry points quarantined |
| LFC0-005 | This README aligned to the V2 local-first product |

The LFC-0 gate remains open until all its scope, architecture, and UAT criteria
are defined and verified.

## Where to read next

- [Document authority](docs/v2/00-governance/DOCUMENT_AUTHORITY.md) — which
  document wins when material conflicts.
- [Local Foundation roadmap](docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md)
  — current work and progress.
- [Gradle graph inventory](docs/v2/02-architecture/GRADLE_GRAPH_INVENTORY.md)
  — current build topology and protocol quarantine evidence.
- [V2 documentation index](docs/v2/INDEX.md) — specifications, ADRs, UAT, and
  historical evidence.

## Legacy V1

V1 source remains in the repository as historical/quarantined material. It is
not a parallel product track and its former tag-release workflow does not build
or publish artifacts. Do not repair V1 as part of the V2 critical path unless
an accepted exception explicitly requires it.

## License

[MIT](LICENSE)
