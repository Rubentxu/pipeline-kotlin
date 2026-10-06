# S5.4 Observation Vertical — IMPLEMENTATION RECEIPT

**Status:** `IMPLEMENTED_UNCERTIFIED` → `CERTIFIED` for the seven requirements R1–R7, bounded to
the exact SHA below. This receipt is evidence for **its own SHA** and inherits nothing.

| Field | Value |
|---|---|
| SHA | `527ca77019f33e9c2eafd20c53c721880feb1a15` |
| Branch | `s5-observation-vertical` |
| Base | `46dd7234` (S5.4 plan) · `main` at `569a088c` |
| Cycle | `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine` |
| WorkItem | `88c5df3f-c1fe-4129-b96b-cb77a002baec` (S5.4 Observation Vertical) |
| Tree fingerprint (12 files) | `3d35eaea34437c9fdc281cf47889b2354f961c23fc96dd3fb19abfc43266fcb0` |
| Gate | `BUILD SUCCESSFUL in 37m 23s` · 329 tasks executed · `EXIT=0` |
| Tests (bounded by gate start `mtime`) | **5077** · 0 failures · 0 errors · 140 skipped · 766 classes |
| `apiCheck` | 6 modules green |
| `detekt` | 27 modules green |

Gate argv: `cd v2 && PIPELINEK_SPIKE_HOME=/var/home/rubentxu/.local/state/pipelinek-bundles/e4c-4700f23d ./gradlew --no-daemon check --rerun-tasks`
Gate start: `2026-10-06T17:04:53Z` (UTC). The test count is bounded by that instant so it counts
what **this** gate ran, over the whole `v2/**` tree — the `v2/*/` glob omits nested modules.

**No remote CI ran, and none is claimed.** GitHub Actions was removed in `754ddda0`; `PRODUCT-GATE`
therefore stays `BLOCKED_EXTERNAL` by its own definition. This is a **STEP-CERT**: complete and
executable on the SHA, with no remote CI required and no contamination of that separate gate.

---

## 1. What was delivered

A durable row that exists and cannot be read now reaches the consumer. Before this block it could
only be expressed by throwing the page away.

```
SqliteEventStore.readRecords        one page of rows, each Decoded or explicitly Undecodable
        ↓
EventHistoryReader.readAfter        the SAME authority, result split instead of collapsed
        ↓
EventPage.refusals                  the refusals travel on the page
        ↓
EventPageDrain                      a bounded FILTERED answer, through EventTail only
        ↓
MainEventsCli                       the only durable entry point an external process can reach
```

Seven commits, each with an SDDK receipt bound to the exact staged tree:

| SHA | Unit |
|---|---|
| `a43525de` | `EventPage.refusals` + `readAfter` → `readRecords` |
| `c8d81714` | `MainEventsCli` → `EventPageDrain`; refusal on stderr; `EventQuery.matches` public |
| `de075023` | Mandatory 40/41/42 case against the real `SqliteEventStore` |
| `ab7b6f6d` | Binary break of `EventPage` registered with its real SHA + `apiDump` |
| `7a17ab16` | External vertical: two processes, one durable cursor |
| `527ca770` | Trailing-newline correction the full gate found |

---

## 2. Requirements, and where each is proven

| Req | Claim | Proof | Level |
|---|---|---|---|
| R1 | 40 / 41 unreadable / 42, against the store that can refuse | `S54DurableRefusalContractTest` | real `SqliteEventStore` |
| R2 | The consumer is told 41 exists | same + `MainEventsCliRefusalVisibilityTest` | store + CLI |
| R3 | The CLI cannot fail open | `EventPageDrain` can only call `EventTail.readAfter` | structural |
| R4 | Built-in and plugin typed; unknown/malformed never invented | `S54DurableRefusalContractTest` | real store |
| R5 | Strict mode still throws | `readSlice` still `requireFullyDecoded()` | real store |
| R6 | A real cursor across a process restart | `S54ExternalVerticalRestartUatTest` part 1 | **installed binary** |
| R7 | No loss, no duplicates | same, parts 1 and 2 + drain paging test | installed binary |
| R8 | ABI accounted for | `ab7b6f6d`, `apiCheck` green | BCV |

R6 is the only claim that needs a distribution image, and it is the only one proven on one. Every
other law in this block runs in-process; the external process is not a detail of it, it is the claim.

---

## 3. Mutations: every behavioural claim has teeth

Each attributed 1:1 and restored with a verified `sha256`. **A requirement that cannot fail is not
a requirement.**

| ID | Mutation | Result |
|---|---|---|
| D-M1 | delete `refusals = slice.refusals` | 4 laws red |
| D-M2 | fabricate an envelope for the refusal | 1 law red |
| D-M3 | advance the cursor by `decoded` | **0 laws red — and that is correct** |
| D-M4 | add the field, leave `readAfter` on `readSlice` | 6 laws red, the 4 structural stay green |
| D-M5a | `Outcome.Answered` returns an empty refusal list | 3 laws red |
| D-M5 | revert the CLI to `history` + `take(limit)` | 3 laws red; **and** part 2 of the external vertical |
| D-M6 | the store's SQL page cut drops a refusal instead of carrying it | **15 laws red** |

Two of these deserve their own line, because what they found was not in the code under test.

**D-M3 found a defect in this test suite, not in the product.** The cursor law used 40/41/42, and
there the last row is valid, so `nextCursor.lastSequence` and `decoded.last().sequence` were the
same number and the wrong computation passed for the right reason. The case that does distinguish
is a page that **ends** in a refusal, and it was added.

**D-M4 measured that the two halves of the law are not redundant.** Adding the field without
touching `readAfter` kills 6 behavioural laws and leaves all 4 structural ones green: a contract
that has the field, always empty, that looks implemented. That is the most likely way to get this
unit wrong, and it is now measured rather than argued.

---

## 4. What the measurement corrected

The first draft of three KDocs claimed the CLI **silently dropped** the unreadable row. D-M5 says
otherwise. `history` routes to `EventStore.eventsFor`, which cannot represent a refusal and
therefore **throws** `UndecodableEventRecordException`. The real defect was worse and simpler:

```
one unreadable row
      ↓
the whole observation dies
      ↓
stack trace, no history, exit status chosen by the JVM
      ↓
the 40 readable rows before it are lost too
```

A second defect — the continuation taken from `envelopes.lastOrNull()` — is real but **unreachable**
through that path, because the throw always fires first. It is proven where it is reachable, at the
reader, with a page that ends in a refusal. All three KDocs were corrected; the claim was not
softened, it was replaced with the measured one.

---

## 5. Cost: the read seam was not free

`EventPage.refusals` is a **binary break** of a published module, registered in
`published-contract-exceptions.json` under `a43525de`. The default value buys **source** compatibility
only: Kotlin emits the full constructor plus a synthetic one taking a `DefaultConstructorMarker`, so
the three-argument `<init>`, `copy` and `copy$default` disappear and a non-rebuilding consumer gets
`NoSuchMethodError`.

`@JvmOverloads` was considered and rejected: it would keep the old constructor in the ABI and make
`apiCheck` pass with no entry, at the price of publishing a constructor that means "a page with no
refusals" — the defect republished as convenience.

**This is a data point for S8.** The read seam is where future observer capabilities will land, and
S8 freezes exactly that surface. What shape the *next* read capabilities take is worth deciding
before the freeze, not after.

`EventQuery.matches` changed in the same block and is deliberately **not** registered: purely
additive, no member removed. Registering it would be a lie about what shipped.

---

## 6. Open items this block did NOT close

- **D4 `CoreWaitUntilStep`** — still allowlisted by file and line. Belongs before S8, which freezes.
- **D5 contract maturity** — `pipeline-domain` stays `EXPERIMENTAL`. Its own `rationale` names S5.4
  as the precondition for promotion, which makes this block a second, undeclared deliverable of it.
- **`CompiledScriptedEntryPointHostTest`** — a pre-existing HF3 defect. `@Timeout(30)` at class
  level against a measured 12.59 s body: a 2.4× margin that a parallel gate can consume. It failed
  the first gate run and passed the second, with no code change between them. Recorded as a
  finding; **not** relaxed and **not** retried away.
- **Two untracked paths in `docs/`** (`pipelinek-v1-to-v2-evolution-bundle*`) are not this block's
  and were neither touched nor committed.
- **`PRODUCT-GATE`** remains `BLOCKED_EXTERNAL`, by definition and not by omission.

## 7. Harness fidelity debt paid by this block

Four of the five failures in this block were **harness** defects, not product defects: two missing
trailing newlines (the second reaching commit because `detekt` had not been run), three fixtures
that misread the store's numbering from 1, and one duration assertion. The one that mattered most —
the `events` verb consumed as a `runId` — made a law green over a history it was not reading. It
surfaced only because three tests failed at once, not because it was known.