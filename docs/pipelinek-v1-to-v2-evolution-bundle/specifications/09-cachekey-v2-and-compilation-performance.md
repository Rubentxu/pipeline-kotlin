# Specification — CacheKey v2 and measured compilation reuse

**Status:** Proposed, refined 2026-10-06. No cache implementation is certified by this document.

## 1. Authority and scope

Operationalize `docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md` §§3–7; do not create a competing compiler/cache API. `CacheKey.v2` is the proposed versioned identity for the reserved seam in the current scripting contract. At the inspected baseline its computation throws `UnsupportedOperationException`; it is not delivered. Reconcile it with canonical `CompilationCacheKey` vocabulary during consumer inventory.

Do not port V1 `ScriptCompilationCache`. Identity, artifact representation and phase equivalence precede reuse. [Spec 19](19-script-compilation-and-evaluation.md) owns phases/profile; [spec 20](20-compiled-artifact-cache.md) owns cache mechanics; [spec 21](21-compiler-session-lifecycle.md) owns optional session reuse.

## 2. Observed implementation gap

At baseline `569a088cc76f1a826c619577eefa1475404c3fb4`, `Kotlin24ScriptingHost` hashes source, sorted canonical classpath paths, a Kotlin version string and a manual host revision. Its `compile()` calls `evalWithTemplate`, compiling and evaluating together. This path does not expose a persistent bytecode cache lookup/write. A key alone is not a delivered cache.

Same-path JAR replacement and effective classpath precedence are not represented by that key. Options depend on a manual revision. These are identity gaps, not proof that an existing disk cache served stale bytecode.

## 3. Semantic input inventory — GR-004

| Dimension | Required material from its actual owner |
| --- | --- |
| Source | Exact original-source digest, actual lowered compiler-input digest, logical source/template identity and source-map revision |
| Dependencies | Ordered content digests from frozen `ScriptDependencyPlan`, including transitive support JARs and generated façades |
| Compiler | Actual embedded compiler/runtime artifact fingerprint and adapter implementation/ABI revision |
| Language | Effective language/API versions, JVM target, compilation JDK/API roots and certified compatibility profile |
| Configuration | Effective options, imports, template/base class, compiler plugins when present, compile-relevant properties |
| Pipeline semantics | DSL, lowering, façade/schema and runtime compatibility revisions required by the existing artifact contract |
| Format | Canonical encoding version and backend artifact-format version |

Compilation JDK and target JVM are different facts. `jvmTarget=21` does not alone prove use of Java 21 APIs. Inventory API roots and certify the intended combination. Profile/option digests derive from effective configuration, not a constant bumped manually. Runtime values that do not affect compilation do not enter compilation identity.

## 4. Canonical encoding and order

- Version a documented structured encoding with unambiguous field boundaries; delimiter concatenation needs proven escaping/length framing.
- Hash exact source bytes; no silent whitespace/newline normalization. Include actual transformations and their revisions.
- Preserve effective classpath/option order. Permutations are equivalent only if resolution produces the same effective ordered plan.
- Canonically sort genuinely unordered sets separately from ordered compiler inputs.
- Hash artifact content; paths, mtime and filename are not substitutes. Distinguish missing/null/empty where semantically different.
- Installation-root relocation does not change semantic identity. Meaningful logical source names or compilation-affecting locations are explicit inputs. Source mapping must still target the current source; portability cannot silently change source-observable behaviour.

## 5. Frozen dependencies — GR-005

Resolution supplies the complete admitted graph before compilation. Compile/evaluation views use coherent identities. The compiler reads the immutable bytes actually hashed/admitted; reopening a mutable user path after hashing is forbidden.

Development directories must be snapshotted or cause an explicit cache bypass. Current artifact admission still runs on a hit. The key is neither trust nor execution authorization.

## 6. Measurement and adoption

Use [the benchmark protocol](../quality/gradle-benchmark-protocol.md), aligned with current `WU-RP-022`. Separate resolve/admit, compile, load, evaluate and runtime durations. Record actual compiler/hit counts, candidate/distribution digests, input identities, JDK, CPU time, RSS and retained resources.

M7 always delivers certified identity, phase/representation characterization and a measured decision. Bounded memory reuse is conditional on GO. Persistence and warm sessions each require separate GO and ADR gates. NO-GO is a valid milestone result with evidence, not a simulated feature. Define/approve SLOs after baseline measurement; this package invents no universal latency/RSS threshold.

## 7. Separate cache responsibilities

| Cache | Stored value | Position |
| --- | --- | --- |
| Resolver | Admitted dependency bytes/graph | M5, resolver owner |
| Compilation | Backend artifact + compile diagnostics + identity | Conditional M7, spec 20 |
| Class loading | Loaded classes/resources in a compatible scope | Optional M7 session slice, spec 21 |
| Evaluation/configuration | Evaluated values, plans or closures | Excluded; future ADR and input-observation model |
| Execution/recovery | Runtime facts/values/durable history | Existing runtime authorities |

## 8. Exit and migration

UAT-050/051/052/053 and AAT-014/015/023 prove content/order/profile invalidation and relocation with correct source mapping. Each GO additionally satisfies its spec 20/21 gates. Historical v1 event decoding remains; v1 is never reinterpreted as v2. Compile from source when a prior entry cannot prove the new identity. Public port migration requires installed consumer/ABI compatibility tests.
