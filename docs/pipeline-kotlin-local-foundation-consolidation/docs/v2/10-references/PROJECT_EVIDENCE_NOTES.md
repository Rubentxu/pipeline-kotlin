# Project evidence notes from the architecture review

These are concrete observations that motivated the consolidation plan. Re-check exact line locations when merging against a newer commit.

- V2 `@Step` is a compile-time annotation and KSP emits `GeneratedStepDescriptors`.
- The reviewed KSP implementation contained a hardcoded mapping for `echo`, `sh`, `error` and `sleep`, emitted empty required capabilities and did not derive full parameter schemas.
- The reviewed SDK `StepContext` carried generic run/parameters/environment maps.
- The reviewed SDK `sh` executor directly constructed `ProcessDurableTaskRuntime` and temporary control directories instead of receiving a process capability.
- `STEP_PLUGIN_SDK.md` already describes the desirable façade -> descriptor -> command -> handler split and minimum typed capabilities; this pack promotes that direction as canonical.
- Declarative DSL review found several surfaces whose apparent runtime result/behavior was not represented honestly in the static builder model.
- Model/runtime review found duplicate pipeline/step descriptor authorities and legacy mapping/registry seams.
- Runtime review found coexistence of old environment composition and newer `EnvironmentComposer` direction.
- Output review found paths that re-aggregate streamed chunks into strings/events, undermining bounded-memory streaming.

The migration plan must validate these observations against HEAD before deleting code; the architectural invariants remain applicable even if filenames have moved.
