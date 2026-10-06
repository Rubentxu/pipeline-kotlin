package dev.rubentxu.pipeline.v2.application

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/**
 * S6/D test fixture — builds a REAL plugin artifact whose class initialiser writes a sentinel.
 *
 * ## Why the class is compiled here instead of living in the test source set
 *
 * Three earlier versions of this harness each failed in a way worth recording:
 *
 * 1. A nested Kotlin class, whose compiled name is `Outer$Inner.class`. The harness guessed
 *    the wrong path and threw NoSuchFileException.
 * 2. A top-level test class. It compiled, but then a [URLClassLoader] with the test
 *    classloader as parent resolved it from the PARENT, so the class under test was never
 *    the one inside the artifact.
 * 3. A static field the test had to write. "No sentinel" then proved nothing, because the
 *    only thing that ever created the file was the assertion itself.
 *
 * So the fixture is compiled from Java source at test time into a JAR that is loaded by a
 * classloader whose parent is the PLATFORM loader: no test classes are visible, the class
 * under test can only be the one inside the artifact, and the file can only appear if
 * something initialised it. That is the property the ordering claim needs.
 *
 * ## No committed binaries
 *
 * The source travels as a string and is compiled with the JDK's own compiler, so the
 * repository carries no `.class` file and the fixture cannot drift away from its source.
 */
object SentinelPluginArtifact {

    /** Binary name the gate will be asked to resolve. Never referenced as a Kotlin symbol. */
    const val PLUGIN_CLASS_NAME: String = "pipelinek.test.sentinel.SentinelPlugin"

    /** Resource inside the artifact holding the absolute path of the sentinel file. */
    const val SENTINEL_RESOURCE: String = "sentinel-target.txt"

    private const val SOURCE = """
package pipelinek.test.sentinel;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SentinelPlugin {

    static {
        try (java.io.InputStream in = SentinelPlugin.class.getClassLoader()
                .getResourceAsStream("sentinel-target.txt")) {
            if (in != null) {
                String target = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!target.isEmpty()) {
                    Files.writeString(Path.of(target), "plugin code ran");
                }
            }
        } catch (Exception ignored) {
            // A fixture must never turn an initialisation problem into a test failure here;
            // the assertion that matters reads the file, not this code path.
        }
    }

    private SentinelPlugin() {
    }
}
"""

    /**
     * Write a JAR carrying the compiled plugin class, the sentinel target and the manifest.
     *
     * @param manifestText the manifest document to place at [dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH].
     * @param sentinelPath the file the plugin's initialiser will write if it ever runs.
     */
    fun build(jarDir: Path, manifestText: String, sentinelPath: Path): Path {
        val workDir = Files.createDirectories(jarDir.resolve("work-${System.nanoTime()}"))
        val sourceDir = Files.createDirectories(workDir.resolve("src/pipelinek/test/sentinel"))
        val classesDir = Files.createDirectories(workDir.resolve("classes"))

        val sourceFile = sourceDir.resolve("SentinelPlugin.java")
        Files.writeString(sourceFile, SOURCE.trimIndent())

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("no system Java compiler available; the S6/D ordering harness cannot build its artifact")

        val exit = compiler.run(
            null,
            null,
            null,
            "-d", classesDir.toString(),
            sourceFile.toString(),
        )
        check(exit == 0) { "sentinel plugin fixture failed to compile (exit $exit)" }

        val classFile = classesDir.resolve("pipelinek/test/sentinel/SentinelPlugin.class")
        check(Files.exists(classFile)) { "compiled sentinel plugin class is missing at $classFile" }

        val jar = jarDir.resolve("plugin-${System.nanoTime()}.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(
                java.util.jar.JarEntry(dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec.RESOURCE_PATH),
            )
            out.write(manifestText.toByteArray(Charsets.UTF_8))
            out.closeEntry()

            out.putNextEntry(java.util.jar.JarEntry("pipelinek/test/sentinel/SentinelPlugin.class"))
            out.write(Files.readAllBytes(classFile))
            out.closeEntry()

            out.putNextEntry(java.util.jar.JarEntry(SENTINEL_RESOURCE))
            out.write(sentinelPath.toString().toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar
    }

    /**
     * A loader whose parent is the PLATFORM loader.
     *
     * Not the test classloader: that would resolve [PLUGIN_CLASS_NAME] from the test output
     * and the artifact under test would never be exercised.
     */
    fun loaderFor(jar: Path): URLClassLoader =
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader())

    /** Convenience for the JAR-less cases: a loader serving an artifact that declares nothing. */
    fun emptyArtifactLoader(jarDir: Path): URLClassLoader {
        val jar = jarDir.resolve("empty-${System.nanoTime()}.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(jar)).use { /* no entries */ }
        return loaderFor(jar)
    }

    fun deleteQuietly(path: Path) {
        runCatching { File(path.toString()).deleteRecursively() }
    }
}
