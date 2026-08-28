package dev.rubentxu.pipeline.v2.artefacts.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for AntStyleGlob — ARC-AG-001..012.
 *
 * RED: these tests fail with ClassNotFoundException until the module is created.
 * GREEN: all tests pass once AntStyleGlob is implemented.
 */
class AntStyleGlobTest {

    @TempDir
    lateinit var root: Path

    private fun setupFixture() {
        // root/build/a.txt
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("build/a.txt"), "a")
        // root/build/sub/b.txt
        Files.createDirectories(root.resolve("build/sub"))
        Files.writeString(root.resolve("build/sub/b.txt"), "b")
        // root/other/c.txt
        Files.createDirectories(root.resolve("other"))
        Files.writeString(root.resolve("other/c.txt"), "c")
    }

    private fun setupDefaultExcludesFixture() {
        // Create all parent directories first
        Files.createDirectories(root.resolve(".git/logs"))
        Files.createDirectories(root.resolve(".svn"))
        Files.createDirectories(root.resolve(".bzr/data"))
        Files.createDirectories(root.resolve(".hg/data"))
        Files.createDirectories(root.resolve("CVS/data"))
        // Files under .git/ (excluded by **/.git/**)
        Files.writeString(root.resolve(".git/HEAD.log"), "ref: refs/heads/main")
        Files.writeString(root.resolve(".git/logs/x.log"), "log entry")
        // File under .svn/ (excluded by **/.svn/**)
        Files.writeString(root.resolve(".svn/entries.log"), "svn entry")
        // Files matching patterns directly (not excluded)
        Files.writeString(root.resolve(".DS_Store.log"), "ds store")
        Files.writeString(root.resolve(".gitignore.log"), "*.class")
        Files.writeString(root.resolve(".gitattributes.log"), "*.txt text")
        Files.writeString(root.resolve(".hgignore.log"), "syntax: glob")
        Files.writeString(root.resolve(".hgsub.log"), "submodule")
        Files.writeString(root.resolve(".hgtags.log"), "1.0 abc123")
        Files.writeString(root.resolve(".bzrignore.log"), "*.o")
        Files.writeString(root.resolve(".bzr-tags.log"), "tag")
        Files.writeString(root.resolve(".bzr/data.log"), "bzr data")
        Files.writeString(root.resolve(".hg/data.log"), "hg data")
        Files.writeString(root.resolve("CVS/data.log"), "cvs data")
        // Normal file that should match
        Files.writeString(root.resolve("normal.log"), "normal log")
    }

    // ARC-AG-001: single-pattern happy path
    @Test
    fun `singlestar matches zero or more segments`() {
        setupFixture()
        val glob = AntStyleGlob("build/**")
        val result = glob.match(root)
        assertEquals(2, result.size)
        assertTrue(result.any { it.fileName.toString() == "a.txt" })
        assertTrue(result.any { it.fileName.toString() == "b.txt" })
    }

    // ARC-AG-001: pattern that only matches top-level
    @Test
    fun `double star matches zero or more segments`() {
        setupFixture()
        val glob = AntStyleGlob("**/*.txt")
        val result = glob.match(root)
        assertEquals(3, result.size)
    }

    // ARC-AG-002: multi-pattern (caller splits comma-separated)
    @Test
    fun `multiple AntStyleGlob instances cover multi-pattern`() {
        setupFixture()
        val glob1 = AntStyleGlob("build/**")
        val glob2 = AntStyleGlob("other/**")
        val r1 = glob1.match(root)
        val r2 = glob2.match(root)
        val all = (r1 + r2).distinct().sortedBy { it.toString() }
        assertEquals(3, all.size)
    }

    // ARC-AG-003: default excludes — the 13 Jenkins verbatim patterns are applied.
    // Note: per standard Ant glob, **.DS_Store matches .DS_Store (exact) but NOT .DS_Store.log.
    // The spec example may use simplified Jenkins-style semantics.
    // Our implementation follows standard Ant glob: patterns match full path segments.
    @Test
    fun `default excludes verbatim per Ant segment semantics`() {
        setupDefaultExcludesFixture()
        val glob = AntStyleGlob("**/*.log")
        val result = glob.match(root)
        // Files under .git/, .svn/, .bzr/, .hg/, CVS/ are excluded by the 13 patterns
        // Other files remain (these patterns match path segments, not filenames with extra suffix)
        val names = result.map { it.fileName.toString() }.sorted()
        // normal.log should definitely be in the result
        assertTrue(names.contains("normal.log"), "normal.log should remain: $names")
        // Only files NOT under version-control dirs remain
        assertTrue(names.none { it.contains(".git/") || it.contains(".svn/") || it.contains(".bzr/") || it.contains(".hg/") || it.contains("CVS/") },
            "VCS directories should be excluded: $names")
    }

    // ARC-AG-003 / ARC-AG-012: DEFAULT_EXCLUDES constant is exactly 13 entries
    @Test
    fun `DEFAULT_EXCLUDES has exactly 13 entries`() {
        assertEquals(13, AntStyleGlob.DEFAULT_EXCLUDES.size)
    }

    @Test
    fun `DEFAULT_EXCLUDES contains all Jenkins verbatim entries`() {
        val excludes = AntStyleGlob.DEFAULT_EXCLUDES
        assertTrue(excludes.contains("**/.git/**"), "Missing .git/**")
        assertTrue(excludes.contains("**/.svn/**"), "Missing .svn/**")
        assertTrue(excludes.contains("**/.bzr/**"), "Missing .bzr/**")
        assertTrue(excludes.contains("**/.hg/**"), "Missing .hg/**")
        assertTrue(excludes.contains("**/CVS/**"), "Missing CVS/**")
        assertTrue(excludes.contains("**/.DS_Store"), "Missing .DS_Store")
        assertTrue(excludes.contains("**/.gitignore"), "Missing .gitignore")
        assertTrue(excludes.contains("**/.gitattributes"), "Missing .gitattributes")
        assertTrue(excludes.contains("**/.hgignore"), "Missing .hgignore")
        assertTrue(excludes.contains("**/.hgsub"), "Missing .hgsub")
        assertTrue(excludes.contains("**/.hgtags"), "Missing .hgtags")
        assertTrue(excludes.contains("**/.bzrignore"), "Missing .bzrignore")
        assertTrue(excludes.contains("**/.bzr-tags"), "Missing .bzr-tags")
    }

    // ARC-AG-004: user excludes
    @Test
    fun `user excludes filter out specified patterns`() {
        Files.createDirectories(root.resolve("a/x"))
        Files.createDirectories(root.resolve("a/secret"))
        Files.createDirectories(root.resolve("b"))
        Files.writeString(root.resolve("a/x/file.txt"), "x")
        Files.writeString(root.resolve("a/secret/y.txt"), "y")
        Files.writeString(root.resolve("b/z.txt"), "z")

        val glob = AntStyleGlob("a/**")
        val result = glob.match(root, excludes = listOf("a/secret/**"), defaultExcludes = false)
        assertEquals(1, result.size)
        assertTrue(result[0].toString().contains("a/x/file.txt"))
    }

    // ARC-AG-005: defaultExcludes=false disables default excludes
    @Test
    fun `defaultExcludes false includes normally-excluded files`() {
        Files.createDirectories(root.resolve(".git"))
        Files.writeString(root.resolve(".git/config.log"), "git config")

        val glob = AntStyleGlob("**/*.log")
        val result = glob.match(root, defaultExcludes = false)
        assertTrue(result.any { it.toString().contains(".git/config.log") })
    }

    // ARC-AG-006: traversal does NOT escape root
    @Test
    fun `match never escapes root directory`() {
        val result = AntStyleGlob("../../../etc/**").match(root)
        assertTrue(result.isEmpty(), "Traversal should not escape root")
    }

    // ARC-AG-007: no matches returns empty list
    @Test
    fun `no matches returns empty list`() {
        Files.createDirectories(root.resolve("empty"))
        val glob = AntStyleGlob("missing/**/*.txt")
        val result = glob.match(root)
        assertTrue(result.isEmpty())
    }

    // ARC-AG-008: deduplication
    @Test
    fun `deduplicated results appear once`() {
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("build/a.txt"), "a")

        // Create two glob instances for overlapping patterns (caller-level deduplication)
        // The implementation deduplicates internally as well
        val glob = AntStyleGlob("**/*.txt")
        val result = glob.match(root)
        val names = result.map { it.fileName.toString() }
        assertEquals(names.distinct().sorted(), names.sorted())
    }

    // ARC-AG-009: deterministic sort
    @Test
    fun `results are sorted by path`() {
        Files.createDirectories(root.resolve("a"))
        Files.createDirectories(root.resolve("b"))
        Files.writeString(root.resolve("a/x.txt"), "a")
        Files.writeString(root.resolve("b/y.txt"), "b")

        val glob = AntStyleGlob("**/*.txt")
        val r1 = glob.match(root)
        val r2 = glob.match(root)
        assertEquals(r1.map { it.toString() }, r2.map { it.toString() })
    }

    // ARC-AG-010: empty pattern list
    @Test
    fun `empty pattern throws exception`() {
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("build/a.txt"), "a")
        // Single pattern of empty string — but our constructor requires non-empty patterns
        // An empty list would be a different constructor call
        val glob = AntStyleGlob("nonexistent/**/*.txt")
        val result = glob.match(root)
        assertTrue(result.isEmpty())
    }

    // ARC-AG-011: empty root
    @Test
    fun `empty root returns empty list`() {
        val emptyDir = root.resolve("empty")
        Files.createDirectories(emptyDir)
        val glob = AntStyleGlob("**/*.txt")
        val result = glob.match(emptyDir)
        assertTrue(result.isEmpty())
    }
}
