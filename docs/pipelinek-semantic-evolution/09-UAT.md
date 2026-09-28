# UAT / Acceptance Matrix

## A. Semantic conservation

### UAT-SC-001 — false condition never executes body

Given a typed `when` false predicate, body Steps emit zero StepStarted/effect events and StageSkipped is emitted.

### UAT-SC-002 — unsupported legacy condition fails closed

`whenCondition("1 == 2")` rejects before body effects.

### UAT-SC-003 — pure builder

`scmGit(...)` produces zero Steps/events; `git(...)` exactly one checkout.

### UAT-SC-004 — retry cannot disappear

Retroactive retry without compatible previous Step fails with actionable diagnostic.

### UAT-SC-005 — post executes from typed outcome

Success/failure/always bodies are selected from `StageOutcome`, not console text or exception strings.

## B. Directives

### UAT-DIR-001

External Guard directive registers without core source changes and can skip a stage.

### UAT-DIR-002

Unknown directive key rejects before stage body.

### UAT-DIR-003

Directive requiring missing capability rejects before effects.

### UAT-DIR-004

Two directives with deterministic phase/order yield byte-stable event ordering in fresh clones.

## C. Scripted runtime

### UAT-SCR-001

`val b = fileExists("x"); if (b) ...` uses real runtime Boolean.

### UAT-SCR-002

`readFile` return survives restart/reuse without rereading.

### UAT-SCR-003

Loop invocation identities are distinct and stable.

### UAT-SCR-004

Source/lowering mismatch rejects compilation; no partial rewrite.

### UAT-SCR-005

External plugin runtime-returning Step uses generic `invokeTyped` with zero compiler Step-specific branch.

## D. Events/reactivity

### UAT-EVT-001

Every supported Step/directive has declared event contract.

### UAT-EVT-002

No secret material in event store/JSONL after withCredentials scenario.

### UAT-EVT-003

Slow observer cannot block pipeline execution beyond bounded append cost.

### UAT-EVT-004

Observer replay reconstructs timeline without generating new durable effects.

### UAT-EVT-005

Duplicate external event delivery yields one reactor command via ReactionId idempotency.

### UAT-EVT-006

Plugin event schema collision rejects registration.

## E. External plugin constitution

### UAT-PLG-001

Atomic plugin Step: install jar, discovery, execute, typed output, event, replay.

### UAT-PLG-002

Block plugin Step using existing BodyExecutionPolicy executes through BodyInvoker with zero core semantic edits.

### UAT-PLG-003

External directive using existing DirectiveExecutionPolicy works with zero coordinator edits.

### UAT-PLG-004

Plugin attempting undeclared capability is rejected.

### UAT-PLG-005

Plugin requesting unsupported structural shape is rejected, never flattened.

### UAT-PLG-006

Plugin compiled against incompatible API range fails admission with typed compatibility error.

## F. Performance

### UAT-PERF-001

Registry composition occurs once; zero ServiceLoader scans during Step hot path.

### UAT-PERF-002

Directive evaluation allocation/time budget established and regression-gated.

### UAT-PERF-003

Event append throughput and bounded observer queue tested under parallel branches.

### UAT-PERF-004

Scripted artifact cache hit avoids recompilation.

### UAT-PERF-005

If K2 call-site injector is tested, compile overhead and diagnostics are compared to PSI baseline before adoption.
