package dev.rubentxu.pipeline.v2.application

/**
 * The ONE minimal JSON reader of this module.
 *
 * ## Why it exists
 * Three files used to carry their own hand-written cursor: the operator trust file, the durable
 * admission control row, and the publisher's detached signature. Three readers
 * for one grammar is three answers to "what does this document say", and they already disagreed:
 * one of them could not read a number at all, another read a number but not an escape sequence.
 * A file this runtime reads is a file whose meaning must not depend on which parser happened to
 * read it.
 *
 * ## What it is, and what it is not
 * A cursor over objects, arrays, strings, numbers, booleans and null. Nothing else: no comments,
 * no trailing commas, no streaming. The documents this runtime reads are small, contractual, and
 * written by an operator or by a publisher — not by an untrusted stream, so a full JSON
 * implementation would be a dependency bought for nothing.
 *
 * ## Why a malformed document is a value and not an exception
 * Every caller here reads a document it did not author: an operator's trust file, a publisher's
 * signature, a row this runtime itself wrote. A parser that throws turns a typo in somebody
 * else's file into a stack trace from inside the product, and the caller cannot tell "the
 * document says no" from "the reader is broken". [parseObject] therefore answers `null` for a
 * document it cannot read, and the caller decides what that means — for the trust file, a
 * document that cannot be read is an empty trust database, which refuses everything (fail closed);
 * for the signature, a document that cannot be read is a missing signature.
 *
 * ## Why `null` is a value and not a parse error
 * A JSON document may legitimately contain a null member, and this runtime writes one: the durable
 * admission row carries `"refusedKind": null` for an admitted plugin. A reader that answered
 * "malformed" for a null member could not read back the rows it writes. The cursor therefore
 * signals malformed input with a private exception and converts it into `null` at the boundary,
 * so a null member and a broken document stay different facts all the way out.
 */
internal object MinimalJson {

    /**
     * Read the whole document as a JSON object.
     *
     * @return the object's members, or `null` when the document is not a JSON object this reader
     *   can read. An empty object is an empty map, not `null`: the two are different facts.
     */
    fun parseObject(text: String): Map<String, Any?>? = try {
        Cursor(text).readObject()
    } catch (e: MalformedJson) {
        null
    }

    /** Internal signal that the document could not be read. Never leaves this file. */
    private class MalformedJson : Exception()

    private class Cursor(private val source: String) {
        private var pos = 0

        fun readObject(): Map<String, Any?> {
            skipWs()
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (consume('}')) return out
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                val value = readValue()
                out[key] = value
                skipWs()
                when {
                    consume(',') -> continue
                    consume('}') -> return out
                    else -> throw MalformedJson()
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            skipWs()
            if (consume(']')) return out
            while (true) {
                val value = readValue()
                out.add(value)
                skipWs()
                when {
                    consume(',') -> continue
                    consume(']') -> return out
                    else -> throw MalformedJson()
                }
            }
        }

        private fun readValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < source.length && isNumberChar(source[pos])) pos++
            if (pos == start) throw MalformedJson()
            val text = source.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw MalformedJson()
        }

        private fun isNumberChar(c: Char): Boolean =
            c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+'

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!source.startsWith(literal, pos)) throw MalformedJson()
            pos += literal.length
            return value
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < source.length) {
                when (val c = source[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= source.length) throw MalformedJson()
                        when (source[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > source.length) throw MalformedJson()
                                val hex = source.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw MalformedJson()
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw MalformedJson()
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw MalformedJson()
        }

        private fun peek(): Char? = if (pos < source.length) source[pos] else null

        private fun expect(c: Char) {
            if (!consume(c)) throw MalformedJson()
        }

        private fun consume(c: Char): Boolean {
            if (pos < source.length && source[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun skipWs() {
            while (pos < source.length && source[pos].isWhitespace()) pos++
        }
    }
}
