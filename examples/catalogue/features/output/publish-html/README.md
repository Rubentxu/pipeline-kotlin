# Publish Html

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `output`

**Expected outcome:** `success`

## What it exercises

The output plane: what a step produced, and how it is read back

*The example says this about itself:*

> 'core.publishHTML' installed-CLI canary.
> Exercises the registry-path 'core.publishHTML' Step through the DSL surface.
> Stage "build" creates an HTML report directory under reports/ in the
> workspace and publishes it under the logical name "lpr090-html".
> Failures here mean one of:
> - registry resolution broke (StepKey "core.publishHTML" not found)
> - the registry handler regressed (no HtmlReportPublished, or wrong path shape)
> - the DSL lowering reverted to a legacy StepSpec
> Storage layout: <controlRoot>/reports/<runId>/<lpr090-html>/. The reports
> directory is a SIBLING of <controlRoot>/workspace/ so cleanup of the build
> stage workspace does NOT destroy the published report.
> Verification: the installed CLI runs this fixture end-to-end. What this
> comment used to claim, and what was false, is that an orchestrator asserted
> the events include exactly one HtmlReportPublished "verified in
> UatCompat001CorpusSmokeRunTest". That test does not exist -- it was removed in
> the RESET -- and every source file that mentions the event lives in
> src/main, none in a test. So the canary had no coverage of the event it names.
> What is actually checked here is narrower and stated as such: the step
> resolves, runs, and leaves its two report files behind with their own bytes.
> Asserting the EMITTED EVENT would need the journal to be read back, which
> this scenario does not do. The guarantee named in the comment above is
> therefore unverified, not verified elsewhere.

## The stages and the steps

### 1. `build`

- `sh` — `mkdir -p reports/lpr090`
- `writeFile` — `file = "reports/lpr090/index.html", text = "<html><body>Hello LPR-090</body></html>"`
- `writeFile` — `file = "reports/lpr090/data.html", text = "<html><body>Data</body></html>"`
- `publishHTML` — `name = "lpr090-html", reportDir = "reports/lpr090", reportFiles = "**/*.html",`
- `sh` — `test -s reports/lpr090/index.html && test -s reports/lpr090/data.html`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/output/publish-html/publish-html.pipeline.kts
pipelinek run      examples/catalogue/features/output/publish-html/publish-html.pipeline.kts
```

## Its lineage

- **Historical name:** `32-publish-html.pipeline.kts`
- **Origin path:** `examples/output/32-publish-html.pipeline.kts`
- **Body digest:** `sha256:38fb729b5cb7d608064c7883199ad66acc01c3d47d0a368a2c3a4a429a62c80c`
- **Origin digest:** `sha256:38fb729b5cb7d608064c7883199ad66acc01c3d47d0a368a2c3a4a429a62c80c`
