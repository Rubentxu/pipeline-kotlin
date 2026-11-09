package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why the service could not start.
 *
 * ## What these words are for
 *
 * The operator asked for a service and did not get one. The reason is a fact
 * about the machine or about the path, and every one of them has a different
 * remedy: another path, another directory, another platform, or nothing at
 * all until a human looks. A single "could not start" would send the operator
 * looking in the wrong place.
 */
internal enum class ServiceStartupRefusal(val reason: String) {

    /** The path exists and it is not a socket: it belongs to something else. */
    SocketPathIsNotASocket("socket-path-is-not-a-socket"),

    /** The directory of the socket is a symbolic link: its mode says nothing about its target. */
    SocketDirectoryIsASymlink("socket-directory-is-a-symlink"),

    /** The socket would live inside the material the service reads, or where it reads it from. */
    SocketPathIsReserved("socket-path-is-reserved"),

    /** The trust profile is a word this build does not serve. */
    TrustProfileUnknown("trust-profile-unknown"),

    /** The authorizations could not be read, or the file that holds them is not private. */
    AuthorizationsRefused("authorizations-refused"),

    /** The isolated profile was asked for and the machine cannot provide it. */
    IsolationUnavailable("isolation-unavailable"),

    /**
     * The operator asked for a profile that keeps the authorization off the
     * disk AND named a file to read it from.
     *
     * The two are the same request said twice, and the service cannot honour
     * both: a file IS on the disk. The reason is its own word because the
     * remedy is its own too — drop the file, or drop the profile.
     */
    AuthorizationFileRefusedByTheProfile("authorization-file-refused-by-the-profile"),

    /** Another service is already listening on that path. */
    AnotherServiceIsListening("another-service-is-listening"),

    /** The directory of the socket can be read by others, so the socket can be too. */
    SocketDirectoryIsNotPrivate("socket-directory-is-not-private"),

    /** The filesystem does not support the permissions the service needs. */
    PosixPermissionsUnsupported("posix-permissions-unsupported"),

    /** This machine has no Unix domain sockets, or no support for them. */
    RuntimeNotAvailable("runtime-not-available"),

    /** The path cannot be used: it has no parent, or the bind failed. */
    SocketPathUnavailable("socket-path-unavailable"),
}

/**
 * The refusal of a service that could not start.
 *
 * It is a refusal and not a stack trace because the CLI prints it as a
 * decision of the verb, and a stack trace would be noise where a word is
 * enough.
 */
internal class ServiceStartupException(
    val refusal: ServiceStartupRefusal,
    val detail: String,
) : RuntimeException(detail)

/**
 * The transport over a Unix domain socket.
 *
 * ## What it owns
 *
 * The socket as a thing of the filesystem: the directory that holds it, the
 * bind, the permissions, the difference between a socket that is alive and one
 * that is left over, and the deletion of its own socket when it closes.
 *
 * ## Why the permissions come before and after the bind
 *
 * Before, so that the socket is never born in a directory others can walk: the
 * directory is created private or checked to be private, and only then does the
 * socket appear. After, because the socket itself is created with the mode the
 * process has (0755 here), and a mode the process has is a mode others can use.
 * The window between the two is crossed by nobody, because the directory is not
 * walkable.
 *
 * ## Why the file's TYPE decides whether it is deleted
 *
 * A path that exists and cannot be connected to may be a leftover of a crash,
 * or it may be a regular file that somebody else put there. The type tells the
 * two apart, and only a socket is ever deleted: a service that deleted
 * whatever it found at the path would be a service that destroys files when it
 * is pointed at the wrong place.
 */
internal class UnixSocketTransport private constructor(
    private val channel: ServerSocketChannel,
    private val socketPath: Path,
    private val socketFileKey: String?,
) : ServiceTransport {

    /** The connections this transport is holding. */
    private val openConnections = ConcurrentHashMap.newKeySet<SocketChannel>()

    /**
     * The alarm that closes a connection whose client went quiet.
     *
     * A single thread for all of them: it only closes channels, it never
     * answers anything, and one thread cannot become the reason a service is
     * slow.
     */
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "pipelinek-service-watchdog").apply { isDaemon = true }
        }

    /**
     * The next connection, or null when the transport is closed.
     */
    override fun accept(): ServiceConnection? {
        val client = channel.accept()
        openConnections.add(client)
        return UnixSocketConnection(client, watchdog) { openConnections.remove(client) }
    }

    /**
     * Closes the transport: the channel, the connections and its own socket.
     */
    override fun close() {
        try {
            channel.close()
        } catch (_: IOException) {
            // A channel that is already closed is a channel that is closed.
        }
        openConnections.forEach { closeQuietly(it) }
        openConnections.clear()
        watchdog.shutdownNow()
        removeOwnSocket()
    }

    /**
     * Deletes the socket of this process, and only if it still is this
     * process's socket.
     *
     * The check is the file key — the device and the inode — captured at the
     * bind, and not the name: a name says where, and a file key says WHICH.
     * If the key is gone or it changed, the file at that path is not the one
     * this process created, and this process does not delete it.
     */
    private fun removeOwnSocket() {
        val key = socketFileKey ?: return
        if (fileKeyOf(socketPath) != key) return
        try {
            Files.deleteIfExists(socketPath)
        } catch (_: IOException) {
            // A socket that cannot be deleted stays as a leftover, and the next
            // start finds it and removes it if it is a socket and nobody
            // answers on it. Failing here would not make it better.
        }
    }

    companion object {

        /**
         * Binds the transport to [socketPath].
         *
         * The order is the contract: the directory is prepared and checked,
         * then the leftover is judged, then the socket is created, and then it
         * is hardened. A socket that was hardened before it was born would be a
         * socket that never existed.
         */
        fun bind(socketPath: Path): UnixSocketTransport {
            val absolute = socketPath.toAbsolutePath()
            val directory = absolute.parent
                ?: throw ServiceStartupException(
                    ServiceStartupRefusal.SocketPathUnavailable,
                    "the socket path has no directory: $socketPath",
                )

            prepareDirectory(directory)
            clearLeftover(absolute)

            val channel = try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            } catch (_: UnsupportedOperationException) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.RuntimeNotAvailable,
                    "this machine has no Unix domain sockets",
                )
            }

            try {
                channel.bind(UnixDomainSocketAddress.of(absolute))
            } catch (e: IOException) {
                closeQuietly(channel)
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketPathUnavailable,
                    "the socket could not be bound at $absolute: ${e.message}",
                )
            }

            try {
                hardenSocket(absolute)
            } catch (e: ServiceStartupException) {
                closeQuietly(channel)
                deleteQuietly(absolute)
                throw e
            }

            return UnixSocketTransport(channel, absolute, fileKeyOf(absolute))
        }

        /**
         * The directory of the socket is private, and it is private BEFORE the
         * socket exists.
         *
         * A directory that does not exist is created and hardened. A directory
         * that already exists is CHECKED and not changed: if it grants
         * anything to the group or to others, the service does not start,
         * because a socket inside a directory others can walk is a socket
         * others can open.
         *
         * ## Why a symbolic link is refused BY NAME
         *
         * A link is refused today, but for the wrong reason: the permission
         * that is read is the permission of the LINK —which is `rwxrwxrwx` on
         * Linux— and not the permission of the directory it points at, so the
         * refusal said "the directory is not private" about a directory the
         * service never looked at. The link is now refused by its own name,
         * because the mode of a link says nothing about where it goes.
         */
        private fun prepareDirectory(directory: Path) {
            if (Files.isSymbolicLink(directory)) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketDirectoryIsASymlink,
                    "the directory of the socket is a symbolic link: $directory",
                )
            }

            val created = !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
            if (created) {
                try {
                    Files.createDirectories(directory)
                } catch (e: IOException) {
                    throw ServiceStartupException(
                        ServiceStartupRefusal.SocketPathUnavailable,
                        "the directory of the socket could not be created: ${e.message}",
                    )
                }
            }

            if (!Files.isDirectory(directory)) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketPathUnavailable,
                    "the directory of the socket is not a directory: $directory",
                )
            }

            val permissions = posixPermissions(directory)
            if (created) {
                setPosixPermissions(directory, PosixFilePermissions.fromString("rwx------"))
                return
            }

            val exposed = permissions.filter { it == PosixFilePermission.GROUP_READ ||
                it == PosixFilePermission.GROUP_WRITE ||
                it == PosixFilePermission.GROUP_EXECUTE ||
                it == PosixFilePermission.OTHERS_READ ||
                it == PosixFilePermission.OTHERS_WRITE ||
                it == PosixFilePermission.OTHERS_EXECUTE }
            if (exposed.isNotEmpty()) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketDirectoryIsNotPrivate,
                    "the directory of the socket is not private: $directory ($exposed)",
                )
            }
        }

        /**
         * The socket file is `rw-------`, and if it cannot be, the service does
         * not start.
         *
         * The socket is created with the mode the process has, and that mode
         * is 0755: anybody local could connect. A socket whose mode cannot be
         * restricted is a socket that anybody local can open, so this is a
         * failure to start and not a warning.
         */
        private fun hardenSocket(socketPath: Path) {
            try {
                Files.setPosixFilePermissions(
                    socketPath,
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: UnsupportedOperationException) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.PosixPermissionsUnsupported,
                    "this filesystem does not support the permissions the socket needs",
                )
            } catch (e: IOException) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.PosixPermissionsUnsupported,
                    "the permissions of the socket could not be set: ${e.message}",
                )
            }
        }

        /**
         * What the path already holds, and what to do with it.
         *
         * A socket that answers is another service, and the service does not
         * start and does not delete it. A socket that does not answer is a
         * leftover, and it is deleted — but only because it IS a socket: any
         * other kind of file at that path stops the service and stays.
         */
        private fun clearLeftover(socketPath: Path) {
            if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) return

            val attributes = try {
                Files.readAttributes(
                    socketPath,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (e: IOException) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketPathUnavailable,
                    "the path of the socket could not be read: ${e.message}",
                )
            }

            if (!attributes.isOther) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketPathIsNotASocket,
                    "the path of the socket exists and it is not a socket: $socketPath",
                )
            }

            if (isAlive(socketPath)) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.AnotherServiceIsListening,
                    "another service is already listening on $socketPath",
                )
            }

            deleteQuietly(socketPath)
        }

        /**
         * Whether somebody answers on [socketPath].
         */
        private fun isAlive(socketPath: Path): Boolean = try {
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { true }
        } catch (_: IOException) {
            false
        }

        /**
         * The permissions of [path], or the refusal of a filesystem that has
         * none.
         */
        private fun posixPermissions(path: Path): Set<PosixFilePermission> = try {
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: UnsupportedOperationException) {
            throw ServiceStartupException(
                ServiceStartupRefusal.PosixPermissionsUnsupported,
                "this filesystem does not support the permissions the socket needs",
            )
        } catch (e: IOException) {
            throw ServiceStartupException(
                ServiceStartupRefusal.SocketPathUnavailable,
                "the permissions of the directory could not be read: ${e.message}",
            )
        }

        /**
         * The permissions of [path], set to [permissions].
         */
        private fun setPosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
            try {
                Files.setPosixFilePermissions(path, permissions)
            } catch (_: UnsupportedOperationException) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.PosixPermissionsUnsupported,
                    "this filesystem does not support the permissions the socket needs",
                )
            } catch (e: IOException) {
                throw ServiceStartupException(
                    ServiceStartupRefusal.SocketPathUnavailable,
                    "the permissions of the directory could not be set: ${e.message}",
                )
            }
        }

        /**
         * The key of the file at [path]: the device and the inode.
         */
        private fun fileKeyOf(path: Path): String? = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()?.toString()
        } catch (_: IOException) {
            null
        }

        /**
         * Deletes [path], ignoring the failure.
         */
        private fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // A file that cannot be deleted is not a reason to keep a
                // channel open.
            }
        }

        /**
         * Closes [channel], ignoring the failure.
         */
        private fun closeQuietly(channel: java.nio.channels.Channel) {
            try {
                channel.close()
            } catch (_: IOException) {
                // A channel that is already closed is a channel that is closed.
            }
        }
    }
}

/**
 * One connection over a Unix domain socket.
 *
 * ## What it owns
 *
 * The line: it reads up to the first newline, it refuses a line bigger than
 * the service reads, and it gives up on a client that does not finish its
 * line within the deadline.
 *
 * ## How the deadline is enforced
 *
 * A watchdog closes the channel when the deadline passes. A read that is
 * blocking cannot be interrupted, so closing the channel is what turns a
 * silent client into a finished read: the read fails, and the read knows why
 * it failed because the watchdog said so before closing.
 */
internal class UnixSocketConnection(
    private val channel: SocketChannel,
    private val watchdog: ScheduledExecutorService,
    private val onClose: () -> Unit,
) : ServiceConnection {

    override fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead {
        val expired = AtomicBoolean(false)
        val alarm = watchdog.schedule(
            {
                expired.set(true)
                closeQuietly()
            },
            deadlineMillis,
            TimeUnit.MILLISECONDS,
        )
        try {
            return readLineOf(maxBytes, expired)
        } finally {
            alarm.cancel(false)
        }
    }

    /**
     * Reads the line, or what stopped it from arriving.
     */
    private fun readLineOf(maxBytes: Int, expired: AtomicBoolean): ServiceRead {
        val buffer = ByteArray(4096)
        val accumulated = ByteArrayOutputStream()
        while (true) {
            val read = try {
                channel.read(ByteBuffer.wrap(buffer))
            } catch (_: IOException) {
                return if (expired.get()) ServiceRead.TimedOut else ServiceRead.Closed
            }
            if (read < 0) {
                return if (accumulated.size() == 0) {
                    ServiceRead.Closed
                } else {
                    ServiceRead.Line(accumulated.toString(Charsets.UTF_8))
                }
            }

            val chunk = buffer.copyOf(read)
            val newline = chunk.indexOf(NEWLINE)
            if (newline >= 0) {
                val size = accumulated.size() + newline
                if (size > maxBytes) return ServiceRead.TooLarge
                accumulated.write(chunk, 0, newline)
                return ServiceRead.Line(accumulated.toString(Charsets.UTF_8))
            }

            accumulated.write(chunk, 0, read)
            if (accumulated.size() > maxBytes) return ServiceRead.TooLarge
        }
    }

    override fun writeLine(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    override fun close() {
        closeQuietly()
        onClose()
    }

    /**
     * Closes the channel, ignoring the failure.
     */
    private fun closeQuietly() {
        try {
            channel.close()
        } catch (_: IOException) {
            // A channel that is already closed is a channel that is closed.
        }
    }

    private companion object {
        /** The byte that ends a line. */
        private val NEWLINE: Byte = '\n'.code.toByte()
    }
}
