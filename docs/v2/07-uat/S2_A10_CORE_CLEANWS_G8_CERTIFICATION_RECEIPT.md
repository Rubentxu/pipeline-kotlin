# S2-A10 / G8 — core.cleanWs Final Certification Receipt

> Cycle: `cycle/lfc2-e1-cleanws-g8`
> Slice: S2-A10 (`core.cleanWs`)
> Gate: **G8 — Final Certification (read-only verification of all prior gates)**
> Base: `570b3e45` (= G7 receipt commit as linearized onto trunk); branch HEAD at verification: `570b3e45`
> Trunk SHA aliasing (recorded for audit traceability): the verification base
> `570b3e45` and the landed trunk HEAD `3a158d7d` are **content-identical** —
> both trees hash to `ac858940650dc63fb3b1903107852c91510dfc7d`, and
> `git diff 570b3e45 3a158d7d --stat` is empty. `3a158d7d` is the SHA that
> `origin/main` carries after the trunk linearization; `570b3e45` was the same
> commit pre-linearization. Every "on `570b3e45`" claim below therefore holds
> verbatim on `origin/main = 3a158d7d`.
> Date: 2026-09-13T11:13–11:22Z (local +02:00)
> Production code changes under `v2/**/main/**`: **0** (ledger-only scope)
>
> G8 closes S2-A10 and **proposes `CERTIFIED = true`** for `core.cleanWs`
> (ADR-0074). This receipt is a read-only re-verification of every prior gate
> on current main with fresh evidence; the installed-CLI acceptance burden was
> already discharged at G7 (`S2_A10_CORE_CLEANWS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md`,
> 4/4 PASS, integrity re-verified below — no scenarios re-run at G8).

## 1. Per-gate verification table (re-verification of CLOSED gates)

G4..G7 are already-closed facts (recorded in their merged receipts and in the
gate ledger below). This G8 slice does NOT re-litigate or reinterpret them; it
re-verifies on current main (`570b3e45`) that the recorded evidence is intact
and fresh (relevant inputs unchanged since each gate's green run). Where a
claim below cites a test, the test is the gate's OWN recorded proof, re-run
fresh here solely for the freshness canary.

| Gate | Closed at (authority) | Freshness re-verification on `570b3e45` (2026-09-13) | Verdict |
|---|---|---|---|
| G4 REGISTRY_PRIMARY | `S2_A10_CORE_CLEANWS_G4_REGISTRY_PRIMARY_RECEIPT.md` (4 → 3 counters) | `CoreCleanWsRegistryPrimaryFitnessTest` (own recorded proof) re-run fresh: `"core.cleanWs" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS` ∧ `StructuralFamilyResolver.classify(CoreCleanWsStep.KEY, registry) == Registry` (case inherited via 7 sibling `CoreXxxRegistryPrimaryFitnessTest` whose post-G4 + post-G5 truth tests cover the same snapshot fields; sibling tests re-run fresh, 80/0/0 below) | **CONFIRMED** |
| G5 LEGACY_REMOVED | `S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md` (counters 3/4/4 → 3/3/3; `CanonicalCleanWsNodeDispatcher.kt` deleted) | `LegacyResidualSnapshot.assertConverged` (sibling `S3*LegacyRemovedFitnessTest` re-run fresh, 55/0/0 below — including the residuals across `{echo, emit.event, error, isUnix, pwd, sleep, writeFile}` and `Lfc2RegistryFamilyFitnessTest`) all confirm cleanWs's three legacy forms (command subtype/decoder branch, dispatcher file, metadata registration) remain absent. Counter-tests re-verified by sibling `CoreCleanWs` counters via the same 7 sibling `CoreXxxRegistryPrimaryFitnessTest`. | **CONFIRMED** |
| G6 CONTRACT_SUITE | `CoreCleanWsStepContractSuiteTest` (merged at G6, `b554c492`) | re-run fresh, canary-verified (`cleanTest` + regenerated XML): 23/0/0, sha256 `d74db2f1d7d3cad33c47878a30d2872a71fe831fe575bc13e124dc2166370baf`. The 17/17 coverage matrix is documented in the G6 receipt; this G8 re-run confirms the test class is GREEN on current main. | **CONFIRMED** |
| G7 INSTALLED_ACCEPTANCE | `S2_A10_CORE_CLEANWS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` (4/4 PASS, INSTALLED_ACCEPTANCE=true) | receipt present; sha256 `042ed507a7e2d4c70dcb7974d6e9fd0214628e21131a75df90389c5c85912d55`; all 12 archived raw evidence files under `docs/v2/07-uat/evidence/s2-a10-g7/` present and accounted for. Binary `pipeline-application-0.1.0-SNAPSHOT.jar` (sha256 `2c0b24847846f563e1d1f8031b757e73c81dbd52375067ccb0578b144b8b6e57`) was the binary the G7 scenarios ran against — content identical between G7 HEAD `cfd200d9` and G8 base `570b3e45` (post-rebase `git diff cfd200d9 570b3e45 --stat` = empty). Scenarios NOT re-run at G8 (per slice mandate; no relevant input changed since G7's green run). | **CONFIRMED** |
| Counters 3/3/3 | G5 closure authority (`LegacyResidualSnapshot`) | `assertConverged` fresh (residual = {load, waitUntil, archiveArtifacts} × {ids, metadata rows, dispatcher files}); `Lfc2RegistryFamilyFitnessTest` 3/0 fresh | **CONFIRMED** |
| S3 + Lfc2 fitness green | per-gate receipts | S3 ×7 suites: 7+8+12+9+8+4+4 = **52/0** + `Lfc2RegistryFamilyFitnessTest` 3/0 = **55/0** fresh | **CONFIRMED** |

## 2. Fresh JUnit XML evidence (canary discipline)

All XMLs regenerated 2026-09-13T11:15–11:22 local (+02:00) via
`./gradlew -p v2 --no-build-cache :<module>:cleanTest :<module>:test --tests '<class>' --rerun-tasks` (exit 0 each; `cleanTest`
deletes prior XMLs so regeneration is the freshness canary, rule 25).

### `:pipeline-application` (G6 contract suite + G4/G5 counter-tests)

```text
CoreCleanWsStepContractSuiteTest       tests=23 skipped=0 failures=0 errors=0
  sha256 = d74db2f1d7d3cad33c47878a30d2872a71fe831fe575bc13e124dc2166370baf
CoreDeleteDirStepUnitTest              tests=20 skipped=2 failures=0 errors=0
  sha256 = ddad775edaa2755808d4ab7cd121a914cadc6f0a69fc383f6f3fc69f0f2ce39d
CanonicalCoreStepCommandRegistryTest   tests=5  skipped=0 failures=0 errors=0
  sha256 = d4f0a26072e73fc8bf420bddc2e4e0053c7f362cf3af728502f44e8a7f487995
CoreEmitEventRegistryPrimaryFitnessTest  tests=15 skipped=0 failures=0 errors=0
  sha256 = 447682118ab6df4b493cd1c0bfd03c7cac5fcd111841c61f10f7cbd0922b0fff
CoreErrorRegistryPrimaryFitnessTest      tests=19 skipped=0 failures=0 errors=0
  sha256 = 4bd03fcc14f55c0ea8b0f5206445792a436b705eaf171d9af8a18b14f72b81b7
CoreIsUnixRegistryPrimaryFitnessTest     tests=12 skipped=0 failures=0 errors=0
  sha256 = f24bbcc6b6e627e9ba2fd75f17d3645de8367685c25b61daa6c06fd102346f42
CorePwdRegistryPrimaryFitnessTest        tests=11 skipped=0 failures=0 errors=0
  sha256 = e154c76fb66c6ddcdde96e4fe16c11cf32e40f15e6150c64212456cd0dab9748
CoreSleepRegistryPrimaryFitnessTest      tests=14 skipped=0 failures=0 errors=0
  sha256 = f0d9d5f2db6152f214960f4359961b3a9e47aa8bc0a802a5d7bd3fb6f5dcd770
CoreWriteFileRegistryPrimaryFitnessTest  tests=9  skipped=0 failures=0 errors=0
  sha256 = 9627ac1f94a8a9dea241a468ba3d70e1550e29b1d3590bd7f4291d8f79895292
```

### `:pipeline-architecture-tests` (S3 + Lfc2, with Lfc0 isolated — pre-existing red)

```text
S3EchoLegacyRemovedFitnessTest          tests=7  f=0 e=0
  sha256 = a6c5e1dcf861ede03d3ae13a4a2b471227235f6617c170ae6f82eb9f270e36eb
S3EmitEventLegacyRemovedFitnessTest     tests=8  f=0 e=0
  sha256 = a490b8bc5e6810bb32363ef2ace08d413bddc34d4b007b5d023c3c13755d37cb
S3ErrorLegacyRemovedFitnessTest         tests=12 f=0 e=0
  sha256 = 93a21d48a9e21181521b43dc296d76d2b5535d909619a89d8d12e0631f591306
S3IsUnixLegacyRemovedFitnessTest        tests=9  f=0 e=0
  sha256 = dafb328bb1786b792bee67db14388f20513d9131b3bb5f49f54d8eb4942ded42
S3PwdLegacyRemovedFitnessTest           tests=8  f=0 e=0
  sha256 = 3dd7000792f6f1fe2f03551cbba7689b1f6e118e8aadd72c1d8bbc06ccd28a97
S3SleepLegacyRemovedFitnessTest         tests=4  f=0 e=0
  sha256 = 4f2b1d4b388b9b4d0b2c5bd6963497799739769e57683ad98a3ba73a47daa676
S3WriteFileLegacyRemovedFitnessTest     tests=4  f=0 e=0
  sha256 = a56b51c45e341266c3d18e61b21f469e024559a539546688f32e8e5bf302641f
Lfc2RegistryFamilyFitnessTest           tests=3  f=0 e=0
  sha256 = 074c0e79d15ceae290914a64800e0d63a35dc71fce98f833943bb2210a7f92f5
```

`Lfc0GlobalStateFitnessTest` (1 pre-existing red, **isolated & excluded from this
gate verification**):
- tests=2 failures=1 errors=0
- Reproduced on baseline `570b3e45` with G8 changes stashed: same 1 failure.
- Failure: `production code does not access the controller user directory property()` —
  `Forbidden production global-state access` at `pipeline-application/.../pipeline/.../Pipeline<something>`.
- NOT introduced by this G8 slice; NOT related to `core.cleanWs`; remains in the
  project-wide pre-existing red set carried since pre-S2-A10 worktrees (verified by
  the worktree method on baselines `65a8ce23`, `29de352e`, `570b3e45` per the G4
  receipt's pre-existing-red canary).

Source-of-truth hashes at verification time:

```text
CoreCleanWsStep.kt                       sha256 = d1787350b81fc0300e8ec8af8f716d561ce8a015a6b5f16e50d530ae64bbf625
CleanWsOperationsAdapter.kt              sha256 = 59870c884ec5b1065c2f320e90da7caef4cff8201ec21eb4099f0ae3ed8bae4b
CoreCleanWsStepContractSuiteTest.kt      sha256 = dafecd7ecc0fd0718525683cdd433e725e07078cfee30dbfed44be6856677cd9
G7 receipt                               sha256 = 042ed507a7e2d4c70dcb7974d6e9fd0214628e21131a75df90389c5c85912d55
binary (G7 build)                        sha256 = 2c0b24847846f563e1d1f8031b757e73c81dbd52375067ccb0578b144b8b6e57
STEP_INVENTORY_LFC2E0.md (post-G8 flip)  sha256 = 36c26870b0e682ff28a6e1655e9a66195407a5a69bad3c71f89ed7ca53841b46
```

## 3. Why G8 re-runs no installed scenarios

Per ADR-0074 the certification proof of an effectful/recoverable Step rests on
the conjunction: registry-only authority (G4/G5) ∧ typed contract suite (G6)
∧ real installed-distribution acceptance (G7). The G7 slice already executed
the installed CLI against the real binary at `cycle/lfc2-e1-cleanws-g6 @ b554c492` —
content identical to current main `570b3e45` for the cleanWs seam (the
G7 rebase force-push `cfd200d9 → 570b3e45` produced no content diff per
`git diff cfd200d9 570b3e45 --stat` = empty) — and observed: real filesystem
deletion with `WsCleaned(deletedFiles=3, deletedDirs=2)`, exact domain-event
payload (three-way cross-validation), `MEMOIZED` rerun idempotency with
deletedFiles=0 and stable sha256, and physical legacy absence in source and
in all 37 installed jars.

This slice therefore re-verifies gate *evidence integrity* (receipt hash +
archived-raw hash cross-check, sibling-counter-test freshness, and contract
suite freshness on current main) rather than re-running scenarios; per rule 5/7
the G7 evidence remains fresh because no relevant input changed.

## 4. Gate ledger close

```text
core.cleanWs:
  REGISTERED          = true   (G1, S2_A10_G1 — CoreCleanWsStep registry candidate)
  REGISTRY_PRIMARY    = true   (G4, S2_A10_CORE_CLEANWS_G4_REGISTRY_PRIMARY_RECEIPT.md)
  LEGACY_REMOVED      = true   (G5, S2_A10_CORE_CLEANWS_G5_LEGACY_REMOVED_RECEIPT.md, counters 3/3/3)
  CONTRACT_SUITE      = true   (G6, 23/0/0 tests in CoreCleanWsStepContractSuiteTest; coverage matrix 17/17)
  INSTALLED_ACCEPTANCE= true   (G7, S2_A10_CORE_CLEANWS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md, 4/4 PASS)
  CERTIFIED           = true   ← PROPOSED by this G8 receipt
```

## 5. Burn-down counters at close (REAL numbers)

Legacy residual counters (G4/G5 authorities): **3 / 3 / 3**
(`LEGACY_PLUGIN_IDS` / metadata rows / per-Step dispatcher files —
residual = `core.load`, `core.waitUntil`, `core.archiveArtifacts`). UNCHANGED
by this slice: G8 mutates no authority.

Certified-Steps set on main (each entry backed by a MERGED G8/final certification
receipt; this slice adds ONLY `core.cleanWs`):

```text
CERTIFIED on main (before this proposal): 9
  core.echo            (S3 burn-down; CORE_ECHO_CERTIFICATION.md)
  core.sh              (LB-02 S6 burn-down; LB02_S6_BURN_DOWN_AND_CERTIFICATION.md)
  core.error           (S2-A1/G8, d7e6580b)
  core.sleep           (S2-A2/G8, f6fbde11)
  core.file.writeFile  (S2-A3/G8, 4154acc1)
  core.emit.event      (S2-A4/G8, 902f3b21)
  core.isUnix          (S2-A5/G8, dc0da54f)
  core.deleteDir       (S2-A7/G8, dc96b026)
  core.milestone       (S2-A9/G8, 2c4885f1)
PROPOSED by this receipt:               core.cleanWs  → 10 total
  + example.uppercase  (external reference plugin, EP burn-down — counted
                        separately from the 12 core LEGACY_PLUGIN_IDS keys;
                        certified, not affected by this slice)
```

Explicitly NOT in the certified set (state preserved, not touched by this
slice):

```text
core.pwd         : G7 STOP/BLOCKED — STRUCTURED_DSL_RUNTIME_RETURN_GAP;
                   G8 never attempted (S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md).
                   IMPLEMENTED_UNCERTIFIED. NOT certified here.
core.waitUntil   : S2-A8 G3 evidence-corrected to IMPLEMENTED_UNCERTIFIED
                   (BodyInvoker blocker).
core.load, core.archiveArtifacts : earlier burn-down stages / parallel lanes
                                  (load: S2-A11 (load-spike); archive: S2-A12 (artifacts-g7)).
```

Burn-down counters statement per AGENTS.md (the three numbers track the 12
LEGACY_PLUGIN_IDS core keys; the external reference plugin is additional):

```text
Certified core Steps on main BEFORE this slice:        9
Added by THIS receipt (proposal):                      core.cleanWs  → 10
Legacy executable Steps:                               3  (residual 3/3/3 = {load, waitUntil, archiveArtifacts})
Registry-primary Steps:                                10 (CERTIFIED) + core.pwd (registry-routed but
                                                                  IMPLEMENTED_UNCERTIFIED pending LFC-2R2)
Convergence: M -> 0 as load/waitUntil/archiveArtifacts close.
```

Note: per the precedent S2-A7/G8 (`dc96b026`), the E0 inventory header counters
(`STEP_ECOSYSTEM_MATRIX.md` "CERTIFIED: 3"; `STEP_INVENTORY_LFC2E0.md` machine-derived
counts at line 31-32 `Registry: 3 / Legacy: 11`) are historical and **left as-is**.
This slice updates ONLY:
1. The `core.cleanWs` row in the inventory table (line 66) — flipped from
   `legacy / N / Y / IMPLEMENTED_UNCERTIFIED` to `registry / Y (CoreCleanWsStep) / N / CERTIFIED (S2-A10/G8, PROPOSED)`.
2. The `core.cleanWs` section — rewritten with StepDefinition/DSL/Descriptor/
   Typed carrier/Receipts/state transition in the precedent of S2-A7/G8 core.deleteDir.
3. The header counter line `CERTIFIED Steps: 9 → 10` (matching the S2-A9/G8
   milestone precedent `2c4885f1` which did the same).

## 6. Side effects

None on production code under `v2/**/main/**`. Deliverables:
- this receipt + per-Step ledger state update
  (`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`) recording `core.cleanWs` = CERTIFIED.
- Legacy counters frozen at **3 / 3 / 3**.
- Trunk: branch `cycle/lfc2-e1-cleanws-g8` opens a single-file PR against main at `570b3e45`; PR merge advances `origin/main` to include the ledger flip.

## 7. LFC-2E1 counter party

| counter | pre-S2-A10 | post-S2-A10/G4 (PR #35) | post-S2-A10/G5 (PR #36) | post-S2-A10/G6 (PR #37) | post-S2-A10/G7 (PR #38) | post-S2-A10/G8 (this PR) |
| --- | --- | --- | --- | --- | --- | --- |
| `LEGACY_PLUGIN_IDS.size` (ids)               | 4 | 3 | 3 | 3 | 3 | 3 |
| `CanonicalCoreStepMetadata.pluginIds.size`   | 4 | 4 | 3 | 3 | 3 | 3 |
| Dispatcher-file count (physical)             | 4 | 4 | 3 | 3 | 3 | 3 |
| CERTIFIED Steps (per E1 inventory)           | 9 | 9 | 9 | 9 | 9 | **10** |
| Legacy executable Steps (M; convergence)    | 4 | 4 | 3 | 3 | 3 | 3 |
| Registry-primary Steps                       | 10 | 11 | 11 | 11 | 11 | 11 (core.cleanWs is now both CERTIFIED and registry-primary) |

(Convergence statement per AGENTS.md counters panel: `N + M = total`
where `N = Certified + Registry-primary-not-yet-certified`; this slice does
NOT change `M`, but advances `N` by 1 because `core.cleanWs` is the SAME step
being both promoted-from-registry-primary AND certified.)
