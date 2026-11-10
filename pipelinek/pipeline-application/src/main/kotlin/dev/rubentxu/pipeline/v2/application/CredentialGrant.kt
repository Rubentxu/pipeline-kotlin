package dev.rubentxu.pipeline.v2.application

/**
 * The kinds of credential the broker hands out.
 *
 * ## Why a closed vocabulary
 *
 * Because a kind is a promise about what travels: the broker answers a
 * request for a `string` with the bytes of a secret text, and nothing else
 * travels under that word. A kind the broker does not carry is refused when
 * the operator's file is read, not discovered when a step asks for it.
 *
 * ## What is not here
 *
 * The credentials that are files, certificates or keys. They need a
 * projection the broker does not offer, and adding a word here without that
 * projection would grant something nobody can receive.
 */
internal enum class BrokeredCredentialKind(
    /** The word the operator's file and the broker's wire spell the kind with. */
    val word: String,
) {

    /** A secret text: the bytes of one value, projected into a step's environment. */
    STRING("string"),
    ;

    internal companion object {

        /** The kind spelled [word], or null when the broker carries none such. */
        fun ofWord(word: String): BrokeredCredentialKind? = entries.firstOrNull { it.word == word }

        /** The words the broker carries, for a sentence that tells the operator what to write. */
        val words: List<String> get() = entries.map { it.word }
    }
}

/**
 * One credential an authorization lets its client's runs request, and the
 * kind it may be requested as.
 *
 * ## What it is
 *
 * The pair the operator writes: the name of a credential and the kind of
 * thing the broker may hand out under it. It names a credential and carries
 * no secret, so it may be printed, compared and stored like any identifier.
 *
 * ## Why the id has its own alphabet
 *
 * The id of a credential in a store may be any text that is not blank, but a
 * grant travels in a line of a file that uses `:`, `,` and whitespace as
 * separators. An id outside the alphabet below could not be written in that
 * line without ambiguity, so it cannot be granted; the credential stays in
 * the store and no run of the service can request it.
 */
internal data class CredentialGrant(
    /** The id of the credential in the store. */
    val credentialId: String,
    /** The kind the broker may hand it out as. */
    val kind: BrokeredCredentialKind,
) {

    init {
        require(isNameable(credentialId)) { "a credential id may hold letters, digits, '.', '_' and '-'" }
    }

    internal companion object {

        /**
         * The longest credential id a grant may name.
         *
         * The wire's bound and not a second one: the id in the grant is the
         * id that travels in a request to the broker.
         */
        const val MAX_CREDENTIAL_ID_LENGTH: Int = 128

        /** The characters a granted credential id may hold. */
        private val ID: Regex = Regex("[A-Za-z0-9._-]+")

        /** Whether [id] is a credential id a grant can name. */
        fun isNameable(id: String): Boolean = id.length <= MAX_CREDENTIAL_ID_LENGTH && ID.matches(id)
    }
}
