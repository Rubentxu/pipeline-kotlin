# Research References / Evidence Baseline

Fecha de investigación: 2026-08-21.

## Kotlin
- Kotlin documentation: latest stable 2.4.10.
- Kotlin 2.4: context parameters promoted to Stable.
- Kotlin custom scripting tutorial: Custom Scripting API remains Experimental.
- Gradle Kotlin DSL roadmap/history: compilation moved to Kotlin Scripting Host API for K2/Gradle 9 work.
- Gradle embedded Kotlin is version-controlled by Gradle releases.
- Kotlin Build Tools API: Experimental; KGP Kotlin/JVM and kotlin-maven-plugin use BTA by default, while direct third-party integration is not yet the primary public path.

## Jenkins
- Jenkins Pipeline best practices: Groovy pipeline logic consumes controller CPU/memory; external build work should live on agents.
- Jenkins Workflow API exposes `FlowDefinition`, `FlowExecution`, `FlowNode`, actions and storage extension points suitable for a non-CPS execution engine projection.
- Durable Task/Shell steps already run external tasks on agents; V2 therefore targets movement of language/control-flow/compilation, not merely `sh`.
- Modern inbound agents can use WebSocket; replacing the transport name “JNLP” alone is insufficient. V2 replaces Remoting semantics for the new worker runtime.
- Modern Jenkins lines use current Java baselines; plugin baseline must be tested against supported LTS/Java matrix rather than historical Jenkins versions.

## Jenkins Kubernetes plugin
- Creates dynamic Pods for agents and deletes them according to retention/lifecycle.
- Supports WebSocket inbound connectivity.
- YAML pod definitions, `inheritFrom`, `defaultContainer`, `yamlMergeStrategy`, `retries` are useful familiarity concepts.
- V2 reuses surface/configuration ideas but not inbound agent/Remoting execution semantics.

## ActiveGraph
- Event log as source of truth; graph as projection.
- Replay reconstructs graph deterministically from history.
- Fork copies a history prefix and allows structural diff.
- Relations can carry behavior; V2 adapts this idea into deterministic relation policies rather than agentic control flow in CI/CD hot path.
- Frames distinguish run-local parallel contexts from durable forks.

## How to maintain this file

On every major architecture review, record the current Kotlin/Jenkins/Kubernetes/ActiveGraph assumptions. If an external premise changes, identify ADRs depending on it and open a review issue.
