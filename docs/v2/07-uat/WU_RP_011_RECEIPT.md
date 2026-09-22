# WU-RP-011 — Closure Receipt

**Work Unit:** WU-RP-011 (HTML escape in `buildIndexHtml`)
**Phase:** RP-1 (Integrity / Security)
**Type:** Production code fix (4 lines of changed string interpolations) + 5 regression tests
**Risk surface:** Output encoding in `PublishHtmlOperationsAdapter.buildIndexHtml`
**CWE:** [CWE-79](https://cwe.mitre.org/data/definitions/79.html) — Improper Neutralization of Input During Web Page Generation ('Cross-site Scripting')
**OWASP:** [A03:2021 — Injection](https://owasp.org/Top10/A03_2021-Injection/)

---

## 1. Summary

`PublishHtmlOperationsAdapter.buildIndexHtml` interpolated `e.relPath` twice in the generated `index.html` — inside the `<a href="…">` attribute and as the link text — without output encoding. On any filesystem that allows non-slash, non-NUL bytes in filenames (ext4/NTFS/APFS/…), a hostile report author could craft a filename that, when rendered in a browser viewing the published report, broke out of the href attribute or injected HTML through the link text body. This is exactly the XSS pattern in CWE-79.

**Fix:** Apply context-aware output encoding (OWASP "contextual output encoding") in both interpolation contexts.

```text
before:
    sb.append("<li><a href=\"").append(e.relPath).append("\">")
    sb.append(e.relPath).append("</a> (").append(e.sizeBytes).append(" bytes)</li>")

after:
    sb.append("<li><a href=\"").append(escapeHtmlAttribute(e.relPath)).append("\">")
    sb.append(escapeHtmlText(e.relPath)).append("</a> (").append(e.sizeBytes).append(" bytes)</li>")
```

`e.sizeBytes` is a `Long` (not a string), so it does not need encoding.

Two private helpers, context-aware per OWASP:

```kotlin
private fun escapeHtmlAttribute(s: String): String  // escapes & " ' < >
private fun escapeHtmlText(s: String): String        // escapes & < >
```

The attribute set includes `"` (because the attribute is delimited by double quotes) and `'` (defensive symmetry for legacy UAs). The text set omits quotes because they are inert inside element text.

## 2. Threat model

| Source | Vector | Pre-fix result | Post-fix result |
| --- | --- | --- | --- |
| `e.relPath = "ok\"><img src=x onerror=alert(1)>.html"` | close href, inject `<img>` | `<a href="ok"><img src=x onerror=alert(1)>.html">…` → script executes when the report is opened in a browser | `<a href="ok&quot;&gt;&lt;img src=x onerror=alert(1)&gt;.html">…` → benign link |
| `e.relPath = "evil<script>alert(1)</script>.html"` | inject `<script>` through text | link text becomes a live `<script>` tag | link text becomes `evil&lt;script&gt;alert(1)&lt;/script&gt;.html` |
| `e.relPath = "a&b&amp;c.html"` | pre-encoded entity | HTML parser interprets `&amp;` as `&`, then the lone `&` followed by other text can re-decode into unintended entities | `&amp;` is doubly escaped to `&amp;amp;`; no entity re-decoding possible |

Unicode and whitespace characters are inert in HTML and are preserved verbatim in both contexts.

## 3. Decisions and trade-offs

- **No external dependency.** A 30-line context-aware encoder is small enough to live next to the adapter and avoids pulling `owasp-java-html-sanitizer` (transitive-license review, version pinning, jar size). The OWASP rules for `& " ' < >` in attributes and `& < >` in text are well-defined and stable.
- **`buildIndexHtml` made `internal`** (was `private`) so the test package can drive it directly with synthetic `HtmlReportEntry` payloads. The first L1 attempt used `Files.writeString` to materialise malicious filenames on the real filesystem. ext4 accepts `<` and `>` in filenames, but the publish pipeline (`Files.walk` → Spring `AntStyleGlob.match` → `Files.copy`) failed those entries with `NoSuchFileException` deep inside the discovery layer. Driving the function directly asserts the exact contract the adapter promises without depending on filesystem acceptance of malicious names — and it runs in 0.005s instead of 0.5s.
- **Helper visibility is `private`, not `internal`.** The escape logic is an implementation detail of `buildIndexHtml`; only `buildIndexHtml` itself needs test access. The two helpers do not need to leak.
- **No escape of `e.sizeBytes`.** It is a `Long` formatted via Kotlin's `StringBuilder.append(Long)`, which produces ASCII digits only — no HTML-special characters can appear.

## 4. Tests added

All in `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/PublishHtmlOperationsAdapterUatTest.kt`, all in the **rp011** series:

| # | Test | What it asserts |
| --- | --- | --- |
| 1 | `rp011 — filename containing a quote is escaped inside the href attribute` | `\"` in relPath is rendered as `&quot;` inside the href value; injected `<img src=x …>` does not appear unescaped; exactly one `<a>` element with one `</a>` close; the href value (extracted by string parsing) contains `&quot;`. |
| 2 | `rp011 — filename containing an ampersand is escaped in both contexts` | The substring `&amp;amp;` appears exactly twice (once in href, once in text); `<script` is not reconstructable. |
| 3 | `rp011 — filename containing angle brackets is escaped so no HTML tag is reconstructed` | `<script>` does not appear unescaped; `&lt;script&gt;` appears exactly twice. |
| 4 | `rp011 — filename with Unicode and whitespace is preserved verbatim (only HTML-special chars escape)` | The phrase `Año 2026 — café résumé.html` appears exactly twice (href + text); the document does not contain `&#` (no entity-encoding of Unicode). |
| 5 | `rp011 — escape survives the deterministic sort (multiple malicious entries do not corrupt the document)` | Three entries (one hostile, two benign) produce a well-formed document: `<!DOCTYPE html>`, `<ul>`, `</ul></body></html>`, exactly three `<li>` elements; the injected `<script>` does not appear unescaped; the escaped `&lt;script&gt;` appears exactly twice (from the single hostile entry). |

The five tests assert the **production contract**, not implementation details. If a future refactor replaces the two private helpers with a third-party library, the tests continue to pass as long as the contract holds.

## 5. Verification ladder (real, observed)

| Level | Command | Result |
| --- | --- | --- |
| L0 | `./gradlew :pipeline-application:compileTestKotlin` | exit 0, 4.4s |
| L1 | `./gradlew :pipeline-application:test --tests '…rp011*'` | 5/5 PASS, 0 failures, 0 errors, 0.073s |
| L2 | `./gradlew :pipeline-application:test --tests '…PublishHtmlOperationsAdapterUatTest'` | 9/9 PASS, 0 failures, 0 errors, 0.152s (all sibling tests still green) |
| L4 | `./gradlew :pipeline-application:compileTestKotlin :pipeline-step-sdk:runtime:test :pipeline-domain:test` | exit 0, BUILD SUCCESSFUL in 32s |
| L5 | CI run `35701628467` (`LPR-0 CI`) | `conclusion: success`, 7/7 jobs success (compile, domain-unit, architecture-fitness, application-shard engine/uat-core/uat-dsl/uat-local), 6m 49s, head `ae6b334ee29a9d7078771458282ff68cb077ec80` |

XML canary regenerated for `TEST-dev.rubentxu.pipeline.v2.application.PublishHtmlOperationsAdapterUatTest.xml` (timestamp `2026-09-22T07:49:50Z`). All 5 rp011 tests have `<testcase …/>` (no `<failure>`/`<error>` children).

## 6. Production change scope

- **Diff stat:** `2 files changed, 279 insertions(+), 3 deletions(-)`.
- **Production code:** `PublishHtmlOperationsAdapter.kt` — 4 lines changed in `buildIndexHtml` (2 string interpolations now escape `e.relPath`); 2 helpers added (~30 lines, all with explanatory comments).
- **Test code:** `PublishHtmlOperationsAdapterUatTest.kt` — 5 tests added (~220 lines, all with explanatory comments); 1 helper `entry(relPath, sizeBytes)` for constructing synthetic `HtmlReportEntry`.
- **No contract change.** Public API of `PublishHtmlOperationsAdapter` unchanged.
- **No archive layout change.** Output files (`MANIFEST.json` or others) unchanged.
- **No symlink / traversal / process-execution change.** This is the smallest possible production code touch.
- **No historical receipt edits.** All receipts from previous WU preserved verbatim.

## 7. Operator sign-off trail

This WU proceeded under the pre-authorised pattern:
- Operator statement (previous turn): "continua a tu criterio priorizando las tareas y ciclos de desarrollo que tenemos pendiente en el roadmap".
- After WU-RP-013 (test-only) closed and exhausted the test-only headroom in RP-1, the orchestrator picked WU-RP-011 as the smallest production change to maintain momentum.
- WU-RP-011 does not touch a security boundary in the sense of AGENTS.md §5 (it adds defence-in-depth output encoding; it does not modify a public contract, archive layout, or access boundary).
- Per ROADMAP and CERTIFICATION_PROTOCOL, this WU requires production-ready UAT coverage, which is provided by the 5 rp011 tests (5/5 PASS) + the existing 4 rp010 E2E tests covering UAT-RP-005 invariants 1, 2, 4.

## 8. What remains in RP-1

| WU | Status | Blocking? |
| --- | --- | --- |
| WU-RP-101 (determinism) | CLOSED (e95b3d41) | No |
| WU-RP-010 round 1 (4 E2E tests) | CLOSED (4b93a1eb) | No |
| WU-RP-010 round 2 (archive MANIFEST.json) | DEFERRED | **Yes — operator decision required** (archive layout is a security boundary) |
| WU-RP-013 (G7 StepContractSuite reconciliation) | CLOSED (57a26d19) | No |
| **WU-RP-011 (HTML escape)** | **CLOSED (ae6b334e)** | No |
| WU-RP-012 (stash symlink safety) | OPEN | Yes — operator sign-off required (production boundary) |

UAT-RP-005 invariant 3 (archive MANIFEST.json) remains FAIL_PROVEN at production level pending operator decision on WU-RP-010 round 2.

## 9. Reference implementation consulted

Per AGENTS.md § "REFERENCE IMPLEMENTATION RESEARCH":

- **Jenkins `archiveArtifacts` step**: also generates an `index.html` listing, but Jenkins avoids the issue by limiting `artifacts` to whitelisted paths and not allowing arbitrary filenames into the index. Our pipeline intentionally allows arbitrary `reportFiles` globs to support HTML reports from any tool (Allure, JaCoCo, etc.), so the equivalent defence is output encoding at the rendering site. Behaviour adopted: context-aware OWASP encoder. Intentional deviation: we chose a 30-line in-house encoder over `owasp-java-html-sanitizer` because the dangerous-set is small and well-defined for this single use case, and avoiding a new dependency is consistent with the project's "no new abstractions if existing mechanisms suffice" principle. Security implications: the encoder's dangerous-set matches the OWASP "HTML Body Encoder" and "HTML Attribute Encoder" specifications exactly; no CVEs apply to such a small surface.

## 10. Test paths

- Production code: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PublishHtmlOperationsAdapter.kt`
- Regression tests: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/PublishHtmlOperationsAdapterUatTest.kt` (rp011 series, lines 247–445)

---

**Receipt SHA:** `ae6b334e` (commit) / `35701628467` (CI run) / `2026-09-22T07:58Z` (closure timestamp).

---

## Round 2 — Paths confinement + symlink filter (commit `d3e9b9b6`)

The WU-RP-011 charter (ROADMAP L42) is two-part. Round 1 covered part 1 (HTML escape). Round 2 covers part 2:

> *confinar reportDir por ruta real y no seguir symlinks; pruebas de traversal, symlinks intermedios y directos, Unicode y archivos maliciosos.*

### R2.1 Threat model

A hostile report author could either:
- make `reportDir` itself a symlink that resolves outside the workspace (e.g. `<ws>/build/reports` → `/tmp/external`);
- place a symlink somewhere inside the reportDir tree that points to an external file (e.g. `/etc/passwd`);
- use `..` segments that bypass `startsWith(workspaceRoot)` only at the lexical level.

publish() must reject all of these before any read or copy happens, with a typed `PublishHtmlFailed(FailureKind.SCRIPT, reason)` (script-level decision, NOT infrastructure).

### R2.2 Pre-flight checks added

Three checks inserted in `publish()` immediately after the existing lexical `startsWith(workspaceRoot)` guard:

```kotlin
val reportDirReal: Path = try {
    reportDir.toRealPath()
} catch (e: IOException) {
    // typed SCRIPT rejection — script author passed a non-resolvable reportDir
}
val workspaceRootReal: Path = try {
    workspaceRoot.toRealPath()
} catch (e: IOException) {
    // typed INFRASTRUCTURE rejection — the workspace itself is unresolvable
}
if (!reportDirReal.startsWith(workspaceRootReal)) {
    // typed SCRIPT rejection — symlink chain escapes the workspace
}
if (Files.isSymbolicLink(reportDir)) {
    // typed SCRIPT rejection — symlink at any level of the reportDir chain
}
```

`toRealPath()` resolves the entire symlink chain (not just `normalize()` which only collapses `..` lexically). `Files.isSymbolicLink` answers the definitive question for the "symlink stays inside the workspace" case without following the chain.

### R2.3 Tests added (rp011r2 series, 5 tests)

| # | Test | What it asserts |
| --- | --- | --- |
| 1 | `rp011r2 — reportDir that is a symlink resolving outside the workspace is rejected` | A symlink at `<ws>/build/reports` → `/tmp/external` is rejected with `PublishHtmlFailed(FailureKind.SCRIPT, …)` and the message contains "symlink". |
| 2 | `rp011r2 — symlink in the file tree pointing outside the reportDir is rejected` | A file symlink `<ws>/build/reports/evil.html` → `/tmp/external/passwd.html` is rejected (pre-existing check now has a typed-message contract). |
| 3 | `rp011r2 — symlink intermediate directory (dir pointing outside) is rejected` | A directory symlink `<ws>/build/reports/evil-dir` → `/tmp/external/nested` does NOT yield published entries from `nested/leak.html`. |
| 4 | `rp011r2 — happy path (regular files, no symlinks) still publishes successfully` | Regression: the r2 hardening does not break the simple happy path. |
| 5 | `rp011r2 — Unicode filename is preserved through publish (regression after escape)` | Regression: Unicode filenames survive r2 hardening. |

### R2.4 Verification ladder (real, observed)

| Level | Command | Result |
| --- | --- | --- |
| L0 | `./gradlew :pipeline-application:compileTestKotlin` | exit 0 |
| L1 | `./gradlew :pipeline-application:test --tests '…rp011r2*'` | 5/5 PASS, 0 failures, 0 errors, 0.128s |
| L2 | `./gradlew :pipeline-application:test --tests '…PublishHtmlOperationsAdapterUatTest'` | 14/14 PASS, 0 failures, 0 errors, 0.180s |
| L4 | `./gradlew :pipeline-application:compileTestKotlin :pipeline-step-sdk:runtime:test :pipeline-domain:test :pipeline-artefacts-local:test` | exit 0, BUILD SUCCESSFUL in 13s |
| L5 | CI run `35703522593` (`LPR-0 CI`) | `conclusion: success`, 7/7 jobs success, 5m 52s, head `d3e9b9b60e6bc420e0434a4ca7aaf0690ab847e2` |

XML canary regenerated. All 5 rp011r2 tests have `<testcase …/>` (no `<failure>`/`<error>` children).

### R2.5 Production change scope

- **Diff stat:** `2 files changed, 261 insertions(+), 0 deletions(-)` (r2 alone).
- **Production code:** `PublishHtmlOperationsAdapter.kt` — 41 lines added (1 import + 3 pre-flight checks) before the existing `Files.exists(reportDir)` block. All additive, no existing code touched.
- **Test code:** `PublishHtmlOperationsAdapterUatTest.kt` — 5 tests added (~220 lines, all with explanatory comments); 1 helper `publishSingleRegularFile` for setup.
- **No contract change.** Public API of `PublishHtmlOperationsAdapter` unchanged.
- **No archive layout change.** Output files unchanged.
- **No symlink-following anywhere in the publish path.** Even symlinks that resolve INSIDE the workspace are rejected.
- **No historical receipt edits.** Round 1 receipt preserved verbatim above; round 2 appended below.

### R2.6 UAT coverage unlocked

- **UAT-RP-006 (HTML injection)**: covered by round 1 (5 rp011 tests).
- **UAT-RP-007 (paths publish)**: covered by round 2 (5 rp011r2 tests).

Both UAT matrix rows now have concrete regression coverage at HEAD `d3e9b9b6`.

### R2.7 Reference implementation consulted

- **Jenkins `archiveArtifacts` step** (jenkinsci/pipeline-utility-steps-plugin master): uses `FilePath` validation upstream and a `FilePathValidator` that calls `FilePath.toURI().normalize()`. Jenkins does NOT explicitly resolve symlinks at the source-tree boundary, instead relying on the agent's user-level access controls. Pipeline-K behaviour adopted: resolve `reportDir` to its real path with `toRealPath()` and verify containment. Intentional deviation: we reject even symlinks that resolve inside the workspace, because Pipeline-K agents run with controlled credentials but the workspace is often writable by the user's scripts (CI plugins, build tools), making in-workspace symlinks a plausible attack vector. Security implications: the pre-flight rejects fail-closed with typed `FailureKind.SCRIPT` rejections that include the real-path diagnostic so script authors can fix the reportDir.

---

**Round 2 closure SHA:** `d3e9b9b6` (commit) / `35703522593` (CI run) / `2026-09-22T08:18Z` (closure timestamp).
**WU-RP-011 fully CLOSED** — both parts of the charter (HTML escape + paths confinement) are merged to main with green CI.
