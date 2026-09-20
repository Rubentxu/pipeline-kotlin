# WU-LPR-074 — Env UAT subprocess stream hygiene

**Date**: 2026-09-20  
**Status**: CLOSED  
**Authority**: LPR-GATE-1 follow-up, V2 testing rules 7–10

## Trigger and diagnosis

The LPR-073 module regression was cancelled after `jcmd` showed
`UatLocal005EnvSpecialCharsTest.WS-S-008` blocked in
`BufferedReader.readText()` over the spawned pipeline's stdout. The test had
created *two* pipes, then drained only stdout before waiting for the process.
A noisy stderr pipe can fill and block the child, leaving the test blocked on
stdout indefinitely.

## Change

`runPipeline` now redirects stdout and stderr to separate temporary files before
launching the child. It waits with a 90-second deadline, returns the durable
JSON stdout file, and forcibly destroys any surviving process in `finally`.
This removes the pipe cycle and provides the child stderr tail in a timeout
failure. The test's byte-level environment assertions are unchanged.

## Verification

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'UatLocal005EnvSpecialCharsTest.WS-S-008*' --rerun-tasks
# BUILD SUCCESSFUL in 41s

timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'UatLocal005EnvSpecialCharsTest' --rerun-tasks
# BUILD SUCCESSFUL in 1m 1s
```

Fresh XML canary:

```text
UatLocal005EnvSpecialCharsTest: tests="5" skipped="0" failures="0" errors="0"
```

Full-class log SHA-256:

```text
351e1a1cfdd448fab3181ccaf06c865e2b01a480c6820a3f7607e83acb80fca0
```

## Follow-up boundary

The three compatibility fixture failures seen before this UAT stall are
independent and remain for separate investigation:
`fixture05ScriptedIf`, `fixture25YamlRoundtrip`, `fixture27ZipUnzip`.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable, JDK ProcessBuilder stream semantics
Behaviour adopted:                  redirect both child streams before wait/read
Intentional deviations:             none
Security implications reviewed:     stderr remains temp-local; no secrets persisted by the test
Tests demonstrating the contract:    UatLocal005EnvSpecialCharsTest (5/0/0/0)
```
