# PipelineK — Credentials & security

**Release verified against**: `pipelinek 0.39.0`.

PipelineK v1 is a **local** engine. There is no remote control plane,
no shared secret store, no central credential broker. Credentials live
where your shell already keeps them.

## What this release handles

- **Secret redaction at the durable shell seam.** `sh("...")` step
  transcripts are scanned for known secret patterns (AWS keys, GitHub
  tokens, generic `KEY=…` / `TOKEN=…` / `SECRET=…` assignments) and
  redacted before being persisted to the control-root or the SQLite
  journal. The redaction is observed and tested; see
  `docs/v2/07-uat/UatLocal008CredentialsTest*` in the repository.

## What this release does NOT do

- **No first-class credential DSL.** There is no
  `credentials { usernamePassword(...) { ... } }` block in `0.39.0`.
  Use your shell, `asdf`, SDKMAN, or environment variables.
- **No built-in secret vault.** PipelineK does not encrypt secrets
  at rest beyond what redaction already provides.
- **No audit log of secret access.** Redaction removes the secret
  from the journal; it does not record that the secret was used.

## How to pass a secret to a step safely

Use environment variables, never inline literals in `sh("...")`:

```kotlin
// good — secret comes from the env, never appears in the script source
sh("git push https://${'$'}{GITHUB_TOKEN}@github.com/owner/repo.git")
```

```kotlin
// bad — secret is in the script, ends up in the transcript
sh("git push https://ghp_abc123…@github.com/owner/repo.git")
```

The first form leaves no secret-shaped substring in the command; the
second form does, and the redactor will mask it, but it is still bad
practice to write secrets to disk in any form.

## Where secrets might leak

Even with redaction, secrets can leak through:

1. **Process listing.** Anyone with `ps` access during the step can
   see the command line.
2. **Network logs.** HTTPS endpoints your step talks to may log
   request URIs.
3. **Subprocess output.** If a child process prints the secret to
   stdout (e.g. a debug flag), that output is captured by the
   transcript.

Mitigate by using **short-lived credentials** (e.g. a token issued for
the run that is invalidated after the run) and by **clearing the
control-root** if it ever contained a secret you don't want kept.

## Inspecting transcripts safely

When looking through `--control-root` for what happened, prefer
**typed events** from the journal (`--db`) over raw transcripts. Typed
events have already been through the redactor; raw transcripts may
contain partial patterns the redactor missed.
