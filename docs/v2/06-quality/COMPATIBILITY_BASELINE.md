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

## Notes

- M1 populates `v2/compatibility/` per `COMPATIBILITY_CORPUS.md`.
- Compatibility matrix is the source of truth for certified/pre-adoption toolchain combinations.
