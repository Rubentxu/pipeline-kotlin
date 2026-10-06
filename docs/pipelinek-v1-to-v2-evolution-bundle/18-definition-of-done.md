# Programme Definition of Done

The programme is complete only when every checkbox below is satisfied on the same release candidate where applicable.

## Architecture

- [ ] Shared Library and Plugin are distinct typed artifact kinds.
- [ ] One `ScriptDependencyPlan` composes compile/eval dependencies.
- [ ] Plugin runtime discovery consumes only plugin artifacts.
- [ ] Artifact admission occurs before plugin class initialization.
- [ ] Runtime contributor metadata is cross-checked against static manifest.
- [ ] One `IntrospectionService` feeds CLI/LSP/MCP projections.
- [ ] One registry preparation decision powers execution admission and planning.
- [ ] No new plugin-specific coordinator branch exists.
- [ ] No tooling-specific Step catalogue exists.

## Product

- [ ] Local and Maven Shared Libraries work in installed distribution.
- [ ] `pipelinek api` discovery is versioned and machine-readable.
- [ ] `explain`, `plan` and `why-not` return actionable stable codes.
- [ ] external plugin appears automatically in discovery/LSP.
- [ ] agent/MCP discovers/invokes through common model.
- [ ] default operation leaves no PipelineK persistence in repository.

## Security/reproducibility

- [ ] artifact bytes determine digest identity.
- [ ] rejected plugin proves zero class-initializer effect.
- [ ] delivery classification is not implicit trust.
- [ ] no fake trust states exist.
- [ ] `CacheKey.v2` invalidates on same-path byte change.
- [ ] library/plugin release identities are recorded in evidence/audit projection.

## Quality

- [ ] UAT master plan mandatory and enabled-slice cases green; disabled-slice dispositions explicit.
- [ ] AAT master plan mandatory and enabled-slice cases green; no unexplained skips.
- [ ] installed distribution tests green.
- [ ] compatibility corpus green.
- [ ] full required gate green.
- [ ] reproducibility checks green.

## Legacy retirement

- [ ] every V1 area has `MIGRATED/SUPERSEDED/REJECTED/DELETE` disposition.
- [ ] current product/build graph has zero V1 dependencies before deletion.
- [ ] legacy archaeology exported externally if desired.
- [ ] V1 source modules deleted.
- [ ] V1-only scripts/build tasks/dependencies deleted.
- [ ] V1/legacy docs removed from working tree.
- [ ] obsolete examples/backups/disabled sources removed.
- [ ] current README/docs navigation rebuilt.
- [ ] post-delete clean-clone certification green.
- [ ] automated legacy-reference scan green.

**No partial declaration:** M0–M9 green with V1 still in the working tree is "migration complete, cleanup pending", not programme DONE. Programme DONE requires M10.

## Gradle-informed compiler gates

- [ ] Canonical scripting/S6 contracts reconciled with current consumers; no third engine/manifest authority.
- [ ] Effective compiler profile owns configuration and complete v2 identity, including ordered transitive bytes/JDK/API roots.
- [ ] Compile/load/evaluate/execute phases preserve both frontend contracts, construction gates and source mapping.
- [ ] Standard-reader compile/mapping succeeds under Unsafe deny in the required fresh-JVM probe; source warnings/errors remain real.
- [ ] Immutable admitted bytes are those actually compiled/loaded; current admission remains effective on hits.
- [ ] Memory, persistence and session decisions each have measured GO or NO-GO receipts.
- [ ] Every enabled reuse mode passes its applicable real artifact/concurrency/cancellation/integrity tests.
- [ ] No evaluated context, secret, Step output or recovery decision is retained in a compilation entry.
- [ ] Memory-only mode is never advertised as independent-CLI persistence; warm sessions have an existing owned lifecycle.
- [ ] Benchmarks record actual compiler calls, phase durations and approved budgets without invented SLOs.

Required correctness rows need PASS on the final candidate. Optional declined modes use NOT_APPLICABLE with a recorded NO-GO and disabled product surface, never PASS or an unexplained skipped test. The document refinement itself has no implementation/UAT certification. M10 sequencing and every original completion gate remain unchanged.
