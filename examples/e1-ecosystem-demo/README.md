# E1.ecosystem-local-first — demo

This directory contains the canonical end-to-end demos for the
**E1.ecosystem-local-first** cycle. Each `.pipeline.kts` runs against
the installed `pipelinek` binary produced by E1.2 (T1 + T3 wire-up):

```bash
./gradlew -p v2 :pipeline-application:installDist
```

## Quick start

```bash
# 1. archive with name -> records into the per-run artifact index
./v2/pipeline-application/build/install/pipelinek/bin/pipelinek run \
    examples/e1-ecosystem-demo/01-archive-with-name.pipeline.kts \
    --db /tmp/e1-demo-1.db --control-root /tmp/e1-demo-1.cr

# 2. query by name in a fresh run -> typed NotFound (per-run index is ephemeral)
./v2/pipeline-application/build/install/pipelinek/bin/pipelinek run \
    examples/e1-ecosystem-demo/02-query-by-name.pipeline.kts \
    --db /tmp/e1-demo-2.db --control-root /tmp/e1-demo-2.cr
# expected: RunFinished outcome=failure, message: "Artifact 'e1-demo-jar' not found in index"

# 3. full bridge in a single run -> Success path
./v2/pipeline-application/build/install/pipelinek/bin/pipelinek run \
    examples/e1-ecosystem-demo/03-full-bridge.pipeline.kts \
    --db /tmp/e1-demo-3.db --control-root /tmp/e1-demo-3.cr
# expected: RunFinished outcome=success, console emits "E1_DEMO_BRIDGE_OK"
```

## What this proves

| Demonstration                                 | Step                         | Outcome |
|-----------------------------------------------|------------------------------|---------|
| archive records a typed handle under a name   | core.archiveArtifacts       | success |
| query resolves by name within the same run    | core.artifact.query         | success |
| query in a fresh run -> typed NotFound        | core.artifact.query         | failure (USER) |
| recorded handle is per-run (not durable)      | see demo 02                 | n/a     |
| bridge survives stage transitions             | see demo 03                 | success |

## Cross-references

- Compatibility corpus fixture 30 (`v2/compatibility/30-artifact-query-bridge.pipeline.kts`)
  covers the same bridge with a fixed expectation of `RunFinished outcome=success`.
- E1.1 checkpoint receipt: `docs/v2/07-uat/E1_1_CHECKPOINT_RECEIPT.md`
- E1.2 checkpoint receipt: `docs/v2/07-uat/E1_2_CHECKPOINT_RECEIPT.md`

## Reference implementation

Per AGENTS.md "Reference Implementation Research": Jenkins pipeline-step
`archiveArtifacts` (catalog §1.1 line 45) is the documented reference.
Behaviour adopted (typed SUCCESS / typed USER / typed SCRIPT), intentional
deviation (in-memory per-run index, no server-side storage), and security
implications (no deserialisation, no remote storage, capability admission
fail-closed) are documented in the E1.1 and E1.2 receipt files.
