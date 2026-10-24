package dev.rubentxu.pipeline.v2.application

import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Where the native library of the SQLite driver is allowed to land.
 *
 * ## The guarantee
 * The library lands in a directory that exists, and the directory is answerable to somebody:
 * the operator's when they named one, and the product's when they did not. A run never ends with
 * a megabyte of native code loose in the working directory, where a step's own files live and
 * where nobody can tell whose the library is.
 *
 * ## The failures this covers
 * Two shapes, measured against the product rather than imagined.
 *
 * The first is a value that names nothing. The property was read with a null check, so an EMPTY
 * value counted as "the operator chose this" — and an empty value names no directory at all. The
 * driver resolved it against the working directory, and the library landed there: measured live,
 * a megabyte and a lock file in the directory the pipeline was running in.
 *
 * The second is a name that is not there yet. The driver creates no directories of its own, so
 * the extraction failed, and the run died saying the DATABASE was not readable — which sent the
 * operator to look at a database that was perfectly fine, because the thing that was missing was
 * the native library.
 *
 * ## Why the instrument is this cheap
 * The decision is a function of two facts — what the operator named, and whether that name is a
 * directory — and the answer is a value with three cases. Nothing here needs a disk, a process
 * or a clock, so the cheapest thing that can fail is a direct call with a value.
 */
class SqliteNativeLibraryHomeTest {

    @Test
    @DisplayName("a value that names nothing is the absence of a choice")
    fun `a blank value is not a choice`() {
        assertNull(operatorNamedDirectory(null), "nobody said anything")
        assertNull(operatorNamedDirectory(""), "an empty value names no directory")
        assertNull(operatorNamedDirectory("   "), "a value of blanks names no directory")
    }

    @Test
    @DisplayName("a name the filesystem cannot spell is the absence of a choice too")
    fun `a name that cannot be spelled is not a choice`() {
        // A NUL in the middle of a name is a name the filesystem refuses, and turning it into a
        // path throws. The product must not die where it is told where to put a library.
        val unspellable = "bad" + 0.toChar() + "name"

        assertThrows(InvalidPathException::class.java) { Path.of(unspellable) }
        assertNull(
            operatorNamedDirectory(unspellable),
            "a name the filesystem refuses cannot be a directory, so it is not a choice",
        )
    }

    @Test
    @DisplayName("a name that names a directory is kept exactly as it was written")
    fun `a name is kept as it was written`() {
        assertEquals(
            Path.of("/var/lib/pipelinek/sqlite"),
            operatorNamedDirectory("/var/lib/pipelinek/sqlite"),
            "the operator's name is the directory the library lands in",
        )
    }

    @Test
    @DisplayName("nobody naming a directory leaves the library to the product")
    fun `no name leaves the library to the product`() {
        assertEquals(
            NativeLibraryHome.Owned,
            decideNativeLibraryHome(named = null, namedIsDirectory = false),
            "with no usable name the library needs a directory that has an owner, and the only " +
                "one that does is the product's own",
        )
    }

    @Test
    @DisplayName("a directory that is there is the operator's")
    fun `a directory that is there is the operator's`() {
        val named = Path.of("/var/lib/pipelinek/sqlite")

        assertEquals(
            NativeLibraryHome.Borrowed(named),
            decideNativeLibraryHome(named = named, namedIsDirectory = true),
            "a directory that exists is used as it is, and nothing of it is removed afterwards",
        )
    }

    @Test
    @DisplayName("a directory that is not there yet is made, because the driver would not make it")
    fun `a directory that is not there is made`() {
        val named = Path.of("/var/lib/pipelinek/sqlite")

        assertEquals(
            NativeLibraryHome.Created(named),
            decideNativeLibraryHome(named = named, namedIsDirectory = false),
            "the operator named a place, so the place is made and their intent is what happens",
        )
    }

    @Test
    @DisplayName("the value that names nothing ends in the product's own directory")
    fun `a blank value ends in the product's own directory`() {
        // The two facts the decision reads, taken the way the product takes them: the value
        // names nothing, so there is no directory to be found.
        val named = operatorNamedDirectory("")

        assertNull(named, "an empty value names no directory")
        assertEquals(
            NativeLibraryHome.Owned,
            decideNativeLibraryHome(named = named, namedIsDirectory = false),
            "an empty value must not send the library to the working directory; it sends it to a " +
                "directory that has an owner",
        )
    }
}
