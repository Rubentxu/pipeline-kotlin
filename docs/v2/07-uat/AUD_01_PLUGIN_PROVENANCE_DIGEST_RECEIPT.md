# AUD-01 — receipt: reproducible plugin provenance digest

**Base SHA:** `6bbee4c6`
**Branch:** `s6-plugin-sdk`
**Date:** 2026-10-08
**Status:** IMPLEMENTED, gate green on the exact tree below. Not yet certified as a release candidate.

## What AUD-01 claimed

The plugin provenance digest was computed with

```bash
sh -c "sha256sum <ABSOLUTE PATHS> | sha256sum | awk '{print $1}'"
```

Two independent defects, both confirmed by measurement before any edit:

1. **Path-dependence.** `sha256sum` PRINTS THE FILENAME IT WAS GIVEN. The absolute path of the
   checkout therefore entered the digest, so identical bytes hashed differently in two
   directories:

   ```text
   absolute paths   checkoutA 6d938320...  checkoutB c8b614a5...   <- differs
   relative paths   checkoutA 666f3d32...  checkoutB 666f3d32...   <- identical
   ```

   A receipt that binds a candidate to a SHA cannot prove anything about an artefact whose
   identity depends on where it was checked out.

2. **Unquoted expansion.** `$all` was interpolated into a shell string with no quoting. Any path
   containing a space or a shell metacharacter broke the command.

## The change

All three plugin modules (`http`, `scm-git`, `utilities`) had the same block, character for
character. Each now:

- hashes `(normalised relative path, SHA-256 of content)` pairs with `MessageDigest`, sorted by
  relative path;
- runs as a plain `DefaultTask` `doLast` instead of `Exec`, so no shell, nothing to quote,
  nothing to inject;
- drops the `.digest` side file that `doFirst` wrote and `doLast` read and deleted — mutable state
  between two hooks with no purpose once the shell is gone;
- keeps an EXPLICIT exclusion of the provenance file and of any leftover `.digest` file.

### The exclusion is load-bearing, and I broke it first

My first implementation asserted in a code comment that the provenance file "is excluded by
construction". That was false, and the runs proved it: three consecutive executions of identical
inputs produced `655d0f4c` -> `5970027b` -> `b1935fc5`, because the previous run's own output sits
under `resources/main` and was hashed into the next digest. A leftover `http-release.properties.digest`
from the pre-AUD-01 shell pipeline was also being hashed, so the digest depended on stale state
from earlier checkouts.

`HEAD` had `excludedOutput` three times per module; the `relativize` rewrite dropped it. Restored,
with the comparison done on the RELATIVE path instead of the absolute one.

After the fix, four consecutive executions of all three modules:

```text
run1 http 20c8ef22…  scm-git d4eb8dc6…  utilities 6c035f25…
run2 http 20c8ef22…  scm-git d4eb8dc6…  utilities 6c035f25…
run3 http 20c8ef22…  scm-git d4eb8dc6…  utilities 6c035f25…
run4 http 20c8ef22…  scm-git d4eb8dc6…  utilities 6c035f25…
```

Path-independence, two full copies of the module tree at different paths, byte-identical:

```text
ruta A  796b23d4c17dbe11a68a5849a3367e1f5adf9a1da548563eace7cbe5b9ecb4d0
ruta B  796b23d4c17dbe11a68a5849a3367e1f5adf9a1da548563eace7cbe5b9ecb4d0
```

## Why there are two tests, and why the first one was not enough

`HttpProvenanceDigestPathIndependenceTest` (4 tests) proves the two FORMULATIONS differ. That is
true and it is NOT sufficient: the digest is computed inside a Gradle `doLast`, which is not on the
module's test classpath. With the build script mutated back to `${file.absolutePath}`, the sibling
test still reported `tests="4" failures="0"`. It stayed green while the defect was back in the build.

`HttpProvenanceDigestSourceLawTest` reads the three build scripts and refuses the offending shape.
It exists because a unit test of the formula cannot observe which formula the TASK uses.

### Two ways this law lied before it worked

Both were measured, both are now in the test's KDoc:

**1. Empty subject.** `System.getProperty("user.dir")` does not resolve to the module directory
under Gradle, so the sibling lookup produced three non-existent paths, the `filter` dropped all
three, and the law passed over ZERO files. A green with no subject is the worst shape a fitness
test can take. Fixed by walking up to the SDK root and by `require`ing a non-empty subject.

**2. FROM-CACHE.** With `classesDir.absolutePath` reintroduced, deleting the result XML and
re-running produced `Task :…:test FROM-CACHE` — Gradle restored the cached PASS from the previous,
correct tree, and printed BUILD SUCCESSFUL. TWO consecutive "GREEN" verdicts in this slice were
that artefact and nothing else. Deleting the XML is not enough because the cache lives above the
module. Every verdict below was taken with `--rerun-tasks` and the task line read.

**3. Literal tokens.** The first token set was `it.absolutePath` / `file.absolutePath`, and the
mutation `listOf(classesDir to classesDir.absolutePath)` SURVIVED it — same defect, different
receiver. A law defeated only by typing its tokens verbatim is a grep. The set now names the
operation (`.absolutePath`, `.getAbsolutePath`, `.toAbsolutePath`, `canonicalize`), and comments
are stripped before matching so the explanatory prose cannot mask or trigger it.

### Mutation ledger

| Mutation | Expected | Observed |
|---|---|---|
| `utilities` back to absolute prefix | RED | RED, `utilities -> .absolutePath` |
| subject empty (wrong `user.dir`) | RED | RED via `require`, would have been green |
| `sha256sum $all` restored | RED | RED (initial run, before the fix) |
| none (this tree) | GREEN | GREEN, `tests=1 failures=0 errors=0`, `Task :…:test` not FROM-CACHE |

## Verification executed

```text
cd v2 && ./gradlew :pipeline-step-sdk:http:check \
                       :pipeline-step-sdk:scm-git:check \
                       :pipeline-step-sdk:utilities:check
exit=0, BUILD SUCCESSFUL in 16s, detekt clean
```

Both AUD-01 tests together, `--rerun-tasks`:

```text
AUD-01 — el digest de procedencia no depende de la ruta del checkout   tests=4 fail=0 err=0
AUD-01 — ningún digest de procedencia de plugin se calcula sobre rutas absolutas  tests=1 fail=0 err=0
```

The three digest tasks were executed with their outputs deleted first (canary), not read
`UP-TO-DATE`:

```text
scm-git: provenance written … (digest=sha256:d4eb8dc6…)
http:    provenance written … (digest=sha256:20c8ef22…)
utilities: provenance written … (digest=sha256:6c035f25…)
```

## Known limits, stated rather than hidden

- **CORRECTION (B0 follow-up, 2026-10-08).** The idempotence claim in this receipt was stated
  more strongly than the code supported. It was measured to hold across four consecutive runs, but
  only because the plugin manifest happened to be byte-identical each time. The manifest carries
  `releaseDigest` and IS part of the hashed tree, so the computation was a fixed-point loop waiting
  for a change. Appending one byte to `plugin-manifest.json` moved utilities `6c035f25` ->
  `5efa299f`, and restoring that byte did NOT return it to `6c035f25`, because the manifest had
  already been regenerated into the tree. The S6/C comment had stated the correct convention
  ("the digest covers the artifact content EXCLUDING this document") and AUD-01 implemented only
  half of it. Fixed in the follow-up commit, with the exclusion enforced by a fitness law of its
  own; the mutation that removes it produces RED naming the module.
- `HttpProvenanceDigestSourceLawTest` pins SOURCE, not behaviour. A build script that became
  path-dependent without containing any of the tokens would evade it. A functional Gradle test
  (TestKit, or asserting the emitted digest across two checkouts) would close that gap; not done.
- The two-copies path-independence measurement used a standalone script that re-implements the
  manifest walk, so the two copies could be hashed in place. Per HARNESS FIDELITY §1 that is a
  `model` harness: it characterises the formulation, it does not certify the Gradle task. The
  idempotence evidence above DOES come from the real tasks.
- The digest values themselves changed with this commit (they must: the algorithm changed), and
again with the B0 follow-up (excluding the manifest changes what is hashed). Any receipt, manifest
or candidate that names the previous http/scm-git/utilities digests is stale and must be
re-derived from this tree. Not audited here.
- Only the three `pipeline-step-sdk` plugins were in scope. If other modules compute provenance
  digests, they were not audited.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable — the defect is in this repository's own
                                     build scripts, not in a copied design.
Behaviour adopted:                digest over sorted (relative path, content-hash) pairs.
Intentional deviations:           three identical copies of one algorithm remain one copy per
                                  build script; no shared Gradle convention was extracted, because
                                  the fitness law pins the per-file shape and extracting it would
                                  require changing every module's task graph in this WU.
Security implications reviewed:   shell interpolation with unquoted expansion removed; no shell
                                  remains on the digest path.
Tests demonstrating the contract: HttpProvenanceDigestPathIndependenceTest,
                                  HttpProvenanceDigestSourceLawTest.
```
