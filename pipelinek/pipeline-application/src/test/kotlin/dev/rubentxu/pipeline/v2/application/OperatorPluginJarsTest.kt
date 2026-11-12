package dev.rubentxu.pipeline.v2.application

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The paths the operator's `--plugin-jar` names resolve to.
 *
 * ## The guarantee under trial
 *
 * That a name the operator wrote becomes the path of the file it names, and that the path says
 * where the file IS: two verbs read this option with the same meaning, and a refusal that named
 * the artifact one way in one of them and another way in the other would be two refusals for one
 * artifact.
 *
 * ## Why this instrument
 *
 * The rule is a pure function of a name and a working directory, so it can be checked without a
 * disk, a classloader or a process. The row that measures what the two verbs SAY is in the file
 * of the verb that writes the project, because that is where the two are compared.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OperatorPluginJarsTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a relative name is resolved against the working directory`() {
        val jars = operatorPluginJars(listOf("plugins/first.jar"), root)

        assertEquals(
            listOf(root.resolve("plugins").resolve("first.jar")),
            jars,
            "the name is resolved against the working directory it was written in",
        )
    }

    @Test
    fun `an absolute name is the path it already is`() {
        val jar = root.resolve("plugins").resolve("first.jar")

        val jars = operatorPluginJars(listOf(jar.toString()), root)

        assertEquals(
            listOf(jar),
            jars,
            "a name that is already a path is not resolved against anything",
        )
    }

    @Test
    fun `a name is not normalised lexically, because the file system resolves the dots`() {
        val jars = operatorPluginJars(listOf("plugins/../plugins/first.jar"), root)

        assertEquals(
            listOf(root.resolve("plugins").resolve("..").resolve("plugins").resolve("first.jar")),
            jars,
            "the path is named as the operator wrote it: `..` is resolved by the file system, " +
                "which follows the links a lexical normalisation walks straight past",
        )
    }

    @Test
    fun `the order the operator wrote the names in is the order they are resolved in`() {
        val jars = operatorPluginJars(listOf("plugins/second.jar", "plugins/first.jar"), root)

        assertEquals(
            listOf(
                root.resolve("plugins").resolve("second.jar"),
                root.resolve("plugins").resolve("first.jar"),
            ),
            jars,
            "the order is the operator's, because the admission offers the artifacts in it",
        )
    }
}
