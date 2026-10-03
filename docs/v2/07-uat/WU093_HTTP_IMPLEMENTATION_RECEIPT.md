# WU-093 — `http.request` implementation receipt

```yaml
id: WU-093
status: CERTIFIED_AT_SHA
base_sha: 530ffa9817ab515e041b7feb52550bde15f94aa2
head_sha: 530ffa9817ab515e041b7feb52550bde15f94aa2
source_tree_sha: 530ffa9817ab515e041b7feb52550bde15f94aa2
artifact: v2/pipeline-application/build/install/pipelinek
artifact_sha256:
  bin/pipelinek: 9eef5b962fc2f5145e6331529b8c39cff8d555dda5e424a5190431c64fc8e9b4
  lib/http-0.36.0.jar: 1950744d17e98390f10255bd9c933e6fa420b09ccb258d3870ea749b9189a389
scope: JDK 21 toolchain, Linux x86_64, loopback-only, installDist, http.request as OFFICIAL_PLUGIN
checks:
  - id: H8-INSTALLED-LADDER
    command: cd v2 && ./gradlew :pipeline-application:test --tests '*HttpInstalledUatTest*'
    exit_code: 0
    xml: v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.cli.HttpInstalledUatTest.xml (16/0/0/0)
    evidence: within the full-gate invocation below; 16 tests, 0 failures, 0 errors, 0 skipped, 131.64s
    result: PASS
  - id: FULL-LOCAL-GATE
    command: cd v2 && ./gradlew check --rerun-tasks
    exit_code: 0
    xml: 673 suites, 4426 tests, 0 failures, 0 errors, 140 skipped
    evidence: BUILD SUCCESSFUL in 25m 27s; 289/289 actionable tasks executed; tree clean at gate time and after
    result: PASS
  - id: ARCH-FITNESS
    command: cd v2 && ./gradlew :pipeline-architecture-tests:check
    exit_code: 0
    xml: within the full gate
    evidence: Lfc2HttpOfficiallyPluginBoundaryFitnessTest FIT-1..FIT-7 green; koverVerify green;
              CoordinatorGrowthGuardrailTest ceiling 562, CanonicalDurableRunCoordinator.kt = 562
    result: PASS
  - id: MUTATION-M-HTTP-14
    command: ./gradlew :pipeline-step-sdk:http:test (mutation applied, then reverted)
    exit_code: 1 (expected — the mutation must be RED)
    xml: 16 tests completed, 3 failed
    evidence: C4 expected 1048576 but was 67108864; H4-C3 expected 1048576 but was 4194304;
              C3 expected 4096 but was 300000
    result: PASS (mutation KILLED, then reverted)
  - id: CI-REMOTE
    command: gh run list --commit 530ffa9817ab515e041b7feb52550bde15f94aa2
    exit_code: 0
    xml: NONE
    evidence: no run exists; .github/workflows/ is empty since 754ddda0
    result: NOT_AVAILABLE — see "CI policy" below
known_failures: []
coverage: >-
  pipeline-application aggregate LINE 5770/7653 (75.4%), BRANCH 1888/3412 (55.3%),
  METHOD 819/963 (85.0%). The http plugin module has NO kover configuration and
  therefore NO independent coverage report; its coverage is UNKNOWN, not high.
security: >-
  H8-09 asserts, against a LIVE credential that was proven sent, that neither the
  stdout nor the run journal contains the password, its base64 form, or the
  "Basic " marker. H8-15 and H8-16 assert that an unresolvable name and a
  wrong-kind credential each send zero requests. No SAST or dependency scan was
  run in this block: NOT_RUN.
performance: >-
  H8-10 measured peak RSS 486/520/531 MiB for 1/32/256 MiB responses. 255 MiB of
  extra body produced 45 MiB of extra peak: ratio 0.176 against a 0.5 threshold,
  where the pre-H4 BodySubscribers.ofByteArray shape would sit near 1.0.
next_action: >-
  SDDK phase.build.complete on cycle rp6c-http-request, then VERIFY. PRODUCT-GATE
  remains BLOCKED_EXTERNAL. RP6-CLOSEOUT to reconcile the Step inventory, the
  ecosystem matrix and the stale core.httpRequest wording.
```

**Delivery form:** `http.request` is an **OFFICIAL_PLUGIN** reached through
`registryStep`, discovered by `ServiceLoader`. It is **not** `core.httpRequest`.
`STEP_ECOSYSTEM_POLICY.md:59` (`docs/v2/03-specifications/`) and
`STEP_ECOSYSTEM_MATRIX.md:288` (`docs/v2/01-product/`) classify it as Tier B
plugin, and the pivot is recorded in
[WU093_HTTP_DELIVERY_RECONCILIATION.md](WU093_HTTP_DELIVERY_RECONCILIATION.md).
`StepSpec.HttpRequest` does not exist, and `pipeline-domain` does not name HTTP.

## Slice ledger

| Slice | Commit | What it established |
|---|---|---|
| HP0 | `5717edb1` | delivery reconciliation: core → OFFICIAL_PLUGIN, ownership changed not design rewritten |
| H1 | `ca7694d3` | plugin contract + `ServiceLoader` contributor, proven with real discovery and the real compiler |
| H2b | `a4710a92` | capabilities contributed through a typed seam, not a locator |
| H3 | `53aa9090` | coroutine-native transport, one reused client per instance |
| H4 | `48436d32` | bounded body subscriber: digest all bytes, retain ≤ cap, `request(1)` demand |
| H5 | `4f1b37a5` | narrow `BasicCredentialSource` capability; secret materialised only at the transport boundary |
| H6 | `d7868bf4` | egress is a question the runtime answers, default-deny, `NETWORK_EGRESS_CAPABILITY` |
| H7 | `65bfa71b` | contributor threaded into the execute-time re-check; `ReplayPolicy.NEVER`, `RecoveryPolicy.None` |
| H8 | `530ffa98` | installed-distribution certification, this receipt's evidence |

## Mutations

**Executed and measured:**

| Mutation | Result | Reading |
|---|---|---|
| M-http-5A — façade writes the wire by hand, skipping `HttpRequestCodec` | RED in FIT-6 | an authorship guard catches what round-trip cannot see |
| M-http-5B — codec renames `timeoutSeconds`→`timeout` in encoder **and** decoder | **SURVIVED** | round-trip and byte-identity do not protect the wire vocabulary |
| M-http-5B′ — same, with the golden vector present | RED | only an assertion on literal bytes catches a coordinated rename |
| M-http-14 — `maxMaterializedBytes`→`totalBytes.toInt()` in `appendBoundedPrefix`, and `minOf(maxMaterializedBytes, …)` dropped from `ensureCapacity` | RED in 3 tests | see below |

M-http-14 in full, since it is the memory law and it was found un-reverted in the
worktree by the session that recovered this context:

```text
mutation:   BoundedBodySubscriber.appendBoundedPrefix
              room = totalBytes.toInt() - prefixSize      (was: maxMaterializedBytes - prefixSize)
            BoundedBodySubscriber.ensureCapacity
              grown = maxOf(required, prefix.size * 2)    (was: minOf(maxMaterializedBytes, …))
property:   retained memory is bounded by maxMaterializedBytes
result:     RED — C4 "expected 1048576 but was 67108864"
                  H4-C3 "expected 1048576 but was 4194304"
                  C3   "expected 4096 but was 300000"
            C4 drives 64× the cap and retained exactly 67108864 = 64 MiB, so retention
            had become O(responseSize). Reverted from HEAD; `git diff` on the file is
            empty against 530ffa98 and no MUTACION marker remains in any source.
```

**Planned and NOT executed — recorded as NOT_RUN, not as passed:**
M-http-1, 2, 3, 4, 6, 7, 8 (`WU093_G1_G4_IMPLEMENTATION_PLAN.md:23-86`) and
M-http-11, 12, 13, 15, 16 (H4 canary plan). M-http-15 in particular — classifying a
mid-body failure as `Unreachable` — is the mutation that would matter most for
`ResponseInterrupted`, and it has not been run. Its absence is a real gap in this
receipt, not a formality.

## Installed distribution

All 16 scenarios ran against the **same** `installDist`, built by the same
invocation as the full gate from SHA `530ffa98`. No scenario was run against a
different build or against the working tree.

| Family | Scenario | Result |
|---|---|---|
| plugin discovery | H8-01, H8-02 | PASS — reached only through `ServiceLoader` |
| default security | H8-01 | PASS — refused, `network.egress` named, 0 arrivals |
| basic transport | H8-02, H8-03 | PASS — GET once; POST body intact |
| status | H8-04 | PASS — accepted succeeds, refused fails naming the status |
| interrupted body | H8-05 | PASS — `ResponseInterrupted`, not `Unreachable` |
| redirects | H8-06 | PASS — not followed |
| timeout | H8-07 | PASS — Step's own `timeoutSeconds` enforced, run does not hang |
| credentials | H8-08, H8-14, H8-15, H8-16 | PASS — unresolvable/absent/wrong-kind each send 0; valid sends 1 with the header |
| redaction | H8-09 | PASS — live secret absent from stdout **and** journal |
| streaming | H8-10 | PASS — ratio 0.176 |
| connection reuse | H8-11 | PASS — 3 requests, 1 client socket |
| replay/recovery | H8-12 | PASS — resuming a finished run sends nothing |
| concurrency | H8-13 | PASS — two requests in one stage both arrive |

## CI policy

`CI-REMOTE` is **NOT_AVAILABLE**, and the reason is a decision on record rather
than an outage:

- `754ddda0` (2026-09-30) removed `lpr0-ci.yml`, `release.yml`,
  `v2-baseline.yml` and `sdkman-publish.yml` after 60 consecutive cancelled runs
  with the self-hosted runners offline since 2026-09-28, closing SDDK backlog
  `bl-bl-01M3STVZ0Z000387KNMMQ96E00` (P2).
- `.github/workflows/` is empty. `gh run list` returns only Dependabot jobs, which
  do not build this code.
- The local-only decision is therefore recorded **in Git and in the SDDK backlog**,
  not merely in `.agent/*` — which matters, because AGENTS.md classifies `.agent`
  as non-authoritative and this receipt may not lean on it.

`297bde9c` amended AGENTS.md's recovery block, which still instructed "lanzar CI
para el NUEVO SHA" — an action that had become impossible. That amendment states
the gate that exists and records that "CI verde" is not an evidence class
available here, so neither its absence nor a `NOT_RUN` may be reported as `PASS`.

`CERTIFICATION_PROTOCOL.md` §4 keeps two gates apart and this receipt does not
merge them:

- **STEP-CERT** — does not require remote CI. This receipt certifies at that level.
- **PRODUCT-GATE** — requires "CI real del SHA". It is **BLOCKED_EXTERNAL** while
  no CI surface exists. A STEP-CERT does not turn it green.

`PR-ADR-002` (Single Release Admission Authority) would replace CI with the
external harness as the single promotion check. Its status is **PROPOSED**, so it
is not authority and nothing here depends on it.

## Residual CI drift — open, not swept

Four normative documents still assume a CI surface that no longer exists. They are
enumerated rather than amended here, because amending them is a governance change
that deserves its own decision and its own receipt:

| Document | Residue |
|---|---|
| `docs/v2/05-roadmap/ROADMAP.md:34-37` | WU-RP-000/001 and the RP-0 exit criterion "CI real verde y no omitido" |
| `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md:14` | UAT-RP-001 requires a GitHub Actions URL as its evidence |
| `docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md:69` | §5 "GitHub Actions release gate" |
| `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md:21` | T5 "CI required" (T5 is release-tier, so it does not affect this STEP-CERT) |

## Known limitations

1. **`timeout { }` does not bound `http.request`.** The block budget is consumed by
   the `core.sh` watchdog and the Step never reads it; a measured
   `timeout(2s) { httpRequest("/slow?ms=30000") }` aborts at the Step's own default
   (~35s). Closing it means reading `EXECUTION_BUDGET_CAPABILITY` and deriving
   `min(step, block)` — a behaviour change, so it was not made inside the
   certification block. H8-07 is the timeout evidence and is honest about scope.
2. **A Step's output is not observable from the installed distribution.** No
   `DomainEvent` carries a Step's typed output, and `operation_journal.output` does
   not hold the readable envelope. Reading it needs either a new published-contract
   member or a key the CLI does not expose.
3. **The http module has no coverage report.** No `kover` block in its
   `build.gradle.kts`, so its coverage is UNKNOWN. The 75.4% figure belongs to
   `pipeline-application` and says nothing about the plugin.
4. **Typed diagnostic text is not pinned.** H8-15 and H8-16 assert a non-zero exit
   and zero arrivals, not the exact wording of `Absent` / `WrongKind`.
5. **The journal sweep proves absence of literals, not of an encrypted copy.**
6. **Twelve planned mutations were not executed** (see above).
7. **The coordinator sits exactly on its ratchet**: ceiling 562,
   `CanonicalDurableRunCoordinator.kt` 562. Any growth trips the guard, which is the
   intent, not a defect to fix here.

## Delta between the certified SHA and this document

`530ffa98` is the certified code tuple. `297bde9c` (this AGENTS.md amendment) and
this receipt are documentation-only commits on top. Per
`CERTIFICATION_PROTOCOL.md` §7 a later commit is `NOT_YET_RECERTIFIED` until its
impact is shown; the impact here is that no file under `v2/` changed, so the
gate, the coverage and the artifact identity above still describe the tree.
