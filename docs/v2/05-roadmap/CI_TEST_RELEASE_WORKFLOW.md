# CI, test, release, and dogfood workflow

**Status:** operative derivative of the accepted roadmap and gates. This document does not create, weaken, or reinterpret a gate.

**Authority:** accepted ADRs and specifications, `docs/v2/05-roadmap/ROADMAP.md` RP-0 through RP-5, `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`, `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`, and `INITIATIVE_LPR_001`. If this document conflicts with an accepted contract, the accepted contract wins. ADR-0094 is referenced by RP-4 as the intended common impact-selection policy, but **ADR-0094 is missing from the repository**. Do not claim that its policy is implemented or accepted until the ADR exists and is accepted.

## 1. Operating laws

1. A result belongs to an exact repository, commit SHA, source tree, build inputs, JDK/OS, artifact bytes, and test profile. Evidence from another SHA is historical evidence only.
2. Keep development testing, integration verification, release certification, and channel publication separate. A narrow green test is not a release gate.
3. Required work that was not executed is `NOT_RUN`, `SKIPPED`, or `BLOCKED` with a reason. It is never inferred green from a tag, receipt, test presence, or an older successful run.
4. Freeze the candidate SHA and exact artifact bytes before publication. Every mandatory gate for integration or publication must describe that same candidate.
5. Never edit an old receipt to repair history. Create a new receipt that points to the original evidence and states the correction or limitation.
6. Do not put secrets in logs, manifests, receipts, reports, or uploaded artifacts. Record only non-sensitive identities and redacted evidence.

## 2. Entry checklist

Before changing code, CI, release configuration, or certification documents:

- [ ] Read `ROADMAP.md`, `CERTIFICATION_PROTOCOL.md`, `PRODUCTION_READY_UAT_MATRIX.md`, the applicable ADRs/specifications, and `INITIATIVE_LPR_001`.
- [ ] Record `base_sha`, current branch, remote state, working-tree changes, and the next authorised WorkItem. Do not overwrite unrelated dirty files.
- [ ] Identify changed files, owning SUT, public contracts, direct consumers, persistence/protocol boundaries, and required UATs.
- [ ] Classify the planned verification as docs-only, T0/T1, component, integration, RP gate, release, or channel certification.
- [ ] Confirm whether the evidence target is current-head verification, a release candidate, or historical v0.39.0 evidence.
- [ ] For release work, freeze the candidate source SHA, build inputs/lock, JDK/OS, version, artifact name, and output directory before building.

**Docs-only rule:** validate Markdown, links, references, and whitespace. Do not run Gradle merely because this document mentions Gradle.

## 3. Change-scoped development testing

Use the smallest sufficient development scope and widen only when impact requires it:

1. **D0 static:** syntax, formatting, type/API/schema checks, documentation/link checks, and compile checks when code is changed.
2. **D1 focused behavior:** the edited behavior and its direct tests, selected by test method or class. A RED result must fail for the expected assertion, not by timeout or compilation error.
3. **D2 component/regression:** the owning module/component, direct consumers, characterization tests, and relevant regressions after the behavior stabilizes.
4. **D3 integration:** real integration boundaries, installed CLI/distribution, persistence/restart, event or process contracts, and external fixtures when crossed by the change.
5. **D4/D5 project or release verification:** complete integration or release profiles only at their required gate, after lower-level evidence is green and on the candidate SHA.

These `D0` through `D5` labels are local change-scoped development levels. They are intentionally distinct from the `T0` through `T5` certification layers in `CERTIFICATION_PROTOCOL.md`. Certification T0 means API and type safety, T1 Step/domain behavior, T2 architecture and compatibility, T3 installed distribution, T4 durability/failure, and T5 release/resistance. Neither ladder replaces the other.

Use incremental commands and the repository's documented wrapper path. For Gradle from the repository root, the wrapper is `v2/gradlew`. Apply timeouts, preserve raw logs and XML, verify that required XML was freshly generated, and clean up child processes. Do not use a bare broad test task as discovery. Do not classify a failure as pre-existing without fresh base-versus-head evidence.

## 4. Report contract

Every meaningful run should produce a machine-readable report or receipt when the applicable runner or gate implements reporting. This section defines a proposed minimum field set for that report. It is not an adopted public schema and does not claim that report generation is already implemented. If no reporter exists, record `NOT_IMPLEMENTED` or `NOT_RUN` in the applicable receipt and do not infer evidence.

```yaml
status: PASS|FAIL|BLOCKED|NOT_RUN|SKIPPED|NOT_IMPLEMENTED
certification_state: CANDIDATE_VERIFIED|CERTIFIED_AT_SHA|RELEASED_ARTIFACT|NOT_APPLICABLE
repo: exact-repository-identity
base_sha: exact-full-sha-or-NOT_APPLICABLE
head_sha: exact-full-sha
source_tree_sha: exact-full-sha-or-UNKNOWN
baseline_artifact: version-and-sha256-or-NOT_APPLICABLE
tested_artifact: path-or-url-or-NOT_BUILT
artifact_sha256: exact-sha256-or-NOT_BUILT
profile: named-test-or-certification-profile
selected_tests: exact-tests-and-selection-reason
argv: exact-argv-or-NOT_RUN
environment: jdk-os-runner-and-relevant-config
exit_code: integer-or-NOT_RUN
xml: paths-and-failure-error-skipped-counts-or-NONE
raw_evidence: immutable-log-or-artifact-paths-and-sha256
reports: events-transcripts-sbom-sast-or-NONE
outcomes: typed-outcomes-and-observed-side-effects-or-NONE
missing_or_skipped: itemized-with-reason
comparison: base-head-or-baseline-candidate-result-or-NOT_APPLICABLE
known_failures: issue-id-and-evidence
security: scan-result-or-UNKNOWN
performance: measured-value-budget-and-result-or-UNKNOWN
next_action: explicit-recovery-or-close-action
```

`status` describes the named check execution. `certification_state` describes a separate lifecycle claim and must not be populated merely because a check passed. `PASS` means the named check actually ran and passed on the reported target. `NOT_RUN` means execution was not attempted. `BLOCKED` means an external prerequisite, credential, provider, runner, or permission prevented execution. `SKIPPED` is an intentional selection exclusion and must identify the governing rule. None of these may be silently promoted to certification.

## 5. Integration gate and CI workflow

For an integration candidate:

1. Freeze the candidate SHA and verify the checkout is clean or that all intentional changes are included.
2. Run the affected checks progressively, then the complete RP-required profile where the gate requires it.
3. Require current CI jobs, not merely workflow definitions or old receipts. Record job IDs, SHA, exit status, XML, logs, and artifact hashes.
4. Require RP-0 through RP-4 prerequisites before RP-5. Preserve known failures and stop on mandatory failures, missing mandatory evidence, or unsafe isolation.
5. Confirm that the candidate has no unapproved public contract change, critical/high defect inside the profile, mandatory disabled test, unresolved security gate, or unverified compatibility requirement.
6. Close only when the report and receipt state the exact evidence and next action. Otherwise leave the gate open.

### CI launcher target

The target architecture is a thin, pinned GitHub Actions launcher and publisher of checks. Bootstrap and verification must remain runnable independently. The current `release.yml` is quarantined V1, `v2-baseline` checks tags, and the SDKMAN publish flow is triggered after a GitHub Release. Do not claim that an operational V2 GitHub publishing workflow exists. Any future launcher must pass the frozen SHA, profile, environment, and artifact locations explicitly, upload raw evidence, and fail when the underlying pipeline fails. It must not conceal a skipped job, replace the pipeline's selection policy, or manufacture a green result.

RP-4 describes N1, N2, and N3 as the same-SHA self-hosting progression:

- **N1 bootstrap:** clean checkout, JDK, PipelineK build, and startup canary. It must still diagnose bootstrap failures when DSL or execution is broken.
- **N2 same-SHA dogfood:** the PipelineK built from that checkout executes a real `.pipeline.kts` from that same SHA, with bounded integration or per-shard tests. Existing `--tests` lanes remain fallback evidence, not an equivalent replacement when N2 is required.
- **N3 external verification:** an observer outside the engine verifies outcomes, logs, reports, and artifacts. An intentional failure must make CI red, and a DSL failure must not erase bootstrap diagnostics.

Current same-SHA N1/N2/N3 implementation status is determined by fresh CI evidence for the candidate. Historical RP-4 dogfood receipts may establish precedent, but do not certify a later SHA without rerunning the required checks.

## 6. Previous-release PipelineK dogfood

The prior-release battery is a **test battery**, not a ceremony. It is
**PLANNED** until it produces a real run, but it is structured like any
other battery: scenarios, pass/fail classification, root cause, and a
machine-readable report.

### 6.1 What it tests

The previous release (old binary) is used to execute the candidate
checkout's supported fixtures and contractual scenarios; the candidate
binary then runs the same scenarios. The battery exists to surface
regressions that no other battery can see: behaviour drift between
versions, fixture compatibility gaps, command-line regressions, and
upgrade-path breaks.

1. Pin and verify the previous released PipelineK binary and digest,
   currently the v0.39.0 GitHub Release artifact (`385b140c…`), when
   that baseline is explicitly selected.
2. Execute the battery scenarios with the old binary.
3. Execute the same battery scenarios with the candidate binary.
4. Compare by scenario. A version-introduced DSL gap is **not** a
   regression unless the scenario was contractual on the old version.
5. Keep the candidate's same-SHA N1/N2/N3 canary separate from this
   previous-release compatibility battery. They answer different
   questions and must not be merged.

### 6.2 Scenario catalogue

Each scenario is a real `.pipeline.kts` (or fixture script) the battery
actually runs, not a description of one. Every scenario declares:

```yaml
- id: DGF-<n>
  description: <one line, plain language>
  old_binary: <sha256 of the pinned previous-release binary>
  candidate_binary: <sha256 of the candidate binary>
  fixtures: [<paths or URIs>]
  expected_outcome: PASS|FAIL|TIMEOUT|RECOVERY|<…>
  pass_criteria: <observable assertion>
  failure_kind: TYPED|INFRASTRUCTURE|REGRESSION|VENDOR|UNKNOWN
  reproducibility: deterministic|flaky|unknown
```

A scenario catalogue with no executed run is `PLANNED`, never `PASS`.

### 6.3 Outcome taxonomy (closed set, no exceptions)

```text
DGF_PASS              -> scenario ran on both binaries with identical
                         expected outcome, observed side effects, and
                         logs.
DGF_REGRESSION        -> candidate deviates from old-binary behaviour
                         on a contractual scenario. Mandatory fix.
DGF_INTENTIONAL_FAIL  -> scenario is designed to fail; old binary
                         fails as expected; candidate must fail the
                         same way. Failure equals PASS.
DGF_INFRASTRUCTURE    -> cannot run because of runner, network, env,
                         credentials, or tool. Status is BLOCKED or
                         NOT_RUN, never promoted.
DGF_VENDOR_GAP        -> scenario exposes an upstream/3rd-party gap
                         (SDKMAN, GitHub Releases, registries). Not
                         the candidate's fault; BLOCKED with
                         remediation plan.
DGF_RECOVERY          -> scenario asserts an intentional recoverable
                         failure (e.g. credential prompt retry, kill
                         + resume). Verifies the typed recovery path.
DGF_VERSION_GAP       -> DSL/scenario introduced or removed between
                         versions. Not a regression by itself; recorded
                         for roadmap attention. Skipped scenarios
                         older than the candidate version are SKIPPED
                         with a reason.
```

`DGF_PASS` of any flavour does **not** certify the candidate on its own.
The battery is a regression detector, not a release gate.

### 6.4 What the report MUST contain (minimum schema)

The dogfood battery is treated like every other test: it must
produce a report an agent can act on without re-reading the scripts.

```yaml
dogfood_run:
  status: PLANNED|RUNNING|PASS|FAIL|BLOCKED|NOT_RUN|DGF_REGRESSION|…
  base_sha: <exact full SHA>
  head_sha: <exact full SHA>
  old_release:
    version: <semver>
    binary_sha256: <sha256>
    source_url: <where it was fetched>
    fetched_at: <UTC>
  candidate_release:
    binary_sha256: <sha256>
    source_tree_sha: <exact full SHA>
  runner:
    os: <linux/macos/windows>
    jdk: <vendor and version>
    runner_class: <local|self-hosted|github-actions|…>
    network_egress: <allowed|blocked|partial>
  scenarios:
    - <the scenario block from §6.2, with observed outcome and
       failure_kind; one entry per scenario id>
  diff_summary:
    scenarios_total: <int>
    scenarios_pass: <int>
    scenarios_regression: <int>
    scenarios_skipped: <int>
    scenarios_blocked: <int>
    intentional_failures_asserted: <int>
    intentional_failures_passed: <int>
    version_gaps: [<id,…>]
  raw_evidence:
    - <path or URL>      # immutable, content-addressed when possible
  next_action:
    - <WU id or "open": concrete step for any non-PASS scenario>
  known_failures: [<id,…>]
  attacker_view:        # adversarial notes the agent owes the human
    - "<what the worst-case reader would notice, missing, or bypass>"
```

A run without `old_release`, `candidate_release`, every executed
`sceanarios[…].outcome`, and an explicit `next_action` is
**NOT a report**. It is a log line.

### 6.5 Promotion rules (no silent greens)

- `DGF_PASS` requires every executed scenario to land on
  `DGF_PASS`, `DGF_INTENTIONAL_FAIL`, or a documented `DGF_VERSION_GAP`.
- Any `DGF_REGRESSION` blocks the candidate until that scenario
  passes on a fresh SHA.
- Any `DGF_INFRASTRUCTURE` is **not** absorbed into a green total; the
  scenario is recorded as `BLOCKED` and the battery remains partial.
- A battery with one or more `BLOCKED`/`NOT_RUN` scenarios is
  `PARTIAL_DOGFOOD_EVIDENCE`, never `PRODUCTION_READY`.
- Two distinct external repositories (RP-5 / UAT-RP-024) is a separate
  requirement; previous-release dogfood is one piece of evidence, not
  the whole gate.

### 6.6 Receipt and archival

Every executed run produces an immutable receipt under
`docs/v2/07-uat/dogfood/<yyyymm>/<sha>-<old-version>-vs-<candidate-version>.yaml`
that names the binary SHAs, the scenario catalogue, the observed
outcomes, and the next action. Receipts are never edited; corrections
are new receipts that reference the original.

### 6.7 Current explicit gaps

The v0.39.0 receipt contains real historical evidence for the
certified commit `951b3cb5695ecc46c877776e330266e4bd44aa9e` and
published ZIP SHA-256 `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`.
It records validate/basic/release smoke evidence and GitHub download
verification for that release. It does **not** prove the current `main`
SHA, does not prove a full root pipeline run on current `main`, does
not satisfy RP-5's two distinct external repositories, and does **not**
satisfy the previous-release dogfood battery's scenario catalogue from
§6.2. A report must label this evidence
`HISTORICAL_RELEASED_ARTIFACT`, not current-head PASS.

RP-5 and UAT-RP-024 require two external repositories of distinct
nature, including an update and an intentionally recoverable failure.
The current explicit gap is **RP5/UAT-RP-024: two external repositories
are not yet proven**. A one-repository or same-repository dogfood
result is partial evidence only.

## 7. Caching and queue experiments

Caching and queue work is an experiment, not a gate relaxation:

- Record cache key inputs, hit/miss, artifact provenance, invalidation behavior, and whether the result was actually rebuilt.
- Compare warm-cache, cold-cache, and cache-disabled runs on the same SHA and environment where reproducibility matters.
- Measure queue wait, execution time, concurrency, resource contention, cancellation, and retry behavior separately.
- Never use a cache hit, queue success, or a prior artifact as proof that a mandatory check executed on the candidate.
- Keep experimental lanes opt-in until their evidence shows correctness, isolation, and useful savings without hiding failures.

## 8. Release-candidate certification

For each release candidate:

1. Freeze the source SHA, version, build inputs, dependency locks, JDK/OS, and reproducibility configuration before building. Do not predeclare the output bytes or digest.
2. Run the mandatory RP-0..RP-5 profile, including UAT, clean installation, reproducibility, compatibility, security, performance budgets, and required dogfood. Record `NOT_RUN` or `BLOCKED` honestly.
3. Produce the exact distribution intended for publication. Capture its final bytes, manifest, and SHA-256, then rebuild as required by the reproducibility contract and compare outputs.
4. Verify the installed candidate with the real CLI and supported fixtures, including success, typed failure, validation, replay or restart scenarios required by the profile.
5. Create the immutable receipt containing the report, exact argv, CI URLs, XML paths, logs, artifact hashes, SBOM/security outputs, and known limitations.
6. Do not publish if a mandatory gate is failed, missing, blocked, or tied to another SHA. Preserve the candidate as STOP evidence and identify the recovery WU.

## 9. Exact artifact publishing and channels

Publish only the exact bytes tested and certified:

- Capture the final ZIP, checksums, manifest, SBOM, signatures or attestations when required, and their digests before upload.
- Verify downloaded bytes from the destination and compare them to the certified digest.
- GitHub Release is a distinct channel gate. A public release proves only the exact assets and target commit documented by its receipt.
- SDKMAN is a separate channel gate. `SDKMAN_READY` requires real vendor publication, clean-runner installation UAT, verification, and promotion checks. Missing vendor credentials or provider access is `BLOCKED`, never green.
- Do not infer SDKMAN readiness from a GitHub Release, a local ZIP, a publish script, or a successful dry run.
- Keep release notes explicit about historical evidence, current-SHA evidence, known limitations, and unexecuted gates.

## 10. Exit checklist

- [ ] Every required check has a fresh result on the exact target SHA or is explicitly `NOT_RUN`, `SKIPPED`, or `BLOCKED`.
- [ ] Current CI jobs and artifacts are linked with exact SHA and digests.
- [ ] N1/N2/N3 status is supported by current same-SHA evidence, not only historical receipts.
- [ ] Previous-release dogfood is labelled PLANNED unless its complete battery is actually executed.
- [ ] RP5/UAT-RP-024 two-external-repository evidence is present, or the gap remains open.
- [ ] The candidate artifact is byte-identical to the uploaded artifact and passes destination verification.
- [ ] GitHub Release and SDKMAN states are reported independently.
- [ ] Security, credentials, manifests, logs, and receipts contain no secrets.
- [ ] The receipt names blockers, failed checks, stale evidence, and the exact next action.

No release, integration, or certification is closed by document presence. It closes only when the applicable gate's real evidence says it closes.
