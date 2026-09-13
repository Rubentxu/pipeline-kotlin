# B10 / W1b — typed body execution policy (mechanism only)

> status: **IMPLEMENTED_UNCERTIFIED** — W1c is the consumer migration; nothing here is claimed done
> lane: `cycle/lfc2-e1-bodyinvoker-w1b`
> base: `5168a064` (= `origin/main` after PR #49, B10 W1a ledger)
> authority: ADR-0073 (block-step re-entry), ADR-0081 (accepted); `AGENTS.md` "closed execution
> structure, open Step registry" / "no central concrete-Step switch"
> milestone: B10 — "block Steps re-enter engine; no `dispatch*Block` collection"

## 1. What W1b is, and what it deliberately is not

W1a made the concrete routing debt measurable and unextendable: the coordinator still routes
bodies on concrete block Step identities, and the pinned ledger records that debt at
`total = 18 == HISTORICAL_CEILING`.

W1b introduces the **typed mechanism** that lets a Step *declare* how its body executes, so the
coordinator can eventually interpret a declaration instead of switching on a name. It changes
**zero routing behaviour** and leaves the debt at exactly 18.

The order matters. Migrating consumers first would have migrated four distinct policies
(scoped-context, retrying, parallel) with no representation able to express them, and no way to
state a would-be-invalid combination as invalid. W1b buys representability and fail-closed
resolution; W1c starts burning the ledger against it.

## 2. The law

```text
a body execution policy is declared next to the Step contract   -> the only authority
resolution is a pure function of the declaration + engine support
unknown StepKey                                                 -> Rejected(UnknownStep)
non-body Step declaring a reshape                               -> Rejected(NotABodyStep)
declaration incoherent with its own metadata                    -> Rejected(IncoherentMetadata)
engine does not support the declared shape                      -> Rejected(UnsupportedByEngine)
a Step name in the policy vocabulary or the coordinator          -> FAIL (fitness)
```

Incoherent combinations are rejected *before* any effect. The four rejections are a closed
algebra (`BodyPolicyRejection`), so a caller cannot coordinate a boolean with a nullable value to
guess which case it holds.

## 3. The representation

`BodyExecutionPolicy` is a closed ADT of execution **shapes**, never of Steps:

```text
Sequential                    body runs once, in declaration order, in the caller's context
Scoped(projection)            body runs once inside one derived context, projected from typed input
Retrying(policy)              body may run more than once; attempt identity is a segment key
Parallel(policy)              body fans out; branch identity is a segment key

BodyContextProjection = WorkingDirectory | Environment | Timestamps | Deadline | CredentialLease
BodyExecutionPolicyShape = SEQUENTIAL | SCOPED | RETRYING | PARALLEL
```

`WorkingDirectory`, `Environment`, `Deadline` and `CredentialLease` map onto the existing
`ContextKind`; `Timestamps` maps to `null` — a real fact ("this projection introduces no
context kind"), not a missing value. That distinction is why the mapping returns a nullable
`ContextKind?` rather than inventing a `NONE` enum member that would collide with a future real
kind.

Coherence (`incoherenceOf`) is checked in one direction only, and deliberately:

```text
Retrying  requires bodyInvocations = ZERO_OR_MORE     (a retry that cannot repeat is incoherent)
ZERO_OR_MORE requires Retrying or Parallel            (repeat cardinality needs a repeat policy)
Scoped    requires a matching introducesContext        (a projection must name the kind it projects)
```

The converse is **not** checked: `core.timeout` declares `Scoped(Deadline)` and `core.catchError`
declares `Sequential`, while both share `introducesContext = CANCELLATION`. Requiring every
`CANCELLATION` Step to be `Scoped(Deadline)` would be a false law — the metadata says which kind
is introduced, the policy says how the body runs, and they are not the same question.

## 4. Where the declaration lives

`StepDescriptor` gains `bodyExecutionPolicy: BodyExecutionPolicy = BodyExecutionPolicy.DEFAULT`
(= `Sequential`), so the declaration is registry metadata resolved **before** decode, next to
`effects`, `replayPolicy` and `recoveryPolicy` (LB-02 / G3-A4.1). The default is total: every Step
that declares nothing keeps sequential execution.

`BodyPolicyResolver` is a `fun interface`; `RegistryBodyPolicyResolver(registry, support)` reads
`registry.definition(key)?.contract?.descriptor` and **never** a name table. `BodyExecutionSupport`
admits over `Set<BodyExecutionPolicyShape>`, so admission is a question about shapes, not keys.

The W1b engine support is `SEQUENTIAL_ONLY`: the coordinator still executes scoped/retrying/
parallel bodies through its concrete scope switch, so those shapes are *representable but
rejected as unsupported* at the boundary. This is the honest state — the mechanism exists, the
consumer does not.

## 5. Declared rows (and the declared gap)

| StepKey | Declared policy |
| --- | --- |
| `core.catchError`, `core.warnError` | `Sequential` |
| `core.withEnv` | `Scoped(Environment)` |
| `core.dir` | `Scoped(WorkingDirectory)` |
| `core.withCredentials` | `Scoped(CredentialLease)` |
| `core.timeout` | `Scoped(Deadline)` |
| `core.retry` | `Retrying` |

Twelve descriptor rows exist; six are body-bearing. The gap is stated rather than hidden:
`core.timestamps` and `core.parallel` are routed by the coordinator but have **no descriptor row
at all**, so they cannot declare a policy yet. That is recorded as `PolicyDeclarationGap` in the
domain tests and asserted in both directions, so it cannot silently widen.

Two descriptors that are *not* routed as bodies (`core.emit.event`, `core.sh`) declare the
`Sequential` default; no body-less row declares a reshape.

## 6. Slice contents

| File | Kind |
| --- | --- |
| `pipeline-domain/.../domain/step/BodyExecutionPolicy.kt` | new — closed policy ADT, projection ADT, support/rejection/resolution algebras, pure `resolveBodyExecutionPolicy`, registry-backed port |
| `pipeline-domain/.../domain/StepDescriptor.kt` | edit — `bodyExecutionPolicy` metadata field (default sequential) |
| `pipeline-domain/.../domain/StepDescriptorRegistry.kt` | edit — six declared rows, plus a read-only `keys()` enumeration seam |
| `pipeline-domain/.../domain/step/BodyExecutionPolicyTest.kt` | new — 20 tests in 4 groups |
| `pipeline-architecture-tests/.../architecture/Lfc2BodyExecutionPolicyFitnessTest.kt` | new — 9 laws |
| `pipeline-domain/.../domain/StepDescriptorBodyMetadataTest.kt` | edit — regression-guard prose extended to name the new field |
| `.agent/TESTING-STATE.md` | edit — W1b baselines, the declared gap, and the lessons this slice recorded |

**No production file outside `pipeline-domain` is touched.** `CanonicalDurableRunCoordinator.kt`,
the legacy plugin-id table, the W1a ledger and the W1a guard are all untouched, and asserted as
such by the verifier.

## 7. Evidence

```text
:pipeline-domain:test               tests=388 failures=0 errors=0    (was 368/0/0 → +20)
:pipeline-architecture-tests:test   tests=261 failures=1 errors=0    (was 252/1 → +9)
BodyExecutionPolicyTest                                          20/0/0
Lfc2BodyExecutionPolicyFitnessTest                                9/0/0
Lfc2ConcreteBodyRoutingDebtFitnessTest (W1a)                     11/0/0 unchanged
pinned debt total                                                18   unchanged
```

The architecture base `252/1` is the W1a-measured value read from its archived XML. The
domain base `368/0/0` is derived (`388` minus the 20 new rows), and the derivation is
cross-checked by the same arithmetic on the architecture suite (`261 - 9 = 252`, the
W1a-measured number). Both suites were executed at the slice commit; base was not recompiled
to re-prove a result already captured.

The single red is the pre-existing `Lfc0GlobalStateFitnessTest`, byte-identical after
checkout-path normalisation to the Lane R base XML, and red for the same reason as there. It is
not widened by this slice.

Freshness: all XMLs were deleted before the recorded run, and the run used `--no-build-cache`, so
the archived results are executions rather than cache restores. Archived at
`evidence/b10-w1b/raw/xml/module-suites-xml.tar.gz`
(`sha256 53b6d471dc3ce4af2bc422f7e0ee025c4884fb81bc28dec29601bda37d68cb6c`) with the console log at
`raw/logs/module-suites.log`.

## 8. Verifier

`evidence/b10-w1b/verify-b10-w1b-receipt.py` reads the slice commit historically
(`git show <sha>:<path>`), never the working tree, so it cannot be satisfied by editing the tree
after the decision was taken. It re-derives its claims in Python rather than reading them back:

```text
scope        the slice changed exactly these files; coordinator/decoder/ledger/build untouched
vocabulary   the policy module holds no step literal, no step-key switch, no else branch, and
             imports nothing outward (no application, coroutines, JDK I/O)
             four shapes, five projections, four rejections, two resolution cases
declaration  every expected row declares the expected policy; every declaration is coherent with
             its own metadata; the gap is exactly {timestamps, parallel}; the default is sequential
debt         the coordinator's concrete routing inventory is re-scanned in Python and compared to
             the W1a-measured inventory: 18, unchanged, at the ceiling
evidence     both suites re-parsed from the archived XML; the single red is the known base failure;
             the architecture delta is exactly +9
```

## 9. Controls

Eleven controls, each run **independently from the pristine slice commit** (`git reset --hard
<slice>`), each printed with the set of files it touched to prove independence, and each required
to fail **for the intended law** — 11/11. One control produced `COMPILE_ERROR` on the first pass
and was rewritten; a control that fails to compile is not a valid red and proves nothing.

| Control | Mutation | Intended law tripped |
| --- | --- | --- |
| C1 | step-name literal in the policy module | "must name execution shapes, not Steps" |
| C2 | compiling `when (stepName)` switch | "not a switch over a StepKey or step name" |
| C3 | `when` with an `else` branch | "else branch would silently absorb" |
| C4 | `import java.nio.file.Path` in the decision layer | "no application, coroutines, or I/O" |
| C5 | `core.dir` declared `Sequential` | "disagrees with its live routing" |
| C6 | `core.retry` declared `Retrying` with `ONCE` cardinality | "must declare a policy that is coherent" |
| C7 | `core.dir` declared `Scoped(Environment)` | "must declare a policy that is coherent" |
| C8 | `core.sh` (no body) declared a reshape | "must not declare a body execution shape" |
| C9 | descriptor default changed away from `Sequential` | "must preserve existing behaviour" |
| C10 | coordinator starts resolving policies | "routing bodies through it is W1c" |
| C11 | rename a coordinator literal **and** the ledger literal together | W1a-measured debt re-scan |

**C11 is the isolating control.** Source and pin are edited so they agree with each other; every
Kotlin law stays green (the W1a ledger test passes). Only the verifier's independent re-scan
against the inventory W1a measured can see it. Without C11 the re-scan could be a mirror of the
pin and nobody would know.

## 10. Gate

```text
base                      5168a064 == origin/main at slice cut
coordinator changes       0
legacy table changes      0
build changes             0
W1a debt                  total = 18, ceiling = 18, unchanged
new reds                  0
verifier                  48/48 (receipt checks included, receipt committed with the slice)
controls                  11/11 for the intended law
```

Nothing in this receipt claims the debt is falling. It is not: W1b is the mechanism, and the
shapes it can express are exactly the shapes W1c must move off the coordinator's switch.
