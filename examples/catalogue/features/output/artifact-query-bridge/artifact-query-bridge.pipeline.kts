// E2E archive->query cross-step data path.
// Pipeline shape (real binary, real coordinator, real process side-effects):
//   stage("seed-then-archive"):
//     sh seeds a tree at <stage-workspace>/build/utils/mix/...
//     archiveArtifacts archives the tree AND records the handle under
//       Name "mix" into the per-run ArtifactIndexCapability
//   stage("query-by-name"):
//     artifactQuery("mix") looks it up via the registered Step. Because
//     Wires the artifact index into the runtime context, the query
//     Sees the handle and resolves it. Before, this fixture was RED
//     (registry boundary failed closed because ARTIFACT_INDEX_CAPABILITY
//     was not admitted).
// seed and archive must live in the SAME stage: per the WorkspaceResolver,
// each stage has its own workspace directory, so the archive glob has to
// run against the same workspace the seed wrote into. Splitting them
// across stages makes the glob return 0 matches and the artifact handle
// records an empty file set — still a valid archive (with empty files),
// but the integration evidence is weaker than the same-stage layout.
// No fabricated runtime values, no DSL-time construction of state.
pipeline {
    stages {
        stage("seed-then-archive") {
            sh("mkdir -p build/utils/mix && echo 'alpha' > build/utils/mix/a.txt && echo 'beta' > build/utils/mix/b.txt")
            archiveArtifacts(
                artifacts = "build/utils/mix/**",
                // `allowEmptyArchive = true` means an archive step that matched
                // nothing still succeeds, which is precisely the failure the
                // comment above describes as "weaker evidence". The seeds are
                // asserted here so that a glob which stopped matching turns the
                // scenario red instead of quietly archiving an empty file set.
                allowEmptyArchive = true,
                name = "mix",
            )
            sh("test -s build/utils/mix/a.txt && test -s build/utils/mix/b.txt")
        }
        stage("query-by-name") {
            artifactQuery("mix")
        }
    }
}
