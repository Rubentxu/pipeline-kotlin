# Compilation benchmark and adoption protocol

**Status:** Required design; this bundle certifies no new cache benchmark.

## Owner and inputs

Consume current WU-RP-022 and the canonical release/harness protocol. Define/approve SLOs after measuring baseline. Record source SHA/dirty status, installed ZIP digest, actual runtime/compiler/JDK/API profile, OS/CPU/storage, enforced constraints, JVM options, XDG/cache state, source/dependency identities and fixture versions. Generated scripts/caches live in user-owned or disposable fixtures, not product checkouts.

Corpus: expressions, typed/declarative builders, scripted dynamic control flow, returnStdout/returnStatus, warning/error, construction rejection, generated façade/plugin, local Shared Library and Maven transitive closure. Include representative small/large cases chosen from real use; freeze bytes/order.

## Separate experiments

| Experiment | Controlled state | Required evidence |
| --- | --- | --- |
| Cold one-shot | New JVM, no compilation entry; resolver state declared | Resolve/admit/compile/load/eval/startup/runtime durations and compiler count |
| Warm resolver | Same artifacts, new JVM, no compilation entry | Resolver hit cannot count as compiler hit |
| Warm compatible JVM | Existing owner, no artifact entry | JIT/environment reuse separated from avoided compilation |
| Memory artifact hit | Same live owner, same v2 inputs | Zero compiler calls, fresh evaluation count/outcome |
| Persistent hit | New CLI/JVM using approved disk entry | Zero compiler calls and real load/evaluation; only after persistence GO |
| Invalidation | Change bytes/order/options/profile independently | New identity, miss and real compile; no stale output |
| Failure/cancel/pressure | Compile failures, shared callers, eviction | No partial hit, bounded resources and context isolation |

Declare resets; do not silently flush OS caches or alter network state. Compare matched environments in a stated order, retain all samples/warm-up decisions. Report distributions with sample counts, CPU time, RSS series, heap/native distinction, handles and retention. Timing alone never proves a cache hit.

## GO receipt

Record measured user benefit, actual representation/equivalence, approved latency/resource budget, real enforcing owners, enabled modes, rejected alternatives and applicable UAT/AAT results including skips. Do not reuse an older SHA/JVM result as certification. One-shot CLI, worker and LSP are distinct consumers. Hits still run current admission and fresh evaluation. Final promotion follows the full gate for the exact candidate bytes.
