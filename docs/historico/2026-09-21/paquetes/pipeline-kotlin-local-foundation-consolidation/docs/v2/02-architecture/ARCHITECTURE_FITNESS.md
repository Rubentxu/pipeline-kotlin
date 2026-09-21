# Architecture fitness functions

Automate these as Gradle tests/static checks. They are build gates, not review suggestions.

## Uniqueness

Fail if more than one production definition exists for:

- canonical pipeline IR root;
- `StepDescriptor` authority;
- `Effect` taxonomy;
- `ReplayPolicy` taxonomy;
- `EnvironmentComposer` authority;
- typed run/step outcome taxonomy.

## Process/runtime access

Fail if production code outside approved platform adapter packages uses:

- `ProcessBuilder`;
- `Runtime.exec`;
- `System.setProperty("user.dir", ...)`;
- direct creation of `ProcessDurableTaskRuntime` from a step/plugin handler.

## Environment/global access

Fail if application/plugin code uses `System.getenv()` or global user-dir reads directly. Platform adapters may expose a sanitized `RuntimeEnvironment` capability.

## Layering

Fail if application depends on modules suffixed/marked as local adapters. Generate and test the Gradle project dependency graph.

## Output

Fail if a `DomainEvent` contains an unbounded stdout/stderr payload type. Events may contain an output reference, cursor/range and bounded diagnostic preview.

## DSL truth

Ban DSL functions that return placeholder runtime values. Static builder functions return `Unit`, node handles or declarative references only. Runtime values exist only in the explicit scripted/runtime API.

## Complexity guardrails

Warn first, then gate repeated regressions:

- production file > 600 LOC;
- function > 80 LOC;
- > 7 parameters without a context/value object;
- nesting > 5;
- repeated run/stage/step index parameter bundles instead of typed IDs.

Thresholds are trend alarms, not design dogma; justified exceptions require a comment/annotation and review.
