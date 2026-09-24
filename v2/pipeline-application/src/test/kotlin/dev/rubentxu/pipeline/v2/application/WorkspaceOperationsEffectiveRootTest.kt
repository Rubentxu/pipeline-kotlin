package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.WorkspaceResolver
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Adversarial coverage for the WU-RP-053 security fix to WorkspaceOperationsAdapter.
 *
 * Adversarial rows (this slice):
 *
 * - inferredWorkspaceAndNestedDirectoryShareFilesystemRoot (pre-existing positive)
 * - legacyStageLayoutAndExplicitOverrideRemainDistinct (pre-existing positive)
 * - relativeTargetInsideCwdEscapingViaDotDotIsRejected
 * - absoluteTargetOutsideAuthorizedRootIsRejected
 * - absoluteTargetPointingInsideAuthorizedRootIsAccepted
 * - symlinkLeafOutsideAuthorizedRootIsRejected
 * - symlinkIntermediateChainLeadingOutsideAuthorizedRootIsRejected
 * - reservedDotV2AgainstAuthorizedRootIsRejected
 * - reservedDotV2ViaEffectiveCwdInsideAuthorizedRootIsRejected
 * - readFileAndFileExistsReturnsFalseForTargetsOutsideAuthorizedRoot
 * - effectiveCwdOutsideAuthorizedRootStillCannotWrite
 * - bareStageWorkspaceWithEffectiveCwdNullFallsBackToAuthorizedRoot
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WorkspaceOperationsEffectiveRootTest {
    @TempDir lateinit var temp: Path

    @Test fun inferredWorkspaceAndNestedDirectoryShareFilesystemRoot() {
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val nested = checkout.resolve("nested")
        val sink = InMemoryEventStore()
        val outside = WorkspaceOperationsAdapter("build", 0, control, sink,
            workspaceBase = checkout)
        outside.writeFile("outside.txt", "outside", "UTF-8")
        assertTrue(Files.exists(checkout.resolve("outside.txt")))
        assertFalse(Files.exists(control.resolve("workspace/build-0/outside.txt")))
        val inside = WorkspaceOperationsAdapter("build", 0, control, sink,
            effectiveWorkingDirectory = nested, workspaceBase = checkout)
        inside.writeFile("inside.txt", "inside", "UTF-8")
        assertEquals("inside", Files.readString(nested.resolve("inside.txt")))
        assertTrue(inside.fileExists("inside.txt").exists)
        assertFalse(outside.fileExists("inside.txt").exists)
        assertFalse(Files.exists(checkout.resolve("inside.txt")))
        assertFalse(Files.exists(temp.resolve("inside.txt")))
    }

    @Test fun legacyStageLayoutAndExplicitOverrideRemainDistinct() {
        val control = temp.resolve("control")
        val legacy = WorkspaceResolver(control)
        assertEquals(control.resolve("workspace/build-2"), legacy.resolve("build", 2))
        val checkout = temp.resolve("checkout")
        assertEquals(checkout, WorkspaceResolver(control, workspaceBase = checkout).resolve("build", 2))
    }

    // ---- WU-RP-053 adversarial rows ----------------------------------------

    @Test fun relativeTargetInsideCwdEscapingViaDotDotIsRejected() {
        // Project workspace = a checkout dir; effective cwd = a subdir of it.
        // Adversarial relative target: "../../outside.txt" escapes the subdir AND
        // escapes the authorised workspace root. Must be rejected.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val nested = Files.createDirectories(checkout.resolve("nested"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
            effectiveWorkingDirectory = nested,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = "../../outside.txt", text = "pwn", encoding = "UTF-8")
        }
        assertTrue(ex.message!!.contains("authorized workspace root"),
            "expected auth error, got: ${ex.message}")
        assertFalse(Files.exists(checkout.resolve("outside.txt")))
        assertFalse(Files.exists(checkout.parent.resolve("outside.txt")))
    }

    @Test fun absoluteTargetOutsideAuthorizedRootIsRejected() {
        // cwd is INSIDE the authorized workspace; absolute target points at a
        // sibling directory outside the workspace. Must be rejected because the
        // adapter's WIDE guard runs against authorizedWorkspaceRoot, not cwd.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val nested = Files.createDirectories(checkout.resolve("nested"))
        val sibling = Files.createDirectories(temp.resolve("sibling"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
            effectiveWorkingDirectory = nested,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = sibling.resolve("pwn.txt").toString(),
                text = "pwn", encoding = "UTF-8")
        }
        assertTrue(ex.message!!.contains("authorized workspace root"),
            "expected auth error, got: ${ex.message}")
        assertFalse(Files.exists(sibling.resolve("pwn.txt")))
    }

    @Test fun absoluteTargetPointingInsideAuthorizedRootButOutsideCwdIsRejected() {
        // The supported file-Step contract is RELATIVE paths (Jenkins semantics).
        // An absolute target inside the authorised workspace but outside `cwd`
        // is rejected: the substrate (`targetPath.startsWith(workspace = effectiveCwd)`)
        // catches it; the adapter-level guard catches anything beyond that. The
        // hard invariant is: the file MUST NOT be written.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val nested = Files.createDirectories(checkout.resolve("nested"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
            effectiveWorkingDirectory = nested,
        )
        val insideAbsTarget = checkout.resolve("generated/output.txt").toAbsolutePath()
        Files.createDirectories(insideAbsTarget.parent)
        // Either layer may catch the escape; the contract is that the file is
        // never written.
        assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(
                file = insideAbsTarget.toString(),
                text = "OK",
                encoding = "UTF-8",
            )
        }
        assertFalse(Files.exists(insideAbsTarget))
    }

    @Test fun symlinkLeafOutsideAuthorizedRootIsRejected() {
        // Symlink INSIDE the workspace that points to a sibling OUTSIDE the
        // workspace. Even though the textual path starts with the authorised
        // root, the realpath leaks. Must be rejected at the wide gate.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        Files.createDirectories(checkout)
        val escapeTarget = Files.createDirectories(temp.resolve("escape"))
        val symlink = checkout.resolve("leak.txt")
        Files.createSymbolicLink(symlink, escapeTarget.resolve("data.txt"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = "leak.txt", text = "pwn", encoding = "UTF-8")
        }
        // Either the textual escape (file does not resolve inside), or the
        // realpath canonicalization (data.txt lives outside). Both are valid
        // rejections; the gate simply must not perform the write.
        assertTrue(ex.message!!.contains("authorized workspace root") ||
            ex.message!!.contains("symlink"),
            "expected auth/symlink error, got: ${ex.message}")
        assertFalse(Files.exists(escapeTarget.resolve("data.txt")))
    }

    @Test fun symlinkIntermediateChainLeadingOutsideAuthorizedRootIsRejected() {
        // A symlink DIRECTORY placed inside the workspace that points to a
        // directory outside. The textual path goes through the symlink, but
        // realpath canonicalises to outside. Adapter must reject.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        Files.createDirectories(checkout)
        val escapeRoot = Files.createDirectories(temp.resolve("escapedir"))
        val symlinkDir = checkout.resolve("escape-here")
        Files.createSymbolicLink(symlinkDir, escapeRoot)
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = "escape-here/pwn.txt", text = "pwn", encoding = "UTF-8")
        }
        assertTrue(ex.message!!.contains("authorized workspace root") ||
            ex.message!!.contains("symlink"),
            "expected auth/symlink error, got: ${ex.message}")
        assertFalse(Files.exists(escapeRoot.resolve("pwn.txt")))
    }

    @Test fun reservedDotV2AgainstAuthorizedRootIsRejected() {
        // .v2 inside the authorised workspace root is a reserved tree (control
        // directory). Filesystem Step MUST NOT write into it regardless of cwd.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        Files.createDirectories(checkout.resolve(".v2"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = ".v2/manifest.json", text = "{}", encoding = "UTF-8")
        }
        assertTrue(ex.message!!.contains(".v2"),
            "expected .v2 reserved error, got: ${ex.message}")
        assertFalse(Files.exists(checkout.resolve(".v2/manifest.json")))
    }

    @Test fun reservedDotV2ViaEffectiveCwdInsideAuthorizedRootIsRejected() {
        // cwd = .v2 (a subdir of the authorised workspace). Even though .v2 is
        // the cwd, the adapter's reserved-.v2 guard is evaluated against the
        // authorised root, so writes into it are still rejected. The substrate
        // alone would accept cwd/.v2/file as "inside cwd"; the adapter MUST
        // refuse it because .v2 is reserved at the authorised root.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val v2 = Files.createDirectories(checkout.resolve(".v2"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
            effectiveWorkingDirectory = v2,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = "manifest.json", text = "{}", encoding = "UTF-8")
        }
        assertTrue(ex.message!!.contains(".v2"),
            "expected .v2 reserved error, got: ${ex.message}")
        assertFalse(Files.exists(v2.resolve("manifest.json")))
    }

    @Test fun readFileAndFileExistsReturnsFalseForTargetsOutsideAuthorizedRoot() {
        // Read/exists semantically return false (Jenkins semantics) for any
        // target escaping the authorised workspace. They MUST NOT throw.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val nested = Files.createDirectories(checkout.resolve("nested"))
        val sibling = Files.createDirectories(temp.resolve("sibling"))
        Files.writeString(sibling.resolve("outside.txt"), "secret")
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
            effectiveWorkingDirectory = nested,
        )
        // Absolute target outside authorised workspace.
        val read = adapter.readFile(file = sibling.resolve("outside.txt").toString(),
            encoding = "UTF-8")
        assertFalse(read.exists, "readFile of an out-of-workspace absolute path MUST return exists=false")
        assertEquals(null, read.content)
        val exists = adapter.fileExists(file = sibling.resolve("outside.txt").toString())
        assertFalse(exists.exists, "fileExists of an out-of-workspace absolute path MUST return exists=false")
        // Relative escape via .. — same expected behaviour.
        val readDotDot = adapter.readFile(file = "../../../sibling/outside.txt", encoding = "UTF-8")
        assertFalse(readDotDot.exists)
        val existsDotDot = adapter.fileExists(file = "../../../sibling/outside.txt")
        assertFalse(existsDotDot.exists)
    }

    @Test fun effectiveCwdOutsideAuthorizedRootStillCannotWrite() {
        // When effectiveWorkingDirectory points OUTSIDE the authorized workspace
        // (e.g. dir("/etc") or dir("../outside")), the adapter's WIDE guard
        // still rejects. Only the substrate-level check would let this through.
        val control = temp.resolve("control")
        val checkout = temp.resolve("checkout")
        val outside = Files.createDirectories(temp.resolve("outside-cwd"))
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter(
            "build", 0, control, sink,
            workspaceBase = checkout,
            effectiveWorkingDirectory = outside,
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            adapter.writeFile(file = "pwn.txt", text = "pwn", encoding = "UTF-8")
        }
        assertTrue(ex.message!!.contains("authorized workspace root"),
            "expected auth error, got: ${ex.message}")
        assertFalse(Files.exists(outside.resolve("pwn.txt")))
    }

    @Test fun bareStageWorkspaceWithEffectiveCwdNullFallsBackToAuthorizedRoot() {
        // No --workspace override AND no effectiveWorkingDirectory: legacy
        // per-stage layout under controlDirRoot is the authorised root.
        val control = temp.resolve("control")
        val sink = InMemoryEventStore()
        val adapter = WorkspaceOperationsAdapter("build", 0, control, sink)
        // The expected authorised root resolves to controlRoot/workspace/build-0
        // per the legacy WorkspaceResolver contract.
        adapter.writeFile("inside.txt", "OK", encoding = "UTF-8")
        val expected = control.resolve("workspace/build-0/inside.txt")
        assertTrue(Files.exists(expected))
        // fileExists inside the auth root works.
        assertTrue(adapter.fileExists("inside.txt").exists)
        // fileExists outside the auth root returns false (not throws).
        assertFalse(adapter.fileExists("../other.txt").exists)
    }
}
