# B1 — Harness entry points: `just` recipes referenced a wrapper that does not exist

- **Date:** 2026-10-15
- **HEAD at authorship:** `1bb6b034fa6652ce14a5a7ec8a77e785a49ee3fd`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** operational/test-tooling surfaces only (`justfile`, README/example commands,
  one comment). No Gradle logic, no gate scope, no test semantics changed.

---

## 1. Defect

Every Gradle-invoking `just` recipe called `./gradlew -p v2 ...` from the repository root,
but there is **no** `./gradlew` at the root: the wrapper lives at `v2/gradlew`. `AGENTS.md`
rule 6 already declares this invocation form erroneous ("NO copiarlas sin corregir la
ruta"), so the documented inner loop contradicted its own governing instruction.

Observed before (real invocation):

```text
$ just t 'NoSuchPattern*'
timeout: fallo al ejecutar la orden «./gradlew»: No existe el fichero o el directorio
error: Recipe `t` failed on line 55 with exit code 127
```

Exit 127 is "command not found": the recipe never reached Gradle. The same broken form
appeared in `just gate`, `just gate-escalate`, `just gate-app`, `just app-fast`,
`just corpus`, `just uat-local-010`, `just changed` suggestions, `AGENTS.md`'s own testing
examples, `README.md`, `examples/run.sh` (executed script) and two module READMEs.

## 2. Closure

- `./gradlew -p v2` → `v2/gradlew -p v2` in: `justfile` (9), `AGENTS.md` testing examples
  (5; the rule-6 sentence that *quotes* the erroneous form is deliberately left intact),
  `README.md` (1), `examples/run.sh` (2), `v2/pipeline-architecture-tests/README.md` (3),
  `v2/pipeline-protocol/README.md` (2), `v2/settings.gradle.kts` comment (1).
- `just changed` also mis-mapped build-system files: `v2/settings.gradle.kts` suggested
  `:settings.gradle.kts:test` (a task that cannot exist). Build-system paths
  (`v2/*.gradle.kts`, `v2/gradle/*`, `v2/gradlew*`) now route to the full round gate, per
  AGENTS.md rule 17 ("Gradle build files … L4 is affected module suites", L5 for global
  build-system change).
- `openspec/` task logs, spike documents and historical receipts that use the old form are
  intentionally NOT rewritten: they are records of what was executed at their time.

Observed after:

```text
$ just -n corpus 11        → timeout 600 v2/gradlew -p v2 :pipeline-application:test --tests "CompatibilityCorpusTest.fixture11*"
$ just changed             → timeout 1270 v2/gradlew -p v2 check   # build-system change: full round gate (L5)
$ timeout 120 just t 'NoSuchPattern*'
BUILD FAILED in 40s        # the wrapper executed; failure is "no tests found", exit 1
```

The 40 s failure is the correct observable: Gradle started, compiled dependencies and
rejected the deliberately impossible test pattern — exit 1 through the recipe, not 127.

## 3. What this does NOT claim

- No recipe budget or gate scope changed; only the wrapper path and the `changed` mapping.
- Not a performance change: the same work runs, it just now runs at all.
- `just` recipes remain developer conveniences; nothing here becomes a gate.

## End-of-work-unit closure

```text
Reference implementation consulted: AGENTS.md rule 6 and the "Intelligent
    Change-Scoped Testing" ladder; no external reference needed.
Behaviour adopted:                  operational commands must match the repository's single
                                    wrapper location; build-system diffs route to L5.
Intentional deviations:             historical records (openspec/, spikes, receipts) keep
                                    the old form on purpose.
Security implications reviewed:     none; command strings in developer tooling only.
Tests demonstrating the contract:    observed CLI invocations above (`just -n`, `just
                                    changed`, real `just t` reaching Gradle with exit 1).
```
