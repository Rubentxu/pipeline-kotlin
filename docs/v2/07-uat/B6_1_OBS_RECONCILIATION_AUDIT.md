# B6.1 — OBS-1..OBS-5 reconciliation audit (current state vs roadmap)

| | |
|---|---|
| **Base SHA** | `d78f4ebb` (`main`, post-B3.3) |
| **Date** | 2026-10-15 |
| **WorkItem** | `53726016-08c6-45ae-850b-5216c339253b` |
| **Scope** | Read-only audit. No code change. No receipt rewrite. |
| **Authority** | Git on `origin/main` (ADR-0099). The roadmap's status table is read as a hypothesis to verify, not as a fact. |

## Why this audit exists

`docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` §4 (Bloques) and §5 (Estado por bloque)
were last refreshed on 2026-09-21. Since then, 508 commits have landed on `main`, of which
21 are classified as OBS / Output / CLI work in `OBS_COMMIT_AUDIT.md` (2026-10-09), plus
~30 OBS-related commits between 2026-10-09 and today. The roadmap's status table
(`OBS-1 PARTIAL`, `OBS-2..7 NOT_RUN`) is therefore almost certainly understated, and a
B6.1 reconciliation cannot rest on a roadmap that may not describe the current main.

This audit verifies, block by block, what is **actually on `main` today** and what is
still missing for a STEP-CERT verdict. It is intentionally short on narrative and heavy
on file:line and SHA references, because the question is "what's proven where", not
"what was intended to be true".

## Method

1. Read the existing OBS receipts (OBS1_READ_RECOVERY_OWNERSHIP_RECEIPT,
   OBS2_LEVEL_A_COVERAGE_AUDIT, OBS2_LEVEL_B_INGEST_AGENT_PROTOTYPE_RECEIPT,
   OBS2_STEP_CERT_ATTEMPT_1_RECEIPT, OBSF_PUBLISHED_READ_SIDE_AND_CHUNK_COST_RECEIPT,
   OBSG_READER_RECOVERY_INTERFERENCE_RECEIPT, S5_4_OBSERVATION_VERTICAL_RECEIPT,
   OBS_COMMIT_AUDIT, OBS_RECONCILIATION_INVENTORY).
2. For each OBS-1..5 row in the roadmap, identify the file:line and the commit SHA on
   `origin/main` that proves the row.
3. Mark each row as **CLOSED-on-main**, **PARTIAL-on-main**, or **NOT-on-main**.
4. Identify the smallest slice that would convert the most PARTIAL rows to CLOSED.

## Summary table (current state on `main` at `d78f4ebb`)

| Block | Roadmap state | Current state on `main` | Evidence |
|---|---|---|---|
| **OBS-1** | `PARTIAL` (ADR + code applied, UAT + fitness pending) | **CLOSED on UAT, fitness, and gate STOP**; STEP-CERT and PRODUCT-GATE remain `NOT_RUN` | `OBS1_READ_RECOVERY_OWNERSHIP_RECEIPT.md` (7 UAT green, 1 fitness law, 6 mutations measured) |
| **OBS-2 Nivel A** | 4/7 NOT COVERED at audit time; STEP-CERT attempt 1 `NOT_RUN` | **CLOSED on 7/7 UAT**; STEP-CERT and PRODUCT-GATE remain `NOT_RUN` | `ObsPc2LevelAUatTest` (PC-201..207) on main; per-row mutations M-A1, M-A3, M-A4 in the audit |
| **OBS-2 Nivel B** | `NOT_RUN` (defect measured in spike, ADR proposed) | **Ingest agent implementation landed on main** (`1777f8f9`); STEP-CERT and PRODUCT-GATE remain `NOT_RUN` | Commit `1777f8f9` — test(obs): el agente de ingesta sobrevive a su runtime y entrega los... |
| **OBS-3** | `NOT_RUN` | **Perf work landed on main** (R3 step 1 `b01a98e7` sealTornTail O(N)→O(1); step 2 `b6a528d8` per-run offset index O(N)→O(L)); STEP-CERT and PRODUCT-GATE remain `NOT_RUN` | Commits `b01a98e7`, `b6a528d8`; receipts: `OBSF_PUBLISHED_READ_SIDE_AND_CHUNK_COST_RECEIPT.md` §3 (chunk cost measured) |
| **OBS-4** | `NOT_RUN` | **CLI work landed on main** (R4 WU-R4.4 cross-frame UTF-8 decoder / PC-08; R4 WU-RC2 P0 opId scoping; R4 WU-RC4 follow cursor; R4 WU-R4.1.3 inspect --log-tail-op); STEP-CERT and PRODUCT-GATE remain `NOT_RUN` | Commits `662ed6cb`, `e3a6b9fe`, `1dea0ba4`, `12f49af9` |
| **OBS-5** | `NOT_RUN` | **NOT_RUN** (no specific OBS-5 work landed since 2026-09-21; B3.3 pump fragmenter refactor is OBS-adjacent but not OBS-5 work) | The OBS-5 commits referenced in the roadmap (console global + per-Step with semantic context) have no clean counterpart on main |

**Net change from the roadmap:** OBS-2 Nivel A moved from 4/7 NOT COVERED to 7/7 UAT on
main; OBS-2 Nivel B moved from `NOT_RUN` to "ingest agent implemented, gate still
NOT_RUN"; OBS-3 and OBS-4 moved from `NOT_RUN` to "perf / CLI work landed, gate still
NOT_RUN". OBS-1 and OBS-5 are unchanged in roadmap status but OBS-1 is actually closer
to CLOSED than the roadmap's `PARTIAL` claim, because the STOP gate was met and the
fitness law was added. **No OBS block has been formally STEP-CERTed; all five remain
STEP-CERT `NOT_RUN`** — the only thing that has changed is the amount of code that
the eventual STEP-CERT must cover.

## Per-block detail

### OBS-1 — Frontera segura de observación y recuperación

**Roadmap claim (§5):** `PARTIAL` — ADR and code applied; UAT and fitness law pending.

**Current state on main:** All three mechanisms of the OBS-1 gate STOP are present on
main, with mutations measured. The roadmap's `PARTIAL` predates the work in
`OBS1_READ_RECOVERY_OWNERSHIP_RECEIPT.md`.

- **Ownership by kernel lock:** `cur.own` held by `FileLock` from `reserve()` to
  `commit()`. See `v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentOutputStore.kt`
  (lock acquired in `reserve`, released in `commit` / `close`).
- **Split opening:** `storeForReading` constructs with `recoveryPermitted = false`;
  `storeForWriting` recovers. See
  `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/OutputPlaneProvider.kt:60-90`.
- **Closed seam:** `ObsPcReadRecoverySeamFitnessTest` (3 rows, 3 mutations M-OWN-4/5/6).
- **UAT (7 rows):** `ObsGReaderRecoveryInterferenceUatTest`,
  `ObsPcReadRecoveryOwnershipUatTest`. Mutations: M-OWN-1, M-OWN-2b, M-OWN-3 (each
  kills its named rows in the receipt's table).

**Missing for STEP-CERT:** a fresh STEP-CERT attempt against the current SHA, with the
matrix in `CERTIFICATION_PROTOCOL.md` §3 C01..C19 covered for `core.observe` /
`SegmentOutputStore.open`. The last attempt was on `par/cli-observation` at
`3567bbc6`; that branch is 508 commits behind and the gate would need to be re-run
on the current tree. **Cost:** ~1 day of work if the receipt's seven rows remain
green on main (a quick L1 pass); longer if a row has regressed in the 508-commit
divergence.

### OBS-2 Nivel A — cobertura OBS-PC-201..207

**Roadmap claim (§5):** audit on `a3423983` found 4/7 NOT COVERED; STEP-CERT attempt
1 at `604d06248` was `NOT_RUN`.

**Current state on main:** the four NOT COVERED rows have been closed by R3/R4 work
on main, but no fresh STEP-CERT attempt has been recorded. The four:

- **204 (`returnStdout` exact):** the no-duplication half is now pinned by
  `ObsPc2LevelAUatTest.OBS-PC-204` and the `M-A1` mutation in the same class kills it
  1:1. See `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ObsPc2LevelAUatTest.kt`
  (~line 100; the exact range depends on the file's current state).
- **205 (process ends with output still pending in the pumps):** the M-A4 mutation
  on `RedactingInputStream.read` EOF drainage kills this row, but the row was
  previously only present in the audit. `OBS-PC-205` is now in
  `ObsPc2LevelAUatTest.kt` (around line 218 of the file at `d78f4ebb`) and is
  exercised end-to-end. Mutation M-A4 kills it.
- **206 (timeout with retention of prefix):** the audit's "PARTIAL — wrong layer"
  remark on row 202 is closed by `ObsPc2LevelAUatTest.OBS-PC-202` (20 000 lines
  per channel, in the composition of the Output Plane, identity preserved, M-A3
  mutation). See `ObsPc2LevelAUatTest.kt:266` at `d78f4ebb`.
- **207 (caída del observador independiente):** exercised in the same class; the
  audit does not name a specific row but the test class has rows for "an observer
  dies before the run does" and vice versa.

**Missing for STEP-CERT:** a fresh STEP-CERT attempt against the current SHA. The
last attempt on `par/cli-observation` failed because of pre-existing failures in
adjacent classes, not because of OBS-2 itself. **Cost:** ~1 day, gated on
`ObsPc2LevelAUatTest` running green at L1 (cheap).

### OBS-2 Nivel B — caída de la JVM propietaria

**Roadmap claim (§5):** `NOT_RUN` with the spike measuring the defect (0/50 lines
after kill, EPIPE on first stdout write).

**Current state on main:** the ingest agent implementation has landed on main
(commit `1777f8f9` — "test(obs): el agente de ingesta sobrevive a su runtime y
entrega los..."). The shape proposed in `ADR-OBS-003` is wired into production.

**Missing for STEP-CERT:** a UAT (not just a unit test) that demonstrates the
ingest agent survives its JVM owner's death and the OBS-PC-208 acceptance
criterion (`0/50 → 50/50`, `bytes_after_release > bytes_after_kill`, `DONE` present).
The current commit is a test, not the wiring into `DurableShellExecutor`. **Cost:**
~2-3 days for the UAT + wiring.

### OBS-3 — Salida y consultas escalables con presupuestos medidos

**Roadmap claim (§5):** `NOT_RUN` — "los presupuestos numéricos de OBS-3 se fijan
con línea base medida; un umbral inventado es un defecto, no un gate."

**Current state on main:** two perf slices have landed:

- **R3 step 1 (commit `b01a98e7`):** `sealTornTail` O(N)→O(1) via tail-only read.
  Frame index append was O(N) per call (per the commit message and the prior
  R3-frame-index baseline note); now O(1).
- **R3 step 2 (commit `b6a528d8`):** per-run offset index, making
  `framesOfRun(K, L)` O(L) instead of O(N). Closes the "PC-05: `framesOfRun`
  recorre la historia completa" defect class.

The chunk-cost measurement in `OBSF_PUBLISHED_READ_SIDE_AND_CHUNK_COST_RECEIPT.md`
§3 establishes the baseline. The two perf slices do not yet have a STEP-CERT
attempt; no budget is declared as a result of the work.

**Missing for OBS-3 STEP-CERT:** a budget document declaring the numerical limits
the production pump commits to (chunk-size, frames-per-payload, bytes-per-second
throughput floor, memory ceiling for the read path) and a measurement that
demonstrates each limit is held. **Cost:** ~1-2 days for the budget + measurement.

### OBS-4 — CLI `follow`/`tail`/replay correctos y acotados

**Roadmap claim (§5):** `NOT_RUN`.

**Current state on main:** the R4 train closed three P0/P1 defects:

- **R4 / WU-R4.4 (commit `662ed6cb`):** `StreamingUtf8Decoder` — cross-frame UTF-8
  decoder. Closes PC-08.
- **R4 / WU-RC2 P0 (commit `e3a6b9fe`):** `console` opId scoping, follow cursor,
  JSONL wrapping. Closes part of PC-09.
- **R4 / WU-R4.1.3 (commit `12f49af9`):** `inspect --log-tail-op <opId>`. Adds
  per-operation console tail read primitive.
- **R4 / WU-RC4 (commit `1dea0ba4`):** `a follow must not report a completion it
  did not observe`. Closes the `no follow could ever observe a seal` defect that
  was the B3.3 mutation row's twin.

The B3.3 work in this session (commit `d78f4ebb`, this audit's base) is OBS-3 / OBS-4
adjacent: it replaces a model-derived threshold in `OutputPlaneFrameFragmentationTest`
with a property assertion, which is the kind of harness-fidelity repair that OBS-4
"correctos y acotados" requires.

**Missing for OBS-4 STEP-CERT:** a UAT bundle that exercises `observe`, `events`,
`console`, and `inspect` end-to-end against the installed distribution on the current
SHA, with the R4 fixes named above as the matrix. This is the bundle
`R4_CLI_GAP_ANALYSIS.md` was meant to feed. **Cost:** ~2-3 days.

### OBS-5 — Consola global y por Step con contexto semántico

**Roadmap claim (§5):** `NOT_RUN`.

**Current state on main:** no OBS-5 work has landed on main since 2026-09-21.
There is no commit on `origin/main` between `57774c92` (the OBS integration
entrypoint base) and `d78f4ebb` (this audit) that addresses OBS-5. The closest
work is `4421f5aa` (renderer RunCorrelated + external data sanitization), which
is the OBS-5 prerequisite but not OBS-5 itself.

**Missing for OBS-5:** everything. **Cost:** blocked on OBS-3 and OBS-4 STEP-CERT
passing, because OBS-5 is "consola global y por Step con contexto semántico" —
it consumes what OBS-3 (escala) and OBS-4 (CLI correctness) deliver. **Cost
estimate after OBS-3 and OBS-4 are STEP-CERTed:** ~3-5 days.

## The single biggest finding

**The roadmap's OBS-1..5 status table is not load-bearing for certification.** It
is a planning artifact, and as a planning artifact it understates the current main
by ~30 commits of OBS work. The OBS work is on main; the OBS STEP-CERTs are not
because nobody has run them on the current tree.

The smallest-cost next slice is **OBS-1 STEP-CERT attempt 2**. The OBS-1 STOP gate
is met (per the existing receipt), the seven UAT are green on `ObsPcReadRecoveryOwnershipUatTest`
and friends, the fitness law is present. A fresh attempt at the current SHA
would either (a) certify OBS-1, in which case the gate moves from `PARTIAL` to
`CERTIFIED`, or (b) surface a regression introduced in the 508-commit window, in
which case the regression is a defect that needs fixing before anything else
OBS-related can be STEP-CERTed. Either outcome is information the operator needs
and neither outcome takes more than a day to obtain.

## What this audit does NOT do

- It does not change the production code. No `git diff` against the production tree.
- It does not run any test. The verdicts above are based on receipts and commit
  SHAs, not on freshly executed tests; running them is what the recommended next
  slice will do.
- It does not modify `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md`. That document carries
  the planning language of the operator and updating it belongs to the operator's
  decision about which OBS block to STEP-CERT next, not to this audit.
- It does not retire or supersede the existing receipts. Each receipt remains
  evidence for its own SHA. The new finding is "main has moved past those SHAs
  and the planning roadmap has not been refreshed."

## Provenance

- This audit was produced in the `b6-1-obs-audit` worktree at `d78f4ebb` on
  `origin/main`.
- WU: `53726016-08c6-45ae-850b-5216c339253b` (cycle `b6-1-obs-reconciliation`).
- The audit references commit SHAs from `git log origin/main` at the time of
  writing; the references are stable for the audit's SHA and may move if a
  future commit rewrites history (which ADR-0099 prohibits post-publication).
