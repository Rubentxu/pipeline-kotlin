# Proposal: local-production-ready

## Summary

Reprioritize the V2 programme around a bounded Local Production Ready gate: a real, installable, high-performance local CI/CD product for software projects, distributed first as a universal JVM ZIP/GitHub Release and then through SDKMAN.

The change integrates — rather than discards — the existing Step Constitution, BodyInvoker, Honest DSL and Event Spine work. It changes sequencing and closes specific production risks before broad plugin expansion.

## Why now

The implementation already has:

- canonical durable execution;
- Step registry/plugin seam;
- installed-distribution UATs;
- SQLite journal/events;
- typed event envelope/cursors/history;
- local credentials/git/artifact foundations;
- real process runtime and replay primitives.

But the product is not yet ready for normal project adoption because:

- coordinator/body responsibility is still too concentrated;
- public DSL honesty is not uniformly proven;
- CLI run output is not a polished streaming product surface;
- event persistence/output pumping need hot-path optimization before live full streaming;
- broad ecosystem expansion is ahead of packaging/release/real-project feedback;
- V2 CI/release path is incomplete.

## Goals

1. Define and certify `local-core-v1`.
2. Strengthen BodyExecution/Invocation boundaries without big-bang rewrite.
3. Make supported DSL declarative/runtime semantics honest.
4. Provide live events/console views with no consumer backpressure on execution.
5. Make streaming memory bounded and event persistence batched/efficient.
6. Add agent-efficient inspection/filter/cursor APIs without a query-language explosion.
7. Certify real Gradle/Maven/Node pipelines through the installed distribution.
8. Produce reproducible universal V2 release artifacts.
9. Publish via GitHub Releases and SDKMAN.
10. Dogfood before resuming broad plugin/controller work.

## Non-goals

- complete Jenkins parity;
- finish LFC-2E E2..E10 before first product release;
- implement controller/Jenkins UI/remote workers;
- select NATS/Kafka;
- build a daemon;
- require native image/jlink;
- merge historical branches wholesale;
- make events the execution/control authority.

## Canonical decisions proposed

ADR-0082..0091 in this package.

## Success

`LPR-GATE-1` in `docs/v2/05-roadmap/LOCAL_PRODUCTION_READY_ROADMAP.md` is green using the actual release distribution and, for SDKMAN-ready claim, a clean SDKMAN install.
