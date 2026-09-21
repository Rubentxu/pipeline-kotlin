# Integration manifest

## Accept first

1. `CURRENT_STATE.md`
2. `DOCUMENT_AUTHORITY.md`
3. `ARCHITECTURE_PRINCIPLES.md`
4. `ROADMAP.md`
5. ADR-LFC-001 (local-first product scope)

## Existing material to preserve and fold in

The following reviewed repository assets contain valuable direction and SHOULD be incorporated rather than rewritten from zero:

- `docs/v2/03-specifications/STEP_PLUGIN_SDK.md`: keep its façade → descriptor → command → handler separation, typed capabilities, manifests and plugin test contracts; supersede implementation details that imply remote workers/OCI are current requirements.
- Jenkins familiarity documents/catalogue: keep compatibility levels and canonical signatures; move status toward generated verification.
- durable runtime/journal/replay ADRs and tests: preserve their established invariants.
- credential projection work and local-first UAT evidence: use as migration baseline.
- output-store and graph spikes: promote their local-first conclusions into accepted specs only when implementation begins.

## Existing material to quarantine/defer

- active protocol/controller code and roadmap work;
- V1 product architecture in root docs/build narratives;
- old release workflows tied to V1/JDK assumptions;
- alternate execution paths superseded by the new dispatcher/handler spine.

## Integration rule for existing ADR numbers

`ADR-LFC-*` are staging identifiers. Renumber sequentially only after checking the repository's current ADR registry. Preserve a `Former-ID: ADR-LFC-xxx` line for traceability.
