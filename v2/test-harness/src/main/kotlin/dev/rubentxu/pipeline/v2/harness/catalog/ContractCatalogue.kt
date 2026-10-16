package dev.rubentxu.pipeline.v2.harness.catalog

/**
 * Bidirectional contract catalogue: source path → contract → capability.
 *
 * The catalogue is built once at startup and queried by the impact decider. It
 * is intentionally append-only: removing a contract would be a behavioural
 * break (the decider would silently drop obligations), so the API does not
 * expose `remove`. To retire a contract, mark it `RETIRED` at the owner
 * level and let a future migration pass clean it up.
 *
 * The catalogue owns no I/O. The owner is responsible for loading entries
 * from a descriptor (e.g. an HOCON file) at construction time.
 */
class ContractCatalogue private constructor(
    private val entriesByPath: Map<String, ContractEntry>,
    private val entriesByContract: Map<ContractId, ContractEntry>,
    private val entriesByCapability: Map<CapabilityId, Set<ContractId>>,
) {
    /** Total number of contracts. The catalogue must never be empty in production. */
    val size: Int get() = entriesByContract.size

    /** All contract entries, in deterministic insertion order. */
    fun all(): List<ContractEntry> = entriesByContract.values.toList()

    /** All contracts protecting [capability]. */
    fun contractsFor(capability: CapabilityId): Set<ContractId> =
        entriesByCapability[capability] ?: emptySet()

    /** All capabilities protected by at least one contract. */
    fun capabilities(): Set<CapabilityId> = entriesByCapability.keys

    /**
     * Resolve the contract entry whose `sourcePatterns` match [path]. A path
     * that matches more than one contract is a `CatalogueGap`: the catalogue
     * must not silently pick one. Returns `null` when no contract claims the
     * path; the decider turns that into a refusal for production paths.
     */
    fun lookupByPath(path: String): MatchResult = when {
        entriesByPath[path] != null -> MatchResult.Single(entriesByPath.getValue(path))
        else -> {
            val matches = entriesByContract.values.filter { entry ->
                entry.sourcePatterns.any { globMatches(it, path) }
            }
            when {
                matches.isEmpty() -> MatchResult.None
                matches.size == 1 -> MatchResult.Single(matches.single())
                else -> MatchResult.Ambiguous(matches.toSet(), path)
            }
        }
    }

    /** True iff a contract with this id exists. */
    fun contains(contract: ContractId): Boolean = entriesByContract.containsKey(contract)

    /** Returns the contract entry for [contract], or null if it does not exist. */
    fun entryFor(contract: ContractId): ContractEntry? = entriesByContract[contract]

    sealed interface MatchResult {
        data class Single(val entry: ContractEntry) : MatchResult
        data object None : MatchResult
        data class Ambiguous(val entries: Set<ContractEntry>, val path: String) : MatchResult {
            init {
                require(entries.size > 1) { "Ambiguous must have > 1 entry" }
            }
        }
    }

    companion object {
        /**
         * Build a catalogue from a list of entries. Validates the bidirectional
         * invariants:
         *  - capability → contracts is a function (no capability maps to two
         *    different contracts with the same id);
         *  - contract id is unique across the catalogue.
         */
        fun of(entries: List<ContractEntry>): ContractCatalogue {
            require(entries.isNotEmpty()) { "Catalogue must be non-empty" }
            val byContract = HashMap<ContractId, ContractEntry>(entries.size)
            val byCapability = HashMap<CapabilityId, MutableSet<ContractId>>()
            val byPath = HashMap<String, ContractEntry>()
            for (e in entries) {
                require(!byContract.containsKey(e.id)) {
                    "Duplicate contract id: ${e.id}"
                }
                byContract[e.id] = e
                byCapability.getOrPut(e.capability) { LinkedHashSet() }.add(e.id)
                for (pattern in e.sourcePatterns) {
                    require(!byPath.containsKey(pattern)) {
                        "Source pattern '$pattern' is claimed by both ${byPath[pattern]?.id} and ${e.id}"
                    }
                    byPath[pattern] = e
                }
            }
            return ContractCatalogue(
                entriesByPath = byPath,
                entriesByContract = byContract,
                entriesByCapability = byCapability.mapValues { it.value.toSet() },
            )
        }

        /**
         * Minimal glob matcher: `*` matches any sequence of path-safe chars
         * (no `**`, no escaping). The catalogue's patterns are well-known
         * constants, not user input, so this is intentional simplicity.
         */
        internal fun globMatches(pattern: String, path: String): Boolean {
            val regex = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> regex.append("[^/]*")
                    '.', '?', '+', '(', ')', '|', '^', '$', '\\', '[', ']', '{', '}' ->
                        regex.append('\\').append(c)
                    else -> regex.append(c)
                }
            }
            regex.append('$')
            return Regex(regex.toString()).matches(path)
        }
    }
}
