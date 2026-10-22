package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SQLite driver extracts its native library somewhere, and it should be
 * somewhere with an owner.
 *
 * The driver reads the system property `org.sqlite.tmpdir` when it extracts
 * the library, and falls back to the shared temporary directory when that
 * property is absent. Measured, with the JVM alive: without the property a
 * run leaves a pair of files loose in the root of the system temporary
 * directory; with it, the pair lands in a directory this process owns.
 *
 * These rows check the decision this process makes about it: which directory
 * the driver is pointed at, and what it refuses to do.
 */
class SqliteNativeLibraryHomeTest {

    private var previous: String? = null

    @BeforeEach
    fun rememberTheProperty() {
        previous = System.getProperty(SqliteNativeLibraryHome.PROPERTY)
        System.clearProperty(SqliteNativeLibraryHome.PROPERTY)
    }

    @AfterEach
    fun restoreTheProperty() {
        if (previous == null) {
            System.clearProperty(SqliteNativeLibraryHome.PROPERTY)
        } else {
            System.setProperty(SqliteNativeLibraryHome.PROPERTY, previous)
        }
    }

    @Test
    @DisplayName("the driver is pointed at a directory that exists")
    fun theDriverIsPointedAtADirectoryThatExists() {
        val home = SqliteNativeLibraryHome.configure()

        requireNotNull(home) { "a directory must have been chosen" }
        assertTrue(
            Files.isDirectory(home),
            "the driver would fail to extract into a directory that does not exist: $home",
        )
        assertEquals(
            home.toString(),
            System.getProperty(SqliteNativeLibraryHome.PROPERTY),
            "the driver reads the property, so the property must name the directory",
        )
    }

    @Test
    @DisplayName("a directory the operator chose is not overwritten")
    fun aDirectoryTheOperatorChoseIsNotOverwritten() {
        // A path, not a directory: the decision under test is whether this
        // process replaces what somebody else pinned, and that does not need
        // the path to exist — so this row leaves nothing behind.
        val chosen = Path.of(System.getProperty("java.io.tmpdir"), "operator-chosen")
        System.setProperty(SqliteNativeLibraryHome.PROPERTY, chosen.toString())

        val home = SqliteNativeLibraryHome.configure()

        assertNull(home, "an operator's choice is not this process's to replace")
        assertEquals(
            chosen.toString(),
            System.getProperty(SqliteNativeLibraryHome.PROPERTY),
            "the property must keep naming what the operator asked for",
        )
    }

    @Test
    @DisplayName("the directory it creates is named after the product")
    fun theDirectoryItCreatesIsNamedAfterTheProduct() {
        val home = requireNotNull(SqliteNativeLibraryHome.configure()) {
            "a directory must have been chosen"
        }

        assertTrue(
            home.fileName.toString().startsWith(SqliteNativeLibraryHome.DIRECTORY_PREFIX),
            "a temporary directory with an owner should say whose it is: $home",
        )
    }
}
