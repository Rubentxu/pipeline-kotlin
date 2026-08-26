# ADR-0048: SandboxProfile.LOCAL — cwd drift fix + env deny-list + PATH normalization

- **Status:** accepted
- **Date:** 2026-08-26
- **Deciders:** Rubentxu (product owner), orchestrator
- **Authority:** binds at apply phase T4 (authoring); required for L3 milestone completeness
- **Related:** [[ADR-0046-local-ecosystem-first-reprioritization]] §D2 (cwd drift), [[ADR-0047-operation-status-failed-timeout]], REQ-Sandbox-Profile, REQ-Sandbox-Profile-Local, UAT-LOCAL-007

## Context

ML-R2 established the durable `sh` pattern with workspace isolation per `ADR-0046`. However, the working directory for shell subprocesses was not being set to the per-stage workspace — the `pb.directory(controlDir)` call launched subprocesses in the control directory instead of `workspace/stage-N/`. This is **cwd drift** (DEC-1): subprocesses writing relative paths (e.g., `touch output.txt`) would write to the control directory rather than the workspace, causing journal divergence on resume.

ML-R3 introduces the full `SandboxProfile.LOCAL` implementation to address cwd drift and add an env deny-list as a defense-in-depth measure against accidental environment variable inheritance from the parent shell.

## Decision

### D1 — CWD flip to workspacePath (DEC-1)

Flip `pb.directory(controlDir)` → `pb.directory(workspaceRoot)` in `DurableShellExecutor.launch()`. The `workspaceRoot` is the per-stage workspace directory (`{controlRoot}/workspace/stage-{n}-{m}/`) resolved by `WorkspaceResolver` and threaded through `ShOptions.workspaceRoot`.

This is a **profile-independent flip** — the cwd is always set to the workspace, regardless of `SandboxProfile.NONE` or `SandboxProfile.LOCAL`. The `NONE` profile is the cwd-flip baseline; `LOCAL` adds the env deny-list.

The flip is implemented by passing `workspaceRoot: Path? = null` as a parameter to `DurableShellExecutor.launch()`. When `null`, the executor falls back to `controlDir` for back-compat with direct SDK callers.

**Relevant scenarios:** SB-S-001, SB-S-006 (pwd = workspace under both LOCAL and NONE)

### D2 — SandboxProfile enum + SandboxConfig record

Add `enum class SandboxProfile { NONE, LOCAL, OS }` with:
- `NONE`: existing behavior (no deny-list, cwd flip via workspaceRoot)
- `LOCAL`: cwd flip + deny-list + PATH normalization
- `OS`: throws `SandboxProfileUnsupportedException` citing `ADR-0016 M5/M9` — OUT of scope for L3

Add `data class SandboxConfig(val profile: SandboxProfile, val allowExtra: Set<String>, val pathKeep: Set<String>)` with factory defaults `SandboxConfig.NONE` and `SandboxConfig.LOCAL`.

`object SandboxConfigResolver` resolves from sysprops (`pipeline.sandbox.profile`) then env vars (`PIPELINE_SANDBOX_ALLOW_EXTRA`, `PIPELINE_SANDBOX_PATH_KEEP`).

**Relevant scenarios:** SB-P-001..007

### D3 — EnvModel extension: applyDenyList + normalizePath

Add to `EnvModel.kt`:

```kotlin
fun Map<String, String>.applyDenyList(allowExtra: Set<String>): Map<String, String>
fun Map<String, String>.normalizePath(pathKeep: Set<String>, javaHome: String?, m2Home: String?): Map<String, String>
```

**applyDenyList** strips 11 deny-list keys: `LD_PRELOAD`, `LD_LIBRARY_PATH`, `BASH_ENV`, `ENV`, `SHELLOPTS`, `BASH_FUNC_*`, `IFS`, `PYTHONPATH`, `NODE_OPTIONS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` — unless the key is in `allowExtra`.

**normalizePath** splits `PATH`, drops entries whose absolute prefix is NOT in `pathKeep + {/usr,/bin,/sbin,/opt}`, then prepends `${javaHome}/bin` and `${m2Home}/bin` (V1 legacy). The keep-set default is `{"/usr","/bin","/sbin","/opt"}`.

The call site in `DurableShellExecutor.launch()`:
```kotlin
if (sandbox.profile == SandboxProfile.LOCAL) {
    val pbEnvFiltered = pbEnv.applyDenyList(sandbox.allowExtra)
    val javaHome = pbEnvFiltered["JAVA_HOME"]
    val m2Home = pbEnvFiltered["M2_HOME"]
    pbEnv.clear()
    pbEnv.putAll(pbEnvFiltered.normalizePath(sandbox.pathKeep, javaHome, m2Home))
}
```

The sandbox filter is applied to `pbEnv` (JVM-inherited environment) **before** the user-provided `env` map is merged. User-provided environment variables (from the DSL `environment {}` block) are merged after the deny-list is applied and always take precedence.

**Honesty disclaimer:** `LOCAL` is NOT a filesystem jail. The JDK has no portable `chroot` equivalent; OS-level sandboxing (M5/M9) requires `linux.unshare` or container runtimes and is explicitly OUT of scope for L3. `LOCAL` is best-effort defense against accidental environment variable inheritance.

**Relevant scenarios:** SB-S-004 (LD_PRELOAD scrubbed from inherited env), SB-S-005 (PATH rogue dropped), SB-S-009 (JAVA_HOME/M2_HOME prepend survives filter)

### D4 — Fingerprint integration (INV-6 / SPEC-FINGERPRINT-DECISION-2026-08-26)

`PipelineRun.stepToParams` adds `"sandboxProfile" -> JsonPrimitive(ctx.sandboxProfile.name)` to the params map when `sandboxProfile != SandboxProfile.NONE`. This changes the fingerprint for `LOCAL` steps, which means:

- A journal entry created with `NONE` is **not re-attached** when resumed with `LOCAL` (fingerprint mismatch → re-execute)
- A journal entry created with `LOCAL` is re-attached when resumed with `LOCAL` (fingerprint match)

The `NONE` fingerprint is byte-identical to ML-R2, preserving full backward-compatibility for journals created before this cycle.

**Relevant scenarios:** SB-S-010 (resume profile change re-attaches), DSE-S-040

### D5 — CLI surface: --sandbox-profile flag

`Main.kt parseCliArgs` accepts `--sandbox-profile <none|local|os>`:
- `none` → `SandboxProfile.NONE` (default)
- `local` → `SandboxProfile.LOCAL`
- `os` → throws `SandboxProfileUnsupportedException` citing `ADR-0016 M5/M9`

Sysprop override: `pipeline.sandbox.profile` (CI escape hatch, documented not advertised).

**Relevant scenarios:** SB-P-002..005

### D6 — T0 debt folds (DEC-1 + pgid→sid + JENKINS_FAMILIARITY DEV-001 REVERSED)

The cwd flip was applied as a 1-line fix in T0 (commit `039319f`):
`pb.directory(controlDir)` → `pb.directory(workspaceRoot)` via `shOptions.workspaceRoot` threading.

Additionally, the cookie-scan kill documentation was updated to use `sid` (session ID) instead of `pgid` (process group ID), and the `JENKINS_FAMILIARITY.md DEV-001` section was annotated as `REVERSED` (signatures preserved per commit `3742ce1`).

### D7 — UAT-LOCAL-007 behavioral coverage

10 SB-S scenarios + 2 TC scenarios are covered by `UatLocal007SandboxProfileTest`:
- SB-S-001: pwd = workspacePath (DEC-1 end-to-end)
- SB-S-002: write-outside-workspace best-effort report
- SB-S-003: HOME unchanged under LOCAL
- SB-S-004: LD_PRELOAD scrubbed from inherited env
- SB-S-005: PATH rogue dropped; which sh → /usr/bin/sh
- SB-S-006: profile=none back-compat (deny-list skipped, cwd still flipped)
- SB-S-007: LOCAL + kill-mid-step → LOST state (not FAILED_TIMEOUT)
- SB-S-008: parallel branch cwds isolated
- SB-S-009: JAVA_HOME/M2_HOME prepend survives filter
- SB-S-010: resume with profile change re-attaches (INV-6)
- UAT-L7-TC-001: @Timeout(120) declared
- UAT-L7-TC-002: @AfterEach kills surviving children

### D8 — TRACEABILITY row

TRACEABILITY.md appended with:
```
| Sandbox local | EXECUTION_SANDBOX | [[ADR-0048-sandbox-profile-local]] | ML | UAT-LOCAL-003 + UAT-LOCAL-007 |
```

## Consequences

- **Positive**: cwd drift fixed (DEC-1); env deny-list defense-in-depth; PATH normalization prevents rogue prepend attacks; fingerprint integration preserves INV-6 resume semantics
- **Negative**: `LOCAL` fingerprint changes step fingerprint (mitigated by NONE back-compat per SPEC-FINGERPRINT-DECISION-2026-08-26)
- **Traceability**: SB-P-001..007, SB-S-001..010, WS-S-014..020, DSE-S-035..040, UAT-L7-TC-001..002

## Alternatives Considered

| Alternative | Rejected because |
|---|---|
| SandboxProfile.OS (M5/M9 container) | OUT of scope for L3; requires linux.unshare; scope creep per ADR-0016 |
| Deny-list applied AFTER user env merge | Would mean user-provided env could reintroduce denied vars; violates defense-in-depth intent |
| JAVA_HOME/M2_HOME NOT prepended in LOCAL | V1 legacy behavior; breaking change for existing pipelines |
| home-rewrite default | Opt-in DEFERRED to ML-R4 per DEC-2 |

## Changelog

- 2026-08-26 | created | status=accepted | valid_from=2026-08-26 | stale_after=2027-08-26
