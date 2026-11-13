package dev.rubentxu.pipeline.v2.domain.step

/**
 * The shape of Jenkins that a script can be written in, and the shape of this
 * product that replaces it.
 *
 * The vocabulary is CLOSED: the compiler points at a token, and that token
 * either is one of these shapes or is none of them. A shape this enum does not
 * name produces no suggestion, and a diagnostic about anything else keeps the
 * words the compiler gave it.
 */
enum class JenkinsFormShape {

    /**
     * The string of single quotes, which Kotlin reads as a character.
     *
     * A literal of one character is a `Char` in Kotlin, and a literal of more
     * than one is not a literal at all: the compiler refuses it. The author who
     * wrote a Groovy string wrote a string, and the string of this product is
     * written between double quotes.
     */
    SINGLE_QUOTED_LITERAL,

    /**
     * The named argument written with a colon, which Kotlin does not admit.
     *
     * Kotlin writes the name of an argument with `=`. The colon of Groovy is
     * not a character of the language, so the compiler stops there, and the
     * author is told what to write instead.
     */
    NAMED_ARGUMENT_COLON,

    /**
     * The property of the `env` object, which Kotlin cannot express.
     *
     * Kotlin has no dynamic properties, so there is no object to read a
     * property of. This product reads the variable of the environment with a
     * call, and the call is the name the author already knows.
     */
    ENV_PROPERTY,
}

/**
 * One form of Jenkins, the form of this product that replaces it, and what the
 * replacement says.
 *
 * ## The text lives HERE and nowhere else
 *
 * The compiler prints the suggestion and the catalogue publishes the form, and
 * both read this declaration: a suggestion written twice would be two texts
 * that could say different things, and the reader of the catalogue would not
 * know which one the compiler printed.
 *
 * @property shape the shape this form is, and the key the recognition uses.
 * @property jenkinsForm how Jenkins writes it.
 * @property pipelineKForm how this product writes it.
 * @property reason why the two differ, said so that the reader can decide
 *   whether the difference is a rule of the language or a decision of the
 *   product.
 * @property suggestion what the compiler appends to the diagnostic, with
 *   `{value}` where the concrete value of the author's script goes.
 */
data class JenkinsForm(
    val shape: JenkinsFormShape,
    val jenkinsForm: String,
    val pipelineKForm: String,
    val reason: String,
    val suggestion: String,
)

/**
 * The forms of Jenkins that a diagnostic can name.
 *
 * They are DECLARED in the code and not written in a document, for the same
 * reason the global divergences are: the catalogue publishes what the product
 * does, and a document with its own copy could say something the code does not.
 */
object JenkinsForms {

    /**
     * The placeholder of a template, where the concrete value of the author's
     * script goes.
     *
     * It is declared here and not in the reader of the template: the template
     * and the placeholder are one contract, and a reader that spelled the
     * placeholder on its own could stop understanding it without saying so.
     */
    const val VALUE_PLACEHOLDER: String = "{value}"

    /**
     * The forms, in the order a reader meets them.
     *
     * The order is the order of the compiler's own errors: the string of single
     * quotes is what a translated Jenkinsfile hits first, the named argument
     * comes with it, and the property of `env` comes when the script reads the
     * environment.
     */
    fun global(): List<JenkinsForm> = listOf(
        JenkinsForm(
            shape = JenkinsFormShape.SINGLE_QUOTED_LITERAL,
            jenkinsForm = "'make'",
            pipelineKForm = "\"make\"",
            reason = "Groovy writes a string between single quotes, and Kotlin reads a " +
                "single-quoted literal as a character: the string of this product is written " +
                "between double quotes. A `$` that has to reach the shell is escaped there, " +
                "because a double-quoted string of Kotlin interpolates.",
            suggestion = "In PipelineK: a string is written between double quotes: {value}.",
        ),
        JenkinsForm(
            shape = JenkinsFormShape.NAMED_ARGUMENT_COLON,
            jenkinsForm = "time: 30",
            pipelineKForm = "time = 30",
            reason = "A named argument is `name: value` in Groovy and `name = value` in Kotlin: " +
                "the same step takes `sh(script: 'ls')` in Jenkins and `sh(script = \"ls\")` in " +
                "a `.pipeline.kts`.",
            suggestion = "In PipelineK: the named argument \"{value}\" is written with \"=\", not with \":\".",
        ),
        JenkinsForm(
            shape = JenkinsFormShape.ENV_PROPERTY,
            jenkinsForm = "env.BRANCH_NAME",
            pipelineKForm = "env(\"BRANCH_NAME\")",
            reason = "Kotlin cannot express a dynamic property, so there is no `env` object to " +
                "read a property of: the variable of the environment is read with a call. The " +
                "read happens while the plan is built, on the host that compiles the script, so " +
                "a variable that only the agent has is not visible to the script.",
            suggestion = "In PipelineK: the variable of the environment is read with env(\"{value}\").",
        ),
    )

    /**
     * The form of the shape, or null when the table carries none.
     *
     * It is null and not an exception: a shape that no form describes has no
     * suggestion to give, and a diagnostic that cannot be improved is still a
     * diagnostic the author has to read.
     */
    fun of(shape: JenkinsFormShape): JenkinsForm? = global().firstOrNull { form -> form.shape == shape }
}
