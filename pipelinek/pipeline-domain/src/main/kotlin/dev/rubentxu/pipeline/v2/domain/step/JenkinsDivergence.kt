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
                "triple-quoted one that interpolates. A string of several lines is a RAW string, and a " +
                "raw string reads a `\$NAME` as a template of Kotlin, the same way a string between " +
                "double quotes does. A dollar that has to reach the shell is written " +
                "`$ESCAPED_DOLLAR` inside a string between double quotes, and `$LITERAL_DOLLAR` " +
                "inside a string of several lines, where no escape is processed.",
            scope = JenkinsDivergence.Scope.LANGUAGE,
        ),
        JenkinsDivergence(
            name = "the workspace of a stage",
            reason = "The stages of a run SHARE one workspace by default: the directory the run was " +
                "invoked in, or the one the operator named with `--workspace`. A stage sees the files " +
                "another stage wrote, the way a Jenkins run shares the workspace of its node. The run " +
                "that gives every stage a workspace of its own is the one that ASKS for it: " +
                "`pipelinek run --isolated` allocates one per stage, and there a stage does not see the " +
                "files another stage wrote. `stash` and `unstash` exist in both products: they are how " +
                "a file is put aside and restored later.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the control flow of the body of a script",
            reason = "The Kotlin of a stage is evaluated while the PLAN is built: the `if` of its body, " +
                "of a `script { }` block and of a `steps { }` block decides which arm is declared, " +
                "before the first step runs. The Groovy of a Jenkinsfile runs during the run, so its " +
                "`if` decides while the run goes on.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the gate compares against what is declared",
            reason = "A `when*` gate compares the value the stage DECLARED, and not the value the agent " +
                "has at runtime: a variable that only the agent has is not visible to the gate. A " +
                "variable that NOBODY declares leaves the gate in a decided NEGATIVE: the stage does " +
                "not run, and the gate does not fail the run.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the predicate of the branch",
            reason = "There is no `branch('main')`: the product has no branch of its own, and the " +
                "condition on the branch is written with a variable that the SCRIPT declares. The gate " +
                "compares that declaration, and not the branch the remote had.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the environment a script reads",
            reason = "Jenkins evaluates `env.X` when the step RUNS, on the agent that runs it. " +
                "Here the read happens while the PLAN is BUILT, on the host that compiles the " +
                "script: the value travels inside the plan, so a replay reproduces it, and a " +
                "variable that only the agent has at runtime is not visible to the script. The " +
                "variable the stage DECLARES is visible; the one the agent happens to have is not. " +
                "A read sees the variables DECLARED BEFORE it: a declaration of the PIPELINE " +
                "written after the `stages { }` block is not seen by a read inside a stage.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
        JenkinsDivergence(
            name = "the declaration of a variable",
            reason = "Jenkins declares a variable with an ASSIGNMENT inside the `environment { }` " +
                "block (`FOO = 'x'`), and this product declares it with a CALL — " +
                "`env(\"FOO\", \"x\")` — inside that same block. The assignment of Jenkins does not " +
                "compile here: the compiler answers `Unresolved reference 'FOO'.`, because the " +
                "`environment { }` block is Kotlin and an assignment does not DECLARE the name it " +
                "assigns. A `val` written in the block does not declare it either: it declares a " +
                "local of the block and nothing else, and only the `env(name, value)` call declares " +
                "a variable the stage, its gate and its steps can see. The variable declared this " +
                "way reaches the environment the steps of the stage that declares it RUN WITH, and " +
                "the one the pipeline declares reaches the steps of every stage that declares no " +
                "variable of the same name: the declaration of a stage wins, name by name.",
            scope = JenkinsDivergence.Scope.PRODUCT,
        ),
    )

    /**
     * The characters a dollar has when it is written ESCAPED in a string
     * between double quotes.
     *
     * They are built out of pieces because the text that spells them IS the
     * trap: a dollar that a backtick follows opens a template of Kotlin, and
     * the published text has to SHOW the escape.
     */
    private const val ESCAPED_DOLLAR: String = "\\" + "\$"

    /**
     * The characters a dollar has when it is written as a LITERAL inside a
     * string of several lines.
     *
     * A raw string processes no escape, so the dollar cannot be escaped there:
     * it is written as a dollar literal, in braces, next to a dollar literal in
     * quotes.
     */
    private const val LITERAL_DOLLAR: String = "\$" + "{'" + "\$" + "'}"
}
