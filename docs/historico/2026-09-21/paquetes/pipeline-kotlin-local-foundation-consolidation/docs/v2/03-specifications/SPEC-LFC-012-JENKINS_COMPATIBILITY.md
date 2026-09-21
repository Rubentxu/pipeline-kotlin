# SPEC-LFC-012 — Jenkins familiarity and migration contract

**Status:** proposed

## Catalogue as data

The Jenkins familiarity catalogue becomes machine-readable enough to drive:

- signature validation;
- generated docs;
- DSL compile fixtures;
- compatibility level checks;
- migration recipes.

## Admission of new steps

No new step family enters the standard distribution until:

1. canonical Jenkins surface/source is recorded;
2. Kotlin API shape is decided;
3. behavior subset is explicit;
4. F-level assigned;
5. UAT fixture exists;
6. output/effects/replay/capabilities are declared.

## Kotlin divergence policy

When Groovy behavior is type-unsafe, Kotlin may offer a safer surface. Example: `sh(returnStdout=true)` versus typed `shStdout()`/capture modes in scripted runtime. Such divergence can still be F3 if migration is mechanical and documented.
