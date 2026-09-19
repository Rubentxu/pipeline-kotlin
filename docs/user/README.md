# PipelineK — User documentation

This directory is the user-facing reference for **PipelineK**, a local-first
CI/CD engine with a Jenkins-familiar Kotlin DSL. The contents here are
aligned with each release and verified against the published binary.

## Index

- [`installation.md`](installation.md) — install on Linux/macOS/Windows (WSL); Java 21 prerequisite; SDKMAN install; GitHub Releases fallback
- [`quickstart.md`](quickstart.md) — your first pipeline, end to end
- [`cli-reference.md`](cli-reference.md) — verified against `pipelinek --help` on the installed binary; flags and exit codes
- [`pipeline-dsl.md`](pipeline-dsl.md) — the Kotlin DSL surface verified against the certified `local-core-v1` ledger
- [`configuration-and-workspace.md`](configuration-and-workspace.md) — `--workspace`, `--db`, `--control-root`, durable state
- [`credentials-and-security.md`](credentials-and-security.md) — secret redaction at the durable shell seam
- [`events-and-troubleshooting.md`](events-and-troubleshooting.md) — where to find transcripts, recover durable runs, inspect events without leaking
- [`upgrading.md`](upgrading.md) — SDKMAN upgrade, rollback, version selection
- [`cheat-sheet.md`](cheat-sheet.md) — short, copyable, UAT-tested

## Authority

Each page references which release it was verified against (commit SHA +
ZIP SHA). When the published binary changes, the documentation is
re-verified and updated in the same release cycle.

## Stability note

Only commands, flags, and exit codes observed against a published binary
are documented as stable. Anything outside the certified contract is
explicitly marked **experimental** or left out.
