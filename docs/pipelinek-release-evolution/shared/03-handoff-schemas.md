# Handoff Schemas v2

## 1. Candidate handoff

Producido por `pipeline-kotlin`.

```json
{
  "schema_version": "pipelinek-candidate-2",
  "candidate_id": "sha256:<full-64-hex>",
  "release_train": "0.44.0",
  "candidate_sequence": 3,
  "product_version": "0.44.0",
  "source_commit": "<git-sha>",
  "artifact": {
    "name": "pipelinek-0.44.0.zip",
    "sha256": "<sha>",
    "size": 12345678,
    "archive_root": "pipelinek-0.44.0"
  },
  "distribution_manifest": {
    "name": "distribution-manifest.json",
    "sha256": "<sha>"
  },
  "sbom": {
    "name": "pipelinek-0.44.0.sbom.json",
    "sha256": "<sha>"
  },
  "built_at": "<iso8601>"
}
```

## 2. Candidate states in harness

```text
RECEIVED
ELIGIBLE
ACTIVE
SUPERSEDED
BLOCKED_ENV
CERTIFICATION_FAILED
CERTIFIED
PROMOTED
```

## 3. Certification result

Producido por el harness.

```json
{
  "schema_version": "pipelinek-certification-result-2",
  "result_id": "<id>",
  "candidate_id": "sha256:<sha>",
  "release_train": "0.44.0",
  "product_version": "0.44.0",
  "artifact_sha256": "<sha>",
  "decision": "CERTIFIED",
  "harness_commit": "<sha>",
  "started_at": "<iso8601>",
  "completed_at": "<iso8601>",
  "runs": [],
  "identity": {
    "expected": "0.44.0",
    "archive_root": "0.44.0",
    "embedded": "0.44.0",
    "runtime": "0.44.0",
    "match": true
  },
  "signature": "sha256:<signature>"
}
```

## 4. Promotion receipt

Producido por el harness si mantiene la automatización de stable promotion.

```json
{
  "schema_version": "pipelinek-promotion-2",
  "candidate_id": "sha256:<sha>",
  "certification_result_id": "<id>",
  "product_version": "0.44.0",
  "stable_tag": "v0.44.0",
  "artifact_sha256": "<same-certified-sha>",
  "outcome": "PROMOTED",
  "published_at": "<iso8601>"
}
```

## 5. Invariantes

- `candidate_id` nunca cambia.
- `artifact_sha256` de handoff, verdict y promotion debe coincidir.
- `product_version` no se reescribe downstream.
- una candidata `SUPERSEDED` no puede producir `PROMOTED`.
- `CERTIFIED` sobre SHA A no habilita SHA B.
- cualquier mismatch de identidad produce `CERTIFICATION_FAILED` o `INVALID_CANDIDATE`, nunca warning.
