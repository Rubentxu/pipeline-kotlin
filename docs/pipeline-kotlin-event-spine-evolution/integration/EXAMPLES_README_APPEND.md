## Event-contract acceptance (EVT)

`examples/` is executable product documentation. A supported example is accepted only when its expected
execution outcome and observable event contract both pass through the installed CLI distribution.

Planned additions:

| Example | Purpose |
|---|---|
| `07-catch-error.pipeline.kts` | nested catchError event/order/scope contract |
| `08-parallel.pipeline.kts` | partial-order branch lifecycle + replay no-fabrication |
| `09-retry.pipeline.kts` | deterministic fail→success + replay no extra attempt |
| `10-timeout.pipeline.kts` | effective timeout scheduling + typed terminal outcome |

Each may have a companion `*.events.yaml` contract. The Event Harness runs POST_RUN by default; live
verification is not required for these assertions.
