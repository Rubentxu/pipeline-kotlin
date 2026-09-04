# Jenkins familiarity guide for plugin authors

If a plugin exposes a Jenkins-familiar extension:

1. reference the canonical Jenkins step/directive/plugin source;
2. record parameter names/defaults and behavior subset;
3. avoid copying Groovy weaknesses that Kotlin can safely improve;
4. assign F0-F3 honestly;
5. provide fixture(s) matching the claim;
6. document semantic divergences;
7. include migration mapping if F3.

## Example

Jenkins `sh(script: ..., returnStdout: true)` changes effective return type based on a boolean. A Kotlin scripted API MAY expose `shStdout(script)` or a typed capture parameter instead. It remains migration-compatible when a deterministic recipe exists.

## Standard bundle priority

Prioritize high-frequency pipeline primitives before long-tail plugins: shell/process, files/workspace, environment, credentials, SCM checkout, artifacts, JUnit/test results, timeout/retry/error handling, parallel, basic notifications only when justified by local-first workflows.
