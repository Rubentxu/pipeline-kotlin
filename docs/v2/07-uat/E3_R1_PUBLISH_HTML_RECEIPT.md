# LFC-2E3-R1 — `core.publishHTML` (HTML REPORT PUBLICATION)

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | R1 — `publishHTML` Step with `PublishedReport` model and fail-closed guards |
| Status | GREEN — 22/22 ContractSuite; installed-CLI execution verified in R2 |
| Predecessors | T0 `106703a6`, T1 `3848955d`, T2 `e660e404`, T3 `ca14d3b8`, T4 `4be4acaf` |
| StepKey | `core.publishHTML` |
| Capability token | `testing.publish.operations` (new, third token of the coordinate) |
| Production core changes | ZERO |
| SDK changes | ZERO |
| External dependencies added | ZERO (pure JDK `java.nio.file`) |

## 1. What landed

`examples/testing-plugin/.../pipeline/testing/publish/PublishHtmlPlugin.kt`

```text
PublishHtmlError (sealed, 6 cases)          typed failure taxonomy
PublishHtmlException                        typed exception carrier
ReportPublishingOperations                  narrow capability port (one method)
PublishHtmlInput                            Jenkins-faithful typed input
MissingReportPolicy (sealed, 2 cases)       closed lowering of `allowMissing`
PublishedReport (sealed, 2 cases)           typed outcome
DefaultReportPublishingOperations           pure-JDK, fail-closed implementation
PublishHtmlStepDefinition                   KEY = core.publishHTML
StageScope.publishHTML(...)                 declarative DSL facade
```

Registered through the SAME `TestingContributor` and the SAME plugin coordinate: a new family
joins the existing JAR rather than requiring its own. The capability is supplied by
`TestingCapabilityContributor`, so the Step is admitted through the installed CLI exactly as it is
in-process (the seam closed in T4).

## 2. Why no `ReportStore` abstraction

The cycle directive forbids a `ReportStore` / `QualityPlatform` / `ResultStore` mega-abstraction
before a third reporting family demonstrates the need. R1 therefore publishes through ONE narrow
capability port, and the absence is enforced mechanically:

```text
no ReportStore mega-abstraction exists in the plugin
```

The guard matches **declarations** (`interface|class|object|typealias ReportStore`) rather than bare
prose, so documentation explaining why the abstraction is absent does not trip it — but declaring
one does. (The first version of the guard matched prose and immediately failed on its own KDoc; that
was corrected rather than relaxed.)

## 3. Jenkins familiarity, and what is deliberately absent

The Step mirrors the Jenkins `publishHTML` surface for the parameters whose semantics this runtime
can honour faithfully:

| Jenkins parameter | Modelled | Notes |
| --- | --- | --- |
| `reportName` | yes | label carried into `PublishedReport` |
| `reportDir` | yes | source directory |
| `reportFiles` | yes | entry document, relative to `reportDir` |
| `allowMissing` | yes | lowered to the closed `MissingReportPolicy` before any decision |
| `keepAll` | **no** | defined in terms of Jenkins' build-history store, which V2 does not have |
| `alwaysLinkToLastBuild` | **no** | same reason |

`keepAll` and `alwaysLinkToLastBuild` are absent from the typed input rather than accepted and
ignored. Silently degrading an unsupported parameter to a no-op is exactly the failure mode
AGENTS.md fails closed against; omitting them means a pipeline using them is rejected at
construction time instead of appearing to work.

`allowMissing` is the one Boolean in the surface, and it exists because it is the Jenkins wire
shape. It is lowered immediately:

```kotlin
MissingReportPolicy.of(allowMissing) ->
    Fail      (allowMissing = false)   // a missing report directory fails the Step
    Tolerate  (allowMissing = true)    // a missing report directory is a typed Missing outcome
```

The handler never branches on a bare Boolean; the closed ADT carries the decision.

## 4. Fail-closed security posture

Publishing copies files OUT of a caller-influenced directory into a destination, which is a
path-traversal surface. Every guard below is exercised by a dedicated test:

| Threat | Guard | Typed outcome |
| --- | --- | --- |
| `../` traversal in the entry | containment checked on the NORMALISED resolved path | `EntryEscapesReportDir` |
| absolute entry path | rejected outright before resolution | `EntryEscapesReportDir` |
| symlinked report directory | rejected, because the whole containment argument rests on the real path | `SymlinkRejected` |
| symlink entry resolving OUTSIDE | contained check catches it first | `EntryEscapesReportDir` |
| symlink entry resolving INSIDE | explicit link guard | `SymlinkRejected` |
| link anywhere in the tree | the whole publication aborts | `SymlinkRejected` |
| entry is a directory / not a regular file | rejected | `EntryNotRegularFile` |
| report directory absent (`allowMissing=false`) | rejected | `ReportDirMissing` |
| I/O failure | wrapped | `IoFailure` |

Two properties beyond the guards themselves:

- **No partial publication.** The entry document and the whole publishable tree are validated
  BEFORE any copy. A rejected tree leaves no half-written destination — asserted explicitly
  (`assertFalse(Files.exists(target.resolve("index.html")))` after a rejected run).
- **Deterministic output.** `publishedFiles` is a sorted, relative, platform-independent list, so
  the same input tree always yields the same typed result and the same durable output.

The symlink classification is worth stating precisely because it is a real design choice: a link
leaving the report directory is reported as an **escape** (the stronger, more actionable statement),
while a link staying inside is reported as **a symlink**. Both are rejections; the tests assert the
exact case per scenario rather than accepting "some rejection", so a regression in classification is
caught.

## 5. Effect and replay

```kotlin
effects = listOf(Effect.WRITES_WORKSPACE)
replayPolicy = ReplayPolicy.MEMOIZED
```

Identical to the established `core.archiveArtifacts` precedent (`CoreArchiveArtifactsStep.kt:166-167`),
which is the closest analogue: a Step that writes into the workspace.

## 6. Test evidence

`TestingPublishHtmlStepContractSuiteTest` — 22 tests, 0 failures, 0 errors.

| Group | Rows |
| --- | --- |
| identity | key + coordinate; both testing families coexist in one registry (19 keys) |
| contract completeness | writer effect + memoized replay + only the publisher port; publisher port distinct from the reader port |
| abstraction guard | no `ReportStore` / `QualityPlatform` / `ResultStore` declaration anywhere in either plugin |
| codecs | input round-trip; `allowMissing` → closed policy ADT |
| capability admission | Ready with the port; **Rejected** without it |
| handler semantics | happy path publishes entry + both assets deterministically with non-zero bytes; missing dir fails or tolerates per policy; missing entry fails |
| security | traversal, absolute path, symlinked dir, symlink-outside, symlink-inside, tree symlink with no partial publication, directory-as-entry, closed 6-case error ADT |
| real DSL | end-to-end run through the canonical durable spine, destination verified on disk |

Full regression across the plugin suites after this slice: **208 tests / 13 suites, 0 failures,
0 errors** (the new 22 plus `TestingJunit` 20, `ExternalStepCapabilityContribution` 6,
`Lfc2E2ExpansionGateFitness` 22, `Lfc2E2PrepFitness` 10, `Uppercase` 14, and the six utilities
suites totalling 114).

Adding a Step moves the discovered-key count 18 → 19. Seven existing assertions were **updated, not
weakened**, and the arithmetic is recorded so the next family updates it deliberately:

```text
16 utilities + 1 example.uppercase + core.junit + core.publishHTML = 19
```

## 7. Counter rollup (R1)

| Indicator | Before R1 | After R1 |
| --- | --- | --- |
| Steps in the `pipeline.testing` coordinate | 1 | 2 |
| Registered StepKeys | 18 | 19 |
| Testing capability tokens | 1 | 2 |
| Reporting mega-abstractions | 0 | **0** (mechanically guarded) |
| Path-traversal / symlink guard tests | 0 | 7 |
| Production core changes | 0 | 0 |
| SDK changes | 0 | 0 |

## 8. Files added / changed

```text
examples/testing-plugin/.../pipeline/testing/publish/PublishHtmlPlugin.kt        (new)
examples/testing-plugin/.../pipeline/testing/TestingContributor.kt               (register + third token)
examples/testing-plugin/.../pipeline/testing/TestingCapabilityContributor.kt     (supply the port)
v2/pipeline-application/src/test/.../TestingPublishHtmlStepContractSuiteTest.kt  (new, 22 rows)
v2/pipeline-application/src/test/.../TestingJunitStepContractSuiteTest.kt        (count 18→19; token set)
v2/pipeline-application/src/test/.../Utilities{Archive,Checksums,Filesystem,Properties,Tar,Yaml}…  (count 18→19)
```

## 9. Known limitations / next slice

- **No value piping between Steps.** A Step cannot consume another Step's typed output as input, so
  the linkage fixture points `publishHTML` at the HTML directory directly rather than handing it the
  parsed `TestReport`. That is a separate generic capability and is NOT invented here (see R2 §4).
- Publishing has no retention/history model, matching the absence of `keepAll` /
  `alwaysLinkToLastBuild`: V2 has no build-history store, so "keep all previous reports" has no
  faithful meaning yet.
- R2 delivers the junit → publishHTML linkage fixture and its CLI acceptance, which is also where
  R1's installed-distribution execution is evidenced.
