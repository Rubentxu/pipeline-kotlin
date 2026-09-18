# Tasks: local-production-ready

## LPR-0 — authority/baseline

- [ ] review/accept ADR-0082..0091 or record amendments;
- [ ] apply roadmap authority banners;
- [ ] classify historical branches reference-only;
- [ ] repair V2 PR/main CI (current build workflow baseline is empty);
- [ ] record exact green/red test baseline;
- [ ] add performance characterization harness before optimization;
- [ ] capture current event connection/transaction counts and output throughput;
- [ ] capture durable sequence restart behavior;
- [ ] closure receipt.

## LPR-1 — product contract

- [ ] freeze intended `local-core-v1` list;
- [ ] inventory public DSL state SUPPORTED/EXPERIMENTAL/REJECTED;
- [ ] characterize/lock CLI 0/1/2 exit semantics;
- [ ] add default `pipeline.kts` discovery design/tests;
- [ ] implement/contract `doctor` and `version`;
- [ ] define `.pipelinek` state ownership/versioning boundaries.

## LPR-2 — execution/body

- [ ] generic BodyRef cardinality `None/Single/Named` proof;
- [ ] `BodyExecutionEngine` + pure plan resolution;
- [ ] migrate Sequential/Scoped;
- [ ] migrate retry/timeout;
- [ ] migrate composable parallel;
- [ ] add coordinator no-concrete-Step fitness;
- [ ] external body plugin proof;
- [ ] extract `InvocationEngine` if characterization demonstrates clean seam;
- [ ] do NOT add RepeatUntil unless waitUntil promotion starts.

## LPR-3 — DSL honesty

- [ ] DSL marker/receiver boundaries;
- [ ] incremental file ownership split;
- [ ] explicit declarative vs runtime API;
- [ ] real runtime `pwd`;
- [ ] real runtime `isUnix`;
- [ ] audit when/post/load/waitUntil/node and fail closed or implement;
- [ ] no-fake-return fitness;
- [ ] installed corpus parity.

## LPR-4 — observation/performance substrate

- [ ] ratify performance budgets from baseline;
- [ ] single long-lived SQLite event writer;
- [ ] prepared statement + micro-batches;
- [ ] bounded ingress and typed degradation;
- [ ] initialize event sequence from durable max on reopen;
- [ ] final flush barrier;
- [ ] remove per-chunk `runBlocking` from process output hot path where safe;
- [ ] streaming redactor across chunk boundaries;
- [ ] buffered append-only transcript;
- [ ] ConsoleCursor/offset read seam;
- [ ] migrate high-volume sh output out of `EchoOutputCaptured` payload after parity;
- [ ] 200 MiB and 1 GiB stress gates;
- [ ] slow consumer isolation gate.

## LPR-5 — CLI observation UX

- [ ] implement `ObservationView` ADT;
- [ ] implement text/jsonl/json renderers;
- [ ] machine stdout purity;
- [ ] normal failure context tail;
- [ ] events filters;
- [ ] console/log tail;
- [ ] `--follow` using wakeup + cursor recovery;
- [ ] `--fields`;
- [ ] `inspect --failed --context --log-tail`;
- [ ] view parity test;
- [ ] agent bounded-diagnosis UAT.

## LPR-6 — certification/real projects

- [ ] generated certification ledger source of truth;
- [ ] certify every supported local-core row;
- [ ] Gradle real-project fixture;
- [ ] Maven real-project fixture;
- [ ] Node real-project fixture;
- [ ] failure-path fixtures;
- [ ] artifact fixture;
- [ ] credential/redaction fixture;
- [ ] durable resume fixture;
- [ ] all through installDist binary.

## LPR-7 — distribution/release

- [ ] application name `pipelinek`;
- [ ] release version authority;
- [ ] reproducible `distZip`;
- [ ] SHA-256;
- [ ] SBOM;
- [ ] V2 release workflow;
- [ ] build-twice digest gate;
- [ ] GitHub Release asset smoke.

## LPR-8 — SDKMAN

- [ ] vendor onboarding/candidate approval;
- [ ] protected SDKMAN credentials;
- [ ] official release action/API integration;
- [ ] publish canonical GitHub ZIP;
- [ ] checksum parity;
- [ ] clean CI-mode SDKMAN installation;
- [ ] run doctor/validate/real project after install.

## LPR-9 — dogfooding

- [ ] adopt in multiple real repos;
- [ ] record defect/UX/performance feedback;
- [ ] execute at least one version upgrade/migration round;
- [ ] review post-LPR ecosystem priority from evidence;
- [ ] LPR-GATE-1 decision/receipt.

## Global stop conditions

Stop and escalate to human review if a WU would:

- add Step-specific coordinator/compiler routing;
- create a second production execution algorithm;
- make observation consumer part of execution cancellation/failure domain;
- persist raw secrets;
- add unbounded queue/list of output/events;
- count disabled/quarantined mandatory UAT as pass;
- cherry-pick historical architecture instead of re-deriving it on main;
- broaden product support without certification evidence.
