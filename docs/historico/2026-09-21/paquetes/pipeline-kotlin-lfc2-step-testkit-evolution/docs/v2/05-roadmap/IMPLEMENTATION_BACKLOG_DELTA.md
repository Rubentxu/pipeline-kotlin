# Implementation backlog delta

## LFC-2 promoted prerequisites

- **LFC2-H00** Step Constitution ADR/SPEC/AGENTS/fitness.
- **LFC2-TK0** Scenario model + runner over current examples.
- **LFC2-TK1** PipelineExtension.
- **LFC2-X01** Generic Invoke/StepDefinition/Registry seam; migrate echo/sh.
- **LFC2-TK2** RealPipelineExtension.
- **LFC2-X02** Independent external reference plugin.
- **LFC2-TK3** StepContractSuite + certification receipt.
- **LFC2-X03** Strict DSL + pure smart constructors.
- **LFC2-X04** StepBodies + BodyInvoker + dir/withEnv/timestamps.
- **LFC2-X05** retry/timeout generic bodies.
- **LFC2-X06** composable parallel.
- **LFC2-X07** durable scripted runtime values.
- **LFC2-X08** typed conditions + post.
- **LFC2-X09** formal @KotlinScript + source fidelity.
- **LFC2-TK4** PipelineSessionExtension.
- **LFC2-TK5** rootless Podman SandboxPipelineExtension.
- **LFC2-TK6** curated 20–30 scenario-backed examples.
- **LFC2-GATE** honest closure.

## Detailed gates

### LFC2-H00
Exit:
- ADR-LFC-018/019/022/023 accepted.
- SPEC-LFC-016 accepted.
- AGENTS rules merged.
- mechanical fitness tests added.

### LFC2-TK0
Exit:
- current supported examples execute unchanged;
- scenario result captures compile, exit, events, diagnostics.

### LFC2-X01
Exit:
- echo/sh no longer concrete-dispatched;
- runtime no longer depends on StepSpec for these routes;
- StepDefinition is semantic authority.

### LFC2-X02
Exit:
- independent Gradle plugin build;
- T2 install/load/compile/execute;
- zero core source edits.

### LFC2-TK3
Exit:
- machine-readable certification;
- echo/sh/external plugin certified.

### LFC2-X03
Exit:
- positive + negative DSL corpus;
- git/scmGit no duplicate emission;
- no fake runtime declarative values.

### LFC2-X04
Exit:
- child Steps re-enter engine;
- parent context restored;
- no block-specific child dispatch.

### LFC2-X05
Exit:
- retry attempt IDs;
- eventual success/exhaustion;
- timeout descendant kill;
- config preserved in payload.

### LFC2-X06
Exit:
- parallel siblings;
- overlap;
- deterministic join;
- resume/replay.

### LFC2-X07
Exit:
- shStdout durable result drives Kotlin control flow;
- replay no blind repeat.

### LFC2-X08
Exit:
- false when skips typed;
- post order/outcome correct.

### LFC2-X09
Exit:
- diagnostic lines reference original source;
- shell dollar corpus green without global source rewrite.

### LFC2-TK5
Exit:
- security scenario on Podman runner;
- explicit local skip when backend missing;
- mandatory CI lane.

## LFC-3 additions

- **LFC3-009** Publish/stabilize pipeline-plugin-testkit.
- **LFC3-010** External block-step reference plugin.
- **LFC3-011** Plugin compatibility matrix.
- **LFC3-012** KSP no-known-step fitness.
- **LFC3-013** Generated certification metadata.

## LFC-4 additions

- **LFC4-009** Remaining atomic core Steps through registry.
- **LFC4-010** Remove obsolete concrete command switches.
- **LFC4-011** Runtime cannot import scripting StepSpec.
- **LFC4-012** Capability use/declaration conformance.

## LFC-5 additions

- **LFC5-009** Credentials via BodyInvoker + lease patch.
- **LFC5-010** catchError/warnError via BodyOutcome.
- **LFC5-011** Nested block pairwise matrix.
- **LFC5-012** External block plugin certification.
