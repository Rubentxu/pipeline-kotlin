package dev.rubentxu.pipeline.v2.domain.step

/**
 * The shape of Jenkins that a script can be written in, and the shape of this
 * product that replaces it.
 *
 * The vocabulary is CLOSED: the compiler points at a token, and that token
 * either is one of these shapes or is none of them. A shape this enum does not
 * name produces no suggestion, and a diagnostic about anything else keeps the
 * words the compiler gave it.
 *
 * ## What a shape does NOT say
 *
 * A shape says WHAT the token is, and not why the compiler stops there: that
 * reason belongs to the form this shape names, in [JenkinsForms], which is the
 * table the catalogue publishes. The reason the two PRODUCTS differ is a third
 * fact, and it belongs to the divergence that realises the shape. Three texts
 * about three facts, and each of them written once.
 */
enum class JenkinsFormShape {

    /**
     * The string of single quotes, which Kotlin reads as a character.
     */
    SINGLE_QUOTED_LITERAL,

    /**
     * The named argument written with a colon.
     */
    NAMED_ARGUMENT_COLON,

    /**
     * The property of the `env` object.
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
 * ## What the reason is, and what it is not
 *
 * The reason of a form is WHY THE COMPILER STOPS at that shape, and not why
 * the two products differ: that difference is a fact of its own, it belongs to
 * the divergence that realises the shape, and it is published with the
 * divergences. The three forms of Jenkins are three of the ten differences,
 * written the way the compiler meets them, and a catalogue that printed the
 * same sentence in both sections would publish one fact twice.
 *
 * @property shape the shape this form is, and the key the recognition uses.
 * @property jenkinsForm how Jenkins writes it.
 * @property pipelineKForm how this product writes it.
 * @property reason why the compiler stops at this shape, said so that a reader
 *   who meets the shape in a script knows what the compiler saw.
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
     *
     * The reason of a form says why the compiler stops at that shape, and it is
     * not the reason the two products differ: the divergence that realises the
     * shape says that, and the two texts cannot be the same sentence.
     */
    fun global(): List<JenkinsForm> = listOf(
        JenkinsForm(
            shape = JenkinsFormShape.SINGLE_QUOTED_LITERAL,
            jenkinsForm = "'make'",
            pipelineKForm = "\"make\"",
            reason = "Kotlin reads a literal of single quotes as a character: a literal of one " +
                "character is a `Char`, and a literal of more than one is not a literal at all, " +
                "so the compiler refuses it.",
            suggestion = "In PipelineK: a string is written between double quotes: {value}.",
        ),
        JenkinsForm(
            shape = JenkinsFormShape.NAMED_ARGUMENT_COLON,
            jenkinsForm = "time: 30",
            pipelineKForm = "time = 30",
            reason = "Kotlin does not admit a named argument written with a colon: the compiler " +
                "stops at the colon, and the suggestion names the character to write instead.",
            suggestion = "In PipelineK: the named argument \"{value}\" is written with \"=\", not with \":\".",
        ),
        JenkinsForm(
            shape = JenkinsFormShape.ENV_PROPERTY,
            jenkinsForm = "env.BRANCH_NAME",
            pipelineKForm = "env(\"BRANCH_NAME\")",
            reason = "Kotlin has no dynamic properties, so there is no `env` object to read a " +
                "property of: the compiler cannot resolve `env.BRANCH_NAME`, and the suggestion " +
                "names the call that reads the variable.",
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
