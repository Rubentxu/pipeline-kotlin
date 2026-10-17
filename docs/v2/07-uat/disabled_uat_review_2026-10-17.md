# Disabled UAT Review — 2026-10-17

Mandate Block D TODO #2: "Revisar las UAT @Disabled, especialmente las
de observabilidad, replay, secretos, sandbox y concurrencia."

This memo is a **review**, not a deletion. Per
`RESPONSIBILITY_MIGRATION_ROADMAP.md` and the Block D rules, an
UAT may only be deleted after the harness produces the 5-axis
verdict (ADR-PK-QV-003) with an equivalent contract test. Until
then, every `@Disabled` UAT must remain `@Disabled` with the
`migrated-pending` marker.

The review's value is the **classification** — knowing which
UATs map to which area, which ones are pure product regression
guards, and which ones depend on harness-side capabilities
(sandbox, certifier, network). This drives the order in which
the harness equivalent can be authored.

## Scope

`grep -rln "@Disabled.*migrated-pending" v2/` returned **63
test files** (one HTML doc snuck in via `*.html`, so 62
Kotlin test files). The memo classifies them by the mandate's
five focus areas; UATs that do not fit any area are listed in
"Other" for completeness.

## Per-area classification

### Observability (OBS) — 5 UATs

| Test | Domain | Harness equivalent? |
|---|---|---|
| `ObsAOutputStreamingCharacterisationTest` | Output Plane, stream order | OBS-B reads frames; harness can author an OBS contract |
| `ObsC23ChannelSeparationUatTest` | Events Plane vs Output Plane channel separation | OBS-C contracts exist (`OutputPlane.uat.fixture`); harness can author the channel-separation contract |
| `ObsPc208LevelBUatTest` | Output Plane level-B (read cursor) | OBS-B read cursor contract already lives in catalogue |
| `ObsPc2LevelAUatTest` | Output Plane level-A (write) | OBS-A contract exists; migration blocked on level-A fix |
| `PublishHtmlOperationsAdapterUatTest` | Stash/Publish HTML operation adapter | Operations adapter contract, not OBS-core |

**Harness-side blocker**: `ObsA` and `ObsPc2` are by-design
RED until the OBS-B/E work delivers the read cursor and
channel-separation contracts. The 5-axis verdict on these
UATs is `BLOCKED_EXTERNAL` because the producer's
regression-test premise (cursor / channel separation) is not
yet implemented.

**Action**: keep `@Disabled` with the existing
`migrated-pending` reason. No deletion; no producer change.
The harness owner must author the OBS equivalent and emit a
verdict before these can be retired.

### Replay — 3 UATs

| Test | Domain | Harness equivalent? |
|---|---|---|
| `UatEvt001ReplayTest` | Replay one event | Replay contract (`ADR-0103`); harness can author |
| `UatEvt002MultiStepReplayTest` | Replay multi-step pipeline | Replay contract |
| `UatDurableDefaultReuseCliTest` | Default `--reuse-prior-run` CLI | Replay + CLI default; harness can author |

**Harness-side blocker**: per `ADR-0103` (one replay
authority), the harness should be the single replay
authority. Until the harness can replay a known-failing
event and produce a verdict, the producer cannot
characterise its own replay behaviour as a stable
regression.

**Action**: keep `@Disabled`. The harness equivalent is
mechanical: replay a known input and assert the output. The
producer can supply a fixture (e.g. a recorded journal
segment) and let the harness run replay on it.

### Secrets (credentials / redaction) — 3 UATs

| Test | Domain | Harness equivalent? |
|---|---|---|
| `Lpr011r2SecretRedactionAtRestUatTest` | Secret redaction at rest | Output Plane / credentials contract |
| `Lpr011SecretRedactionTranscriptUatTest` | Secret redaction in transcript | Output Plane / credentials contract |
| `UatLocal008SshPrivateKeyRoundGateTest` | SSH private key round-gate | Credentials + git round-trip contract |

**Harness-side blocker**: secrets are inherently a
producer-internal concern (the producer knows the secret
material under test). The harness cannot author a
"secret-leak" test without a fixture that contains the
secret. The producer owns the secrets UATs.

**Action**: **MIGRATION_PENDING is the wrong state for
secrets UATs.** They are NOT migration candidates — they
belong to the producer because the harness cannot author
them. The review's recommendation is to **change the
`@Disabled` reason from `migrated-pending` to
`producer-internal: secrets cannot be reconstructed by an
external harness without a fixture the producer must
maintain`**. This is a documentation fix, not a code
change; it preserves the @Disabled state but corrects the
stated reason so a future contributor does not assume the
harness is the right place to look.

### Sandbox — 4 UATs

| Test | Domain | Harness equivalent? |
|---|---|---|
| `UatLocal007SandboxProfileTest` | Sandbox profile config | Test sandbox spec |
| `UatLocal010SmokeE2ESandboxOfflineTest` | Sandbox offline mode | Test sandbox spec |
| `UatLocal010SmokeE2ESandboxTest` | Sandbox online mode | Test sandbox spec |
| `CandidateIdentityEndToEndTest` | Candidate identity (SHA-256 of ZIP) | Release contract |

**Harness-side blocker**: per `TEST_SANDBOX_PROFILES.md`,
the sandbox profile is a producer concern (it determines
which tests run, which is the lane partition). The harness
runs as one of three fidelity levels (HF0, HF1, HF2) but
the *profile selection* is producer-side.

**Action**: same as secrets. Keep `@Disabled`, but the
`migrated-pending` reason is misleading. The sandbox
profile tests are producer-internal: the harness executes
within a chosen profile but does not author which profile
to choose. The `@Disabled` reason should be updated to
`producer-internal: sandbox profile selection is not
replayable by an external harness`.

### Concurrency — 3 UATs

| Test | Domain | Harness equivalent? |
|---|---|---|
| `UatRunConcurrencyCharacterisationTest` | Pipeline-run concurrency | Replay + parallel body contract |
| `UatDsl003ParallelTest` | DSL parallel block | Body execution contract |
| `UatParallelBlockDurableTest` | Durable parallel block | Body execution + durable contract |

**Harness-side blocker**: per the BODY execution contract
(`BodyExecutionPolicy`, `BodyInvoker`, `BranchInvoker`),
parallel semantics are the producer's authoritative
implementation. The harness can run the producer's binary
with concurrency settings and observe outcomes, but it
cannot author a "concurrency is wrong" test without
inverting the producer's contract.

**Action**: keep `@Disabled` with `migrated-pending` reason.
The harness equivalent is a CLI invocation with
concurrency flags, asserted against the contract; the
producer can ship that as a CLI characterisation.

### Other — 46 UATs (not in the five focus areas)

Categories present in the 46:
- **Block-step Durables** (8): `UatRetry/Timeout/Lock/Input/
  ParallelBlockDurableTest`, `RetryAcceptanceMatrixTest`,
  `EmitEventCatchErrorRegistryUatTest`. Block-Step
  semantic equivalents live in the registry primary
  contracts (LB-02).
- **Process lifecycle** (5): `UatLocal001KillDuringShTest`
  through `UatLocal006LostHeartbeatTest`. The producer
  already has unit tests for these (e.g. `DurableShell-
  ExecutorAdversarialTest`); the UATs are end-to-end
  verifications on the installed binary.
- **Git / checkout** (8): `UatLocal005CheckoutGitTest`,
  `UatLocal005GitAuthCanaryRoundGateTest`, etc. Migration
  blocked on git-fixture availability in the harness.
- **Step behavior** (5): `UatStep001-004`, `UatM0001Hello-
  PipelineTest`. Covered by `StepContractSuite` in
  producer.
- **DSL grammar** (5): `UatDsl001-008`. Covered by
  `CompatibilityCorpusTest` in producer.
- **Compat** (4): `UatComp001-003`, `UatCompat001`. Covered
  by `CompatCorpusTest` in producer.
- **S2 features** (2): `StashOperationsAdapterUatTest`,
  `UatS2R0RunOwnershipCliTest`. Operations adapter
  contract.
- **CLI** (3): `UatS2AWhenGateInstalledBinaryTest`,
  `UatDurableDefaultReuseCliTest`. Already in
  `installedContractTest` lane per producer's lane
  partition.
- **Misc** (6): `UatLocal005CorpusUntouchedTest`,
  `UatLocal005RegressionGateTest`, etc.

These 46 are the "second wave" of the review. They follow
the same pattern as the focus areas: keep `@Disabled`,
update the reason to reflect whether the harness can
author the equivalent or the producer owns it.

## Action items (proposed for the integrator)

1. **Secrets + Sandbox + Concurrency UATs**: the
   `migrated-pending` reason is misleading. A follow-up
   change to the `@Disabled` annotation's reason string
   should be applied, distinguishing "harness-migration
   candidate" from "producer-internal: not migration
   candidate". The state remains `@Disabled`; only the
   documentation changes.
2. **OBS UATs**: no action beyond documenting the
   `BLOCKED_EXTERNAL` status. The OBS-B/E work is the
   upstream dependency.
3. **Replay UATs**: producer can author a journal fixture
   for the harness to replay. Out of scope for this slice;
   follow-up work item.
4. **All other UATs**: the review is complete. Per
   `RESPONSIBILITY_MIGRATION_ROADMAP.md`, deletion awaits
   the harness's 5-axis verdict with the equivalence
   contract.

## Numbers

- 63 files with `@Disabled` and `migrated-pending`
- 5 OBS, 3 Replay, 3 Secrets, 4 Sandbox, 3 Concurrency
  in the focus areas
- 46 in other categories (block-step, process lifecycle,
  git, step behavior, DSL, compat, S2, CLI, misc)
- 0 deletions in this review (deletion is gated on the
  harness verdict per Block D rules)
- 1 producer change (BannedImportsFitness, commit
  `fc38dfdd`) is the only code change this slice

## Cross-references

- Mandate: 2026-10-17 Block D TODO #2
- `RESPONSIBILITY_MIGRATION_ROADMAP.md` — migration policy
- `ADR-PK-QV-003` — 5-axis verdict (the gate for deletion)
- `ADR-0103` — one replay authority (replay category)
- `TEST_SANDBOX_PROFILES.md` — sandbox profile policy
- `ADR-0107` — typed deciders (referenced by the OBS
  category; OBS-B/E use the same decider pattern)
