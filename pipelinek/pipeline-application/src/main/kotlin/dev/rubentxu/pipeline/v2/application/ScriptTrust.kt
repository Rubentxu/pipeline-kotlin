package dev.rubentxu.pipeline.v2.application

/**
 * The trust model of a `.pipeline.kts`, written ONCE.
 *
 * ## What this is
 *
 * A `.pipeline.kts` is not data the product reads. It is CODE the product
 * compiles and runs, inside the product's own process, with the product's own
 * classpath and the operator's own permissions. That fact has to be somewhere
 * an operator reads BEFORE they point the product at a script they did not
 * write — and it has to be in exactly ONE place, because a model stated twice
 * is two claims free to disagree, and the one that lies is the one nobody
 * re-reads.
 *
 * ## Why it was written twice, and wrong once
 *
 * The model used to live in a KDoc bullet of `fun main` and in a comment above
 * the `validate` branch, and the two said different things: the KDoc promised
 * that `validate` starts nothing at all, and the comment promised that the
 * branch does nothing but compile and report. The product did NEITHER:
 * `validate` compiled the script AND evaluated it, so the author's top-level
 * body ran inside the validating process.
 *
 * The comment was the more dangerous of the two, because it sat on the line
 * that ran the body and read as a promise about the code three lines below it.
 *
 * The old sentences are not quoted here on purpose: a copy of a false claim,
 * even a historical one, is a second copy of the claim, and this file is the
 * one place where the model lives. [ScriptTrustTest] requires the old wording
 * to appear in no source file of the product.
 *
 * ## What is here instead
 *
 * The sentences below are the model, and the help renders them. Every other
 * place that has to speak about the subject POINTS here instead of restating
 * it: a second copy of a sentence is a second claim, and this one is the kind
 * of claim an operator acts on.
 *
 * The check that keeps it that way is in `ScriptTrustTest`: it reads the
 * module's own sources and requires every sentence of the model to appear in
 * exactly one file — this one — and requires the old false claim to appear in
 * none.
 */
internal object ScriptTrust {

    /** The heading the help renders above the model. */
    const val HEADING: String = "Trust model:"

    /**
     * The model itself, one sentence per line.
     *
     * A list and not a paragraph because the help renders one line per entry,
     * and a paragraph would have to be re-wrapped by whatever reads it next.
     */
    val LINES: List<String> = listOf(
        "A .pipeline.kts is TRUSTED CODE, not data.",
        "'run' compiles it and RUNS its top-level body, inside the product process,",
        "with the product's classpath and the operator's own permissions.",
        "'validate' does the same: it compiles the script AND evaluates it, so the",
        "script's body runs there too. It starts no step of the pipeline, but the",
        "body it runs is the author's own code and may do whatever the author wrote.",
        "Use 'validate --compile-only' to check that a script COMPILES without running it.",
        "It builds NO plan: it checks no shape of the pipeline, because the body that",
        "declares the pipeline is the body that did not run.",
    )

    /**
     * What a compile-only validation reports, so that two validations that
     * differ in what they did never print the same line.
     *
     * It lives here for the same reason the model does: it is a sentence about
     * what the product did with the author's body, and there is one owner of
     * those sentences.
     */
    const val COMPILE_ONLY_NOTE: String = "compile only: the script was not run"
}
