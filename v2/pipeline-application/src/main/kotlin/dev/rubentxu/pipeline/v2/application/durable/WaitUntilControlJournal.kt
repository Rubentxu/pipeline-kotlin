package dev.rubentxu.pipeline.v2.application.durable

/**
 * WU-G5R.4 / WU-G5R.5: durable control journal for the waitUntil predicate polling loop.
 *
 * ## Single-writer law
 * The dispatch loop in [CanonicalDurableRunCoordinator] is the only caller of journal
 * operations. Child executors, step handlers, and event projectors MUST NOT mutate
 * the control file.
 *
 * ## Persist-before-effects contract
 * A journal operation writes its state to durable storage BEFORE returning. The caller
 * MUST launch any child effect only after the operation has returned without throwing.
 *
 * ## Scope
 * Created in WU-G5R.5; this interface is the wiring point introduced in WU-G5R.4.
 * The nullable default (`= null`) preserves all existing coordinator constructions.
 *
 * @see FileBasedWaitUntilControlJournal — the production implementation (WU-G5R.5)
 */
interface WaitUntilControlJournal
