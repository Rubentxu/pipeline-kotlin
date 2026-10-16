# Recibo de ciclo BQ{N} — TEMPLATE (rellenar con observación REAL)

```yaml
cycle_id: BQn-YYYYMMDD-hash
worktree_branch: feat/bqn-name
base_sha: NOT_OBSERVED
head_sha: NOT_OBSERVED
source_tree_sha: NOT_OBSERVED
source_artifact_sha256: NOT_BUILT
value_demo_command: NOT_RUN
value_demo_result: NOT_RUN
pure_contracts:
  - id: MUST_FILL
    path: MUST_FILL
    evidence: NOT_RUN
negative_mutations:
  - id: MUST_FILL
    expected_failure: MUST_FILL
    observed_result: NOT_RUN
ephemeral_probes:
  - id: MUST_FILL
    source_path: MUST_FILL
    test_count: 0
    skipped_count: 0
    verdict: NOT_RUN
    replacement_id: NOT_PROVIDED
    replacement_evidence: NOT_PROVIDED
    deleted_before_release: false
legacy_uat_disposition:
  - id: MUST_FILL
    status: MIGRATION_PENDING
    matching_failure_fingerprint: UNKNOWN
    certifier_verdict: BLOCKED_EXTERNAL
merge:
  main_commit_sha: NOT_INTEGRATED
  remote_url: NOT_PUSHED
release:
  product_version: UNKNOWN
  tag: NOT_PUBLISHED
  url: NOT_PUBLISHED
  candidate_id: UNKNOWN
  sha256sum: UNKNOWN
  sbom: UNKNOWN
  manifest: UNKNOWN
external_certification:
  state: BLOCKED_EXTERNAL
  evidence_url: NOT_PROVIDED
final_result: NOT_RUN
```

**Regla:** si cualquier dato de certificación es desconocido, usar estado `UNKNOWN/NOT_RUN/BLOCKED`, nunca inventar PASS. Los recibos de ejecución grandes viven en artefactos/ledger externo; si hace falta referencia en Git, guardar URI/digest sin secretos.
