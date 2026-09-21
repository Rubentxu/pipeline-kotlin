# SPEC-LFC-017 — Executable Scenario Corpus

**Status:** proposed

## Inventories

```text
examples/                  # user-facing
v2/compatibility/          # compatibility corpus
test-fixtures/scenarios/   # adversarial/internal
plugin-fixtures/           # independent plugin builds
```

## Bundle

```text
20-nested-env/
  pipeline.pipeline.kts
  scenario.yaml
  expected/
    ir.json
    events.json
    stdout.txt
    stderr.txt
    filesystem.json
    replay.json
```

El layout plano actual puede coexistir durante migración.

## Manifest

Ejemplo:

```yaml
schemaVersion: 1
id: EX-020
title: nested environment
kind: example
fidelity: T2
pipeline: pipeline.pipeline.kts

requires:
  os: [linux]
  network: false

expect:
  compilation: success
  exitCode: 0
  ir: expected/ir.json
  events: expected/events.json
```

## ScenarioRunner

Responsabilidades:

1. crear fixture aislada;
2. resolver plugins;
3. compilar/validar;
4. comparar diagnostics o IR;
5. ejecutar;
6. recoger typed events/output/filesystem;
7. opcionalmente kill/restart/replay;
8. normalizar sólo campos nondeterministas;
9. comparar expectations;
10. guardar diagnostics;
11. cleanup.

JUnit, Just, CLI y CI reutilizan el mismo runner.

## Golden policy

Se pueden normalizar:

- timestamps;
- run IDs;
- temp workspace absolute roots.

No se normaliza:

- StepKey;
- body shape;
- FailureKind;
- attempts/branches;
- replay decision;
- capability errors;
- exit codes.

## Corpus mínimo

### Basics
hello, multi-stage, env, ordering.

### Shell
stdout, stderr, exit code, multiline, shebang, `$VAR`, Unicode, huge output, child process.

### Workspace/files
dir nesting, read/write/existence, cleanup, missing file, traversal negative.

### Environment
pipeline/stage env, nested withEnv, PATH, special chars, restore.

### Error/control
error, unstable, catchError, warnError.

### Retry
first success, eventual success, exhausted, nested, attempts, counters.

### Timeout/cancel
success, deadline, process-tree kill, nested, Ctrl-C.

### Parallel
2/4 branches, siblings, failure, cancel, nested block, overlap.

### Credentials
text, username/password, file, redaction, cleanup, nested restore.

### Git
checkout, ref, invalid URL, credentials, duplicate-emission regression.

### Scripted
shStdout, runtime if, loop, memoized result, resume.

### Conditions/post
branch/env/all/any/not, skip reason, post lifecycle.

### Plugins
external atomic, external block, missing capability, incompatible API, duplicate StepKey.

### Security
secret leak, env leak, path escape, orphan process.

## Product examples target

Mantener 20–30 examples excelentes; no convertir todos los fixtures adversariales en tutoriales.
