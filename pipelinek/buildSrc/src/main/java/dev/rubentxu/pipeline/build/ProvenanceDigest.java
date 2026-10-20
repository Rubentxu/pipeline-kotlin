package dev.rubentxu.pipeline.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The single provenance-digest primitive shared by the three plugin SDK build scripts
 * (http, scm-git, utilities).
 *
 * <h2>Why this class exists (B0.2)</h2>
 *
 * The three build scripts each carried their own copy of the same walk + framing +
 * exclusion logic. AUD-01 fixed the absolute-path defect in all three copies by hand,
 * but the copies are still three authorities: a future fix applied to one copy can
 * silently miss the other two. This class removes that failure mode — the three scripts
 * now call the same implementation and only declare their own roots and exclusions.
 *
 * <h2>What the digest is</h2>
 *
 * The plugin's provenance identity: the SHA-256 of a canonical framing of the compiled
 * class files and processed resources. A contributor refuses to register a plugin that
 * cannot present this identity, so it must be a function of the artifact content and of
 * nothing else — not of where the tree was checked out, not of a shell, not of JAR
 * packaging timestamps.
 *
 * <h2>Framing (frozen; changing it changes every plugin fingerprint)</h2>
 *
 * <pre>
 *   &lt;sha256(content)&gt;  &lt;root-name&gt;/&lt;relative path with '/'&gt;
 * </pre>
 *
 * One such line per file, lines joined with {@code \n} in ascending order of the
 * relative path, UTF-8 encoded, then hashed with SHA-256. The two spaces between the
 * content hash and the path mirror {@code sha256sum} output deliberately: the digest was
 * originally produced by a shell pipeline and this framing keeps the same value for the
 * same tree. Do not "tidy" it.
 *
 * <h2>Path independence (AUD-01)</h2>
 *
 * Only the root NAME and the RELATIVE path are framed. The absolute checkout path never
 * enters the material, so two checkouts of identical bytes produce the same digest even
 * when their absolute paths differ or contain spaces.
 *
 * <h2>Exact exclusions, not broad suffix matches</h2>
 *
 * Exclusions are EXACT framed relative paths, supplied by the caller. The previous
 * implementation dropped any file whose name ended with {@code plugin-manifest.json},
 * which excluded that document only by accident of it being the only such file in the
 * tree; a sibling {@code unrelated-plugin-manifest.json} would have been dropped too.
 * Exclusion by exact relative path keeps every other file — including any other
 * manifest — inside the identity.
 */
public final class ProvenanceDigest {

    private ProvenanceDigest() {}

    /** A named root. Files under {@code dir} are framed as {@code name + "/" + relativePath}. */
    public record Root(String name, Path dir) {}

    private record Entry(String relativePath, byte[] content) {}

    /**
     * Computes the hex SHA-256 of the canonical framing of every regular file under the
     * given roots, minus the exact relative paths in {@code excludedRelativePaths}.
     *
     * @param roots                named roots to walk; missing directories are skipped
     * @param excludedRelativePaths exact framed relative paths to omit (e.g.
     *                             {@code "resources/META-INF/pipelinek/plugin-manifest.json"})
     * @return 64-character lowercase hex SHA-256
     * @throws IllegalStateException if the resulting material is empty; a digest over
     *                               nothing is not a provenance identity
     */
    public static String computeDigestHex(List<Root> roots, Set<String> excludedRelativePaths) {
        List<Entry> entries = new ArrayList<>();
        for (Root root : roots) {
            Path dir = root.dir();
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    String relativePath = root.name() + "/" + toForwardSlash(dir.relativize(file));
                    if (excludedRelativePaths.contains(relativePath)) {
                        return;
                    }
                    entries.add(new Entry(relativePath, readBytes(file)));
                });
            } catch (IOException e) {
                throw new UncheckedIOException("failed walking provenance root " + dir, e);
            }
        }
        entries.sort(Comparator.comparing(Entry::relativePath));
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "no class or resource files to hash: refusing to produce a provenance digest over an empty tree");
        }
        StringBuilder framed = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                framed.append('\n');
            }
            framed.append(sha256Hex(entries.get(i).content()))
                    .append("  ")
                    .append(entries.get(i).relativePath());
        }
        return sha256Hex(framed.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The one hashing primitive. Kept public so tests can build expectations from it. */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    private static String toForwardSlash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + file, e);
        }
    }
}
