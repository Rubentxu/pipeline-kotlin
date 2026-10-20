package dev.rubentxu.pipeline.v2.domain

import org.jetbrains.annotations.NotNull

/**
 * Typed boundary carrier for credentials-by-ID references.
 *
 * ## Role in L1 Structural Redaction
 *
 * [CredentialsRef] is the SOLE mechanism for passing credentials across
 * module boundaries. It carries only the [CredentialsId] - never the
 * secret value. This ensures that at the type level, a credentials reference
 * cannot accidentally be substituted with secret bytes.
 *
 * ## Architectural Constraint
 *
 * No function or event field should ever carry `Map<String, String>` or
 * raw secret bytes across module boundaries. Instead, they carry
 * [CredentialsRef] which is resolved to [SecretHandle] only at the
 * [CredentialScope.env] call site (the single coercion choke).
 *
 * ## Usage
 *
 * ```
 * val credentialsId = CredentialsId("github-token")
 * val ref = CredentialsRef(credentialsId)
 * // ref crosses module boundary - no secret bytes involved
 * ```
 *
 * @see CredentialsId for the ID type
 * @see SecretHandle for the secret value channel
 * @see dev.rubentxu.pipeline.v2.credentials.api.CredentialScope for the scope that resolves refs
 */
@JvmInline
value class CredentialsRef(@NotNull val id: CredentialsId)
