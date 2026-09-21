# pipeline-kotlin — Local Foundation Consolidation Pack

**Status:** Proposed integration pack  
**Prepared:** 2026-09-03  
**Repository baseline reviewed:** `Rubentxu/pipeline-kotlin` at commit `0685de8081a3be10e8400eff109a832042b2bad0` plus the architectural review performed around that baseline.

This pack turns the accumulated architecture review into an executable modernization programme. Its goal is to make `pipeline-kotlin` a stable, local-first CI/CD engine with a Jenkins-familiar Kotlin DSL, a real plugin SDK, deterministic/durable execution, typed capabilities, scalable output handling, and frictionless installation for local CI.

## Product north star

A Jenkins user should be able to read and write a pipeline almost immediately:

```kotlin
pipeline {
    agent(any)
    environment {
        "CI" set "true"
    }
    stages {
        stage("Build") {
            steps {
                sh("./gradlew build")
                junit("**/build/test-results/*.xml")
            }
        }
    }
}
```

The implementation behind that familiar surface is deliberately not a Jenkins clone. It uses Kotlin's type system, a canonical IR, generated plugin metadata, context parameters, durable process execution, local-first stores, and explicit architectural boundaries.

## Non-negotiable consolidation rule

> One model, one owner, one execution path per important concept.

Until milestones LFC-0 through LFC-6 are closed, the project MUST NOT reopen remote controllers, network protocols, Jenkins runtime integration, or broad new step families. New functionality is allowed only when it closes a consolidation gate or is required by an accepted UAT.

## Contents

- `docs/v2/00-governance/`: current-state truth, decision policy, debt and merge plan.
- `docs/v2/01-product/`: local-first product and Jenkins migration experience.
- `docs/v2/02-architecture/`: target architecture, module boundaries and fitness functions.
- `docs/v2/03-specifications/`: normative specifications.
- `docs/v2/04-adrs/`: architecture decisions for the consolidation programme.
- `docs/v2/05-roadmap/`: milestone roadmap and deferred backlog.
- `docs/v2/06-uat/`: UAT catalogue, reliability and performance gates.
- `docs/v2/07-distribution/`: Jlink/JReleaser/SDKMAN/Homebrew/mise/asdf strategy.
- `docs/v2/08-plugin-sdk/`: plugin authoring and capability contracts.
- `docs/v2/09-migration/`: migration from current implementation and from Jenkins.
- `docs/v2/10-references/`: research sources and traceability.

Start with `docs/v2/00-governance/INTEGRATION_MANIFEST.md`, then execute `docs/v2/05-roadmap/ROADMAP.md` milestone by milestone.
