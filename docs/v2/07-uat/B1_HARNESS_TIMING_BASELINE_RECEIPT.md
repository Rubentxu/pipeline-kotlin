# B1 — timing baselines: what the dev gate costs, what the round gate cannot tell us

- **Date:** 2026-10-15
- **HEAD at authorship:** `efbfde97` (worktree `test-harness-brave-harbor-6920`)
- **Scope:** measurement only. The only product change is rule 4's budget fallback in
  `AGENTS.md`.

---

## 1. Why this receipt is shorter than the roadmap item

The backlog asked for (a) a measured dev-vs-gate comparison and (b) an N≥5 full-gate
baseline. (b) cannot be produced honestly on this base:

```text
just gate  (check -Pshard.excludes, incremental)  -> FAIL, pre-existing
            5 rows of certification-pin drift in :pipeline-application:test
            (proven base-vs-head in B1_HARNESS_ROUND_GATE_STATE_RECEIPT.md §2)
just gate-escalate (--rerun-tasks, budget 1270)   -> killed by its timeout, exit 124
```

A red gate stops early, so its duration measures the distance to the first failure and
nothing else. Deriving a budget from it would be a fabricated number. What is recorded below
is what can be measured on this tree, plus the policy consequence.

## 2. Measured: the dev gate

```text
$ time just dev efbfde97~1
dev plan: gradle=[] python=[scripts/test_gen_current_uat_status.py scripts/test_admission_check.py]
dev> python3 scripts/test_gen_current_uat_status.py     Ran 44 tests in 121.086s  OK
dev> python3 scripts/test_admission_check.py            OK
exit=0  wall=177s
```

Read: the L-DEV gate for a harness-only diff is **177 s**, and it is dominated by the two
python suites of the changed tools (121 s + ~56 s); no Gradle graph was planned at all
(`gradle=[]`), because the diff touched no module source. The contrast with the round gate
(failed at 1270 s and still not finished) is the whole point of the ladder: the inner-loop
gate costs minutes and localises the failure; the round gate is the once-per-round gate.

## 3. Measured: N≥5 samples with the fresh-vs-warm instrument

```text
$ just baseline ':pipeline-events:detekt' 5
run 1: exit=0 wall=2.081s  executed=0 up-to-date=3
run 2: exit=0 wall=1.889s  executed=0 up-to-date=3
run 3: exit=0 wall=18.674s executed=0 up-to-date=3   [startup=7.572 config=6.187]
run 4: exit=0 wall=2.958s  executed=0 up-to-date=3
run 5: exit=0 wall=2.394s  executed=0 up-to-date=3
median=2.394s min=1.889s max=18.674s verdict=PASS work_executed=False
```

Three facts from this sample, all of them load-bearing for rule 4:

1. a warm no-op is ~2 s and `work_executed=False` — the instrument refuses to call that
   "work", which is exactly the distinction rule 2 relies on;
2. the 18.7 s outlier is configuration/startup under **CPU contention** (a sibling worktree
   running `check --rerun-tasks --no-build-cache`), not a property of the task;
3. therefore a single sample is not a baseline, and a contended sample is not evidence about
   the code. N=5 with a median is the minimum that survives this host.

## 4. Policy consequence (the only file changed besides this receipt)

`AGENTS.md` rule 4 now states the fallback explicitly:

```text
- a red gate on the base yields NO green duration: do not derive a budget from a red run;
- keep the last green duration recorded for ANY base, cite its SHA, and re-derive when a
  green run exists;
- one gate at a time: a concurrent full gate can blow the budget by itself (2.4s -> 18.7s
  measured above).
```

The 977 s / 948 s figures already in rule 2 remain the last green references available for
this repository; they are cited with the dates they were measured, not re-attributed to this
base.

## 5. What is still open, with the exact reason

- A green L-ROUND duration for this base cannot exist until the certification-pin drift is
  resolved (owner/version-bump decision, see the round-gate receipt). When it is, run
  `just gate` once and record the duration here.
- The dev-vs-gate ratio above is for a harness-only diff. An application-code diff would plan
  Gradle tasks as well, so the ratio is not a constant of the repository and must not be
  quoted as one.
