# S2 Release Receipt — Slice 2 (YAML + findFiles + zip + unzip)

**Date:** 2026-09-19
**Scope:** Slice 2 release — 5 CERTIFIED Steps + corpus closure
**Release tag:** `v2-lfc2e2-s2-2026-09-19` (annotated, points to release commit)
**Tag dereference:** `git rev-parse v2-lfc2e2-s2-2026-09-19^{commit}` returns the release commit SHA.
**Tag annotation:** see `git tag -l v2-lfc2e2-s2-2026-09-19 -n10`.
**Base before S2 work:** `2a7beedb` (S2.1 first commit) was on top of `69efcd20`
  (LFC-2E2 utilities first-slice CERTIFICATION RECEIPT).
**Push plan:** `git push origin main` then `git push origin v2-lfc2e2-s2-2026-09-19`.

## Steps released

| Key | Family | State |
|---|---|---|
| `core-utils.readYaml` | utilities | CERTIFIED |
| `core-utils.writeYaml` | utilities | CERTIFIED |
| `core-utils.findFiles` | utilities | CERTIFIED |
| `core-utils.zip` | utilities | CERTIFIED |
| `core-utils.unzip` | utilities | CERTIFIED |

## Verification evidence

### L0 — Compile
- `:pipeline-step-sdk:utilities:compileKotlin` — green
- `:pipeline-step-sdk:utilities:compileTestKotlin` — green
- `:pipeline-application:compileTestKotlin` — green

### L1 — Targeted contract suites
- `:pipeline-step-sdk:utilities:test --tests '*CoreYamlReadStepTest'` — 14/14 green
- `:pipeline-step-sdk:utilities:test --tests '*CoreYamlWriteStepTest'` — 11/11 green
- `:pipeline-step-sdk:utilities:test --tests '*CoreFindFilesStepTest'` — 18/18 green
- `:pipeline-step-sdk:utilities:test --tests '*CoreZipStepTest'` — 18/18 green (12/12 contract + 6/6 safety)
- `:pipeline-step-sdk:utilities:test --tests '*CoreUnzipStepTest'` — 20/20 green

### L2 — Module suite
- `:pipeline-step-sdk:utilities:test` — 112 contract + 6 safety = **118/118 green**

### L4 — Corpus full
- `:pipeline-application:test --tests 'CompatibilityCorpusTest.*'` — **28/29 green**
  - 1 pre-existing failure: `fixture05ScriptedIf` (WU-LPR pre-existing regression, documented in `S2_HANDOFF.md`)

### L5 — Round gate (`./gradlew -p v2 check`)
- Build succeeded for all modules except 3 pre-existing test failures (no regressions introduced by S2):

| Failing test | Owner | Reason (pre-existing) |
|---|---|---|
| `Lfc0GlobalStateFitnessTest` | scm-git / junit (commits `035b3afe`, `add1dfa6`) | `System.getProperty("user.dir")` fallback in production code — debt tracked by WC-SCM/F5.2 owners |
| `Lfc0V1QuarantineFitnessTest` | documentation owner | Root `README.md` lacks `LOCAL_FOUNDATION_CONSOLIDATION.md` link |
| `UatLocal005CorpusUntouchedTest.CP-002` | corpus steward | Asserts `corpus has exactly 22 valid fixture files`; corpus now has 28 (S2 added 5, plus 1 prior `24-utilities-roundtrip`) |

All S2 contributions verified at L0/L1/L2 with fresh JUnit XML; no S2 production change introduced a regression.

## Corpus growth

```text
v2/compatibility/*.pipeline.kts:
  before S2: 23 (22 LPR + 24-utilities-roundtrip already present)
  after  S2: 28 (added 25-yaml-roundtrip, 26-find-files,
                  27-zip-unzip, 28-zip-slip-defense, 29-mixed-utilities)
```

## Counter ledger (project dashboard)

```text
Certified Steps:            5   (S2.1..S2.5)
Legacy executable Steps:    0   (no LPR steps touched)
Registry-primary Steps:     5
```

## S2 commits included in this release

```text
2a7beedb  S2.1  readYaml step
aa5cf444  S2.2  writeYaml
fa8f9633  S2.2  writeYaml fix/follow-up
ed0c43a0  S2.3  findFiles
df534f36  S2.3  findFiles follow-up
28968af7  S2.4  zip
ec9f7be1  S2.4  zip closure receipt
8af34b81  S2.5  unzip
ed1c262d  S2.5  unzip closure receipt
215f8cfd  S2    5 corpus fixtures + 5 CompatibilityCorpusTest methods
bda2c060  S2    handoff doc
```

## Documentation published

- `S2_READYAML_WRITEYAML_JENKINS_REFERENCE.md`
- `S2_FINDFILES_JENKINS_REFERENCE.md`
- `S2_FINDFILES_CLOSURE_RECEIPT.md`
- `S2_WRITEYAML_CLOSURE_RECEIPT.md`
- `S2_ZIP_JENKINS_REFERENCE.md`
- `S2_ZIP_CLOSURE_RECEIPT.md`
- `S2_UNZIP_JENKINS_REFERENCE.md`
- `S2_UNZIP_CLOSURE_RECEIPT.md`
- `S2_HANDOFF.md`
- `S2_RELEASE_RECEIPT.md` (this file)

## Architectural decisions (frozen in code)

1. Sealed ADTs over JsonElement in DSL surface (`UnzipMode`, `UnzipOutput`, `YamlDocument`).
2. Capability-routed handlers via `WORKSPACE_IDENTITY_CAPABILITY`; `Effect.WRITES_WORKSPACE + ReplayPolicy.NEVER` for write Steps; `overwrite=false` default.
3. Symlinks never followed for reads (`Files.walk` without `FOLLOW_LINKS`).
4. Zip Slip containment on **both** archive-creation and extraction sides (CVE-2023-32981).
5. Jenkins-compat glob fallback (`**/` matches with or without prefix).
6. Closed ADTs over JsonElement in DSL surface — `YamlDocument` ADT (scripting host doesn't resolve `kotlinx.serialization.*`).

## Next steps (carried from `S2_HANDOFF.md`)

1. Patch `UatLocal005 CP-002` corpus count (22 → 28) — affects another agent's WU.
2. Patch `Lfc0V1QuarantineFitnessTest` README link (debt owner: doc steward).
3. `scm-git` / `junit` global-state debt: `System.getProperty("user.dir")` fallback (debt owner: WC-SCM/F5.2).
4. Slice 3 planning per `LPR_WORK_UNITS.md`.
