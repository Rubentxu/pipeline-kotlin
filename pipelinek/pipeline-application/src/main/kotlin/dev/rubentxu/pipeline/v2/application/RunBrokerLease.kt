package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The broker of ONE run, with the directory where the run finds it.
 *
 * ## What the directory holds
 *
 * Two files and nothing else: the socket of the broker and the token of the
 * session. The directory is created private and empty by this very call, so the
 * socket is never born where others can walk and the token never exists under
 * another mode. The run learns the directory from its arguments, reads the token
 * once and deletes the file; it holds no secret of any credential.
 *
 * ## Whose lifetime it is
 *
 * The lease is opened BEFORE the child is launched and closed when the run is
 * over, whatever way it ended. Closing stops the broker, removes the socket and
 * removes the directory; a lease that was closed answers nobody, so a token that
 * a process kept after the run ended opens nothing. [close] is idempotent
 * because a run can end through more than one path.
 *
 * ## Why the name of the directory is random and short
 *
 * A Unix socket path cannot be longer than the runtime binds, and a name made of
 * the identity of the run would make the limit depend on it. The name says
 * nothing about the run; the service knows which lease belongs to which run.
 *
 * @property directory where the run finds the socket and the token.
 */
internal class RunBrokerLease private constructor(
    val directory: Path,
    private val broker: RunCredentialBroker,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /** Stops the broker and removes everything the lease created. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            broker.close()
        } finally {
            deleteTree(directory)
        }
    }

    internal companion object {

        /** The name of the socket inside the directory of a lease. */
        const val SOCKET_FILE_NAME: String = "broker.sock"

        /** The name of the file with the token of the session. */
        const val TOKEN_FILE_NAME: String = "session"

        /**
         * The longest socket path the runtime binds, in UTF-8 bytes.
         *
         * The kernel keeps 107 bytes of `sun_path` below its terminator, but the
         * JDK refuses a path of 107 bytes already, so the budget is the stricter
         * of the two: a path above it is a lease that could never be started.
         */
        const val MAX_SOCKET_PATH_BYTES: Int = 106

        private const val DIRECTORY_NAME_CHARS: Int = 12

        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        /**
         * Whether a lease below [root] gets a socket path the runtime binds.
         *
         * The service asks it when it starts, so that a root that is too deep is
         * a refusal to start and not a refusal of every run.
         */
        fun fitsBelow(root: Path): Boolean =
            socketPathBytes(root.toAbsolutePath().resolve("x".repeat(DIRECTORY_NAME_CHARS))) <= MAX_SOCKET_PATH_BYTES

        /**
         * Opens the broker of one run below [root].
         *
         * The order is the contract: the length of the path is judged before
         * anything exists, then the directory is created private, then the token
         * is written with the mode it keeps, and only then does the socket
         * appear and the broker start answering. A failure at any step removes
         * what the earlier steps created.
         *
         * @param grants what the run may ask for; an empty set is a run that is
         *   refused everything it asks.
         * @param provider where the credentials come from, or null when there is
         *   no store behind the service.
         * @throws RunBrokerLeaseException when the lease could not be opened.
         */
        fun open(root: Path, grants: Set<CredentialGrant>, provider: CredentialProvider?): RunBrokerLease {
            val absoluteRoot = root.toAbsolutePath()
            val name = UUID.randomUUID().toString().replace("-", "").take(DIRECTORY_NAME_CHARS)
            val directory = absoluteRoot.resolve(name)
            if (socketPathBytes(directory) > MAX_SOCKET_PATH_BYTES) {
                throw RunBrokerLeaseException("the socket path of a run broker is longer than a socket can be bound to")
            }
            try {
                Files.createDirectories(absoluteRoot, PRIVATE_DIRECTORY)
                Files.createDirectory(directory, PRIVATE_DIRECTORY)
            } catch (_: IOException) {
                throw RunBrokerLeaseException("the directory of a run broker could not be created")
            }
            val token = CredentialBrokerWire.newSessionToken()
            var transport: UnixSocketTransport? = null
            try {
                writeToken(directory.resolve(TOKEN_FILE_NAME), token)
                transport = UnixSocketTransport.bind(directory.resolve(SOCKET_FILE_NAME))
                val broker = RunCredentialBroker(transport, token, grants, provider).start()
                return RunBrokerLease(directory, broker)
            } catch (_: Exception) {
                try {
                    transport?.close()
                } finally {
                    deleteTree(directory)
                }
                throw RunBrokerLeaseException("the broker of a run could not be started")
            }
        }

        /**
         * Removes what earlier processes of the service left below [root].
         *
         * It is called once, after the service owns its own socket: from then on
         * no other service of this machine can be using the directory, and the
         * runs that a previous process supervised have lost their broker with it.
         */
        fun sweep(root: Path) {
            val absolute = root.toAbsolutePath()
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) return
            try {
                Files.newDirectoryStream(absolute).use { entries ->
                    entries.forEach { deleteTree(it) }
                }
            } catch (_: IOException) {
                // A leftover that cannot be listed is a leftover that stays; the next start tries again.
            }
        }

        private fun socketPathBytes(directory: Path): Int =
            directory.resolve(SOCKET_FILE_NAME).toString().toByteArray(Charsets.UTF_8).size

        private fun writeToken(file: Path, token: String) {
            val bytes = token.toByteArray(Charsets.UTF_8)
            try {
                Files.newByteChannel(
                    file,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PRIVATE_FILE,
                ).use { channel -> channel.write(ByteBuffer.wrap(bytes)) }
            } finally {
                bytes.fill(0)
            }
        }

        /** Deletes [entry] and, when it is a directory, what it holds. A link is deleted and not followed. */
        private fun deleteTree(entry: Path) {
            try {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.newDirectoryStream(entry).use { children ->
                        children.forEach { deleteTree(it) }
                    }
                }
                Files.deleteIfExists(entry)
            } catch (_: IOException) {
                // What cannot be deleted stays for the next sweep; failing here would hide the failure that came first.
            }
        }
    }
}

/**
 * The broker of a run could not be opened.
 *
 * The message is fixed: it reaches the answer of the service, and nothing in it
 * was read from the machine.
 */
internal class RunBrokerLeaseException(message: String) : RuntimeException(message)

/**
 * Where the runs of one service open their brokers.
 *
 * The service holds one factory when it was started with credential grants. The
 * [provider] is the service's own access to the local store, shared by every
 * broker and owned by whoever composed the service; a broker never closes it.
 *
 * ## Whose grants a broker gets
 *
 * The ones of the client that started the run, looked up by the digest of that
 * client's token and frozen when the lease opens. A client the service does not
 * know, or none, gets an empty set: its run has a broker that refuses
 * everything, and not a run with the broker of somebody else.
 *
 * @param root the directory below which every lease gets its own directory.
 * @param provider the local provider the brokers read from, or null when there is no store.
 * @param grantsOf the grants of the authorization whose token has the given digest.
 */
internal class RunBrokerFactory(
    private val root: Path,
    private val provider: CredentialProvider?,
    private val grantsOf: (String?) -> Set<CredentialGrant>,
) {

    /** The broker of one run started by the client whose token has [clientDigest]. */
    fun open(clientDigest: String?): RunBrokerLease =
        RunBrokerLease.open(root, grantsOf(clientDigest), provider)

    /** Removes the leftovers of earlier processes of the service. */
    fun sweep() = RunBrokerLease.sweep(root)

    /** Whether the socket path of a lease fits what the runtime binds. */
    fun fits(): Boolean = RunBrokerLease.fitsBelow(root)
}
