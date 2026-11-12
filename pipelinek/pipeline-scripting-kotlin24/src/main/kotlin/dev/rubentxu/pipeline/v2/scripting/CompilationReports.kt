package dev.rubentxu.pipeline.v2.scripting

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

/**
 * What the compiler said while it produced a stored artifact, kept with the artifact.
 *
 * ## Why it is kept
 *
 * A reuse skips the compiler, and the compiler is where the diagnostics come from. Without these,
 * a script with a warning compiled "with 1 warning" the first time and, served from the store the
 * next time, with none, although nothing in the script had changed. The store keeps the reports of
 * the compilation that produced the artifact, and a reuse publishes them as that compilation did.
 *
 * ## A report about the script names the script it is read for
 *
 * The key of a stored artifact is the text of the script and what it is compiled against, not the
 * file the text was read from: two files with the same text share one artifact. A report the
 * compiler raised about the compiled script is therefore kept without the name of its file, and
 * [about] gives it the name of the script the reuse compiles. A report about another file keeps
 * that file's name, and a report about no file keeps having none.
 *
 * ## What is not kept
 *
 * The exception a report may carry. It is a live object of the compilation that raised it, and
 * nothing the product publishes reads it.
 */
class CompilationReports private constructor(private val entries: List<Entry>) {

    /**
     * One report, with the name of its file taken out when the file is the compiled script.
     *
     * [report] carries no exception, and no file when [aboutScript] is true.
     */
    private data class Entry(val report: ScriptDiagnostic, val aboutScript: Boolean)

    /** The reports, with every report about the compiled script naming [script]. */
    fun about(script: String?): List<ScriptDiagnostic> = entries.map { entry ->
        if (entry.aboutScript) entry.report.copy(sourcePath = script) else entry.report
    }

    /** The bytes the store keeps; [decode] reads them back. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(FORMAT)
            out.writeInt(entries.size)
            entries.forEach { entry -> out.writeEntry(entry) }
        }
        return bytes.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is CompilationReports && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "CompilationReports(${entries.size})"

    companion object {
        /**
         * The name and version of the encoding. Bytes that do not start with it are not reports, and
         * [decode] refuses them instead of reading them with another version's recipe.
         */
        private const val FORMAT = "pipelinek.compilation-reports/1"

        private const val NO_FILE = 0
        private const val THE_SCRIPT = 1
        private const val ANOTHER_FILE = 2

        /** A compilation that said nothing. */
        val NONE: CompilationReports = CompilationReports(emptyList())

        /**
         * The reports of a compilation of the script whose location is [script].
         *
         * A report whose file is [script] is a report about the compiled script, and it is kept
         * without that name.
         */
        fun of(reports: List<ScriptDiagnostic>, script: String?): CompilationReports =
            CompilationReports(
                reports.map { report ->
                    val aboutScript = script != null && report.sourcePath == script
                    Entry(
                        report.copy(sourcePath = if (aboutScript) null else report.sourcePath, exception = null),
                        aboutScript,
                    )
                },
            )

        /**
         * The reports [encode] wrote into [bytes].
         *
         * @throws IOException when the bytes are not reports of this encoding: another format, a
         *   truncated record, a length longer than what is left, or bytes left over at the end.
         */
        fun decode(bytes: ByteArray): CompilationReports {
            val source = ByteArrayInputStream(bytes)
            val input = DataInputStream(source)
            if (input.readUTF() != FORMAT) throw IOException("not $FORMAT")
            val count = input.readInt()
            if (count < 0) throw IOException("a negative count of reports")
            val entries = List(count) { input.readEntry(source) }
            if (source.available() != 0) throw IOException("bytes left over after the reports")
            return CompilationReports(entries)
        }

        private fun DataOutputStream.writeEntry(entry: Entry) {
            val report = entry.report
            writeUTF(report.severity.name)
            writeInt(report.code)
            writeText(report.message)
            val path = report.sourcePath
            when {
                entry.aboutScript -> writeByte(THE_SCRIPT)
                path == null -> writeByte(NO_FILE)
                else -> {
                    writeByte(ANOTHER_FILE)
                    writeText(path)
                }
            }
            val location = report.location
            writeBoolean(location != null)
            if (location != null) {
                writePosition(location.start)
                writeBoolean(location.end != null)
                location.end?.let { writePosition(it) }
            }
        }

        private fun DataInputStream.readEntry(source: ByteArrayInputStream): Entry {
            val severity = try {
                ScriptDiagnostic.Severity.valueOf(readUTF())
            } catch (unknown: IllegalArgumentException) {
                throw IOException("an unknown severity", unknown)
            }
            val code = readInt()
            val message = readText(source)
            val file = readByte().toInt()
            val (aboutScript, path) = when (file) {
                NO_FILE -> false to null
                THE_SCRIPT -> true to null
                ANOTHER_FILE -> false to readText(source)
                else -> throw IOException("an unknown kind of file: $file")
            }
            val location = if (readBoolean()) {
                val start = readPosition()
                SourceCode.Location(start, if (readBoolean()) readPosition() else null)
            } else {
                null
            }
            return Entry(ScriptDiagnostic(code, message, severity, path, location, null), aboutScript)
        }

        /** Text of any length: `writeUTF` refuses one longer than 65535 bytes, and a message can be. */
        private fun DataOutputStream.writeText(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readText(source: ByteArrayInputStream): String {
            val size = readInt()
            // The length is checked against what is left BEFORE anything is allocated for it: a
            // damaged length would otherwise ask for gigabytes to read a few bytes.
            if (size < 0 || size > source.available()) throw IOException("a text longer than what is left")
            val bytes = ByteArray(size)
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun DataOutputStream.writePosition(position: SourceCode.Position) {
            writeInt(position.line)
            writeInt(position.col)
            writeBoolean(position.absolutePos != null)
            position.absolutePos?.let { writeInt(it) }
        }

        private fun DataInputStream.readPosition(): SourceCode.Position {
            val line = readInt()
            val col = readInt()
            return SourceCode.Position(line, col, if (readBoolean()) readInt() else null)
        }
    }
}
