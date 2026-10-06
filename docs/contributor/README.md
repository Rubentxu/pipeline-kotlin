# Contributor guides

> **What this directory is.** Two orientation guides for someone who has just joined the project and
> needs to build a mental model before touching code. They are **not normative**. When one of them
> contradicts the authoritative documentation, the authoritative one wins:
>
> | If your question is about… | The answer is in |
> |---|---|
> | Architectural decisions and *why* | [`docs/v2/04-adrs/`](../v2/04-adrs/) and [`docs/v2/02-architecture/`](../v2/02-architecture/) |
> | Rules you must not break | [`AGENTS.md`](../../AGENTS.md) and [`01-semantic-constitution.md`](../pipelinek-semantic-evolution/01-semantic-constitution.md) |
> | What is verified and what is not | [`CERTIFICATION_PROTOCOL.md`](../v2/07-uat/CERTIFICATION_PROTOCOL.md) and the receipts in [`docs/v2/07-uat/`](../v2/07-uat/) |
> | Official vocabulary | [`CONTEXT.md`](../../CONTEXT.md) |
> | What users see | [`docs/user/`](../user/) |

---

## The two guides

| Guide | Read it for | Length |
|---|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The four layers, what a run does end to end, why it is built this way, the five mistakes newcomers hit | ~300 lines |
| [`MODULES.md`](MODULES.md) | All 27 Gradle modules, one by one: what each owns and what it talks to | ~210 lines |

Suggested order: `ARCHITECTURE.md` first (it gives you the vocabulary), then `MODULES.md` (it gives you
the map). If you only want to run pipelines and not change the engine, you do not need either — start
at [`docs/user/`](../user/) instead.

---

## Read these two before you start

Both guides point at older documents that look authoritative and are not.

1. **`docs/v2/02-architecture/MODULES.md` does not match the build.** It names modules that do not
   exist (`pipeline-worker-runtime`, `pipeline-worker-gateway`, `pipeline-jenkins-plugin`,
   `pipeline-step-codegen`) and omits 27 that do. Dated 2026-08-21. It describes an aspirational
   architecture, not the current one. **The truth is `v2/settings.gradle.kts:29-71`.**

2. **`docs/v2/02-architecture/ARCHITECTURE.md` contains a section that was retired.** The
   "Controller vs worker" split no longer exists.

If you find a contradiction and want it fixed, that is decision **A2** from the 2026-10-06 grill:
leave the documents in place but mark them obsolete at the top, rather than rewriting normative
documents that nobody has reviewed.

---

## Language

`ARCHITECTURE.md` and `MODULES.md` are **Spanish only**. The user documentation in [`docs/user/`](../user/)
is maintained in English and Spanish with identical commands, flags and identifiers in both; these two
guides do not yet have English mirrors.

---

## How these guides were verified

Not by their author. Per the repository's Harness Fidelity Law, three independent agents checked 31
claims against the code. Nine were false and were corrected before publication:

- `FArch001DomainFrameworkFreeTest` was described as rejecting `implementation(libs.sqlite.jdbc)`. It
  does not: its denylist is 8 literal tokens and a dependency only matches if quoted. The dependency
  *direction* is covered, by a different test.
- The described dispatch order was wrong. The journal is read **and** written; the summary showed it
  once.
- The event chain was missing `StageStarted`/`StageFinished` and `EchoOutputCaptured`.
- "About 45 fitness test files" was 47 files and 88 test classes.
- `pipeline-architecture-tests` was described as a standalone Gradle project; it is a subproject of `v2`.
- There are two generic Step primitives (`registryStep` and `registryBlock`), not one.
- `scripting-kotlin24` was called the only allowed scripting target; the allow-list accepts four.
- `StepContract`, `StepHandler`, `StepCodec` and `requiredCapabilities` were placed in `step-sdk:api`;
  they live in `pipeline-domain` (`domain/step/StepRegistry.kt`).

**The lesson that generalises:** several code comments in this repository are stale. One says
`LEGACY_PLUGIN_IDS == 12` when the set is empty. **Cite the manifest or the code, never the comment.**

---

## Conventions used in these guides

| Marker | Meaning |
|---|---|
| `path:line` | A claim bound to a specific line of a specific file |
| **NO VERIFICADO** | Could not be checked from this repository. Do not treat it as true |
| **probado** | Exercised by an example or a test whose assertions you can read |
| **declarado** | Exists in the DSL or manifest; no example runs it |
| **falla cerrado** | Rejected before any effect; never silently ignored |