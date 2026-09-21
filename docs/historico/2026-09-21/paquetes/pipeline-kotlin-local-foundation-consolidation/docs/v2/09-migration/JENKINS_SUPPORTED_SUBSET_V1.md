# Proposed Jenkins migration subset v1

This is a target corpus, not a claim of current implementation.

## Declarative directives

Prioritize:

- `pipeline`
- `agent any` / `agent none`
- `environment`
- `options` for implemented options
- `parameters` basic string/boolean/choice
- `stages` / `stage` / `steps`
- `when` common branch/environment/expression boundary
- `post` common conditions
- `parallel`
- `script` as explicit assisted-migration boundary

## Standard steps first wave

- `echo`, `error`, `sleep`;
- `sh`;
- `dir`, `pwd`, `fileExists`, `readFile`, `writeFile`, `deleteDir` once real semantics exist;
- `withEnv`;
- `withCredentials` common bindings;
- `timeout`, `retry`, `catchError`, `warnError`;
- `checkout`, `git`;
- `archiveArtifacts`;
- `junit`.

## Out of v1 automatic migration

- arbitrary Groovy metaprogramming;
- dynamic shared-library APIs without a typed mapping;
- Jenkins controller internals (`currentBuild.rawBuild`, plugin-specific Java objects);
- CPS-specific tricks;
- steps whose behavior cannot be implemented/tested locally.

Unsupported constructs produce explicit structured diagnostics with source location and migration guidance.
