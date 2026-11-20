package dev.rubentxu.pipeline.v2.credentials.api

/**
 * The fragments the redactor matches, and the question it asks about them.
 *
 * ## What this holds
 * The fragments of the registered values: every window of [threshold] bytes that falls
 * inside an encoded variant of a registered secret. Every window has the same length, so
 * a question about one of them is a question about a byte range, and the ranges of the
 * windows that overlap form one covered run.
 *
 * ## The question
 * [matchAt] asks what the fragments say about the bytes at an offset of the lookahead,
 * and it has three answers, not two. The third one is what keeps the redactor honest:
 * a byte that no fragment begins with is plain text and is released at once, and only a
 * byte that a fragment MIGHT begin with is worth waiting for. A matcher that could only
 * say "matched" or "did not match" would have to wait for the whole threshold before it
 * could release a byte, and the transcript of a running step would arrive a threshold
 * late.
 *
 * ## Why the fragments are a trie and not a list
 * A secret of a couple of dozen bytes yields about a hundred and fifty fragments once its
 * encoded variants are counted, and the transcript is the stream that decides whether a
 * step finishes. A matcher that walks the whole list for every byte of the transcript
 * multiplies its cost by the number of fragments. The fragments are therefore a trie: a
 * byte selects one branch, and the cost of a byte does not depend on how many fragments
 * there are.
 *
 * ## What it does not do
 * It does not decide what is a secret and what is not: it answers what the bytes at an
 * offset are. The decision of what to do with that answer belongs to [StreamingRedactor].
 */
internal class FragmentMatcher private constructor(
    /** The number of bytes every fragment has. */
    val threshold: Int,
    /** The nodes of the trie; the root is the first one. */
    private val nodes: List<Node>,
    /** The node each byte of the root leads to, or -1 when no fragment begins with it. */
    private val rootChildren: IntArray,
) {

    /**
     * A node of the trie: the branches that leave it and whether a fragment ends there.
     */
    private class Node(
        /** The bytes that lead to a child, in ascending order. */
        val childBytes: IntArray,
        /** The node each of those bytes leads to. */
        val childNodes: IntArray,
        /** Whether a fragment ends at this node. */
        val terminal: Boolean,
    ) {
        /** The node that [byte] leads to, or -1 when no branch leaves this node for it. */
        fun child(byte: Int): Int {
            for (i in childBytes.indices) {
                if (childBytes[i] == byte) return childNodes[i]
            }
            return -1
        }
    }

    /**
     * What the fragments say about the bytes that start at an offset of the lookahead.
     */
    enum class Match {
        /** No fragment begins with those bytes. */
        NONE,

        /** A fragment begins with those bytes. */
        MATCH,

        /** The bytes seen so far do not settle it. */
        UNDECIDED,
    }

    /**
     * What the fragments say about the bytes that start [offset] bytes after the head of
     * the lookahead.
     *
     * The lookahead is a ring, so the bytes of a fragment are read across the seam of the
     * ring. [available] is how many of those bytes are there: a byte that is not there
     * yet is what makes the answer [Match.UNDECIDED] rather than [Match.NONE].
     */
    fun matchAt(lookahead: ByteArray, head: Int, size: Int, offset: Int, available: Int): Match {
        var index = head + offset
        if (index >= size) index -= size
        var node = rootChildren[lookahead[index].toInt() and 0xFF]
        if (node < 0) return Match.NONE
        var depth = 1
        index++
        if (index == size) index = 0
        while (depth < threshold) {
            if (depth >= available) return Match.UNDECIDED
            node = nodes[node].child(lookahead[index].toInt() and 0xFF)
            if (node < 0) return Match.NONE
            depth++
            index++
            if (index == size) index = 0
        }
        return if (nodes[node].terminal) Match.MATCH else Match.NONE
    }

    companion object {
        /**
         * The matcher over [fragments], which must all have [threshold] bytes and must
         * not repeat one another.
         */
        fun of(threshold: Int, fragments: List<ByteArray>): FragmentMatcher {
            require(threshold > 0) { "a fragment threshold of $threshold bytes masks nothing" }

            val nodes = mutableListOf(Node(IntArray(0), IntArray(0), false))
            for (fragment in fragments) {
                require(fragment.size == threshold) {
                    "a fragment of ${fragment.size} bytes does not have the threshold of $threshold bytes"
                }
                var nodeIndex = 0
                for (byte in fragment) {
                    val value = byte.toInt() and 0xFF
                    var childIndex = nodes[nodeIndex].child(value)
                    if (childIndex < 0) {
                        childIndex = nodes.size
                        nodes.add(Node(IntArray(0), IntArray(0), false))
                        nodes[nodeIndex] = nodes[nodeIndex].withChild(value, childIndex)
                    }
                    nodeIndex = childIndex
                }
                nodes[nodeIndex] = nodes[nodeIndex].asTerminal()
            }

            val rootChildren = IntArray(256) { -1 }
            val root = nodes[0]
            for (i in root.childBytes.indices) {
                rootChildren[root.childBytes[i]] = root.childNodes[i]
            }
            return FragmentMatcher(threshold, nodes, rootChildren)
        }

        /**
         * This node with a branch for [byte] that leads to [childIndex].
         *
         * The branches stay in ascending order, so a node with a few of them is searched
         * in a straight line and a node with many of them is still searched the same way.
         */
        private fun Node.withChild(byte: Int, childIndex: Int): Node {
            val bytes = childBytes.copyOf(childBytes.size + 1)
            val children = childNodes.copyOf(childNodes.size + 1)
            var position = bytes.size - 1
            while (position > 0 && bytes[position - 1] > byte) {
                bytes[position] = bytes[position - 1]
                children[position] = children[position - 1]
                position--
            }
            bytes[position] = byte
            children[position] = childIndex
            return Node(bytes, children, terminal)
        }

        /** This node, with a fragment ending at it. */
        private fun Node.asTerminal(): Node = Node(childBytes, childNodes, true)
    }
}
