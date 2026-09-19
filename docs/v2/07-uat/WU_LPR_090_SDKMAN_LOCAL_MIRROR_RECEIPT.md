# WU-LPR-090: SDKMAN local mirror spike — receipt

**Status: PASS (local test only). NOT_PROVEN for official SDKMAN publication.**

**Date:** 2026-09-19
**Spike owner:** agent (jcode)
**Approver:** user (`[2026-09-19T10:08:34.720Z]`)
**Scope:** validate that the canonical `pipelinek 0.39.0` ZIP can be
installed and used through the real `sdkman-cli` client by pointing it at
a local HTTP mirror. **Does NOT prove official publication on SDKMAN.**

---

## Scope and limits (recap of the approver's instructions)

- **In scope:** trace the actual flow of `sdk install pipelinek 0.39.0`,
  prove that the canonical ZIP is installable end-to-end through the real
  client, prove that the installed binary executes and compiles real
  pipeline scripts.
- **Out of scope:** official publication on the public SDKMAN catalog,
  installation from the public catalog, upgrade between two real
  releases (we only have one published release).
- **Hard isolation:** the spike runs entirely under
  `$WU_LPR_090_DIR = /home/rubentxu/.local/share/jcode/wu-lpr-090`. It
  must NOT modify `$HOME/.sdkman`, `$HOME`, the user's persistent shell
  state, or the repo's normal development paths.
- **Mirror constraints:** HTTP (not HTTPS), listens only on `127.0.0.1`,
  no auth, no persistence, no remote deployment, no extensibility. A
  fixture, not a product.

---

## Results table

| Dimension | Status | Evidence |
|---|---|---|
| Installation with the real SDKMAN client | **PASS** | `Verifying artifact: ... SHA256:385b140c...cbb8` → `Installing: pipelinek 0.39.0` → `Done installing!` |
| Installation against local mirror | **LOCAL_TEST_ONLY** | mirror at `127.0.0.1:9999`, NOT the public SDKMAN catalog |
| Publication on official SDKMAN catalog | **NOT_PROVEN** | still blocked on `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`; the onboarding email was sent on 2026-09-19 to `info@sdkman.io` (Gmail id `1a0b8fd0db9ad3fe`); `sdkman/sdkman-db-migrations` is read-only per its README |
| Installation from official SDKMAN catalog | **NOT_PROVEN** | depends on publication being accepted; cannot be tested until publication is proven |
| `sdk install pipelinek 0.39.0` against mirror | **PASS** | end-to-end, see spike-run.log |
| `sdk current pipelinek` reading installed state | **DEFERRED** | `sdk current` reads PATH from the **calling shell**; in a non-interactive driver the PATH does not include `candidates/pipelinek/current/bin`. The installation is on disk and the binary invokes correctly when invoked by absolute path. |
| Installed binary executes real pipelines | **PASS** | JVM starts, kotlin-compiler loads, compilation events emit, validation runs against a real `.pipeline.kts`. (One test script I authored returned VALIDATION FAILED due to incorrect DSL syntax on my part — that is a script issue, not a binary issue.) |
| Installed binary SHA matches the certified ZIP | **PASS** | the same canonical bytes are downloaded by the mirror (verified by SHA on every install) |
| Upgrade between two real releases | **DEFERRED** | only one release exists. Cannot fabricate a second release per approver's instructions. |

---

## What was actually proven

The spike proves that the **technical protocol** that SDKMAN's real
client (sdkman-cli 5.23.0) uses to install a candidate is:

1. `GET SDKMAN_CANDIDATES_API/candidates/validate/<cand>/<ver>/<platform>` → body must be `"valid"`
2. `GET SDKMAN_BROKER_API/download/<cand>/<ver>/<platform>` → binary stream with header `X-Sdkman-Checksum-Sha256: <sha>`
3. `GET SDKMAN_CANDIDATES_API/hooks/post/<cand>/<ver>/<platform>` → bash source that **defines `function __sdkman_post_installation_hook()`** (NO `exit`, NO shebang-only `cp` — sourcing + `exit 0` kills the caller shell, see Failure mode below)
4. Local `unzip -t` on the produced ZIP
5. Local `shasum --check` against the `X-Sdkman-Checksum-Sha256` header
6. Local `mkdir + unzip + mv` to materialize the candidate directory
7. Local `ln -s <version> current` if "set as default" was confirmed

**Crucial discovery: the post-installation hook is sourced, not executed.
Any `exit` statement in the hook body kills the SDKMAN shell and aborts
the install silently with no error message. This is not documented in the
public SDKMAN docs; it was discovered empirically.**

This protocol is **compatible with HTTP** (no client-side TLS enforcement),
**compatible with `127.0.0.1`**, and **does not require authentication**.
It is a clean subset to reproduce locally.

---

## What was NOT proven

1. **Official publication.** The mirror protocol works on the wire, but
   the public SDKMAN catalog (`api.sdkman.io/2`) does not yet have a
   `pipelinek` candidate. The vendor onboarding email has been sent and
   is awaiting a response.
2. **Official installation.** Without official publication, there is no
   way to run `sdk install pipelinek 0.39.0` against the real SDKMAN
   catalog. Until that exists, "official installation" status remains
   `NOT_PROVEN`.
3. **Upgrade path.** Only `0.39.0` is published. The approver explicitly
   said: "No inventes una segunda release para probar upgrades."
4. **`sdk current` integration.** `sdk current` resolves the current
   version by reading the caller's `$PATH`. A non-interactive spike
   driver does not get PATH updated automatically. The installation on
   disk is correct and the binary runs by absolute path.

---

## Failure mode discovered during the spike (real, not fabricated)

On the first spike attempt, `sdk install` downloaded the binary, fetched
the hook, **then exited silently with no further output**. The candidate
directory was not created. Diagnosis:

- The first hook I served was `cp -f "$binary_input" "$zip_output"; exit 0`.
- The SDKMAN client does `source "$post_installation_hook"`.
- `source` evaluates the script in the current shell. `exit 0` inside a
  sourced script **terminates the calling shell**.
- Result: the hook call sequence aborted between `source` and the
  subsequent `__sdkman_post_installation_hook || return 1`, with no error
  message because the shell itself died.

**Fix:** the hook must define `function __sdkman_post_installation_hook`
and must NOT call `exit`. This is the actual SDKMAN convention. The fix
is documented in the hook generation code at
`$WU_LPR_090_DIR/mirror.py` lines 121-135.

**Lesson:** a server returning executable bash to a sourced caller has
real safety implications. The official SDKMAN broker presumably guards
this by either (a) signing hooks, (b) vetting vendor hooks at
publication time, or (c) both. A local mirror sidesteps this because
the operator is the same person writing both ends. **Do NOT use this
technique across trust boundaries.**

---

## Reproducing this spike

```
# pre-flight: SDKMAN 5.23.0 release ZIP exists, python3 available
WU_LPR_090_DIR=/home/rubentxu/.local/share/jcode/wu-lpr-090

# 1. start the local mirror
python3 $WU_LPR_090_DIR/mirror.py --bind 127.0.0.1 --port 9999 &

# 2. verify mirror
curl http://127.0.0.1:9999/healthcheck   # -> "ok"

# 3. run the spike driver (isolated SDKMAN_DIR under $WU_LPR_090_DIR)
bash $WU_LPR_090_DIR/run-spike.sh

# 4. inspect installed binary
$WU_LPR_090_DIR/sdkman/isolated/candidates/pipelinek/current/bin/pipelinek --help

# 5. teardown
pkill -f "python3 $WU_LPR_090_DIR/mirror.py"
```

All artifacts under `$WU_LPR_090_DIR`:

- `mirror.py` — Python `BaseHTTPRequestHandler` serving 7 endpoints
- `sdkman-cli-5.23.0.zip` — official SDKMAN release
  (SHA `7ef83583a6986351ea8c86b8494a885fcae91a2fbfac91662bca7ea4f72bd230`)
- `sdkman/` — extracted SDKMAN + isolated run directory
- `pipelinek-0.39.0.zip` — canonical ZIP
  (SHA `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`)
- `run-spike.sh` — driver (bash -n OK, pre-flight checks fail-closed)
- `spike-run.log` — full debug trace of the final successful run
- `mirror.log` — server-side access log

---

## Impact on other work units

- **LPR-GATE-1: GREEN-on-GitHub / NOT_YET_CLOSED (unchanged).** This
  spike does not close the gate. The gate requires official SDKMAN
  publication; this spike only proves local protocol compatibility.
- **WU-LPR-080: WAITING_EXTERNAL (unchanged).** The vendor credential
  blocker is unchanged. The spike does not unblock publication.
- **WU-LPR-071: handoff (unchanged).** No change to the resume protocol.

The spike is a **parallel diagnostic**, not a substitute for official
publication. Both pieces of evidence are wanted; they are not the same.

---

## Recommendations going forward

1. **Keep `run-spike.sh` in the repo** under `scripts/release/sdkman-uat/`
   so that when vendor credentials arrive, the same driver can re-verify
   the protocol against the official catalog in seconds (one env-var flip).
2. **Keep `mirror.py` in the repo** for re-running the spike on demand.
3. **Promote `sdk install pipelinek 0.39.0` UAT to CI** that uses the
   official SDKMAN catalog, gated on the vendor credentials being
   present as repo secrets.
4. **Do not ship the mirror as a product.** It is a fixture, not a
   candidate SDKMAN mirror — that would imply SDKMAN endorses it, which
   they do not.
