# V2 Compatibility Baseline

## Runtime Environment

| Field | Value |
|---|---|
| pipelineRuntime | 2.0-dev |
| certified Kotlin | 2.4.10 |
| certified JDK | build 21 (temurin) |
| planned JDK | 25 |
| preAdoption Kotlin | 2.4.x-next, 2.5-RC |
| compilerPlugin required | false |

## Growth Plan (M1)

- Populate `v2/compatibility/` with corpus scripts per [COMPATIBILITY_CORPUS](./COMPATIBILITY_CORPUS.md).
- Expand matrix rows as new Kotlin/JDK versions are evaluated.
- Track breaking changes via ADR in `docs/v2/04-adrs/`.

## M2-R3 Population

- **Cycle**: `p-733fb505b5a6bd2d/m2-r3-jenkins-lsp-corpus`
- **Baseline**: `v2/compatibility/baseline.json` captured at first green run (M2-R3 apply phase)
- **Fixtures**: 6 corpus fixtures:
  - `01-basic.pipeline.kts` — single-stage hello world
  - `02-environment.pipeline.kts` — environment block + single stage
  - `03-stages.pipeline.kts` — 3 sequential stages (Build, Test, Deploy)
  - `04-sh.pipeline.kts` — shell execution with argv list
  - `05-scripted-if.pipeline.kts` — conditional via M2-R1 DSL `script {}`
  - `06-loop.pipeline.kts` — `for` loop via M2-R1 DSL `script {}`
- **Corpus harness**: `CompatibilityCorpusTest` in `:pipeline-application`
- **Diff policy**: 7-bucket classification per `COMPATIBILITY_CORPUS.md §7`
- **Note**: All fixtures use M2-R1 DSL shape (documented deviation from `DSL_SPEC.md §5-§8` per R5 deferral). No fixture uses `retry`/`timeout`/`parallel`/`credentials`.

## Notes

- M1 populates `v2/compatibility/` per `COMPATIBILITY_CORPUS.md`.
- Compatibility matrix is the source of truth for certified/pre-adoption toolchain combinations.
