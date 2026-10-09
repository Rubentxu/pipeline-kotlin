package dev.rubentxu.pipeline.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B0.2 — Gradle-level fixture: a REAL Gradle build runs a REAL task that calls the SAME
 * compiled production class ({@link ProvenanceDigest}) the plugin build scripts call.
 *
 * <p>Why this exists alongside {@link ProvenanceDigestTest}: the unit test proves the class is
 * path-independent; this proves the same property holds when the class is invoked through an
 * actual Gradle task in two different project roots, one of them containing a space. A harness
 * that reimplemented the algorithm would certify only its own arithmetic, so this one injects the
 * production class under test onto the fixture's buildscript classpath and lets the fixture task
 * call it.
 *
 * <p>Hermetic by construction: the fixture project is written into a JUnit {@code @TempDir}, has
 * no plugins, no repositories and no network; GradleRunner uses the Gradle installation running
 * the tests.
 */
@DisplayName("B0.2 — fixture Gradle: la tarea real en dos raíces da el mismo digest")
class ProvenanceDigestGradleFixtureTest {

    @Test
    @DisplayName("una tarea Gradle real, en dos raíces (una con espacios), da el mismo digest")
    void gradleTaskIsPathIndependentAcrossRoots(@TempDir Path root) throws IOException, URISyntaxException {
        Path classpathEntry = Path.of(ProvenanceDigest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());

        Path checkoutA = root.resolve("a checkout with spaces");
        Path checkoutB = root.resolve("b");
        writeFixture(checkoutA, classpathEntry);
        writeFixture(checkoutB, classpathEntry);

        String digestA = runFixture(checkoutA);
        String digestB = runFixture(checkoutB);

        assertTrue(
                digestA.matches("sha256:[0-9a-f]{64}"),
                "the fixture task must write a real digest, got: " + digestA);
        assertEquals(
                digestA,
                digestB,
                "the same bytes under two Gradle project roots must hash identically; a difference "
                        + "means the task consumed an absolute path");
    }

    private static void writeFixture(Path projectDir, Path classpathEntry) throws IOException {
        Files.createDirectories(projectDir.resolve("tree/classes/com/example"));
        Files.createDirectories(projectDir.resolve("tree/resources/META-INF/pipelinek"));
        Files.writeString(projectDir.resolve("tree/classes/com/example/A.class"), "class A {}");
        Files.writeString(projectDir.resolve("tree/resources/app.conf"), "k=v\n");
        Files.writeString(
                projectDir.resolve("tree/resources/META-INF/pipelinek/plugin-manifest.json"),
                "{\"releaseDigest\":\"sha256:x\"}\n");

        String escaped = classpathEntry.toString().replace("\\", "\\\\");
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"b0-2-fixture\"\n");
        Files.writeString(
                projectDir.resolve("build.gradle.kts"), FIXTURE_BUILD.replace("__CLASSPATH__", escaped));
    }

    private static String runFixture(Path projectDir) throws IOException {
        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("digest", "-q", "--stacktrace")
                .build();
        return Files.readString(projectDir.resolve("build/digest.txt")).trim();
    }

    /** The fixture build: a real task that calls the production class under test. */
    private static final String FIXTURE_BUILD =
            """
            buildscript {
                dependencies {
                    classpath(files("__CLASSPATH__"))
                }
            }

            tasks.register("digest") {
                val out = layout.buildDirectory.file("digest.txt")
                outputs.file(out)
                doLast {
                    val tree = file("tree")
                    val hex = dev.rubentxu.pipeline.build.ProvenanceDigest.computeDigestHex(
                        listOf(
                            dev.rubentxu.pipeline.build.ProvenanceDigest.Root(
                                "classes", java.io.File(tree, "classes").toPath()),
                            dev.rubentxu.pipeline.build.ProvenanceDigest.Root(
                                "resources", java.io.File(tree, "resources").toPath()),
                        ),
                        setOf("resources/META-INF/pipelinek/plugin-manifest.json"),
                    )
                    out.get().asFile.writeText("sha256:" + hex)
                }
            }
            """;
}
