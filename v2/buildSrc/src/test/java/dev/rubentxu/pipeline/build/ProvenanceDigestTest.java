package dev.rubentxu.pipeline.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rubentxu.pipeline.build.ProvenanceDigest.Root;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B0.2 — the shared provenance digest.
 *
 * <p>These tests execute the SAME production class the three plugin build scripts call
 * ({@link ProvenanceDigest#computeDigestHex}). They do not reimplement the algorithm:
 * the only thing they re-derive is the FIXTURE layout, never the digest formula. A test
 * that recomputed the digest its own way would certify the test's arithmetic, not the
 * production authority (harness fidelity law §1/§2).
 *
 * <p>They live in {@code buildSrc} on purpose: {@code ProvenanceDigest} is deliberately
 * build-only and is not on any module's test classpath, so the closest honest place to
 * exercise it is its own project.
 */
@DisplayName("B0.2 — digest de procedencia compartido: ruta, contenido y exclusiones")
class ProvenanceDigestTest {

    /** The exclusion set the real plugin build scripts declare for their own metadata. */
    private static final Set<String> PLUGIN_EXCLUSIONS = Set.of(
            "resources/META-INF/http-release.properties",
            "resources/META-INF/http-release.properties.digest",
            "resources/META-INF/pipelinek/plugin-manifest.json");

    private static List<Root> rootsOf(Path buildDir) {
        return List.of(
                new Root("classes", buildDir.resolve("classes/kotlin/main")),
                new Root("resources", buildDir.resolve("resources/main")));
    }

    private static String digestOf(Path buildDir) {
        return ProvenanceDigest.computeDigestHex(rootsOf(buildDir), PLUGIN_EXCLUSIONS);
    }

    /** Writes an identical class + resource tree under a given build directory. */
    private static void writeSameBytesAt(Path buildDir) throws IOException {
        Files.createDirectories(buildDir.resolve("classes/kotlin/main/com/example"));
        Files.createDirectories(buildDir.resolve("resources/main/META-INF/pipelinek"));
        Files.writeString(
                buildDir.resolve("classes/kotlin/main/com/example/Same.class"), "class A {}");
        Files.writeString(buildDir.resolve("resources/main/app.conf"), "name=http\n");
        // The generated metadata documents, which the real task excludes by exact path.
        Files.writeString(
                buildDir.resolve("resources/main/META-INF/http-release.properties"),
                "pipeline.http.release.digest=sha256:deadbeef\n");
        Files.writeString(
                buildDir.resolve("resources/main/META-INF/pipelinek/plugin-manifest.json"),
                "{\"releaseDigest\":\"sha256:deadbeef\"}\n");
    }

    @Test
    @DisplayName("los mismos bytes en dos raíces distintas, una con espacios, dan el mismo digest")
    void sameBytesTwoRootsOneWithSpaces(@TempDir Path root) throws IOException {
        var checkoutA = root.resolve("checkout-a/build");
        var checkoutB = root.resolve("an unrelated checkout name/build");
        writeSameBytesAt(checkoutA);
        writeSameBytesAt(checkoutB);

        assertEquals(
                digestOf(checkoutA),
                digestOf(checkoutB),
                "identical bytes under two different roots must hash identically; a difference "
                        + "means an absolute path entered the hashed material (AUD-01)");
    }

    @Test
    @DisplayName("cambiar un byte de una clase cambia el digest")
    void changingOneClassByteChangesDigest(@TempDir Path root) throws IOException {
        var before = root.resolve("before/build");
        var after = root.resolve("after/build");
        writeSameBytesAt(before);
        writeSameBytesAt(after);
        Files.writeString(
                after.resolve("classes/kotlin/main/com/example/Same.class"), "class A { int x; }");

        assertNotEquals(digestOf(before), digestOf(after));
    }

    @Test
    @DisplayName("cambiar un byte de un recurso cambia el digest")
    void changingOneResourceByteChangesDigest(@TempDir Path root) throws IOException {
        var before = root.resolve("before/build");
        var after = root.resolve("after/build");
        writeSameBytesAt(before);
        writeSameBytesAt(after);
        Files.writeString(after.resolve("resources/main/app.conf"), "name=scm-git\n");

        assertNotEquals(digestOf(before), digestOf(after));
    }

    @Test
    @DisplayName("renombrar la ruta relativa cambia el digest aunque el contenido no cambie")
    void renamingRelativePathChangesDigest(@TempDir Path root) throws IOException {
        var before = root.resolve("before/build");
        var after = root.resolve("after/build");
        writeSameBytesAt(before);
        writeSameBytesAt(after);
        Files.move(
                after.resolve("classes/kotlin/main/com/example/Same.class"),
                after.resolve("classes/kotlin/main/com/example/Renamed.class"));

        assertNotEquals(
                digestOf(before),
                digestOf(after),
                "the relative path is part of the identity on purpose: a renamed class is a "
                        + "different artifact even with identical bytes");
    }

    @Test
    @DisplayName("cambiar unrelated-plugin-manifest.json cambia el digest (no está excluido)")
    void unrelatedPluginManifestIsHashed(@TempDir Path root) throws IOException {
        var before = root.resolve("before/build");
        var after = root.resolve("after/build");
        writeSameBytesAt(before);
        writeSameBytesAt(after);
        var unrelated = "resources/main/META-INF/pipelinek/unrelated-plugin-manifest.json";
        Files.writeString(before.resolve(unrelated), "{\"a\":1}\n");
        Files.writeString(after.resolve(unrelated), "{\"a\":2}\n");

        assertNotEquals(
                digestOf(before),
                digestOf(after),
                "exclusion must be by EXACT relative path; a broad *plugin-manifest.json suffix "
                        + "would silently drop this file and hide the change");
    }

    @Test
    @DisplayName("modificar la metadata generada propia (properties + manifest) es estable")
    void ownGeneratedMetadataIsExcludedAndStable(@TempDir Path root) throws IOException {
        var before = root.resolve("before/build");
        var after = root.resolve("after/build");
        writeSameBytesAt(before);
        writeSameBytesAt(after);
        Files.writeString(
                after.resolve("resources/main/META-INF/http-release.properties"),
                "pipeline.http.release.digest=sha256:0000\n");
        Files.writeString(
                after.resolve("resources/main/META-INF/pipelinek/plugin-manifest.json"),
                "{\"releaseDigest\":\"sha256:0000\"}\n");

        assertEquals(
                digestOf(before),
                digestOf(after),
                "the properties file and the manifest carry the digest, so hashing them would be "
                        + "a fixed-point loop; they are excluded by exact relative path");
    }

    @Test
    @DisplayName("el digest no es un entero negativo y tiene 64 hex")
    void digestIs64LowercaseHex(@TempDir Path root) throws IOException {
        writeSameBytesAt(root.resolve("build"));
        String digest = digestOf(root.resolve("build"));

        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"), digest);
    }

    @Test
    @DisplayName("un árbol vacío falla cerrado en vez de producir un digest de la nada")
    void emptyTreeFailsClosed(@TempDir Path root) throws IOException {
        Path empty = root.resolve("build");
        Files.createDirectories(empty.resolve("classes/kotlin/main"));
        Files.createDirectories(empty.resolve("resources/main"));

        assertThrows(IllegalStateException.class, () -> digestOf(empty));
    }

    /**
     * Pins the exact framing: {@code <hash>  <root>/<relpath>} join with {@code \n}, UTF-8.
     * The two spaces are not cosmetic — they are what a {@code sha256sum} line carries, and
     * this digest must keep producing the same value for the same tree.
     */
    @Test
    @DisplayName("el framing canónico conserva dos espacios y el orden de la ruta relativa")
    void framingIsCanonical(@TempDir Path root) throws IOException {
        Path build = root.resolve("build");
        Files.createDirectories(build.resolve("classes"));
        Files.createDirectories(build.resolve("resources"));
        Files.writeString(build.resolve("classes/b.class"), "B");
        Files.writeString(build.resolve("resources/a.txt"), "A");

        String expectedFraming = hash("B") + "  classes/b.class\n" + hash("A") + "  resources/a.txt";
        String expectedDigest = independentSha256(expectedFraming);

        assertEquals(
                expectedDigest,
                ProvenanceDigest.computeDigestHex(
                        List.of(new Root("classes", build.resolve("classes")),
                                new Root("resources", build.resolve("resources"))),
                        Set.of()));
    }

    /**
     * Independent of {@link ProvenanceDigest#sha256Hex}: the framing expectation must not be
     * built with the very primitive under test, or a shared hashing bug would be invisible.
     */
    private static String hash(String content) {
        return independentSha256(content);
    }

    private static String independentSha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
