package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import dev.rubentxu.pipeline.v2.credentials.executor.SpiCredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.LocalFileMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
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
 * ## The files it projects
 *
 * The SAME materializer the local store uses, and the linked secrets resolved through the
 * SAME provider -- the broker of this run. A private key is a file and a keystore is a file,
 * and a run whose credentials are held by a controller has to be able to use them: the
 * material is written with the same permissions, in the same place, and is wiped the same way.
 *
 * What the run may NOT have is a secret the controller did not hand over: the passphrase of a
 * key is resolved through the broker, so a controller that does not hold it produces a refusal
 * and the binding fails before the step runs. There is no second place where the passphrase
 * could be found.
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
    val materialization: CredentialMaterialization = LocalFileMaterialization(
        CredentialMaterializer(SpiCredentialLinkedSecretResolver(provider)),
    )
    val executor = WithCredentialsExecutor(
        provider,
        materialization,
        clock,
        redactionRegistry,
        maskingStrictness = resolveRunMaskingStrictness(maskingStrictness),
    )
    // The process wrote the credential material to disk, so the process is what gives it back.
    // Without this the material of a run that ended would outlive it under the shared temporary
    // directory, which is where a secret outlives the run that needed it.
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                closeMaterialization(materialization)
                closeQuietly(provider)
            },
            "pipelinek-brokered-credentials-cleanup",
        ),
    )
    return CredentialSeam(provider = provider, executor = executor)
}
