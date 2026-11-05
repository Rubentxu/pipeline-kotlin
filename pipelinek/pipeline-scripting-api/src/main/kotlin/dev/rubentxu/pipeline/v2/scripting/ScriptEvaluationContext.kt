package dev.rubentxu.pipeline.v2.scripting

/**
 * The context a script is evaluated in.
 *
 * The type names that role and declares no members of its own, so it fixes
 * nothing a caller can read from it: a caller may pass one around, and the
 * behaviour behind it belongs to whoever implements it.
 */
interface ScriptEvaluationContext
