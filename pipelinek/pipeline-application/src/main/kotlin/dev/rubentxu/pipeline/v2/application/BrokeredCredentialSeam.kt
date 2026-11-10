package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKindUnsupportedException
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import java.nio.file.Path

/**
 * The directory of the broker this run was given, or null when it was given none.
 *
 * The flag travels as text because that is what an argv holds; the run reads it as a path in ONE place.
 */
internal fun CliFlags.brokerDir(): Path? = credentialBrokerPath?.let(Path::of)

/**
 * The credential seam of a run whose credentials come from a broker.
 *
 * ## What changes against the local store
 *
 * A run that was given a broker never opens the local store: it does not read the
 * environment for a store path, it does not ask for a passphrase and it does not look for
 * the file where the secrets live. It holds the token of its session and nothing else, and
 * it asks the broker for a credential when it enters `withCredentials`. If the broker refuses
 * or cannot be reached, the run does NOT fall back to the store: a fallback would give the
 * run what its client was not granted.
 *
 * ## What stays the same
 *
 * The executor is the one the local store uses, with the same clock, the same registry and the
 * same masking posture, so a secret that arrives through the broker is bound, masked and wiped
 * exactly like one that arrives from the store.
 *
 * ## Why the token is taken even when the run binds nothing
 *
 * The token file is deleted by the first look at it. A run that asks for nothing would
 * otherwise leave the token readable for as long as it lives.
 *
 * @param brokerDirectory the directory of the broker, as the service handed it to the run.
 * @return the seam of the run: no provider and no executor when the run declares no binding.
 */
internal fun composeBrokeredCredentialSeam(
    redactionRegistry: SecretPatternRegistry?,
    clock: Clock,
    pipelineSpec: PipelineSpec?,
    maskingStrictness: SecretMaskingStrictness,
    brokerDirectory: Path,
): CredentialSeam {
    val provider = SocketCredentialProvider.inDirectory(brokerDirectory)
    if (pipelineSpec?.declaresCredentialBinding() != true) {
        return CredentialSeam(provider = null, executor = null)
    }
    val executor = WithCredentialsExecutor(
        provider,
        NoFileMaterialization,
        clock,
        redactionRegistry,
        maskingStrictness = resolveRunMaskingStrictness(maskingStrictness),
    )
    return CredentialSeam(provider = provider, executor = executor)
}

/**
 * The materialization of a run whose credentials come from a broker: it projects no file.
 *
 * The broker carries secret texts, which travel as bytes in the environment of a step. A
 * credential that would need a file on disk is refused by name, so the run never writes the
 * material of a secret where it could outlive the run.
 */
internal object NoFileMaterialization : CredentialMaterialization {

    override fun materialize(credential: Credential, kind: MaterializationKind): MaterializedCredential =
        throw MaterializationKindUnsupportedException(
            credentialsId = credential.id,
            actualKind = kind::class.simpleName ?: "unknown",
            supportedKinds = emptyList(),
        )

    override fun close() = Unit
}
