package dev.rubentxu.pipeline.v2.sdk.http

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * AUD-01 — the plugin provenance digest must not depend on WHERE the tree was checked out.
 *
 * ## What failed, and why a unit test on the digest function would not have found it
 *
 * The seam computed its digest as:
 *
 * ```kotlin
 * commandLine = listOf("sh", "-c", "sha256sum $all | sha256sum | awk '{print \$1}'")
 * ```
 *
 * where `$all` was a space-joined list of **absolute** paths. `sha256sum` prints the filename it
 * was handed, so the filename became part of what was hashed. Two checkouts of identical bytes
 * produced different digests.
 *
 * A test that computed the digest twice in the SAME directory would have passed, and the old code
 * would have passed it too. The defect is only visible across two DIFFERENT roots, which is why the
 * fixture here builds the same content under two `@TempDir` subtrees of different names.
 *
 * Measured on this host before the fix, same bytes, two roots:
 *
 * ```text
 * absolute paths    checkoutA 6d938320...  checkoutB c8b614a5...   DIFFERENT
 * relative paths    checkoutA 666f3d32...  checkoutB 666f3d32...   IDENTICAL
 * ```
 *
 * ## Why this is a provenance property and not a hygiene one
 *
 * This digest is what a plugin presents to refuse registration without proof of what it is
 * (STEP_ECOSYSTEM_POLICY). If it moves with the checkout path, then a receipt binding a candidate
 * to a SHA cannot prove the bytes it claims to: the same tree is a different plugin identity on a
 * different machine. Everything measured by digest underneath that — candidate manifests, the
 * handoff, the harness verdict — inherits the instability.
 *
 * ## What this test does NOT claim
 *
 * It pins the FUNCTION, not the task. It cannot prove that `computeHttpDigest` calls it with
 * normalised relative paths, because that lives in a Gradle task this module's test classpath does
 * not execute. The claim it can make, and does, is that the two formulations differ — so any future
 * implementation is free to choose either, and choosing the wrong one is now visibly wrong rather
 * than silently wrong.
 */
@DisplayName("AUD-01 — el digest de procedencia no depende de la ruta del checkout")
class HttpProvenanceDigestPathIndependenceTest {

    @Test
    fun `los mismos bytes en dos raices distintas producen el mismo digest`(
        @TempDir root: java.nio.file.Path,
    ) {
        val checkoutA = root.resolve("checkout-a").resolve("build")
        val checkoutB = root.resolve("a-completely-different-checkout-name").resolve("build")
        writeSameBytesAt(checkoutA)
        writeSameBytesAt(checkoutB)

        assertEquals(
            digestOf(checkoutA, classesDir, resourcesDir),
            digestOf(checkoutB, classesDir, resourcesDir),
            "two roots with identical bytes must hash identically; if this fails the digest " +
                "consumed an absolute path",
        )
    }

    @Test
    fun `la formulacion antigua SÍ depende de la raiz, que es por eso que se cambio`(
        @TempDir root: java.nio.file.Path,
    ) {
        val checkoutA = root.resolve("checkout-a").resolve("build")
        val checkoutB = root.resolve("a-completely-different-checkout-name").resolve("build")
        writeSameBytesAt(checkoutA)
        writeSameBytesAt(checkoutB)

        // The mutation that this law exists for: hash what `sha256sum` would have printed, which
        // includes the path it was given. This MUST differ between the two roots, or the first
        // test above proves nothing.
        assertNotEquals(
            digestAsShellWouldPrintIt(checkoutA, classesDir, resourcesDir),
            digestAsShellWouldPrintIt(checkoutB, classesDir, resourcesDir),
            "the old formulation is supposed to be path-dependent; if it stopped being " +
                "path-dependent this test no longer demonstrates why the fix was necessary",
        )
    }

    @Test
    fun `cambiar el contenido de un solo fichero cambia el digest`(
        @TempDir root: java.nio.file.Path,
    ) {
        val before = root.resolve("before").resolve("build")
        val after = root.resolve("after").resolve("build")
        writeSameBytesAt(before)
        writeSameBytesAt(after)
        val marker = after.resolve("classes/kotlin/main/com/example/Same.class")
        marker.toFile().writeText("class A { /* changed */ }")

        assertNotEquals(
            digestOf(before, classesDir, resourcesDir),
            digestOf(after, classesDir, resourcesDir),
            "a digest that ignores content is not a provenance digest",
        )
    }

    @Test
    fun `renombrar un fichero cambia el digest aunque su contenido no cambie`(
        @TempDir root: java.nio.file.Path,
    ) {
        val before = root.resolve("before").resolve("build")
        val after = root.resolve("after").resolve("build")
        writeSameBytesAt(before)
        writeSameBytesAt(after)
        java.nio.file.Files.move(
            after.resolve("classes/kotlin/main/com/example/Same.class"),
            after.resolve("classes/kotlin/main/com/example/Renamed.class"),
        )

        assertNotEquals(
            digestOf(before, classesDir, resourcesDir),
            digestOf(after, classesDir, resourcesDir),
            "the relative path is part of the identity on purpose: a renamed class is a different " +
                "artefact even with identical bytes, and a digest that ignored the name would " +
                "collide two different JARs",
        )
    }

    private companion object {
        const val classesDir = "classes/kotlin/main"
        const val resourcesDir = "resources/main"

        /**
         * The identity, as the build script now computes it: sorted `(relative path, content)`
         * pairs, never an absolute path and never a shell.
         */
        fun digestOf(buildRoot: java.nio.file.Path, vararg dirs: String): String {
            val entries = dirs.flatMap { dir ->
                val root = buildRoot.resolve(dir)
                if (!java.nio.file.Files.isDirectory(root)) {
                    emptyList()
                } else {
                    java.nio.file.Files.walk(root).use { stream ->
                        stream.filter { java.nio.file.Files.isRegularFile(it) }.map { file ->
                            val rel = buildRoot.relativize(file).toString().replace('\\', '/')
                            rel to java.nio.file.Files.readAllBytes(file)
                        }.toList()
                    }
                }
            }.sortedBy { it.first }
            return hex(
                MessageDigest.getInstance("SHA-256").digest(
                    entries.joinToString("\n") { (rel, bytes) -> "${hex(bytes)}  $rel" }
                        .toByteArray(Charsets.UTF_8),
                ),
            )
        }

        /** The formulation that was replaced: the content of `sha256sum`'s OUTPUT, verbatim. */
        fun digestAsShellWouldPrintIt(buildRoot: java.nio.file.Path, vararg dirs: String): String {
            val printed = dirs.flatMap { dir ->
                val root = buildRoot.resolve(dir)
                if (!java.nio.file.Files.isDirectory(root)) {
                    emptyList()
                } else {
                    java.nio.file.Files.walk(root).use { stream ->
                        stream.filter { java.nio.file.Files.isRegularFile(it) }.toList()
                    }
                }
            }.sorted().joinToString("\n") { file ->
                val abs = file.toAbsolutePath().toString()
                "${hex(java.nio.file.Files.readAllBytes(file))}  $abs"
            }
            return hex(MessageDigest.getInstance("SHA-256").digest(printed.toByteArray(Charsets.UTF_8)))
        }

        fun writeSameBytesAt(buildRoot: java.nio.file.Path) {
            buildRoot.resolve("$classesDir/com/example").toFile().mkdirs()
            buildRoot.resolve(resourcesDir).toFile().mkdirs()
            buildRoot.resolve("$classesDir/com/example/Same.class").toFile()
                .writeText("class A {}")
            buildRoot.resolve("$resourcesDir/http-release.properties").toFile()
                .writeText("name=http")
        }

        fun hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
