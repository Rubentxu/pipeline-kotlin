# LPR Work Units — immediate execution queue

This file translates the roadmap into bounded WUs suitable for agent-first implementation. Each WU starts from current `main`; no branch WIP is assumed.

## WU-LPR-000 — Authority + baseline

**Inputs:** ADR-0082..0091 review.  
**Do:** apply roadmap status banners, implement/repair CI baseline, capture exact green/red and benchmark baseline.  
**Must not:** refactor code.  
**Exit:** baseline receipt + hashes.

## WU-LPR-010 — CLI contract characterization

Characterize current run/validate/events/credentials behavior and exit codes through installDist. Add tests for proposed 0/1/2 contract before changing parser/UX.

## WU-LPR-020 — Generic body carrier proof

Prove current BodyRef/BlockStepNode can represent `None/Single/Named` without plugin-specific node. Add structural tests; no engine migration yet.

## WU-LPR-021 — BodyExecutionEngine Sequential/Scoped

Extract pure policy decision and interpreter. Migrate one Scoped family first. Golden event/journal parity required.

## WU-LPR-022 — Retry/timeout migration

Move generic durable control execution behind engine. Kill/resume/replay divergence tests mandatory.

## WU-LPR-023 — Parallel migration

Named bodies + BranchInvoker, composable siblings, no coordinator-specific branch.

## WU-LPR-024 — InvocationEngine seam

Extract metadata/fingerprint/journal/replay/capability boundary while preserving coordinator lifecycle.

## WU-LPR-030 — DSL marker/file responsibility

Pure structural change with compiler corpus parity.

## WU-LPR-031 — Runtime value honesty

Migrate `pwd/isUnix` supported usage to real runtime context. Remove stable fake fallbacks; fail-closed unsupported declarative value use.

## WU-LPR-032 — Unsupported DSL admission

Audit `when/post/load/waitUntil/node` surface. For Gate-1, implement or reject explicitly.

## WU-LPR-040 — Observation benchmark harness

Create high-output/slow-consumer/restart harness before performance refactor.

## WU-LPR-041 — SQLite single writer

Persistent writer, batch transactions, durable sequence resume, flush barrier. Compare before/after.

## WU-LPR-042 — Console hot path

Streaming redactor + buffered transcript; eliminate per-chunk renderer coupling/runBlocking where safe. 200 MiB + 1 GiB stress.

## WU-LPR-043 — Shell output event de-dup

Add transcript observation parity then stop storing high-volume process output in semantic event payload.

## WU-LPR-050 — CLI views/formats

Implement normal/events/full/console/quiet and text/jsonl/json as read-side projection.

## WU-LPR-051 — Inspect/filter/cursor

Agent-efficient filters, fields, tail/context/follow; no query language.

## WU-LPR-060 — Certification ledger

Generate source of truth from registry/tests/evidence; generated Markdown replaces manually drifting counts.

## WU-LPR-061..063 — Real projects

Gradle, Maven, Node installed-distribution fixtures; success and meaningful failure each.

## WU-LPR-070 — Distribution

`pipelinek` application name, distZip, version, checksum, SBOM, reproducibility.

## WU-LPR-071 — Release workflow

V2 GitHub Release workflow + release smoke. Keep V1 quarantine explicit.

## WU-LPR-080 — SDKMAN

Onboarding + publish + clean install smoke.

## WU-LPR-090 — Dogfood round 1

Adopt in real repos, collect defects, no large new plugin family until review.
