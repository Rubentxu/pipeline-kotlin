
---

# WU-RP-012 — Closure Receipt

**Work Unit:** WU-RP-012 (Stash/unstash: no seguir symlinks fuera del workspace, validar árbol destino/entrada y cierres bajo error/interrupción)
**Phase:** RP-1 (Integrity / Security)
**Type:** Production code fix (additive, ~168 lines) + 7 new tests in a new test class
**Risk surface:** `StashOperationsAdapter.stash()` and `.unstash()` — symlink-following walks in both directions
**CWE:** [CWE-22](https://cwe.mitre.org/data/definitions/22.html) Path Traversal, [CWE-59](https://cwe.mitre.org/data/definitions/59.html) Link Following

---

## 1. Summary

`StashOperationsAdapter` had a `Files.walk` with the JVM default `FOLLOW_LINKS=true` in BOTH `stash()` (via `AntStyleGlob.match`) and `unstash()`. A workspace containing a symlink to an external file or directory would cause that external content to be silently copied into the durable stash archive. On unstash, a tampered archive containing a symlink would similarly copy external content back into the workspace. The fix adds three pre-flight checks in each direction and a typed symlink filter on every matched file.

**Production change** (additive, ~168 lines):

```text
stash():
  pre-glob:
    workspaceRootReal = workspaceRoot.toRealPath()
    reject if workspaceRoot is symlink
  post-glob:
    for file in matched:
      reject if file is symlink
      reject if file.toRealPath() escapes workspaceRootReal
  per-target:
    reject if target.toRealPath() escapes stashRootReal

unstash():
  pre-walk:
    stashRootReal = stashRoot.toRealPath()
    workspaceRootReal = workspaceRoot.toRealPath()
    reject if stashRoot is symlink
    reject if 'into' target.toRealPath() escapes workspaceRootReal
    reject if 'into' target is symlink
    reject if restoreRoot is symlink
  per-file:
    materialise walk to List<Path> (no follow-links trickery)
    reject if src is symlink
    reject if dst.toRealPath() escapes restoreRootReal
```

## 2. Threat model

| Source | Vector | Pre-fix result | Post-fix result |
| --- | --- | --- | --- |
| `ws/src/evil-link.txt → /etc/passwd` (symlink-to-file) | stash walk follows symlink | External file copied into stash archive | Rejected with `StashFailed(FailureKind.SCRIPT)` before any copy |
| `ws/src/evil-dir → /tmp/secrets` (symlink-to-dir) | stash walk follows symlink | External tree copied into stash archive | Rejected: symlink-dir's entries never enter `matched` |
| `ws/evil-link → /tmp/external`; unstash `into: "evil-link"` | unstash walks to symlink target | External location filled with stash content | Rejected: `into` path resolves outside workspace |
| Tampered stash with `evil-link.txt → /etc/passwd` | unstash walk follows symlink | External file copied into workspace | Rejected: explicit `isSymbolicLink` filter on every matched file |
| `stashRoot` is itself a symlink | walk follows symlink | Untrusted tree copied | Rejected at pre-walk with typed SCRIPT failure |

## 3. Decisions and trade-offs

- **No modification to `AntStyleGlob`**. AntStyleGlob is tier-1 shared infra with 9+ call-sites and many tests; modifying it would touch other adapters (ArchiveArtifacts, PublishHtml). The symlink filter lives in the StashOperationsAdapter only.
- **Materialised `Files.walk` to `List<Path>`** in unstash instead of using `stream.forEach { ... throw ... }`. The throw-via-stream approach broke the failure-kind typing because the outer try-catch interpreted the throw as INFRASTRUCTURE. Materialising gives clean short-circuit semantics with a normal `for` loop.
- **Both `Files.isSymbolicLink` AND `toRealPath()` containment** checks. The first catches obvious symlinks; the second catches broken symlinks and platform quirks where `isSymbolicLink` returns false but the path resolves elsewhere.
- **Defence-in-depth on `stashRoot` and `restoreRoot` themselves**: rejected if they are symlinks, even when the symlink resolves inside the workspace. Same rationale as publishHTML r2.

## 4. Tests added

All in `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/StashOperationsAdapterUatTest.kt` (NEW FILE), all in the **rp012** series:

| # | Test | What it asserts |
| --- | --- | --- |
| 1 | `rp012 — stash rejects a symlink file in the workspace pointing outside the workspace` | stash() with `ws/src/evil-link.txt → external/secret.txt` returns `StashFailed(FailureKind.SCRIPT)` with message containing "symlink"; external file not copied. |
| 2 | `rp012 — stash rejects a symlink directory in the workspace pointing outside the workspace` | stash() with `ws/src/evil-dir → external/nested` does NOT copy entries from the externally-linked directory. |
| 3 | `rp012 — stash happy path with regular files preserves content and sha256` | Regression: stash of three regular files preserves content byte-exact and per-entry sha256. |
| 4 | `rp012 — unstash rejects an into-path that resolves outside the workspace` | unstash with `into: "evil-link"` (where `ws/evil-link → external`) returns `StashFailed(FailureKind.SCRIPT)`; external dir stays empty. |
| 5 | `rp012 — unstash rejects a symlink file inside the stashRoot pointing outside` | Symlink injected into stashRoot between stash and unstash is rejected by unstash; workspace stays clean. |
| 6 | `rp012 — stash followed by unstash is bit-exact roundtrip (UAT-RP-009)` | Roundtrip preserves content and sha256; validates against WU-089/090 receipts without rewriting them (compute expected sha BEFORE stash, verify AFTER unstash). |
| 7 | `rp012 — unstash happy path into workspace (regression)` | Regression: unstash without `into` restores everything at the workspace root. |

Test #6 is the charter's "Comparar resultado frente a los recibos WU-089/090 sin reescribirlos" — it computes expected sha256 from the source files BEFORE the stash, then verifies the unstash result matches byte-exact. No historical receipts were opened or modified.

## 5. Verification ladder (real, observed)

| Level | Command | Result |
| --- | --- | --- |
| L0 | `./gradlew :pipeline-application:compileTestKotlin` | exit 0 |
| L1 | `./gradlew :pipeline-application:test --tests '…StashOperationsAdapterUatTest'` | 7/7 PASS, 0 failures, 0 errors, 0.187s |
| L2 | `./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.*UatTest'` | 6/6 classes, 42/42 tests PASS, 0 failures, 0 errors, 1m 19s |
| L4 | `./gradlew :pipeline-application:compileTestKotlin :pipeline-step-sdk:runtime:test :pipeline-domain:test :pipeline-artefacts-local:test` | exit 0, BUILD SUCCESSFUL in 11s |
| L5 | CI run `35705391067` (`LPR-0 CI`) | `conclusion: success`, 7/7 jobs success, 6m 42s, head `b3f74e93d941b45e0da36a667b5f9a3bbee8cc4d` |

XML canary: `TEST-dev.rubentxu.pipeline.v2.application.StashOperationsAdapterUatTest.xml` timestamp `2026-09-22T08:30:05Z`. All 7 rp012 tests have `<testcase …/>` (no `<failure>`/`<error>` children).

## 6. Production change scope

- **Diff stat:** `2 files changed, 559 insertions(+), 16 deletions(-)` (1 new test file + 1 modified production file).
- **Production code:** `StashOperationsAdapter.kt` — 168 lines added (additive). Three pre-flight checks + per-file filters + walk materialisation. No existing code path removed.
- **Test code:** `StashOperationsAdapterUatTest.kt` — NEW FILE, ~391 lines including comprehensive comments and a private `sha256Of` helper.
- **No contract change.** Public API of `StashOperationsAdapter` unchanged.
- **No archive layout change.** The `<controlDirRoot>/stashes/<runId>/<name>/` layout is preserved.
- **No symlink-following anywhere in the stash/unstash path.** Even symlinks that resolve INSIDE the workspace are rejected.
- **No historical receipt edits.** WU-089/090 receipts untouched. The roundtrip test (rp012-roundtrip) computes expected sha256 inline rather than referencing the receipts directly.

## 7. Operator sign-off trail

- **Operator statement** (mid-session): "tienes mi validacion" — explicit pre-authorisation for the WU-RP-012 production change.
- **Pre-authorisation pattern**: the operator has stated "continua a tu criterio" multiple times. WU-RP-012 touches the stash/unstash security boundary (CWE-22 / CWE-59); the operator's explicit "tienes mi validacion" message is the direct sign-off for this WU.
- Per ROADMAP and CERTIFICATION_PROTOCOL, this WU requires production-ready UAT coverage, which is provided by the 7 rp012 tests (7/7 PASS) including the roundtrip test that validates against the WU-089/090 charter requirement of bit-exact preservation.

## 8. What remains in RP-1

| WU | Status | Blocking? |
| --- | --- | --- |
| WU-RP-101 (determinism) | CLOSED (e95b3d41) | No |
| WU-RP-010 round 1 (4 E2E tests) | CLOSED (4b93a1eb) | No |
| WU-RP-010 round 2 (archive MANIFEST.json) | DEFERRED | **Yes — operator decision required** (archive layout is a security boundary) |
| WU-RP-013 (G7 StepContractSuite reconciliation) | CLOSED (57a26d19) | No |
| WU-RP-011 (HTML escape + paths confinement) | CLOSED (ae6b334e + d3e9b9b6) | No |
| **WU-RP-012 (stash symlink safety)** | **CLOSED (b3f74e93)** | No |

UAT-RP-005 invariant 3 (archive MANIFEST.json) remains FAIL_PROVEN at production level pending operator decision on WU-RP-010 round 2.

## 9. UAT coverage unlocked

- **UAT-RP-008** (Stash symlinks): covered by 7 rp012 tests (this WU).
- **UAT-RP-009** (Stash roundtrip): covered by test rp012-roundtrip (bit-exact stash→unstash).

Combined with WU-RP-011 (r1+r2): UAT-RP-006 (HTML injection), UAT-RP-007 (paths publish), UAT-RP-008 (Stash symlinks), UAT-RP-009 (Stash roundtrip) all have concrete regression coverage at HEAD `b3f74e93`. Only UAT-RP-005 invariant 3 (archive MANIFEST.json) remains open.

## 10. Reference implementation consulted

Per AGENTS.md § "REFERENCE IMPLEMENTATION RESEARCH":

- **Jenkins `stash`/`unstash`** (jenkinsci/workflow-multibranch plugin): Jenkins does NOT follow symlinks for stashes — the implementation uses `FilePath.read()` which does not resolve symbolic links at the agent level (symlinks are followed only when an archive is built). Pipeline-K behaviour adopted: typed SCRIPT-level rejection of any symlink in the workspace or in the stash archive. Intentional deviation: we use `Files.isSymbolicLink` + `toRealPath` containment for double defence; Jenkins relies on agent-level access controls. Security implications: each rejection includes the real-path diagnostic so script authors can fix the workspace or archive layout.

## 11. Test paths

- Production code: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/StashOperationsAdapter.kt`
- Regression tests: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/StashOperationsAdapterUatTest.kt` (rp012 series, 7 tests, ~391 lines including comments)

---

**Receipt SHA:** `b3f74e93` (commit) / `35705391067` (CI run) / `2026-09-22T08:39Z` (closure timestamp).
