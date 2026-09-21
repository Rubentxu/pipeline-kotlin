# ADR-LFC-013 — Jlink plus JReleaser is the primary distribution path

**Status:** proposed

## Context

Local CI adoption suffers if every user/project must provision the correct JDK and manually install a development build. Kotlin scripting also makes native-image a risky primary route.

## Decision

Publish platform-specific Jlink distributions and orchestrate releases with JReleaser. Publish through GitHub Releases, SDKMAN and Homebrew; support asdf; prefer mise Aqua/GitHub-release integration rather than bespoke plugin maintenance.

## Consequences

Installation becomes one command and independent of host JDK. Release matrix cost increases because Jlink images are platform-specific.

## Rejected/Deferred alternatives

GraalVM native-image as the primary 1.0 artifact; one universal fat JAR as the recommended install.
