# RP034-I — UAT instalada: multi-toolchain y self-hosting

```yaml
id: WU-RP-034
status: PASS
base_sha: c06331af94dc5c37abba235484b1a48d37e39fa8   # candidate certified by the check gate
head_sha: c06331af94dc5c37abba235484b1a48d37e39fa8
source_tree_sha: 18e95177f6df1313a1ff80bc89c59841b7a4789c
artifact: v2/pipeline-application/build/install/pipelinek   (installDist, rebuilt at this SHA)
artifact_sha256: NOT_BUILT        # dist attestation belongs to the release phase
scope: installed distribution, local-first default, no --workspace unless stated
supersedes: the EXIT-GRADLE-MAVEN-NODE row recorded NOT_RUN in
            RP034_ID_CERTIFICATION_AND_SECURITY_CORRECTION.md
```

All four scenarios run the **installed** `pipelinek` binary against real
projects on this host. Toolchains: OpenJDK 21.0.8 and 25.0.4 via asdf, Gradle
8.14.5, Maven 3.9.9, Node 25.9.0, npm 11.12.1.

| id | scenario | mode | exit | observed |
|---|---|---|---|---|
| UAT-RP034-01 | real Gradle `classes` on a Java project | default (no `--workspace`) | 0 | `build/classes/java/main/demo/App.class` produced |
| UAT-RP034-02 | real Maven `compile` on a Java project | default (no `--workspace`) | 0 | `target/classes/demo/App.class` produced |
| UAT-RP034-03 | real `npm run build` on a Node project | default (no `--workspace`) | 0 | `dist/bundle.js` produced with expected content |
| UAT-RP034-04 | self-hosting: `pipelinek` runs a pipeline that runs `pipelinek` | default (no `--workspace`) | 0 | see semantics below |

## UAT-RP034-04 — what the self-hosting scenario actually proves

The first draft of this scenario asserted that a nested run would write into
the nested script's directory. It did not, and the reason is the product
working correctly:

```text
NESTED-DEFAULT-ATTACHED-TO-INVOCATION      # pipelinek run nested/pipeline.kts -> inner.txt at the ROOT
SCRIPT-DIR-DID-NOT-DEFINE-WORKSPACE       # nested/inner.txt did NOT exist at that point
NESTED-EXPLICIT-WORKSPACE-HONOURED        # --workspace nested -> inner.txt lands in nested/
```

**The script's directory does not define the workspace. The invocation directory
does.** With no flag, `WorkspaceIntent` resolves `AttachInvocationDirectory`,
so a nested run invoked from the workspace root attaches the workspace root,
regardless of where its script file sits. Passing `--workspace nested` moves the
effects, proving the two are independent decisions rather than one inferred from
the other.

This is the distinction ADR-0100 exists to enforce, and it is now observed
end-to-end through the real binary instead of only in unit tests.

### `dir()` derives cwd and restores the root

```text
inside  dir("nested") { pwd } -> /tmp/rp034-real/selfhost/nested
after   the block             -> /tmp/rp034-real/selfhost
```

The cwd moved and was restored. The workspace root never moved, which is the
non-collapse requirement: `dir(...)` derives only the cwd and never redefines the
workspace root.

## Environment findings — NOT PipelineK defects

Two initial failures were environmental and are recorded so they are not
re-diagnosed later as product bugs:

1. **exit 126 on `gradle` / `mvn`.** The asdf shims resolve a version from a
   `.tool-versions` file in the *current working directory*. A workspace without
   one yields `No version is set for command gradle`. The fix is a project-level
   toolchain pin, which is what real repositories do.
2. **Gradle 8.14.5 with Java 25.** Gradle 8.14.5 does not support Java 25; the
   build failed until the project pinned `java temurin-21.0.8`. A toolchain
   compatibility constraint, unrelated to the workspace model.

## Coverage limits of this receipt

Proven here: the installed binary drives real Gradle, Maven and Node builds in
the local-first default, self-hosts, and keeps workspace root, cwd and
invocation origin separate.

**Not proven here:** `--isolated` multi-toolchain runs, `--workspace <path>`
compatibility for the three toolchains, nested `dir` coherence across
`pwd`/`sh`/file Steps/stash/plugins in the same run, and fresh/replay on one
DB and control root. Those remain exit-criteria work, not claims of this receipt.

**Not run:** the scenarios were executed manually against the installed
distribution and are **not** yet encoded as automated tests, so they are not
regression-protected. Encoding them is the natural next slice.