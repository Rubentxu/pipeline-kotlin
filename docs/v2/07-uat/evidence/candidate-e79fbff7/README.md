# Local candidate evidence (e79fbff7)

Built in the worktree `worktree/test-harness-brave-harbor-6920` at commit
`e79fbff72…` (clean tree) with:

```text
cd v2 && ./gradlew :pipeline-application:distZip :pipeline-application:cyclonedxBom
cd v2 && ./gradlew :pipeline-release:candidateAdmission -Pcandidate.tag=v0.61.0-rc-local
python3 scripts/emit-candidate-receipt.py --zip … --manifest … --handoff … --sbom … \
    --expect-sha "$(git rev-parse HEAD)" --out candidate-receipt.json
```

`candidate-receipt.json` records the measured identity: ZIP sha256
`1b1a2c01715c92c5125e727f311c63b77f2fd6d29c05ecef99178660766860b7`, 92 985 677 bytes,
archive root `pipelinek-0.61.0`, embedded `Implementation-Version` 0.61.0, product
identity law satisfied as exact tokens, `source_commit` = authoring HEAD.

The ZIP itself is NOT committed (92 MB build output); its digest is bound here and
in `zip.sha256`. This candidate was admitted by `candidateAdmission` (exit 0) and
has NOT been handed to the external `pipelinek-release-harness`: that handoff is an
operator action.
