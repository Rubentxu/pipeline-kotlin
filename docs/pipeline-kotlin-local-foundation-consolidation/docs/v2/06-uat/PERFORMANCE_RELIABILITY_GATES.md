# Performance and reliability gates

## Output memory

For a >=1 GiB stdout/stderr workload, resident memory MUST remain bounded independently of total output size. Define an initial ceiling after a baseline benchmark; target a conservative envelope such as <256 MiB incremental RSS for the pipeline process, then tighten with evidence.

## Startup

Track separately:

- CLI cold start;
- plugin resolution/verification;
- script compilation cold/warm;
- run startup.

Do not optimize by weakening verification or correctness.

## Cancellation

No child process should remain alive after timeout/cancel UAT beyond a short bounded cleanup period.

## Durability

Crash-injection tests at operation boundaries verify that resume never treats an incomplete result as committed.

## Store growth

- event count/size scales with step/control activity, not log bytes;
- journal scales with operations, not streamed output;
- output store scales with actual output and has retention/cleanup controls.
