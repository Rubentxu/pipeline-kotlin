package dev.rubentxu.pipeline.v2.domain.directive

/**
 * A directive that says how it relates to Jenkins.
 *
 * ## Why it is an OPTIONAL interface and not a field of the definition
 *
 * The definition of a directive is an open SPI: a plugin contributes one, and
 * the plugin that compiled against a previous artifact keeps working. A field
 * ADDED to the definition would be a method that a compiled plugin does not
 * have, and the catalogue would ask a plugin for a fact the plugin cannot
 * answer.
 *
 * An interface a definition MAY implement asks nothing of the plugins that do
 * not implement it: the catalogue reads what the definition declares, and the
 * definitions that declare nothing say so by not implementing this.
 *
 * The two facts are the same two the catalogue publishes for a step, and they
 * mean the same there:
 *
 * - [jenkinsSurface] is the equivalent in Jenkins, and it is empty when there
 *   is none;
 * - [jenkinsDivergence] is the difference a reader meets, with its motive, and
 *   it is empty when there is nothing to declare. A directive with no
 *   equivalent in Jenkins says WHY in here, so that an empty surface is a
 *   statement and not an oversight.
 */
interface JenkinsSurfaceDeclaration {

    /** The equivalent in Jenkins, empty when the directive has none. */
    val jenkinsSurface: String

    /** The divergence with the equivalent in Jenkins, with its motive. */
    val jenkinsDivergence: String
}
