# Risk register

| ID | Risk | Impact | Control / gate |
|---|---|---:|---|
| R1 | Shared Library becomes a hidden runtime contributor | Critical | all canonical contributor families rejected, including transitives; AAT-004/UAT-031; arbitrary JVM I/O is a distinct trust/isolation concern |
| R2 | Tooling creates a second Step registry | High | IntrospectionService projection law, AAT-007 |
| R3 | `--plan` diverges from runtime admission | Critical | one preparation decision algebra, mutation/AAT-008 |
| R4 | Static plugin manifest lies about runtime contributor | High | runtime cross-check, fail closed |
| R5 | Rejected plugin initializes code before trust decision | Critical | static JAR manifest reader + initializer-canary UAT/AAT |
| R6 | Library resolution makes builds non-reproducible | High | immutable version+digest, no Git build-on-run, cache evidence |
| R7 | Maven resolver writes into repo | Medium | XDG resolver, no-litter UAT/AAT |
| R8 | Cache serves stale compilation after same-path JAR replacement | High | CacheKey.v2 content digests before cache |
| R9 | Compilation cache refactor breaks DSL honesty gates | Critical | no cache until equivalence spike; AAT-015 |
| R10 | LSP metadata drifts from runtime | Medium | common introspection snapshot; versioned loader |
| R11 | HATEOAS-like API becomes its own command router | High | actions are projections; invocation through existing facade/runtime |
| R12 | Catalog digest mistaken for signature | Medium | document as cache invalidation only |
| R13 | Trust enum gains fake states | High | evidence-backed subtype law, AAT-012 |
| R14 | V1 removed before migration oracle is exhausted | High | M10 terminal sequencing |
| R15 | V1 never removed because "historical" becomes permanent | High | M10 mandatory programme exit criterion |
| R16 | Legacy cleanup deletes still-current examples/docs | High | M0 inventory + M9 zero-dependency/disposition + deletion manifest |
| R17 | Old docs retained create two roadmap authorities | High | M10 docs cleanup; current roadmap index rebuilt |
| R18 | CLI schema breaks agents between minor versions | Medium | formatVersion rules + golden compatibility fixtures |
| R19 | Sorting destroys semantic classpath order or order is nondeterministic | High | preserve effective ordered plan in identity; sort only unordered metadata; UAT-052/AAT-023 |
| R20 | CPU/memory "limits" are falsely advertised | High | exclude non-enforceable budgets; spec 10 |
| R21 | Artifact retains prior context, credentials, Step output or evaluated graph | Critical | compiled code only; fresh evaluation and retention canaries; GR-003/010, UAT-055/058, AAT-021 |
| R22 | Digest is checked then mutable path is reopened | Critical | immutable admitted-byte snapshot; deterministic barrier UAT-064/AAT-023 |
| R23 | Hit bypasses current revocation/capability admission | Critical | GR-012, UAT-063/AAT-028; no cached readiness verdict |
| R24 | Partial/corrupt persistent entry loads executable code | Critical | atomic publication, integrity before load, process fault injection; UAT-065/066/AAT-024 |
| R25 | Cancellation reports success while compiler CPU/process keeps running | High | actual backend-stop evidence; subscriber isolation; UAT-057/081 |
| R26 | Compile cache is mistaken for configuration/evaluation cache | High | no evaluated graph/input-read model; separate cache scopes, GR-010 |
| R27 | Duplicate profile/manifest/port authority drifts from actual compiler/SDK | High | existing canonical ports and S6 reused; GR-001/006; AAT-022/023 |
| R28 | Warm classloaders leak static state or incompatible Kotlin identity | High | profile partitioning and state canaries; fresh scopes or NO-GO; UAT-080/082 |
| R29 | Memory-only cache is advertised as cross-command CLI speedup | Medium | new-process actual compile count; persistence separately gated; UAT-056/059 |
| R30 | Suppression masks real warnings or an upgrade is assumed to fix Unsafe | High | standard reader, real deny/warning/error corpus; GR-007/009, UAT-070/071 |
| R31 | JVM target is mistaken for the compilation JDK API limit | High | actual profile/API roots and emitted target matrix; UAT-053/072 |
| R32 | Local checksum is sold as shared-cache authenticity or library purity | Critical | owner-local trust boundary; no shared executable cache or arbitrary-I/O purity claim; specs 04/19/20 |
