# WU-LPR-064 Receipt — Real Node.js project via installed distribution

## Fixture: `integration/node-demo/`
Real Node.js project (package.json, node:test runner): `src/app.js` (add),
`test/app.test.js` (pass + deliberate-break switch via `DEMO_BROKEN=true`).
`pipeline.kts` (`node --test` + NODE-DEMO-OK) and `pipeline-fail.kts`
(`DEMO_BROKEN=true node --test`).

## Change
No production change: reuses WU-LPR-062's generic `--workspace`.

## Evidence (OBSERVED)
- Success: installed `pipeline-application run --db ... --workspace . pipeline.kts`
  → exit 0, RunFinished success, NODE-DEMO-OK echoed.
- Failure: `pipeline-fail.kts` → exit 1, RunFinished failure, node:test fail 1.
- Baselines: `npm test` 2/2 pass; `DEMO_BROKEN=true npm test` fail 1.

## Counters
Certified Steps unchanged: 15 SUPPORTED_CERTIFIED (+1 EXPERIMENTAL).
