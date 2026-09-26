package dev.rubentxu.pipeline.v2.domain.credentials

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Canonical POSIX file permission sets used by credential and artifact
 * adapters when materialising sensitive files to disk.
 *
 * The constants live here (rather than inside each adapter) because:
 *
 * - The two permission shapes below are an invariant of the credential
 *   materialisation contract: "owner-only readable" for secrets, and
 *   "owner-only readable + executable" for credential helper scripts
 *   that the runtime invokes directly. Several adapters independently
 *   require the same posture, and a drift between adapters (e.g. one
 *   using `rwx------` for a non-executable secret) would be a security
 *   regression that fails CI silently.
 * - These constants are pure values derived from
 *   [PosixFilePermissions.fromString]; the import is a one-time static
 *   initialisation, so introducing a JDK platform concept here does not
 *   turn the domain into a JVM-IO module. The shape (a `Set<PosixFilePermission>`)
 *   is the only thing adapters need to receive.
 *
 * Consumers MUST treat these as immutable; if a future use case requires
 * a different permission set (e.g. group-readable), add a new constant
 * here rather than calling [PosixFilePermissions.fromString] inline.
 *
 * D-001: replaces inline `fromString("rwx------")` / `fromString("rw-------")`
 * literals across `:pipeline-step-sdk:scm-git`,
 * `:pipeline-credentials-local`, `:pipeline-credentials-multipart`, and
 * `:pipeline-artefacts-local`. Cosmetic refactor; no behavioural change.
 */
object CredentialFilePermissions {
    /**
     * `rw-------` — owner read + write, no permissions for group or others.
     *
     * The default posture for any file holding secret material at rest
     * (credential files, SSH keys, password files, keystores, etc.).
     */
    val OWNER_READ_WRITE: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rw-------")

    /**
     * `rwx------` — owner read + write + execute, no permissions for group or others.
     *
     * Required for credential helper scripts (e.g. Git credential helpers,
     * askpass scripts, SSH wrappers) that the runtime must invoke
     * directly via `ProcessBuilder`. The execute bit is necessary for
     * the kernel to launch the script; the absent group/other bits keep
     * the helper script from leaking its own commands to non-owners.
     */
    val OWNER_READ_WRITE_EXECUTE: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwx------")
}
