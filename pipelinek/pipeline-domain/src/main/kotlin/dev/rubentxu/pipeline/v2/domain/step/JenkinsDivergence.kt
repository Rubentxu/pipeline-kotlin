package dev.rubentxu.pipeline.v2.domain.step

/**
 * One divergence between this product and Jenkins.
 *
 * ## What a divergence is
 *
 * A place where the two products say the same thing in different words, or
 * where they do the same thing in a different way. It is not a criticism of
 * Jenkins and not a defect of this product: it is the difference a reader who
 * comes from Jenkins is going to meet, said out loud so that nobody has to
 * discover it by failing.
 *
 * ## The two scopes
 *
 * A divergence of the LANGUAGE is imposed by Kotlin: it is a fact about the
 * words a script is written with, and it applies to every script. A divergence
 * of the PRODUCT is a fact about what this product DOES: the workspace of a
 * stage, the moment the control flow is evaluated, what a gate compares
 * against. The scope is declared, because a reader who has to guess which
 * kind of difference they are reading cannot tell whether a workaround applies
 * to their script.
 *
 * ## Where a divergence lives
 *
 * The GLOBAL ones live here, declared once, because they are the same for
 * every script. The ones that belong to a single entry live in the entry
 * itself: in the descriptor of a step, and in the definition of a directive.
 * A catalogue that carried a second copy of them would be a second authority
 * for a fact the entry already owns.
 */
data class JenkinsDivergence(
    /** What the divergence is about, in the words a reader would use to look for it. */
    val name: String,
    /** Why the two products differ, and what it means for the script. */
    val reason: String,
    /** Whether the divergence is imposed by the language or by the product. */
    val scope: Scope,
) {
    /** The kinds of divergence there are. */
    enum class Scope {
        /** Imposed by the language the script is written in. */
        LANGUAGE,

        /** A fact about what this product does. */
        PRODUCT,
    }
}

/**
 * The divergences that are the same for every script.
 *
 * They are declared HERE, in the code, and not written in a document: the
 * catalogue publishes them, and a document that carried its own copy would be
 * a second authority that could say something the code does not.
 */
object JenkinsDivergences {

    /**
     * The global divergences, in the order a reader meets them.
     *
     * The language ones come first, because they are the ones the reader meets
     * while writing the script, and the product ones after, because they are
     * the ones that explain what the script does.
     */
    fun global(): List<JenkinsDivergence> = listOf(
        JenkinsDivergence(
            name = "when is a reserved word",
            reason = "Kotlin reserves `when` as a keyword of the language, so the condition of a gate " +
                "cannot be written `when(...)`: the DSL writes `whenEnvIs`, `whenEnvPresent` and " +
                "`whenGate`, and the directive the gate declares is `core.when`.",
            scope = JenkinsDivergence.Scope.LANGUAGE,
        ),
        JenkinsDivergence(
            name = "the command of a shell step",
            reason = "Groovy writes the command of a shell step between single quotes (`sh 'ls'`), and " +
                "Kotlin writes it between double quotes. A command of several lines is a triple-quoted " +
                "string in both.",
            scope = JenkinsDivergence.Scope.LANGUAGE,
        ),
        JenkinsDivergence(
            name = "the named arguments of a step",
            reason = "A named argument is `name: value` in Groovy and `name = value` in Kotlin: the same " +
                "step takes `sh(script: 'ls')` in Jenkins and `sh(script = \"ls\")` in a `.pipeline.kts`.",
            scope = JenkinsDivergence.Scope.LANGUAGE,
        ),
        JenkinsDivergence(
            name = "the dollar of a string",
            reason = "Groovy has a single-quoted string that does not interpolate, and Kotlin has a " +
                "triple-quoted one. A `\$` that has to reach the shell is written `\\$` inside a " +
                "double-quoted string, and the string of several lines of the DSL does not interpolate at all.",
            scope = JenkinsDivergence.Scope.LANGUAGE,
        ),
        JenkinsDivergence(
            name = "the workspace of a stage",
            reason = "Every stage runs in its OWN workspace, and a stage does not see the files another " +
                "stage wrote. A Jenkins run shares the workspace of its node across the stages, and that " +
                "is why `stash` and `unstash` exist there.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the control flow of the body of a script",
            reason = "The Kotlin of a `script { }` block is evaluated while the PLAN is built: its `if` " +
                "decides which arm is declared, before the first step runs. The Groovy of a Jenkinsfile " +
                "runs during the run, so its `if` decides while the run goes on.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the gate compares against what is declared",
            reason = "A `when*` gate compares the value the stage DECLARED, and not the value the agent " +
                "has at runtime: a variable that only the agent has is not visible to the gate.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the predicate of the branch",
            reason = "There is no `branch('main')`: the condition on the branch is written with the " +
                "variable of the branch, because a gate only sees what is declared.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
    )
}
