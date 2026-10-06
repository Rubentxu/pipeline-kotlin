# Configuration and workspace

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

- Explain **where a run happens** (the workspace) and **where it remembers things** (the journal).
- Choose between `--workspace` and `--isolated`, and know why you cannot use both.
- Decide whether your run is **durable** (`--db`) or **in-memory**, and know exactly what you lose without `--db`.
- Turn network access on and off deliberately.
- Read the four environment variables that change runtime behaviour.
- Recognise exit code `2` coming from configuration rather than from your script.

---

## The one rule that explains most surprises

> **Without `--db`, your run has no memory.**

That is not a policy choice. It is what the code does: `--db` is what selects the SQLite journal
(`CliParser.kt:192`). Without it the run state lives only in the process, and when the process ends,
the state ends with it.

| | With `--db` | Without `--db` |
|---|---|---|
| Where state lives | SQLite journal on disk | Process memory only |
| Survives a restart | Yes | No |
| `--resume` / `--rerun` | Available | **Rejected, exit 2** |
| `pipelinek events` afterwards | Yes | Nothing to read |
| `examples/06-durable.pipeline.kts` | Second run reuses work | Not applicable |

**Analogy.** The workspace is the workbench where your tools are laid out. The journal is the
operator's notebook: if the notebook is not there, the next shift starts from nothing no matter how
careful the previous one was.

---

## Flags: they belong to `run` and `validate` only

Only `validate` and `run` go through the CLI parser (`CliParser.kt:135`). The other subcommands
(`version`, `doctor`, `events`, `console`, `credentials`) have their own flag sets.

| Flag | Argument | Effect | Source |
|---|---|---|---|
| `--db` | path | Selects the SQLite journal. Without it, everything is in memory | `CliParser.kt:192` |
| `--resume` | — | Resumes a previous run. Requires `--db`, otherwise exit 2 | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | Forces a fresh run. Requires `--db` | `CliParser.kt:202` |
| `--control-root` | path | Root of the durable shell control area | `CliParser.kt:208` |
| `--workspace` | path | Working directory for the run | `CliParser.kt:212` |
| `--isolated` | — | Engine-managed scratch workspace | `CliParser.kt:216` |
| `--plugin-jar` | path, repeatable | Plugin JARs to load | `CliParser.kt:220` |
| `--allow-network` | — | Permits egress. **Denied by default** | `CliParser.kt:229` |
| `--sandbox-profile` | `none` \| `local` \| `os` | `os` is rejected | `CliParser.kt:233` |

### Flags go **before** the script. Always.

This is the single most common silent mistake, so it gets its own section.

The parser loop consumes tokens while they start with `--` (`CliParser.kt:144`). The first token that
does not start with `--` is taken as the script path and **parsing stops there** (`CliParser.kt:151`).
No error is raised. The rest of the line is simply ignored.

```bash
# Correct: flags first.
pipelinek run --db journal.sqlite --workspace ./build pipeline.kts

# Wrong: silent no-op. --db is never read.
pipelinek run pipeline.kts --db journal.sqlite
```

The second command exits as if everything were configured. You get an in-memory run and no warning.
**Put the flags first, every time.**

### Mutually exclusive pairs

| Combination | Result |
|---|---|
| `--isolated` + `--workspace` | Error. They mean opposite things (`CliParser.kt:154`) |
| `--resume` + `--rerun` | Error. They are opposite modes (`CliParser.kt:196`, `:202`) |
| `--allow-network=<value>` | Not supported. The flag takes no value (`CliParser.kt:224`) |
| `--sandbox-profile os` | Rejected |

Unknown flags are a different story: they produce a usage message and **exit 1**
(`CliParser.kt:242`, `Main.kt:151`).

---

## Workspace: pick one, not both

`--workspace <path>` and `--isolated` are exclusive (`CliParser.kt:154`).

| | `--workspace <path>` | `--isolated` |
|---|---|---|
| Who creates the directory | You do, or it must already exist | The engine |
| Good for | Real checkouts, builds that need real files | Tests, throwaway runs, isolation |
| Survives after the run | Yes | No |

**Use `--isolated` when** you are testing a script and do not want the run touching your checkout.

---

## `--db`: making a run durable

Pass a path to any writable location:

```bash
pipelinek run --db .pipelinek/journal.sqlite pipeline.kts
```

With a journal in place you also get:

- `--resume` — continue a run that did not finish.
- `--rerun` — start a fresh run but keep the journal as history.
- A readable history afterwards with `pipelinek events --db <path>`.

`examples/06-durable.pipeline.kts` exists to demonstrate exactly this: run it twice with the same
`--db` and the second run reuses the first one's work. `examples/08-parallel.pipeline.kts` and
`examples/09-retry.pipeline.kts` do the same.

---

## `--control-root`: where the durable shell keeps its files

`--control-root <path>` selects the control root used by the durable shell layer
(`CliParser.kt:208`). Think of it as the operator's desk, separate from the workbench.

An invalid control root is rejected at admission with **exit 2**.

You rarely need to set it by hand. If you do set it, give it a real, writable directory — the same
kind of directory you would give to `--db`.

---

## Network access is denied by default

`--allow-network` is the only way to permit egress (`CliParser.kt:229`). Without it, the run is
offline.

| Command | Network |
|---|---|
| `pipelinek run pipeline.kts` | Denied |
| `pipelinek run --allow-network pipeline.kts` | Permitted |

Note the syntax. `--allow-network` takes no argument. Writing `--allow-network=true` does not grant
anything; it is a different token (`CliParser.kt:224`).

Keep this denied unless your pipeline genuinely needs to fetch something. It is the cheapest
protection in the tool.

---

## Sandbox profiles

`--sandbox-profile` accepts `none`, `local` and `os` (`CliParser.kt:233`).

**`os` is rejected.** The OS-level profile is not available, so passing it fails rather than
silently downgrading. If you find an old script using `--sandbox-profile os`, that script predates
the current parser.

Two environment variables relax sandbox behaviour:

| Variable | Effect | Source |
|---|---|---|
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Relaxes the sandbox | `SandboxConfig.kt:54` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Keeps sandbox paths instead of discarding them | `SandboxConfig.kt:55` |

Treat both as debugging aids. They widen what the sandbox permits.

---

## Environment variables

| Variable | Effect | Source |
|---|---|---|
| `PIPELINE_STORE_PASSPHRASE` | Passphrase for the secrets store | `PassphraseResolver.kt:20` |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Sandbox relaxation | `SandboxConfig.kt:54` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Sandbox path retention | `SandboxConfig.kt:55` |
| `APP_HOME` | Classpath of bundled plugins | `ScriptDefinition.kt:81` |

Two facts worth knowing:

- **No `.env` file is loaded automatically.** If a variable is not exported in your shell, the
  process does not see it.
- **No other `System.getenv` call exists in `src/main`.** The four above are the whole list as of
  this commit.

`PIPELINE_STORE_PASSPHRASE` is covered in detail on the credentials page.

---

## Plugins: `--plugin-jar`

`--plugin-jar <path>` is repeatable (`CliParser.kt:220`). Each occurrence adds one JAR.

Steps contributed by plugins discovered this way (`http.request`, `junit.results`,
`scm-git.checkout`, `core-utils.*`) travel the same execution path as core steps. There is no
privileged route for them.

---

## Exit codes from configuration problems

| Code | Means |
|---|---|
| `0` | Success |
| `1` | Pipeline failure or abort, **and invalid CLI arguments** (`Main.kt:151`) |
| `2` | Admission rejection: script not found, `--resume`/`--rerun` without `--db`, invalid `--control-root`, non-canonical Step, held lease, failed compilation |
| `3` | Artifact with no `Implementation-Version` |
| `4` | Tampered credentials store |

The trap: **invalid CLI arguments give `1`, while most other input rejections give `2`.** Do not
collapse them into one number (`Main.kt:151` versus `Main.kt:186,225,241,269,430,830`).

Full list on the events and troubleshooting page.

---

## Checklist before you run

- [ ] Flags come **before** the script path.
- [ ] `--db` present if you plan to use `--resume`, `--rerun`, or `events` afterwards.
- [ ] `--workspace` and `--isolated` are not both present.
- [ ] `--resume` and `--rerun` are not both present.
- [ ] `--sandbox-profile` is not `os`.
- [ ] `--allow-network` has no `=` value attached.
- [ ] Any secret your script needs comes from the store, not from a literal in the script.

---

## Authority note

This repository has had **no remote CI since 2026-09-30** — the commit `754ddda0` removed
`lpr0-ci.yml`, `release.yml`, `v2-baseline.yml` and `sdkman-publish.yml`. Do not expect a green CI
badge to stand behind these pages, and do not treat the product gate as passed. Everything here is a
static reading of the source at `b08fa948`.

---

## Next

- **Credentials and secrets** → [credentials-and-security.md](credentials-and-security.md)
- **All user pages** → [README.md](README.md)