package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The shape of the requests `serve` accepts.
 *
 * ## The guarantee under trial
 *
 * The service is composed over the substrate the operator NAMES, and every
 * part of that substrate is named with an option: the store, the control root
 * and the socket. A request that leaves one of them out is refused by naming
 * the one, because a service that invented a default for any of the three
 * would be serving something the operator did not choose — and a service
 * listening somewhere the operator did not name is a service nobody can find
 * and nobody can close.
 *
 * ## The capacity this serves
 *
 * The operator reads a refusal and knows what to type next. The alternative —
 * a service that starts with a store of its own, or on a socket of its own
 * choosing — gives the operator a process they cannot point a client at and
 * a substrate they cannot compare against the command line.
 *
 * ## Why this instrument
 *
 * The admission is a pure function of an array of arguments: no socket, no
 * store, no clock, no process. Every row below is REFUSED before the verb
 * touches the filesystem, so the cheapest instrument that can fail is to
 * call the verb and read what it printed.
 *
 * The handler is called with the arguments that follow the verb's own words,
 * which is what the inventory's dispatcher does: a row that passed the
 * verb's word as if it were an argument would be testing the wrong request.
 *
 * ## What this does not freeze
 *
 * The words the operator reads and the exit code, which the taxonomy owns.
 */
class MainServeCliTest {

    @Test
    fun `a request without the store is refused by naming it`() {
        val answer = ask()

        assertEquals(
            2,
            answer.exit.code,
            "a request without the store is refused as an invalid argument",
        )
        assertTrue(
            answer.stderr.contains("missing required argument: --db <path>"),
            "the refusal names the option that is missing: ${answer.stderr}",
        )
    }

    @Test
    fun `a request with the store and without the control root is refused by naming it`() {
        val answer = ask("--db", "/tmp/store.db")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("missing required argument: --control-root <path>"),
            "the refusal names the option that is missing: ${answer.stderr}",
        )
    }

    @Test
    fun `a request without the socket is refused by naming it`() {
        val answer = ask("--db", "/tmp/store.db", "--control-root", "/tmp/control")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("missing required argument: --socket <path>"),
            "the refusal names the option that is missing: ${answer.stderr}",
        )
    }

    @Test
    fun `an option the verb does not own is refused by name`() {
        val answer = ask(
            "--db",
            "/tmp/store.db",
            "--control-root",
            "/tmp/control",
            "--socket",
            "/tmp/service.sock",
            "--json",
        )

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("unknown option: --json"),
            "the refusal names the option the verb does not own: ${answer.stderr}",
        )
    }

    @Test
    fun `a positional argument is refused`() {
        val answer = ask("extra")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("unexpected argument: extra"),
            "the refusal names the argument that does not belong: ${answer.stderr}",
        )
    }

    @Test
    fun `the refusal prints the usage of the verb`() {
        val answer = ask()

        assertTrue(
            answer.stderr.contains("pipelinek serve --db <path> --control-root <path> --socket <path>"),
            "the refusal says how the verb is called: ${answer.stderr}",
        )
    }

    @Test
    fun `a socket that is the store is refused by name`() {
        val answer = ask(
            "--db",
            "/tmp/store.db",
            "--control-root",
            "/tmp/control",
            "--socket",
            "/tmp/store.db",
        )

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("socket-path-is-reserved"),
            "the refusal names the reason the socket cannot be the store: ${answer.stderr}",
        )
    }

    @Test
    fun `a socket that is the file of the authorizations is refused by name`() {
        val answer = ask(
            "--db",
            "/tmp/store.db",
            "--control-root",
            "/tmp/control",
            "--socket",
            "/tmp/service.sock",
            "--authorization-file",
            "/tmp/service.sock",
        )

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("socket-path-is-reserved"),
            "the secret and the door cannot be one file: ${answer.stderr}",
        )
    }

    @Test
    fun `a trust profile this build does not serve is refused by name`() {
        val answer = ask(
            "--db",
            "/tmp/store.db",
            "--control-root",
            "/tmp/control",
            "--socket",
            "/tmp/service.sock",
            "--trust-profile",
            "open-to-the-world",
        )

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("trust-profile-unknown"),
            "a boundary nobody chose is not a boundary: ${answer.stderr}",
        )
    }

    @Test
    fun `the isolated profile and a named file are refused by name`() {
        val answer = ask(
            "--db",
            "/tmp/store.db",
            "--control-root",
            "/tmp/control",
            "--socket",
            "/tmp/service.sock",
            "--trust-profile",
            "isolated",
            "--authorization-file",
            "/tmp/authorizations",
        )

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("authorization-file-refused-by-the-profile"),
            "the profile that keeps the authorization off the disk cannot read it from a file: " +
                answer.stderr,
        )
    }

    @Test
    fun `a deadline that is not a positive number is refused by name`(@TempDir root: Path) {
        val answer = ask(root, "--run-deadline-ms", "0")

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("run-policy-refused"),
            "a deadline that is not a number of milliseconds is refused by name: ${answer.stderr}",
        )
    }

    @Test
    fun `a budget of supervised runs that is not a positive number is refused by name`(@TempDir root: Path) {
        val answer = ask(root, "--max-supervised-runs", "-1")

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("run-policy-refused"),
            "a budget that is not a number of runs is refused by name: ${answer.stderr}",
        )
    }

    @Test
    fun `a start deadline that is not a positive number is refused by name`(@TempDir root: Path) {
        val answer = ask(root, "--start-deadline-ms", "0")

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("run-policy-refused"),
            "a start deadline that is not a number of milliseconds is refused by name: " +
                answer.stderr,
        )
    }

    @Test
    fun `a record of the starts that cannot be read is refused by name`(@TempDir root: Path) {
        Files.writeString(root.resolve(ServiceStartRegistry.FILE_NAME), "not a document")

        val answer = ask(root)

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("starts-record-refused"),
            "a service that cannot read its record of the starts does not start: ${answer.stderr}",
        )
    }

    @Test
    @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `a store that is not there is refused by name in the lane that writes`(@TempDir root: Path) {
        // A scope that may CANCEL puts the service in the lane that writes:
        // that lane creates the file it does not find, and a service that
        // creates the substrate it is about to read answers about runs in a
        // store that holds none.
        //
        // The row carries a timeout because the verb SERVES when it does not
        // refuse, and serving blocks: a row that hangs is a row that failed,
        // and the timeout says so instead of leaving the build waiting.
        val answer = askOver(root, "all+cancel")

        assertEquals(4, answer.exit.code)
        assertTrue(
            answer.stderr.contains("the substrate cannot be served"),
            "a service reads the substrate it is given and does not create one: ${answer.stderr}",
        )
        assertFalse(
            Files.exists(root.resolve("store.db")),
            "the refusal must not leave an empty store where the operator kept their facts",
        )
    }

    /**
     * The decision of the verb, and what it printed.
     */
    private data class Answer(val exit: CliExit, val stdout: String, val stderr: String)

    /**
     * Asks the verb with [words] — the arguments that follow the verb's own
     * words — and reads its answer.
     *
     * The [scope] is the one the authorization of the operator carries, and
     * it decides the lane the service is composed in: a scope that may
     * cancel opens the store to write.
     */
    private fun ask(root: Path, vararg words: String): Answer = askOver(root, "all", *words)

    /**
     * Asks the verb over a substrate in [root], with the operator's
     * authorization carrying [scope], and reads its answer.
     */
    private fun askOver(root: Path, scope: String, vararg words: String): Answer {
        val authorizations = root.resolve("authorizations")
        Files.writeString(authorizations, "token-of-the-operator operator $scope\n")
        Files.setPosixFilePermissions(
            authorizations,
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
        )
        val named = listOf(
            "--db",
            root.resolve("store.db").toString(),
            "--control-root",
            root.resolve("control").toString(),
            "--socket",
            root.resolve("service.sock").toString(),
            "--authorization-file",
            root.resolve("authorizations").toString(),
        ) + words
        return ask(*named.toTypedArray())
    }

    private fun ask(vararg words: String): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val exit = runServeVerb(words.toList().toTypedArray(), out, err)
        out.flush()
        err.flush()

        return Answer(exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
