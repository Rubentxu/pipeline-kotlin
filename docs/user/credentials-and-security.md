# Credentials and security

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Not verified against a published binary.** The current published release is `0.47.0` (its ZIP digest is listed in the release `SHA256SUMS`); see the divergence note below.

> **Documentation divergence.** This page previously carried the header *"Release verified against:
> `pipelinek 0.39.0`"*. That header cannot be sustained: the page described a surface the current
> branch no longer matches, and it was never re-verified against a published binary. The page now
> documents the development branch (`0.47.0`). Recorded 2026-10-06. See `docs/user/README.md` →
> "Known divergences". The stronger claim that `parallel` / `retry` / `catchError` did not exist
> applied only to `pipeline-dsl.md` and `cli-reference.md`.

---

## What you will be able to do after reading this page

- Store a secret in the credentials store and use it from a pipeline.
- Set the passphrase the store needs, and understand what happens when it is missing or wrong.
- Use `pipelinek credentials add`, `list` and `remove` correctly.
- Explain why the durable shell layer redacts secrets from its transcript.
- Apply the rule that keeps secrets out of your `.pipeline.kts` files.
- Recognise exit codes `3` and `4`, which belong to this page rather than to the configuration one.

---

## The golden rule

> **Never write a secret into a `.pipeline.kts` file that is going anywhere near a repository.**

A pipeline script is source code. It is diffed, reviewed, copied between machines, pasted into
tickets, and committed. A literal like `token = "ghp_..."` inside a script has the same lifetime as
the repository itself.

**Analogy.** A script is a public notice board. A secret is a key. You do not pin the key to the
notice board, even a helpful one.

What to do instead:

| Instead of | Do |
|---|---|
| `token = "abc123"` in the script | Store it: `pipelinek credentials add …`, read it with `withCredentials(…)` |
| `password = "hunter2"` in the script | Export it from your shell environment, or read it from the store |
| A `.env` checked into Git | Keep it out of Git; note that PipelineK does **not** auto-load `.env` anyway |

---

## The credentials store

Secrets live in an encrypted local store, opened with a passphrase supplied by the environment
variable `PIPELINE_STORE_PASSPHRASE` (`PassphraseResolver.kt:20`).

| Fact | Detail |
|---|---|
| Where the passphrase comes from | `PIPELINE_STORE_PASSPHRASE`, read from the environment |
| What happens with no passphrase | Rejected, **exit 3** |
| What happens with a wrong passphrase | Rejected, **exit 3** |
| What happens with a damaged store | **Exit 4** |

The passphrase is never read from the script, never read from a file inside the repository, and
never prompted for interactively in the CLI paths described here. If the variable is not set in the
shell that launches `pipelinek`, the store cannot be opened.

---

## The command

```bash
pipelinek credentials add
pipelinek credentials list
pipelinek credentials remove
```

The subcommand is dispatched at `Main.kt:140`; the three actions are handled at
`MainCredentialsCli.kt:94`.

| Action | What it does |
|---|---|
| `add` | Reads a secret from stdin and stores it under a new identifier |
| `list` | Shows the identifiers in the store, never the secret material |
| `remove` | Deletes an entry |

`PIPELINE_STORE_PASSPHRASE` must be set and correct for all three.

### Checklist

- [ ] `PIPELINE_STORE_PASSPHRASE` is exported **in the shell that runs the command**.
- [ ] The passphrase is not itself stored in the repository.
- [ ] Secrets are piped in, never typed into a script or a file that is tracked.
- [ ] You have checked the working tree for pre-existing literals before you start, not after.

---

## Using a stored secret from a pipeline

`withCredentials(vararg bindings, block)` (`StageScopeBuilders.kt:240`) is the declared DSL form.

`withCredentials` has a registered block descriptor (`StepDescriptorRegistry.standard()`,
`core.withCredentials`), so it is part of the supported block set.

> **Declared, not demonstrated.** No file in `examples/` uses `withCredentials`. The declaration is
> real and it is registered, but there is no reference example asserting its runtime behaviour at
> this commit. Treat it as a declared construct and validate it on your own pipeline before relying
> on it.

`withCredentials` requires the secret to come from the store. If you try to bind a literal you typed
into the script, you have bypassed the rule at the top of this page.

---

## Secret redaction in the durable shell seam

When a shell Step runs durably, its output is written to a console transcript. That transcript is
part of the run's history and can be read later with `pipelinek console`.

Secrets are redacted at that durable seam before the transcript is written, so a value that came from
the credential store does not appear in the persisted console output.

Two consequences follow:

| Expectation | Reality |
|---|---|
| Secrets in the transcript | Redacted at the durable seam |
| Secrets in the journal or event stream | Not promised here. **NO VERIFICADO.** |

**Analogy.** The transcript is a recording of the operator's speech. Redaction happens before the
recording is filed, so the filed copy is safe to read in a later shift.

Because redaction lives at the durable shell boundary, do not assume it covers output produced by
other paths. Anything printed by a plugin Step is outside what this page can certify.

---

## What `list` will not show you

`credentials list` reports identifiers. It does not print secret material. If a secret appears in a
listing, something is wrong and the output should be treated as compromised: rotate the secret.

---

## Exit codes `3` and `4`

These two codes belong to this page. Do not confuse them with `2`, which covers configuration and
admission rejections.

| Code | Meaning | Trigger |
|---|---|---|
| `3` | Passphrase missing or wrong | `credentials` without `PIPELINE_STORE_PASSPHRASE`, or with an incorrect value |
| `3` | Artifact without `Implementation-Version` | `pipelinek version` on an artifact built without the manifest entry (`Main.kt:80`) |
| `4` | Credentials store tampered with | The store failed its integrity check |

The second row for code `3` is listed here because the number is shared. When you see `3`, read the
message before deciding which of the two situations you are in.

`4` is different in kind: a missing or wrong passphrase is a **you did not unlock it** problem, while
`4` means the thing itself is not trustworthy. Do not try to "work around" a `4` by deleting the store
and re-adding everything without first understanding why it failed its check.

---

## Practical hygiene

- [ ] No secret literal in any `.pipeline.kts` file, at any commit in history.
- [ ] `PIPELINE_STORE_PASSPHRASE` exported from the shell, not from a tracked dotfile.
- [ ] Network denied unless a step genuinely needs it (`--allow-network`, denied by default).
- [ ] `git()`, `load()`, `node { }` and `ansiColor { }` fail closed at run time with exit 2. A
      construction that silently returns something is worse than one that refuses; refusal is the
      designed behaviour, and it is the reason those four never reach your filesystem or shell
      (`StageScopeBuilders.kt:193`, `StageScope.kt:349,435,449`).
- [ ] Journal paths (`--db`) and control roots are writable, private directories.

---

## Authority note

This repository has had **no remote CI since 2026-09-30** — the commit `754ddda0` removed the CI workflows Do not expect a green CI
badge to stand behind these pages. Everything here is a static reading of the source at `b08fa948`.

---

## Next

- **Events and troubleshooting** → [events-and-troubleshooting.md](events-and-troubleshooting.md)
- **Workspace and flags** → [configuration-and-workspace.md](configuration-and-workspace.md)
- **All user pages** → [README.md](README.md)