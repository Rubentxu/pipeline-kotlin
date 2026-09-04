# LFC0-002 — Gradle graph inventory

**Status:** completed  
**Date:** 2026-09-03  
**Milestone / backlog:** LFC-0 / LFC0-002  
**Exit relevance:** repository-truth snapshot taken before LFC0-003 changed the active build

![Root, V1, and V2 Gradle module graph](../build/diagrams/lfc0-gradle-module-graph.svg)

Source: [Mermaid graph](diagrams/lfc0-gradle-module-graph.mmd). The graph was
derived from both settings files and every V1/V2 `build.gradle.kts` project
dependency declaration on 2026-09-03.

## Findings

- The root build includes the V1/legacy modules and V2 through
  `includeBuild("v2")`; these are separate Gradle builds, not a single module
  graph.
- The V1 root still includes `:pipeline-steps-system:compiler-plugin`. V1
  `:core` has its compiler-plugin classpath disabled, but the V1 Gradle plugin
  depends on the compiler plugin. This remains legacy scope and must not leak
  into V2.
- At the time of this inventory, `:pipeline-protocol` was included in V2 and
  depended on `:pipeline-domain`. No V2 production build file depended on it;
  its only active-build consumer was `:pipeline-architecture-tests`, which
  inspected its boundary and source layout.
- `:pipeline-application` is the local product composition root. It depends on
  domain, events, scripting, SDK, credentials, and local artefact modules, but
  not on protocol.

## Decision input for LFC0-003

The protocol module is unconsumed by the local product path, but it is not yet
removable without changing the architecture-test contract. LFC0-003 must first
replace or retire that boundary test, then remove the module from
`v2/settings.gradle.kts` and re-run the affected architecture suite.

## Limits

This is an inventory of declared Gradle project dependencies. It does not claim
runtime reachability, nor does it classify every external library dependency.
