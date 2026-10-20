package dev.rubentxu.pipeline.v2.domain

/**
 * Records how a credential is bound to a step at runtime.
 *
 * ## Purpose
 *
 * [BoundPurpose] documents the injection method for audit trail purposes.
 * It is carried in [CredentialBound][dev.rubentxu.pipeline.v2.events.CredentialBound],
 * [CredentialUsed][dev.rubentxu.pipeline.v2.events.CredentialUsed] events.
 * The purpose is informational only - it does not affect security.
 *
 * ## Variants (ML-R6)
 *
 * Maps to Jenkins credentials-binding kinds per JENKINS_FAMILIARITY_CATALOG.md §1.6:
 * - [API_KEY]: Secret text credential — `string` binding → env variable
 * - [USERNAME_PASSWORD]: Username/password pair — `usernamePassword` binding
 * - [SSH_KEY]: SSH private key with optional passphrase — `sshUserPrivateKey` binding
 * - [FILE]: Secret file credential — `file` binding
 * - [CERTIFICATE]: Keystore certificate — `certificate` binding
 * - [ZIP]: ZIP archive credential — `zip` binding
 * - [USERNAME_COLON_PASSWORD]: Colon-joined credentials — `usernameColonPassword` binding
 *
 * ## Deprecation aliases
 *
 * [ENV] is deprecated — renamed to [API_KEY] (semantically equivalent for L4 callers).
 * [VALUE] is deprecated — no direct replacement in ML-R6; reserved for future `returnStdout` interplay.
 */
enum class BoundPurpose {
    /**
     * Secret text credential — maps to `string` binding.
     * Injects via environment variable (pb.environment().putAll).
     */
    API_KEY,

    /**
     * Username/password pair — maps to `usernamePassword` binding.
     */
    USERNAME_PASSWORD,

    /**
     * SSH private key with optional passphrase — maps to `sshUserPrivateKey` binding.
     */
    SSH_KEY,

    /**
     * Secret file credential — maps to `file` binding.
     */
    FILE,

    /**
     * Certificate keystore — maps to `certificate` binding.
     */
    CERTIFICATE,

    /**
     * ZIP archive credential — maps to `zip` binding.
     */
    ZIP,

    /**
     * Colon-joined credentials (user:pass) — maps to `usernameColonPassword` binding.
     */
    USERNAME_COLON_PASSWORD,
    ;

    companion object {

        /**
         * The single boundary where a declared token becomes typed, for a decoder that has to
         * refuse rather than default.
         *
         * [BoundPurpose] has **no `UNKNOWN` member**, deliberately: every variant names a real
         * binding kind, so a catch-all would be a lie about what was bound. That is why
         * `try { valueOf(raw) } catch { API_KEY }` was wrong — it did not degrade to a neutral
         * value, it invented "this credential is an API key" from a string that said nothing of
         * the kind. On `CredentialBound` / `CredentialUsed` that puts a wrong fact on the durable
         * event stream, and an observer cannot distinguish it from a real API-key binding.
         *
         * The map is derived from [entries] rather than written out again: a second list of the
         * same vocabulary is a second authority, and the two drift the first time someone adds a
         * constant. This mirrors `FailureKind.parse`, which exists for the same reason.
         *
         * Returns **null** outside the vocabulary and deliberately does NOT fall back to a
         * variant. The caller decides what an unreadable credential purpose means, because only
         * the caller knows whether it can report a refusal or must keep reading history.
         */
        private val BY_TOKEN: Map<String, BoundPurpose> = entries.associateBy { it.name }

        /** Every token this runtime accepts, for diagnostics on a refused spelling. */
        val supportedTokens: Set<String> = BY_TOKEN.keys

        /** Total over the vocabulary; **null** for anything else. Never defaults. */
        fun parse(token: String): BoundPurpose? = BY_TOKEN[token]
    }
}
