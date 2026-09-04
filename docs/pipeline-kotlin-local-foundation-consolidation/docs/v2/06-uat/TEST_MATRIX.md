# Test matrix

| Layer | Test type | Runs on PR | Nightly/release |
|---|---|---:|---:|
| domain/IR | unit + property/golden | yes | yes |
| DSL compiler | compile fixtures | yes | yes |
| KSP | compile-testing/golden metadata | yes | yes |
| plugin API | binary/source compatibility | yes | release |
| process runtime | integration | yes | yes |
| credentials | integration/security | yes | yes |
| output store | integration | yes | stress |
| graph | projection/rebuild | yes | yes |
| CLI | black-box | yes | yes |
| Jenkins migration | corpus | selected | full corpus |
| distribution | smoke | release branch | release matrix |
| performance | small regression sentinel | selected | full benchmark/soak |

## Platform baseline

PR: Linux x86_64 mandatory; add macOS arm64 for platform-sensitive slices.  
Release: every officially published platform artifact must execute its distribution UAT before publication/default promotion.
