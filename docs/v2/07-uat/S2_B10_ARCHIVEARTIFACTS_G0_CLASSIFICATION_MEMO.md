# S2-B10 / G0 — `core.archiveArtifacts` Baseline Audit + CORE-vs-PLUGIN Classification Memo

**Slice:** LFC-2E1 S2-B10 (archiveArtifacts burn-down, cycle `cycle/lfc2-e1-archive-artifacts`)
**Base SHA:** `53b8fca0` (trunk merge of `cycle/lfc2-e1-delete-dir-g6prep`)
**Candidate commit:** `6eba03b6`
**Gate scope:** G0 (baseline audit + fresh evidence) + G1 (registry candidate). **NO G4/G5** —
`LEGACY_PLUGIN_IDS`, the legacy decoder, the metadata row and the legacy dispatcher are NOT touched.
Counters remain **5 / 5 / 5** at this slice's exit.

---

## Part 1 — Legacy surface inventory (G0)

All legacy forms verified present at base `53b8fca0`:

| # | Legacy form | Location | Status at base |
|---|---|---|---|
| 1 | Catalogue entry | `CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` contains `"core.archiveArtifacts"` (last member of the set) | PRESENT |
| 2 | Legacy command subtype | `CanonicalCoreStepCommand.ArchiveArtifacts(artifacts, allowEmptyArchive=false, excludes="", fingerprint=false)` | PRESENT |
| 3 | Decoder branch | `CanonicalCoreStepDecoder.decode` `ARCHIVE_ARTIFACTS_PLUGIN_ID -> ...` branch (requiredString `kind=="archiveArtifacts"`, `artifacts` required; defaults `allowEmptyArchive=false`, `excludes=""`, `fingerprint=false`) | PRESENT |
| 4 | Metadata row | `CanonicalCoreStepMetadata["core.archiveArtifacts"] = StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED)` | PRESENT |
| 5 | Dispatcher file | `durable/CanonicalArchiveArtifactsNodeDispatcher.kt` (hand-rolled `globToRegex`, `Files.walk` match, copy to `controlDirRoot/artifacts/<runId>/<stageName>/`, sha256+size entries, emits `ArtifactArchived`/`ArtifactArchiveFailed`, returns `StepOutcome`) | PRESENT |
| 6 | Dispatcher wiring | `CanonicalNodeDispatcher` field `archiveArtifactsDispatcher`, `is CanonicalCoreStepCommand.ArchiveArtifacts ->` branch, `archiveArtifactsContext()` helper | PRESENT |
| 7 | DSL StepSpec form | `StepSpec.ArchiveArtifacts(artifacts, allowEmptyArchive: Boolean?, excludes, fingerprint: Boolean?)` + `PipelineDsl.archiveArtifacts(...)` extension (Jenkins-verbatim signature) | PRESENT |
| 8 | Compiler lowering | `DslCompiledPipelineCompiler.encodePayload` `is StepSpec.ArchiveArtifacts -> {kind:"archiveArtifacts", artifacts, allowEmptyArchive (?: false), excludes, fingerprint (?: false)}` | PRESENT |

### 1.1 Observable legacy behaviour (characterization)

From `CanonicalArchiveArtifactsNodeDispatcher.dispatch` (verbatim reading):

- **Glob**: hand-rolled `globToRegex` (literal `.`→`\.`; `**/`→`.*`; `*`→`[^/]*`; `?`→`[^/]?`).
  Malformed glob → `ArtifactArchiveFailed` + `StepOutcome.Failure(SCRIPT, "archiveArtifacts glob failed: …")`.
- **Empty match + `allowEmptyArchive=false`** → `ArtifactArchiveFailed` + `Failure(SCRIPT, "archiveArtifacts: no files matched '<glob>'")`.
- **Empty match + `allowEmptyArchive=true`** → success; `ArtifactArchived` IS still emitted with an
  EMPTY `files` list (controlDirRoot != null path).
- **Copy loop**: files copied preserving relative paths; each entry carries
  `relPath`, `sha256` (full-file digest), `size`; first copy failure → `ArtifactArchiveFailed`
  + `Failure(SCRIPT)` (no partial success).
- **`excludes`**: present on the wire but **silently ignored** by the legacy dispatcher
  (recorded candidate delta — see 1.3).
- **`controlDirRoot == null`** → `return StepOutcome.Success` WITHOUT globbing, copying or
  emitting events (silent no-op).
- **`fingerprint`**: carried on the wire, never acted on (L7.1 deferral; sha256 is computed
  unconditionally in the entries).

### 1.2 Fresh pre-existing failure evidence (base SHA, zero-fabrication)

Command: `gradlew -p v2 :pipeline-application:test --tests 'UatLocal009*'`
(argv recorded in the run log; budget rule 4: `timeout 600`)

Result (JUnit XML truth, canary-verified fresh):
`tests=13 failures=7 errors=0 time=73.58s`

| Failed test | Failure |
|---|---|
| CR-U9-001 writeFile readFile round-trip with sha256 event | Should emit FileWritten event (writeFile event channel, NOT archiveArtifacts) |
| CR-U9-002 fileExists true after writeFile | Should emit FileWritten (same class) |
| CR-U9-003 writeFile atomic write succeeds | Should emit FileWritten |
| CR-U9-004 writeFile cross-fs fallback documented in atomicallyMoved | Should emit FileWritten |
| CR-U9-008 archiveArtifacts sha256 and size in event | Pipeline should exit 0 (pipeline-level; writeFile event channel implicated) |
| CR-U9-011 archiveArtifacts AntStyleGlob pattern matches files | Pipeline should exit 0 (same class) |
| CR-U9-012 cross-step writeFile then archiveArtifacts picks up file | Pipeline should exit 0 (same class) |

The 4 CR-U9-008/011/012 failures are the known **pre-existing UatLocal009 red baseline**
(declared pre-existing in the slice briefing). Their common proximate cause at the pipeline
level is the writeFile→`FileWritten` event channel, upstream of the archiveArtifacts legacy
dispatcher itself; they are recorded as base truth, **not fixed in this slice** (out of scope).

Evidence archived (sha256 canaries):

```
docs/v2/07-uat/evidence/s2-b10-g0/TEST-dev.rubentxu.pipeline.v2.application.UatLocal009TopStepsTest.xml
  sha256 b34ca154162e37f5359eab50b9ca37db51887b3ed462786600e521e912b3913f
docs/v2/07-uat/evidence/s2-b10-g0/uat009-baseline-run.log
  sha256 ffdaf77032b89a93103643db370c53e7cd49f978717065df32d91c0e85e5a1a6
docs/v2/07-uat/evidence/s2-b10-g0/base-sha-fitness-red.txt
  (rule-16 evidence: the 7 stale S2-A6/G4 sibling fitness pins listed below are ALREADY RED
   on the clean base SHA, before any change from this slice)
```

### 1.3 Additional pre-existing red baseline (rule-16 discipline)

While validating G1, 7 sibling fitness pins were found red. Rule 16 requires base-vs-head
evidence before classifying a failure as pre-existing: the suites were run on a **clean stash
of base `53b8fca0`** and re-run with the G1 changes — the red set is **identical** (7 of 60).
These pins assert the pre-S2-A7 counter `6` and a 9-key registry set that base `53b8fca0`
no longer satisfies (S2-A7/G5 converged `core.deleteDir` to 5/5/5 but did NOT update these
six sibling pins; they are stale on trunk, listed in `base-sha-fitness-red.txt`).

This slice does NOT widen, re-baseline or "fix" those pins (scope firewall): the burn-down
ledger owns their convergence. They are documented so a future gate does not misread them
as regressions from S2-B10.

### 1.4 Counters at G0/G1 exit (unchanged)

```
LEGACY_PLUGIN_IDS  = 5   (core.milestone, core.cleanWs, core.load, core.waitUntil, core.archiveArtifacts)
metadata rows      = 5   (CanonicalCoreStepMetadata pluginIds)
dispatcher classes = 5   (Canonical{Milestone,CleanWs,Load,WaitUntil,ArchiveArtifacts}NodeDispatcher)
```

Pinned by `CoreArchiveArtifactsStepUnitTest.counters - G1 leaves legacy counters at 5 5 5`.

---

## Part 2 — CORE-vs-PLUGIN Classification Memo

**VERDICT: `CORE_KEEP` (with a documented, low-cost PLUGIN_CANDIDATE exit path).**

`core.archiveArtifacts` remains a core Step. It burns down through the standard
G0→G8 sequence exactly like its siblings (echo, sh, error, sleep, writeFile, emitEvent,
isUnix, pwd, deleteDir). Rationale:

### 2.1 Why CORE_KEEP

1. **Jenkins-familiarity baseline is mandatory core surface.**
   `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md` (catalog §1.1 line 45) and the DSL
   docstring pin `archiveArtifacts` as a Jenkins-verbatim top-level step with its
   core signature `archiveArtifacts(artifacts, allowEmptyArchive, fingerprint, onlyIfSuccessful)`.
   The step semantics law (AGENTS.md §STEP SEMANTICS) requires Jenkins users to adopt
   pipelines without relearning. The local-first Step ecosystem is defined as
   "universal core + already-present families" (STEP_ECOSYSTEM_MATRIX §Universal core);
   artifact archiving is part of the retention story every pipeline run depends on.
2. **Zero-production-change rule does not force PLUGIN_CANDIDATE.** That rule says a new
   EXTERNAL plugin must require zero core semantic changes; it does not say universal-core
   steps must become plugins. `example.uppercase` proved the external path is viable
   (ServiceLoader discovery, external JAR, isolation pair, installed-distribution execution)
   — that proof makes the PLUGIN exit path CHEAP, not mandatory.
3. **No integration-adapter identity.** The step is a pure local file-system effect
   (workspace glob → artefacts retention copy). It has no external product dependency, no
   credential surface, no network surface — nothing that would benefit from the plugin
   boundary (versioning, distribution, permission-scoped identity). Compare `scm-git`,
   which IS listed as an OFFICIAL_PLUGIN candidate precisely because it carries an
   external product integration.
4. **Runtime coupling is already neutral and seam-shaped.** The G1 candidate reaches only
   `AntStyleGlob` (pipeline-artefacts-local, already a core classpath module used by
   artifact retention machinery) and the canonical `WorkspaceResolver`. If a future cycle
   reclassifies it, the capability seam (`ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY`) makes
   the extraction a registration move, not a rewrite.

### 2.2 What would flip the verdict to PLUGIN_CANDIDATE (recorded, not acted on)

- Remote/distributed artifact retention (network storage backend) — that is an
  integration adapter and belongs in a plugin boundary.
- A plugin marketplace/identity model (PLUGIN_IDENTITY_MODEL / POLICY_READINESS_GATE)
  where third-party retention providers coexist — the `ResourceRef`/policy work (LFC-2E2+)
  would make retention a pluggable resource.
- Product-level decision to slim universal core (STEP_ECOSYSTEM_MATRIX forward plan keeps
  archiveArtifacts in the local-first core set; a reversal would be an ADR).

### 2.3 Both paths documented (no change now)

- **CORE_KEEP path (active):** burn down the legacy entry through G2 (differential
  contract freeze) → G3 (parity/readiness) → G4 (REGISTRY_PRIMARY flip: remove
  `"core.archiveArtifacts"` from `LEGACY_PLUGIN_IDS`) → G5 (LEGACY_REMOVED: delete
  command subtype, decoder branch, metadata row, dispatcher file and `CanonicalNodeDispatcher`
  wiring) → G6/G7 (contract suite) → G8 (CERTIFIED). The G1 candidate in this slice is the
  G1 entry of that sequence.
- **PLUGIN_CANDIDATE path (dormant, cheap):** if a future ADR reclassifies, the already
  certified `example.uppercase` chain applies: move the definition behind a
  `StepDefinitionContributor` in an external JAR, register via ServiceLoader discovery,
  keep the same `ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY` seam, run the external-plugin
  certification rows (real JAR, isolation pair, installed distribution). The legacy
  burn-down still applies to the `LEGACY_PLUGIN_IDS` entry first — the external path
  cannot become the routing authority while the key remains in the closed legacy set.

---

## Part 3 — G1 design notes (recorded deltas for the G2 differential freeze)

1. **Effect classification.** Legacy metadata row declares `Effect.READ_ONLY`. The Effect
   ADT offers `READ_ONLY | EXECUTES_SUBPROCESS | ABORTS_PIPELINE | WRITES_WORKSPACE`.
   The ADT has NO artifact-write variant, and G1 does not invent one (the instruction was
   to verify, not to extend the ADT without memo justification). Justification recorded:
   archiveArtifacts copies workspace files OUT to the retention directory — a workspace
   file-system write is the closest honest classification, so the candidate descriptor
   declares `{WRITES_WORKSPACE}` (same class the `writeFile`/`deleteDir` candidates use).
   The legacy row stays byte-identical until G4 (the metadata authority flip); the delta
   is frozen at G2, where the legacy-vs-registry differential must reconcile it.
2. **Glob engine.** Legacy uses a hand-rolled `globToRegex`; the candidate delegates to
   the certified `AntStyleGlob` (Spring `AntPathMatcher`, Jenkins 13-entry default
   excludes verbatim, traversal-safe). Consequence: patterns invalid under AntPathMatcher
   (e.g. unbalanced `[`) fail as typed SCRIPT failures instead of raw regex errors; `**`
   matching is Jenkins-correct rather than the legacy `.*` approximation.
3. **`excludes`.** Legacy silently ignores the field. The candidate applies it
   (comma-split, after default excludes) — the Jenkins-verbatim semantic, covered by a
   dedicated unit test and flagged as a recorded behavioral delta for G2.
4. **Idempotence.** `Files.copy(..., REPLACE_EXISTING)` makes fresh re-execution
   idempotent, matching MEMOIZED+WRITES_WORKSPACE semantics already frozen for
   `core.deleteDir` (S2-A7/G6 coverage matrix entry 17).
5. **Typed failure convention.** Handler failures are TYPED outputs
   (`ArchiveArtifactsFailureOutput(failureKind=SCRIPT, message=…)`) rather than thrown
   exceptions: the boundary classifies a thrown handler as `ENGINE`, while the legacy
   dispatcher's contract is `FailureKind.SCRIPT` with exact legacy messages — pinned by
   tests (`failureKind == the Step's contractual kind`, per the replay-policy test law).
6. **Output ADT.** Success carries `archivedCount`; the per-file evidence (relPath, sha256,
   size, archivedAt) is the `ArtifactArchived` event's job (console/transcript rule: typed
   value and observability event are independent channels; no content duplication).
7. **`allowEmptyArchive=true` parity.** Empty `ArtifactArchived` event still emitted —
   byte-parity with the legacy dispatcher.

---

## Part 4 — G1 evidence

- `CoreArchiveArtifactsStepUnitTest`: **21 tests, 0 failures, 0 errors** (fresh XML,
  canary-verified). Covers: identity, contract completeness, both codecs (round-trip +
  foreign-kind rejection + legacy-default decode + durable persistence law), handler
  happy path (exactly one `ArtifactArchived`, byte-identical archived bytes), typed
  failure path (SCRIPT kind + legacy message + `ArtifactArchiveFailed` event),
  `allowEmptyArchive=true` parity, excludes, fail-closed admission (missing capability →
  Rejected, handler invocation 0, no event), real-seam execution through
  `RegistryExecutionPreparation` + `RegistryExecutionBoundary`, structural family
  LegacyCore, counter invariant 5/5/5, duplicate-registration fail-closed.
- Sibling scope check: `CanonicalCoreStepCommandRegistryTest`,
  `CoreDeleteDirStepUnitTest`, all six `S3*LegacyRemovedFitnessTest` — 46 tests, 0 failed.
- Known-red neighborhood (pre-existing, base-equal): the 7 stale S2-A6/G4 sibling pins
  (§1.3), plus the 7 pre-existing UatLocal009 failures (§1.2). Both sets documented with
  fresh base-SHA evidence; untouched by design.

**STOP.** This slice ends at G1. G2 (differential contract freeze) is the next gate.
