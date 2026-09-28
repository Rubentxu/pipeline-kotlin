# Evolution Roadmap — DSL Semantics, Directives, Events and Plugin Certification

## Strategy

Do not rewrite PipelineK. Migrate the current architecture in slices, preserving the durable spine and open Step registry.

Two horizons:

1. **Release honesty/stabilization** — remove semantic lies before the next stable candidate.
2. **Semantic evolution** — implement real directive/runtime extensibility after the honest baseline is frozen.

---

# TRAIN S0 — Semantic Honesty Gate (release blocker)

Goal: no current stable-claimed surface silently loses intent.

### S0.1 Reconcile actual Git/SDDK identity

- bind train to exact SHA;
- reconcile `d0a1da4a`/agent branch vs public `main`;
- never move published tags.

### S0.2 DSL Surface Manifest v1

Inventory every public DSL symbol and classify it.

### S0.3 Fix confirmed semantic drops

- `whenCondition` fail closed;
- `scmGit` pure / `git` exactly one checkout;
- retroactive `retry(count, delay)` no silent no-op;
- remove unsupported `whenCondition` from self-host release pipeline;
- verify `post`, `agent`, `options` are either real or explicitly unsupported/partial;
- no docs claim stronger semantics than code.

### S0.4 Semantic witness suite

Add discriminating tests for all stable `local-core` surfaces.

### S0.5 Release gate

Full suite + installed distribution + fresh clone.

Exit: safe to create immutable final RC. No real new `when/post` feature yet.

---

# TRAIN S1 — Directive Kernel v1

Goal: introduce a generic directive architecture without implementing vendor-specific features.

### S1.1 ADTs and registries

- `DirectiveKey`
- `DirectiveInvocation`
- `DirectiveDefinition<I,O>`
- `DirectiveExecutionPolicy`
- `DirectiveRegistry`
- contributor SPI

### S1.2 Stage interpreter phases

Implement deterministic stage lifecycle and directive admission.

### S1.3 Events

Add directive lifecycle/decision events.

### S1.4 External directive fixture

Create a trivial external guard/context directive proving zero core semantic changes.

Exit: directive engine is open by key but closed by structural policy.

---

# TRAIN S2 — Real `when` + `post`

## S2.1 `when`

- typed `StagePredicate` ADT;
- pure evaluator;
- phase selection;
- `Match/NoMatch/Rejected` outcomes;
- skip semantics;
- events;
- replay.

Do not support arbitrary expression strings in declarative `when` yet.

## S2.2 `post`

- `PostCondition` ADT;
- `PostPlan`;
- pure outcome->finalizer decision;
- deterministic ordering;
- execute via BodyInvoker;
- events and replay.

Exit: supported Jenkins-familiar subset with real semantics.

---

# TRAIN S3 — Agent/environment/options semantics

### S3.1 `agent`

Local capability/label constraints become real. Remote requirement fails closed until allocator installed.

### S3.2 declarative `environment`

Immutable context patch at stage boundary.

### S3.3 `options`

Replace optional flag bag with `StageOption` ADT; implement only options with real policy carriers.

Exit: no ornamental metadata.

---

# TRAIN S4 — Scripted Runtime v2

### S4.1 Generic `invokeTyped`

Remove per-Step scripted invocation boilerplate.

### S4.2 Explicit `StageBody`

Separate Declarative and Scripted forms. Do not permit ambiguous hybrid ordering.

### S4.3 Harden current PSI lowering

Exact node/range validation, no placeholder args, fail closed on mismatch.

### S4.4 K2 call-site injector spike

Benchmark an optional compiler/IR metadata injector. Adopt only if gates pass. No CPS.

### S4.5 Remove old textual surgery path if replacement wins

Exit: typed normal Kotlin control flow on durable runtime values.

---

# TRAIN S5 — Reactive Event Spine v2

### S5.1 EventEnvelope/EventRegistry
### S5.2 Plugin event definitions
### S5.3 Consumer offsets + replay projection
### S5.4 Observer API
### S5.5 Durable reactor API
### S5.6 agent/MCP projection adapter

Exit: local and external systems can react to typed facts without scraping logs.

---

# TRAIN S6 — Plugin SDK v2

### S6.1 unified plugin manifest
### S6.2 Step contributor v2 metadata
### S6.3 Directive contributor
### S6.4 Event contributor
### S6.5 KSP metadata generation only
### S6.6 ABI/API compatibility gate

Exit: external library can add Step + directive + event with zero semantic core edits.

---

# TRAIN S7 — Certification Harness v2

### S7.1 Semantic Witness matrix
### S7.2 directive certification
### S7.3 plugin event certification
### S7.4 external plugin mutation tests
### S7.5 exact installed distribution
### S7.6 restart/resume suite
### S7.7 structured verdict

Exit: a third-party plugin cannot be called certified while bypassing core laws.

---

# TRAIN S8 — Migration and compatibility

- migrate current surfaces to manifest;
- deprecate retroactive retry;
- migrate current `post/agent/options` claims;
- update docs/reference;
- compatibility fixtures;
- public plugin author guide.

---

## Non-goals until later

- full Jenkins controller compatibility;
- Groovy CPS;
- arbitrary string `when` expression interpreter;
- remote executor scheduler unless separately chartered;
- plugin-defined arbitrary coordinator phases.
