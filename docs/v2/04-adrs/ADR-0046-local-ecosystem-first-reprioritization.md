# ADR-0046: Local Execution Ecosystem First — Reprioritization and Durable sh Pattern

- **Status:** accepted
- **Date:** 2026-08-25
- **Deciders:** Rubentxu (product owner), orchestrator
- **Authority:** replaces the strict M4→M5 sequencing for the local-execution scope; M4 remaining sub-cycles (E5-02..E5-10) are deferred until milestone **ML** closes.
- **Related:** ADR-0006 (durable replay), ADR-0009/0010/0011 (protocol), ADR-0014 (credentials), ADR-0016 (sandbox), UAT-REC-002, UAT-LOCAL-001..006

## Context

A capability audit (2026-08-25) against user expectations showed:

1. The **durable local engine** (thesis of the product) is implemented and proven: kill+resume without replay *after step completion*, divergence fail-closed, parallel frames (M3, closed at v0.13.5-rc1).
2. The **execution ecosystem** users need to actually run real pipelines is absent: only `echo/sh/error/sleep` steps; no workspace/env wiring, no `returnStdout`, no sandbox, no credentials, no `checkout`, no ecosystem steps (git, writeFile, archiveArtifacts, maven/gradle wrappers).
3. There is **no sandbox** in V2 (V1's Security Manager approach was classified as conceptual debt and deliberately not ported — ADR-0016 decides OS/container-level sandboxing).
4. **UAT-REC-002** ("worker dies during durable process; reconciliation must not assume success") is specified but unimplemented: M3 proved kill-after-completion, never kill-*during*-`sh`. Today a crash mid-`sh` re-executes the step (at-least-once), which is unacceptable for non-idempotent commands (`mvn deploy`, `docker push`, `git push`).

Primary-source research on `jenkinsci/durable-task` `BourneShellScript.java` (the reference implementation of Jenkins `sh`) shows the script-file pattern is what makes shell steps durable, safe to wrap, and secret-safe.

## Decision

### D1 — Reprioritize: local execution ecosystem before external controller connectivity

User decision. A new milestone **ML (Ecosistema de ejecución local)** is inserted now; the remaining M4 protocol/gateway sub-cycles (E5-02..E5-10) resume after ML closes. Rationale: the local ecosystem is what makes the engine usable and testable end-to-end on real projects today; controller/protocol is distribution, not capability. INC-011/E5-11 (capability trust) remains BLOCKED and untouched.

### D2 — Adopt the durable-task script-file pattern for `sh` (ML / L1)

Two binding design principles:

**P1 — Durability lives in the filesystem, not in JVM memory.**
Each `sh` step materializes on disk (inside a per-step control dir):

| File | Role |
|---|---|
| `script.sh` | the user script, written verbatim before launch |
| `jenkins-log.txt` (equivalent) | stdout+stderr of the script; **never piped to the JVM** |
| `result.txt` | exit code, written atomically (`echo $? > tmp && mv tmp result.txt`) |
| heartbeat | wrapper `touch`es the log every ~3s while the control dir exists and no result exists — liveness signal decoupled from the JVM |
| cookie env var | `…=please-do-not-kill-me` protects the process tree from process-tree-killer sweeps; passed via intermediate variable so it never appears in `argv` |

Consequences: a runner crash mid-`sh` leaves the shell running detached (`nohup … >&- 2>&- &`); on resume the reconciler reads `result.txt`/log from disk and re-attaches **without re-executing** (at-most-once per step). This closes UAT-REC-002 and upgrades `sh` from at-least-once to effectively-once for completed processes. If no result file and the heartbeat is stale → classify LOST and apply policy (never assume success). Interpreter: `sh -xe` by default (audit + fail-fast); user `#!` shebang respected (`chmod 0755`).

**P2 — The user script never crosses a shell layer.**
The script travels **exclusively via the filesystem** (`scriptFile.writeText(script)`), and only the *fixed, Jenkins-authored-style wrapper* (which quotes file *paths*, never script text) is passed to `sh -c`. This is the only composition that is simultaneously:
- **semantics-safe**: single expansion — exactly one shell interprets the user script once (no premature `$`-expansion or quoting corruption by an outer shell);
- **injection-safe**: arbitrary user text (quotes, `$(…)`, backticks) cannot break out of the wrapper — the same class of solution as parameterized SQL;
- **secret-safe**: nothing sensitive lands in `argv` (world-readable via `/proc/<pid>/cmdline`).

Explicit anti-pattern (rejected):

```kotlin
val wrapper = "(heartbeat…) & bash -xe ${userScript} > log 2>&1; echo $? > result"
ProcessExecutor().execute(listOf("sh", "-c", wrapper))   // ❌ double expansion + injection
```

### What we adopt vs. defer

| Adopt (L1) | Defer (with reason) |
|---|---|
| script.sh + result.txt (atomic mv) + log file + heartbeat + cookie | binary wrapper (`USE_BINARY_WRAPPER`) — optimization, not needed locally |
| `sh -xe` default; user shebang respected; `cp script.sh script.sh.copy` (Text-file-busy, JENKINS-70874) | z/OS encodings — not a target platform |
| `nohup … >&- 2>&- &` detachment (JENKINS-58290) | Windows `BatchScript` variant — local is Linux; containers arrive at M5 |
| heartbeat constants documented (`HEARTBEAT_CHECK_INTERVAL`, `MINIMUM_DELTA`) as config | remote/agent filesystem variants — M4-rest/M5 |

## ML scope (summary — authoritative list lives in ROADMAP.md / IMPLEMENTATION_BACKLOG.md)

- **L1** durable `sh` Jenkins-faithful (this ADR, P1+P2). Closes UAT-REC-002 / UAT-LOCAL-001.
- **L2** workspace per stage + environment (PATH/JAVA_HOME/M2_HOME legacy semantics) + `returnStdout` (via output file) + real timeouts.
- **L3** local sandbox profile (workspace/env confinement best-effort per ADR-0016; full OS/container profile stays M5/M9).
- **L4** local credentials provider + secret redaction in logs/events/journal (partial UAT-SEC-001; ADR-0014 slice).
- **L5** `checkout`/git step.
- **L6** most-used Jenkins ecosystem steps (writeFile/readFile, minimal archiveArtifacts, maven/gradle wrappers).
- **L7** smoke E2E harness over real famous repositories (Gradle/Maven wrapper builds) — UAT-LOCAL-006.

## Consequences

- Positive: `sh` becomes effectively-once and crash-survivable mid-step; the M3 reconciler (`NEEDS_REATTACH`/`STUCK`) gains a real re-attachment substrate; the local ecosystem becomes testable end-to-end (the user's "build a real project" scenario) without any controller.
- Negative/risks: the wrapper line is security- and quoting-sensitive → must ship with adversarial tests (quoting, `$`, newlines, unicode, hostile scripts) before merge; heartbeat heuristics inherit known false-positive modes on very slow filesystems (documented, configurable); result-file polling adds ~seconds latency to step completion detection.
- Traceability: ROADMAP ML section, IMPLEMENTATION_BACKLOG Epic ML, UAT_SCENARIOS UAT-LOCAL-001..006, TRACEABILITY row "Durable sh".

## Changelog (bi-temporal)

- 2026-08-25 | created | status=accepted | valid_from=2026-08-25 | valid_to=∞
