package dev.rubentxu.pipeline.v2.domain.directive

/**
 * The precedence rule that decides which value a gate observes for a variable.
 *
 * This is a decision, not an effect: [resolve] is a total pure function over two
 * maps, so the rule can be tested and reasoned about without a process, an
 * environment block, or a coordinator. Reading the ambient process environment is
 * the caller's job (an adapter), and it arrives here already captured as [host].
 */
sealed interface GateEnvironmentPrecedence {
    /**
     * The stage's declaration wins over a host variable of the same name.
     *
     * The stage's own `environment { env(...) }` block is what the DSL can
     * express, so it is the authority for the values a gate is allowed to see.
     * Letting a host variable win would make the same script evaluate differently
     * on two machines for a reason the author never wrote down.
     */
    data object DeclarationWins : GateEnvironmentPrecedence

    /**
     * [resolve] against this precedence. [host] is the captured ambient
     * environment; only names the stage declares are read from it, so a gate can
     * never observe an arbitrary process variable by guessing its name.
     */
    fun resolve(
        declared: Map<String, String>,
        host: Map<String, String>,
    ): GateContext = when (this) {
        DeclarationWins -> {
            val visibleFromHost = host.filterKeys { key -> key in declared.keys }
            GateContext(values = visibleFromHost + declared)
        }
    }

    companion object {
        val DEFAULT: GateEnvironmentPrecedence = DeclarationWins
    }
}
