package dev.rubentxu.pipeline.v2.release

/**
 * One incompatibility between the surface the previous release published and
 * the surface the candidate publishes.
 *
 * A break is what a consumer compiled against the previous artifact would
 * fail to find. It is named by a REMOVAL KEY: the artifact's own spelling of
 * what is gone, so the key can be looked up in the exception ledger without
 * anyone having to translate between two vocabularies.
 */
sealed interface PublishedApiBreak {

    /** The key that names this break in the exception ledger. */
    val removalKey: String

    /** A one-line, operator-facing description of what a consumer would lose. */
    fun describe(): String

    /**
     * A public declaration the candidate no longer publishes.
     *
     * The declaration's own members are part of it: a consumer that linked
     * `Outer$Nested` fails with `NoClassDefFoundError` before any member is
     * consulted, so reporting the members separately would be noise.
     */
    data class DeclarationRemoved(val declaration: String) : PublishedApiBreak {
        override val removalKey: String get() = declaration

        override fun describe(): String =
            "the public declaration $declaration is no longer published"
    }

    /**
     * A member of a surviving declaration that the candidate no longer
     * publishes with the same erased descriptor.
     *
     * A changed parameter list is this and not a separate kind: the old line
     * is gone, and the old line is what the consumer linked.
     *
     * The key carries the erased descriptor, not only the member's name:
     * two constructors of one declaration share the name `<init>`, and a key
     * built from the name alone would let an approval written for one of
     * them authorize the removal of the other.
     */
    data class MemberRemoved(
        val declaration: String,
        val member: String,
        val memberLine: String,
    ) : PublishedApiBreak {
        override val removalKey: String
            get() = "$declaration#${PublishedApiSurfaceReader.memberKeyOf(memberLine)}"

        override fun describe(): String =
            "the member $member of $declaration is no longer published with the same signature " +
                "($memberLine)"
    }
}

/**
 * What two published surfaces say about each other.
 */
data class PublishedApiComparison(
    /** The declarations the candidate no longer publishes. */
    val removedDeclarations: List<String>,
    /** The members the candidate no longer publishes with the same signature. */
    val removedMembers: List<PublishedApiBreak.MemberRemoved>,
    /** The declarations the candidate adds. An addition breaks nobody. */
    val addedDeclarations: List<String>,
) {
    /** Every break, declarations before members. */
    val breaks: List<PublishedApiBreak>
        get() = removedDeclarations.map { PublishedApiBreak.DeclarationRemoved(it) } + removedMembers
}

/**
 * What the comparison says about one module, once the ledger has been asked
 * about each break.
 */
sealed interface PublishedApiModuleVerdict {

    /** The module this verdict is about. */
    val module: String

    /** The candidate publishes everything the previous release published. */
    data class Compatible(
        override val module: String,
        val added: List<String>,
    ) : PublishedApiModuleVerdict

    /**
     * Every break the candidate introduces was approved by an entry of the
     * exception ledger. The release may proceed.
     */
    data class ApprovedBreaks(
        override val module: String,
        val authorized: List<PublishedApiBreak>,
    ) : PublishedApiModuleVerdict

    /**
     * At least one break the candidate introduces has no approval. The
     * release must not proceed until the ledger says otherwise.
     */
    data class UnauthorizedBreaks(
        override val module: String,
        val unauthorized: List<PublishedApiBreak>,
    ) : PublishedApiModuleVerdict

    /**
     * The two surfaces could not be compared, so nothing was certified.
     */
    data class NotComparable(
        override val module: String,
        val reason: String,
    ) : PublishedApiModuleVerdict
}

/**
 * Compares two published surfaces and asks the ledger about each break.
 *
 * Pure: it reads surfaces and a ledger, and decides. It never opens a file
 * and never asks a build anything, so the decision the gate acts on can be
 * exercised without an artifact.
 */
object PublishedApiCompatibility {

    /**
     * Compare the previous published surface with the candidate's.
     *
     * Only removals are breaks. An addition is compatible by definition: a
     * consumer compiled against the previous artifact cannot be broken by
     * something that did not exist in it.
     */
    fun compare(
        previous: PublishedApiSurface,
        candidate: PublishedApiSurface,
    ): PublishedApiComparison {
        val removedDeclarations = previous.declarations.keys.filter { it !in candidate }
        val removedMembers = previous.declarations.entries
            .filter { it.key in candidate }
            .flatMap { (declaration, members) ->
                val candidateMembers = candidate.membersOf(declaration)
                members
                    .filter { it !in candidateMembers }
                    .map {
                        PublishedApiBreak.MemberRemoved(
                            declaration = declaration,
                            member = PublishedApiSurfaceReader.memberNameOf(it),
                            memberLine = it,
                        )
                    }
            }
        val addedDeclarations = candidate.declarations.keys.filter { it !in previous }
        return PublishedApiComparison(
            removedDeclarations = removedDeclarations,
            removedMembers = removedMembers,
            addedDeclarations = addedDeclarations,
        )
    }

    /**
     * Decide what one module's comparison means for the publication.
     *
     * [previousVersion] is the version of the artifact the candidate is
     * compared against: it is what decides whether an approval applies, since
     * an approval is written for the consumers of a named version.
     */
    fun decide(
        module: String,
        previousVersion: String,
        previous: PublishedApiSurface,
        candidate: PublishedApiSurface,
        ledger: PublishedContractLedger,
    ): PublishedApiModuleVerdict {
        val comparison = compare(previous, candidate)
        val unauthorized = comparison.breaks.filter {
            ledger.approvalFor(module, previousVersion, it.removalKey) == null
        }
        if (unauthorized.isNotEmpty()) {
            return PublishedApiModuleVerdict.UnauthorizedBreaks(module, unauthorized)
        }
        if (comparison.breaks.isEmpty()) {
            return PublishedApiModuleVerdict.Compatible(module, comparison.addedDeclarations)
        }
        return PublishedApiModuleVerdict.ApprovedBreaks(module, comparison.breaks)
    }
}
