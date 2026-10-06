# ADR-EVO-014 — Own effective compiler/JVM compatibility policy

**Status:** Proposed; existing adapter, mapper and diagnostic projection.

## Context

Kotlin 2.4.10 FastJarFileSystem invokes deprecated Unsafe cleanup. Gradle 8.14.5 and 9.8.0 differ; 9.8 also permits Unsafe in its Java 24+ daemon. A quiet console does not prove the operation is absent.

## Decision

Select the standard JAR reader in host and mapper. Prove real compilation under Unsafe deny in a fresh JDK 24+ process. Preserve source warnings/errors/raw severity/location; retain the reader INFO in structured evidence and apply human verbosity in the renderer only.

One effective profile drives both actual configuration and cache identity, including actual compiler/runtime artifacts, JDK/API roots, target, language, imports, template/options. No ambient global mutation, blanket stderr filtering or manual host revision as the sole identity mechanism.

## Consequences

Measure reader cost. Faster replacements/upgrades require evidence and compatibility gates. Adding allow or changing a Kotlin version is not proof of removal. A focused correction may attach to the active authorized TRAIN without opening the full evolution programme. JDK 25 stays unverified until actually tested.

## Acceptance

UAT-053/070/071/072, AAT-023/026 and existing DSL/source-map corpus. Public support follows the certified matrix.
