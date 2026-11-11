package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The OCI profile of the local service, and the promises it makes.
 *
 * ## The guarantee under trial
 *
 * The image is a PACKAGING of the published distribution, and what it
 * promises is about the identity it runs as, the state it keeps in volumes
 * and the boundary a client of the same environment crosses to reach it.
 * Those promises are read from the definition of the image and from the
 * recipe the build prints, because a promise that lives only in a document
 * is a claim and not a guarantee.
 *
 * ## Why the facts are DERIVED and not written down here
 *
 * The uid, the volumes and the paths are taken from the definition of the
 * image, and the README is required to state the identity the image
 * declares. Writing `1000` here would freeze the truth in this test, and
 * the test would keep passing the day the image changed its identity —a
 * test that cannot fail is not an instrument.
 *
 * ## Why this instrument reads the tree and does not BUILD the image
 *
 * Building an image needs a container runtime, a registry and minutes of
 * network, and a lane that needs all three measures the machine and not the
 * profile. What can fail here is the SHAPE of the profile: the identity it
 * runs as, the state it declares as volumes, the entrypoint it starts, the
 * base it is pinned to and the privileges the recipe asks for. The behaviour
 * of the image is measured where the image runs.
 */
class OciProfileTest {

    @Test
    fun `the image runs as a user that is not root`() {
        val user = instruction("USER")
        val uid = user.substringBefore(':').trim()

        assertTrue(uid.isNotEmpty(), "the image declares no user: $user")
        assertTrue(uid.toIntOrNull() != null, "the user of the image is not numeric: $user")
        assertTrue(uid.toInt() != 0, "the image runs as root: $user")
    }

    @Test
    fun `the state the service is told to use is the state the image declares as volumes`() {
        val serve = arguments("CMD")
        val volumes = arguments("VOLUME").toSet()

        val socket = valueOf(serve, "--socket")
        val controlRoot = valueOf(serve, "--control-root")
        val substrate = valueOf(serve, "--db")

        assertTrue(
            socket.substringBeforeLast('/') in volumes,
            "the directory of the socket is not a volume: $socket (the volumes are $volumes)",
        )
        assertTrue(
            controlRoot in volumes,
            "the control root is not a volume: $controlRoot (the volumes are $volumes)",
        )
        assertTrue(
            substrate.substringBeforeLast('/') in volumes,
            "the directory of the substrate is not a volume: $substrate (the volumes are $volumes)",
        )
    }

    @Test
    fun `the image starts the entrypoint of the profile`() {
        val copied = instruction("COPY").substringAfterLast(' ').trimEnd('/')
        val entrypoint = arguments("ENTRYPOINT")

        assertEquals(1, entrypoint.size, "the image declares more than one entrypoint: $entrypoint")
        assertEquals(
            "$copied/entrypoint.sh",
            entrypoint.single(),
            "the image does not start the entrypoint of the profile",
        )
    }

    @Test
    fun `the entrypoint prepares the substrate before it hands over to the launcher`() {
        val script = Files.readString(entrypointScript())

        val preparation = script.indexOf(PREPARATION)
        val handover = script.lastIndexOf(HANDOVER)

        assertTrue(
            preparation >= 0,
            "the entrypoint never prepares a substrate, so a container against empty volumes " +
                "starts a service that refuses to serve:\n$script",
        )
        assertTrue(
            handover >= 0,
            "the entrypoint never hands over to the launcher of the distribution:\n$script",
        )
        assertTrue(
            preparation < handover,
            "the entrypoint hands over to the service before it prepares the substrate:\n$script",
        )
    }

    @Test
    fun `the base of the image is pinned by a digest`() {
        val base = instruction("FROM")

        assertTrue(
            base.contains("@sha256:"),
            "the base of the image is named by a tag and not by a digest, so the bytes it " +
                "holds are not the bytes this profile declared: $base",
        )
    }

    @Test
    fun `the recipe asks for no docker socket, no privileged container and no host network`() {
        val recipe = Files.readString(repositoryRoot().resolve("scripts/build-oci-image.sh"))

        assertFalse(recipe.contains("docker.sock"), "the recipe names a Docker socket")
        assertFalse(recipe.contains("--privileged"), "the recipe asks for a privileged container")
        assertFalse(recipe.contains("--network=host"), "the recipe asks for the host network")
    }

    @Test
    fun `the README and the recipe of the script say the same steps in the same order`() {
        val readme = readmeSection("### The OCI profile")
        val recipe = Files.readString(repositoryRoot().resolve("scripts/build-oci-image.sh"))

        assertSameSteps(readme, "the README")
        assertSameSteps(recipe, "the recipe of the script")

        assertTrue(
            readme.contains(PREPARES),
            "the README does not say that the container prepares the substrate before it serves it",
        )
        assertTrue(
            recipe.contains(PREPARES),
            "the recipe of the script does not say that the container prepares the substrate " +
                "before it serves it",
        )
    }

    @Test
    fun `the README states the identity the image runs as`() {
        val user = instruction("USER")
        val section = readmeSection("### The OCI profile")

        assertTrue(
            section.contains(user),
            "the README does not state the identity of the image ($user), so the operator " +
                "cannot tell which uid owns the socket",
        )
    }

    /**
     * The instructions of the image definition, with the comments left out.
     *
     * A comment is not an instruction: the file explains itself in prose and
     * the prose is read by a person, while the rows above read what the
     * build does.
     */
    private fun instructions(): List<Pair<String, String>> =
        Files.readAllLines(containerfile())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val name = line.substringBefore(' ').uppercase()
                name to line.substringAfter(' ', "")
            }

    /** The instruction named [name], with the rest of its line. */
    private fun instruction(name: String): String {
        val found = instructions().firstOrNull { it.first == name }
        return requireNotNull(found) { "the image declares no $name instruction" }.second.trim()
    }

    /**
     * The arguments of an instruction, read as the list they are.
     *
     * The definitions of the image spell their arguments as a JSON array, and
     * they are read as the list the runtime reads, not as the text it was
     * written in.
     */
    private fun arguments(name: String): List<String> {
        val body = instruction(name)
            .substringAfter('[', "")
            .substringBeforeLast(']')
        return body.split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
    }

    /** The value that follows an option, in the arguments of a command. */
    private fun valueOf(arguments: List<String>, option: String): String {
        val index = arguments.indexOf(option)
        require(index >= 0 && index + 1 < arguments.size) {
            "the command does not name $option: $arguments"
        }
        return arguments[index + 1]
    }

    /** The definition of the image, in the module that carries the distribution. */
    private fun containerfile(): Path =
        repositoryRoot()
            .resolve("pipelinek")
            .resolve("pipeline-application")
            .resolve("oci")
            .resolve("Containerfile")

    /**
     * The entrypoint of the profile, in the module that carries the distribution.
     *
     * It is read from the tree and not written here, because a copy of the
     * script in this test would be a copy that says whatever this test says:
     * the promise under trial is the one the profile SHIPS.
     */
    private fun entrypointScript(): Path =
        repositoryRoot()
            .resolve("pipelinek")
            .resolve("pipeline-application")
            .resolve("oci")
            .resolve("entrypoint.sh")

    /**
     * The section of the README that a heading opens.
     *
     * The section ends with the next heading of its own level, so a rule
     * that reads one section is not silently reading the rest of the
     * document.
     */
    private fun readmeSection(heading: String): String {
        val lines = Files.readAllLines(repositoryRoot().resolve("README.md"))
        val start = lines.indexOfFirst { it.trim() == heading }
        require(start >= 0) { "the README has no section '$heading'" }
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("### ") }
        return rest.take(if (end < 0) rest.size else end).joinToString("\n")
    }

    /**
     * The steps that have to be there before the service starts, in the order
     * the document names them.
     *
     * The three volumes come first and the container comes after them: a
     * reader who is told to run the container before the volumes exist is
     * told to start a service that has nowhere to keep what it writes.
     */
    private fun assertSameSteps(document: String, what: String) {
        val volumes = VOLUMES.map { document.indexOf("volume create $it") }
        val container = document.indexOf(CONTAINER)

        assertTrue(volumes.all { it >= 0 }, "$what does not name the three volumes")
        assertTrue(container >= 0, "$what does not name the container")
        assertTrue(
            volumes.zipWithNext().all { (first, second) -> first < second },
            "$what does not name the volumes in the same order",
        )
        assertTrue(
            volumes.last() < container,
            "$what names the container before the volumes that have to be there first",
        )
    }

    /**
     * The root of the repository, found from the working directory.
     *
     * The lane runs in the module, and the profile is a file of the tree
     * above it: the marker is what tells one root from another.
     */
    private fun repositoryRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            val directory = candidate
            if (ROOT_MARKERS.all { Files.isRegularFile(directory.resolve(it)) }) {
                return directory
            }
            candidate = directory.parent
        }
        error("the repository root was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    private companion object {

        /** The files that only the root of the repository holds. */
        val ROOT_MARKERS = listOf("AGENTS.md", "README.md", "scripts/verify-all.sh")

        /** The words the entrypoint uses to prepare the substrate it serves. */
        const val PREPARATION: String = "substrate init"

        /** The words the entrypoint uses to hand over to the launcher. */
        const val HANDOVER: String = "exec \"\${LAUNCHER}\""

        /** The words both documents use to say that the container prepares the substrate. */
        const val PREPARES: String = "PREPARES the substrate before it serves it"

        /** The three volumes the profile declares, in the order they are named. */
        val VOLUMES = listOf("pipelinek-socket", "pipelinek-control", "pipelinek-state")

        /** The line that starts the container of the recipe. */
        const val CONTAINER: String = "podman run -d --name pipelinek-service"
    }
}
