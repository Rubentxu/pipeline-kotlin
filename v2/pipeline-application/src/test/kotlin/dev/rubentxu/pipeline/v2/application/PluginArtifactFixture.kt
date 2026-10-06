package dev.rubentxu.pipeline.v2.application

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipInputStream

/**
 * S6/C+E — test support for loading ONE real plugin artifact without the others interfering.
 *
 * ## Why this exists rather than a plain URLClassLoader
 *
 * Three separate failures produced this fixture, all of them real and all of them in the
 * harness rather than in the product:
 *
 * 1. With the test classloader as parent, a plugin class resolves from the test output
 *    directory instead of the JAR, so `strict` refuses: from the gate's point of view the
 *    plugin declared itself in one artifact and contributed code from another.
 * 2. With the platform loader as parent, `pipeline-domain` disappears and resolution dies with
 *    NoClassDefFoundError.
 * 3. With a delegating parent, the parent still publishes
 *    `META-INF/pipelinek/plugin-manifest.json` — and since all four official plugins put a copy
 *    of that SAME path on the test classpath, whichever the parent answers first wins. The
 *    failure named scm-git's JAR while loading http's class.
 *
 * The parent below delegates for everything and refuses for exactly two things: the official
 * plugin packages, and the canonical manifest resource. That reproduces the shape of a real
 * runtime, where PipelineK's own classes are visible and a plugin resolves to the artifact
 * under test.
 *
 * A `strict` check that fires because the harness leaked a classpath entry is not a finding;
 * it is the harness disguising itself as a defect.
 */
object PluginArtifactFixture {

    /** The four official plugin packages, hidden from the parent so the JAR under test wins. */
    private val HIDDEN_PACKAGES = listOf(
        "dev.rubentxu.pipeline.v2.sdk.http",
        "dev.rubentxu.pipeline.v2.sdk.scm",
        "dev.rubentxu.pipeline.v2.sdk.junit",
        "dev.rubentxu.pipeline.v2.sdk.utilities",
    )

    private val MANIFEST_PATH = dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH

    /** Delegates, but refuses the official plugin packages and the manifest resource. */
    private class ScopedParent(
        private val delegate: ClassLoader = PluginArtifactFixture::class.java.classLoader,
    ) : ClassLoader(null) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (HIDDEN_PACKAGES.any { name.startsWith(it) }) {
                throw ClassNotFoundException(name)
            }
            return delegate.loadClass(name)
        }

        override fun getResource(name: String): java.net.URL? =
            if (name == MANIFEST_PATH || HIDDEN_PACKAGES.any { name.startsWith("dev/rubentxu/pipeline/v2/sdk") }) {
                null
            } else {
                delegate.getResource(name)
            }
    }

    /** The built JAR of an official plugin. Fails loudly when absent — a skip proves nothing. */
    fun builtJar(module: String): Path {
        val libsDir = Paths.get("..", "pipeline-step-sdk", module, "build", "libs")
        val jars = if (Files.isDirectory(libsDir)) {
            Files.list(libsDir).use { stream ->
                stream.filter {
                    it.fileName.toString().startsWith(module + "-") &&
                        it.toString().endsWith(".jar") &&
                        !it.fileName.toString().endsWith("-sources.jar")
                }.toList()
            }
        } else {
            emptyList()
        }
        require(jars.isNotEmpty()) {
            "no built $module JAR under $libsDir. The build must produce the artifact before " +
                "this proof can mean anything; skipping here would be a green that proves nothing."
        }
        return jars.first()
    }

    /**
     * Copy a built plugin JAR and rewrite its manifest.
     *
     * The class stays inside the copy, so the mutant is a REAL artifact with a REAL
     * contributor — the refusal it produces comes from the cross-check, not from `strict`
     * refusing a substitution the harness created.
     */
    fun jarWithRewrittenManifest(module: String, transform: (String) -> String): Path {
        val source = builtJar(module)
        val target = source.resolveSibling("mutant-$module-${System.nanoTime()}.jar")

        ZipInputStream(Files.newInputStream(source)).use { input ->
            JarOutputStream(Files.newOutputStream(target)).use { out ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    // Paths are preserved EXACTLY. An earlier version flattened META-INF/* to
                    // its basename to avoid duplicate names, which silently destroyed
                    // META-INF/services and left the mutant with no provider — the harness was
                    // manufacturing the very failure it claimed to be testing.
                    val path = entry.name
                    val newEntry = JarEntry(path)
                    // Fixed timestamp so the mutant is reproducible rather than time-dependent.
                    newEntry.time = 0L
                    out.putNextEntry(newEntry)
                    if (path == MANIFEST_PATH) {
                        out.write(transform(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
                    } else {
                        out.write(bytes)
                    }
                    out.closeEntry()
                }
            }
        }
        return target
    }

    /** Run [block] with a loader whose only view of this plugin is the artifact in [jar]. */
    fun <T> withScopedLoader(jar: Path, block: (URLClassLoader) -> T): T {
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), ScopedParent())
        return try {
            block(loader)
        } finally {
            loader.close()
        }
    }
}