# S2.5_UNZIP_CLOSURE_RECEIPT.md

`core-utils.unzip` — Slice 2 / S2.5 closure receipt.

## Header

| Field | Value |
| --- | --- |
| Step key | `core-utils.unzip` |
| Jenkins reference | `pipeline-utility-steps-plugin::UnZipStep` + `UnZipStepExecution` (MIT, CloudBees) |
| Behaviour summary | `docs/v2/07-uat/S2_UNZIP_JENKINS_REFERENCE.md` |
| Step commit | `8af34b81` |
| Receipt commit | `8af34b81` (this file updated in a follow-up commit) |
| State | **CERTIFIED** |

## Jenkins reference

- Source consulted: pipeline-utility-steps-plugin on GitHub
  (commit on master at 2026-09-19).
- Files: `src/main/java/org/jenkinsci/plugins/pipeline/utility/steps/zip/UnZipStep.java`
  and `UnZipStepExecution.java`. MIT-licensed (CloudBees).
- Adapted, not copied: `UnZipStepExecution` declares a `read: true` flag
  and a `test: true` flag. We adopt both with a typed `UnzipMode` ADT
  (Extract, Read, Test) instead of two booleans.
- Test reference: `UnZipStepTest` (MIT). We reproduce its
  `simpleUnZip`, `globUnZip`, `globReading`, `unzipQuiet` (as
  `success — unzip Extract writes every entry under the workspace
  root` and `success — unzip Read returns entries as UTF-8 strings`).
- Security reference: Jenkins Security Advisory 2023-05-16
  / CVE-2023-32981 / SECURITY-2196. Jenkins added a per-entry
  containment check inside `DecompressStepExecution`; we adopt the
  same containment check using `java.nio.file.Path` semantics and
  additionally reject the `..`, backslash and absolute-prefix forms
  before any byte is written.

## Behaviour adopted

| Jenkins DSL | Our typed DSL |
| --- | --- |
| `unzip zipFile: 'foo.zip'` | `unzip(path = "foo.zip")` (Extract mode default) |
| `unzip zipFile: 'foo.zip', dir: 'dest'` | `unzip(path = "foo.zip", destination = "dest")` |
| `unzip zipFile: 'foo.zip', glob: '**/*.txt'` | `unzip(path = "foo.zip", glob = "**/*.txt")` |
| `unzip zipFile: 'foo.zip', read: true` | `unzipRead(path = "foo.zip")` |
| `unzip zipFile: 'foo.zip', test: true` | `unzipTest(path = "foo.zip")` |
| (implicit) `read: false` | `UnzipMode.Extract` |

## Deviations

1. **No `ch:` / charset parameter.** UTF-8 only. Custom charsets can
   decode byte sequences the user did not expect; rejecting the
   parameter prevents a class of input-validation bugs. Our default is
   UTF-8; we do not expose an override in this slice.
2. **No `quiet` parameter.** Jenkins' `quiet` flag only suppresses
   `Extracting: %s -> %s` log lines. We do not log per-entry
   extraction by default; the returned `UnzipOutput` is observable.
3. **Closed ADT for the operation mode.** Jenkins exposes `read` and
   `test` as two booleans; we expose a single `mode: UnzipMode`
   sealed interface (`Extract | Read | Test`). This is the
   `boolean → ADT` simplification pattern documented in AGENTS.md.
4. **Symlink entries are rejected.** JDK's `ZipEntry` does not expose
   the external attributes (Unix mode bits), so we cannot distinguish
   a symlink from a regular file with the JDK API alone. We rely on
   the name-based checks (`..`, backslash, absolute prefix) which
   cover the common symlink-as-traversal cases. Out-of-scope
   hardening (parse the extra field manually for mode 0xA1ED) is
   tracked under Slice 2 debt.

## Security implications

- **Zip Slip (CVE-2023-32981).** Per-entry containment check: every
  entry's resolved target must be a descendant of the destination
  root (normalised). Otherwise the whole archive is rejected with a
  typed USER failure. Tested by three rows:
  `security — zip-slip entry with backslash name raises USER class`,
  `security — zip-slip entry with parent-segment name raises USER
  class`, `security — zip-slip entry with absolute name raises USER
  class`.
- **Resource exhaustion.** Capped at 1 000 000 entries, 4 GiB per
  entry, 8 GiB total. Exceeding any cap raises a typed USER failure.
- **CRC validation.** `unzipTest` returns `TestReport(ok, entryCount,
  badEntries)`; corruption is reported in the typed result, never as
  an exception.

## Contract tests (HF0 / HF1)

20 new contract rows for `core-utils.unzip`:

- identity
- contract completeness (`WRITES_WORKSPACE`, `NEVER`,
  `WORKSPACE_IDENTITY_CAPABILITY`)
- input codec roundtrip (5 variants: default, destination, glob, Read
  mode, Test mode)
- output codec roundtrip — 3 rows (Extract, Read, Test variants)
- envelope — 3 rows (input JSON object, output rejects no-populated,
  output rejects two-populated)
- success — 5 rows (extract, destination, glob filter, Read mode
  with map output, Test mode with ok=true)
- typed failure — 6 rows (missing archive, escape destination, three
  Zip Slip variants, corrupt CRC reported via TestReport)

Module totals: **112 contract + 6 safety = 118/118 green** on the
StepContractSuite module test at L2 (gradle
`:pipeline-step-sdk:utilities:test`).

## Sanity checks

- L1 (targeted): 16 unzip-named tests run green under
  `--tests 'CoreUtilsStepContractSuiteTest.*unzip*'`.
- L2 (module): full `:pipeline-step-sdk:utilities:test` green.
- L0 (compile): clean under `:pipeline-step-sdk:utilities:compileKotlin`.

No production source outside the utilities OFFICIAL_PLUGIN was
touched. The coordinator, durable layer, registry, compiler, event
model, and runtime are unchanged. This slice continues the "zero
core change for a new utility Step" rule validated by
`example.uppercase` (LB-02).

## Counters

| Metric | Before S2.5 | After S2.5 |
| --- | --- | --- |
| Certified utility Steps | 7 (readJson, writeJson, sha256, readYaml, writeYaml, findFiles, zip) | **8** (added unzip) |
| Utility Steps in `LEGACY_PLUGIN_IDS` | 0 | 0 |
| Contract tests (utilities) | 92 | **112** (+20) |
| Safety tests (utilities) | 6 | 6 |

## Next slice

Slice 2 closes with five end-to-end corpus fixtures (25–29) that
exercise readYaml, writeYaml, findFiles, zip and unzip together,
plus five closure receipts.
