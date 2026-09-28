# Semantic Constitution

## 1. Purpose

PipelineK must never accept a DSL declaration that becomes indistinguishable from a different program at runtime.

The `whenCondition` defect is the canonical counterexample:

```text
whenCondition("1 == 2") { echo("SHOULD-NOT-RUN") }
```

was accepted, the predicate was discarded, the body was flattened to canonical Steps, and the run succeeded. A canonical-Step gate could not detect the semantic loss.

This constitution generalizes the fix to the whole language.

## 2. Closed semantic categories

```kotlin
sealed interface DslSemanticKind {
    data object DeclarativeDirective : DslSemanticKind
    data object AtomicStep : DslSemanticKind
    data object BlockStep : DslSemanticKind
    data object PureBuilder : DslSemanticKind
    data object ScriptedRuntimeCall : DslSemanticKind
}
```

A public DSL function must have exactly one `DslSemanticKind` entry in the machine-readable surface manifest.

### 2.1 Declarative Directive

Changes stage/pipeline orchestration, admission, context, allocation, lifecycle or finalization.

Examples:

- `when`
- `post`
- `agent`
- stage/pipeline `environment`
- `options`
- future `input` directive

A directive is **not** a Step.

### 2.2 Atomic Step

One typed invocation with no body.

Examples:

- `sh`
- `echo`
- `writeFile`
- `archiveArtifacts`
- `scm-git.checkout`

### 2.3 Block Step

A Step with body/bodies whose execution shape is declared by `StepBody` / `BodyExecutionPolicy`.

Examples:

- `dir`
- `withEnv`
- `withCredentials`
- `retry {}`
- `timeout {}`
- `parallel {}`
- `waitUntil {}`

### 2.4 Pure Builder

Constructs configuration only and performs no effect nor Step emission.

Examples:

- `scmGit(...)`
- credentials binding constructors
- future typed predicates
- plugin configuration builders

Law:

```text
PURE_BUILDER -> zero StepNode / zero event / zero runtime effect
```

### 2.5 Scripted Runtime Call

A typed runtime-returning invocation used by normal Kotlin control flow.

Examples:

- `pwd(): String`
- `isUnix(): Boolean`
- `fileExists(): Boolean`
- `readFile(): String`
- `sh(... returnStdout=true): String`

It is suspend/runtime semantics, not eager graph construction.

## 3. Semantic Conservation Law

Every public construct must satisfy exactly one of:

```text
A. EXPLICIT_CARRIER
   Intent is represented in typed IR/metadata/runtime call.

B. PURE_DESUGAR
   Intent lowers to another supported carrier with proven semantic equivalence.

C. FAIL_CLOSED
   Unsupported construct rejects before any effect.
```

Forbidden states:

- `SEMANTIC_DROP`
- `FLATTEN_WITH_LOSS`
- `SILENT_NOOP`
- `DEFAULT_SUCCESS`
- `MUTATE_IF_POSSIBLE_ELSE_IGNORE`
- dead parameters whose values never reach a carrier
- metadata accepted but never interpreted

## 4. Machine-readable DSL surface manifest

Add a versioned manifest owned by `pipeline-scripting-api`, preferably Kotlin data + generated JSON projection:

```kotlin
data class DslSurfaceEntry(
    val symbol: DslSymbol,
    val kind: DslSemanticKind,
    val carrier: SemanticCarrier,
    val support: SupportStatus,
    val eventContract: EventContractRef,
    val certificationProfile: CertificationProfileRef,
)
```

`SupportStatus`:

```kotlin
sealed interface SupportStatus {
    data object Stable : SupportStatus
    data object Experimental : SupportStatus
    data class Partial(val limitation: LimitationId) : SupportStatus
    data class Unsupported(val reason: String) : SupportStatus
    data object Deprecated : SupportStatus
}
```

Fitness law: every public DSL symbol in approved scopes must be represented exactly once. A new symbol without manifest entry fails CI.

## 5. Carrier examples

| Surface | Kind | Carrier |
|---|---|---|
| `echo` | AtomicStep | `StepInvocation(core.echo)` |
| `git` | PureDesugar | `checkout(scmGit(...))` |
| `scmGit` | PureBuilder | `CheckoutSpec` |
| `dir` | BlockStep | `StepBody.Declared + Scoped(CWD)` |
| `retry {}` | BlockStep | `Retrying` |
| `when` | DeclarativeDirective | `DirectiveInvocation(core.when)` |
| `post` | DeclarativeDirective | `FinalizerPlan` |
| `pwd()` runtime | ScriptedRuntimeCall | `ScriptedRegistryInvocation<PwdOutput>` |
| `whenCondition(String)` legacy | Unsupported | fail closed |

## 6. Structural purity law

Configuration/builders cannot append Steps as a side effect.

Canonical example:

```kotlin
fun scmGit(...): CheckoutSpec = CheckoutSpec(GitScm(...))
fun git(...): Unit = checkout(scmGit(...).scm)
```

Required tests:

- `scmGit()` -> zero Steps;
- `checkout(scmGit())` -> one checkout;
- `git()` -> one checkout.

## 7. No retroactive mutation

APIs such as `retry(count, delay)` that mutate the previously emitted Step are prohibited as a stable design pattern.

If retained temporarily for compatibility:

- no previous Step -> fail closed;
- incompatible previous Step -> fail closed;
- mark deprecated;
- canonical replacement is a block Step.

## 8. Observable semantics requirement

For every supported construct, certification must include:

1. structural witness — correct carrier exists;
2. semantic witness — behavior differs correctly under discriminating inputs;
3. outcome witness — typed outcome is correct;
4. event witness — expected typed events occur and forbidden ones do not;
5. replay witness — restart/resume preserves semantics;
6. negative witness — unsupported/incoherent variants fail before effects.

Compilation alone is never semantic evidence.
