package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.credentials.multipart.LocalFileMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialMaterialization
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import java.nio.file.Path

/**
 * Compose the credential injection stack ONCE, shared by the durable (--db)
 * and in-memory (default `pipeline run`) branches.
 *
 * T12: the store location comes from [CredentialsStorePath], the
 * ONE authority: PIPELINE_CREDENTIALS_STORE if set, else ~/.pipeline/credentials.bin.
 * PIPELINE_STORE_PASSPHRASE supplies the passphrase. A run never asks for one:
 * the passphrase is read from the environment or the run refuses, because a run
 * that waits for a human waits forever where runs usually happen. The command
 * that manages the store is the interactive one, and it asks.
 *
 * This decision is written ONCE: the in-memory branch and the durable branch call
 * this same function, hand it the run's clock, and get the same store. Two copies
 * of a decision are two chances to disagree, and the disagreement would be invisible.
 * One function, one clock, one store.
 *
 * Behavior:
 * - The run declares no credential binding -> null (no store is opened, and no
 *   passphrase is asked for: a run that wants no secret does not have to unlock
 *   the place where secrets live)
 * - The run declares a binding and the store file does not exist -> null (the
 *   user runs `pipeline credentials add` first; no error at startup)
 * - The run declares a binding, the store file exists and the passphrase is
 *   available -> provider and executor
 * - The run declares a binding, the store file exists and the passphrase is
 *   wrong/missing -> fail fast with actionable error
 *
 * @param pipelineSpec the declaration of the run, which says whether it binds a
 *   credential. The need for the store is a fact about the RUN, and asking the
 *   machine whether its store file exists answered a different question: it made
 *   every run on a machine that had ever stored a secret ask for a passphrase,
 *   including the runs that bind nothing. The caller therefore hands over the
 *   declaration and lets this function decide.
 * @param credentialBroker the directory of the broker the run was given, or null when it was
 *   given none. With a broker the run asks it for each credential when it enters
 *   `withCredentials` and never opens the local store; without one it behaves as described above.
 */
internal fun composeCredentialSeam(
    redactionRegistry: SecretPatternRegistry?,
    clock: Clock,
    pipelineSpec: PipelineSpec?,
    maskingStrictness: SecretMaskingStrictness,
    credentialBroker: Path?,
): CredentialSeam {
    // A run that was given a broker gets its credentials from it and from nowhere else: the
    // branch below never reads the environment, never looks for the store and never asks for a
    // passphrase, and it is decided BEFORE any of that so that it cannot be reached by accident.
    if (credentialBroker != null) {
        return composeBrokeredCredentialSeam(
            redactionRegistry,
            clock,
            pipelineSpec,
            maskingStrictness,
            credentialBroker,
        )
    }

    // [controlDirRoot] is deliberately NOT a parameter of this function. A control
    // root would put the store under the run's own directory, and the documented
    // `credentials add` -> `run` flow could not find the store it had just written.
    // Its absence from the signature is what makes that impossible to reintroduce by
    // accident; see CredentialsStorePath's KDoc.
    val credentialsStorePath: Path = CredentialsStorePath.fromEnvironment(System.getenv())

    val storeNeeded = pipelineSpec?.declaresCredentialBinding() == true
    if (!storeNeeded || !credentialsStorePath.toFile().exists()) {
        return CredentialSeam(provider = null, executor = null)
    }

    // THE POSTURE OF THE RUN, ONCE. The branch asks for the posture it needs — the durable
    // branch persists the transcript, so a secret it cannot mask becomes a secret on disk —
    // and the operator may move it in either direction with one variable. A variable that
    // names a posture this product does not have is a decision that cannot be honoured, and
    // guessing what the operator meant is not honouring it: the run is refused instead.
    val strictness = resolveRunMaskingStrictness(maskingStrictness)

    val secretStore: SecretStore = try {
        val passphraseChars = PassphraseResolver.resolve()
        // The passphrase is credential material: it opens the store that holds every secret.
        // Registering it means that IF it ever reaches an output, a log or an error message, the
        // redactor masks it instead of printing it in clear. The registry keeps its own copy, so
        // this does not disturb the CharArray the store is about to take ownership of.
        //
        // A passphrase shorter than the registry's maskable floor is not registered. That is the
        // registry's deliberate trade-off (a short literal collides with ordinary text), and it
        // means a very short passphrase is not covered by redaction.
        redactionRegistry?.addSecretValue(String(passphraseChars))
        // Do NOT wipe passphraseChars here — LocalSecretStore stores the
        // same CharArray reference and would lose its own copy. The store
        // zeros the passphrase in its own close().
        LocalSecretStore(credentialsStorePath, passphraseChars)
    } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
        // A run does not offer an interactive path: a run that waited for a
        // human to type the passphrase would wait forever in the place runs
        // usually happen, and a run triggered by a scheduler has nobody to
        // ask. The resolver therefore refused without a reader, and its
        // diagnostic names the environment variable and nothing else — which
        // is exactly the alternative this caller has.
        System.err.println("Error: ${e.message}")
        // The refusal UNWINDS instead of ending the process: a branch that owns a lease and an
        // event store has to give them back before the process ends, and ending here would skip
        // that release. The branch that owns nothing turns the refusal into the exit.
        throw CommandRefused(CliExit.IncompatibleVersion(message = "the credential store could not be opened: no passphrase is available"))
    } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
        System.err.println("Error: $e.message")
        System.err.println("Hint: check PIPELINE_STORE_PASSPHRASE.")
        throw CommandRefused(CliExit.IncompatibleVersion(message = "the credential store could not be unlocked: the passphrase did not match"))
    } catch (e: LocalSecretStore.SecretStoreTamperException) {
        System.err.println("Error: credentials store tampered: ${e.message}")
        throw CommandRefused(CliExit.RuntimeNotAvailable(message = "the credential store did not pass its integrity check"))
    }

    // Composition root: construct credential ports and executor
    // LocalSecretStore (existing) -> LocalCredentialProvider -> CredentialMaterializer
    // -> LocalFileMaterialization -> WithCredentialsExecutor
    val provider: CredentialProvider = LocalCredentialProvider(secretStore)
    val materialization: CredentialMaterialization = LocalFileMaterialization(CredentialMaterializer(secretStore))
    val executor = WithCredentialsExecutor(
        provider,
        materialization,
        clock,
        redactionRegistry,
        maskingStrictness = strictness,
    )
    // The process opened the store and wrote the credential material to disk, so
    // the process is what gives both back. The materializer removes the files
    // it wrote and the provider closes the store; without this the material of
    // every run stayed on disk under the shared temporary directory, which is
    // where a secret outlives the run that needed it.
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                closeQuietly(materialization)
                closeQuietly(provider)
            },
            "pipelinek-credentials-cleanup",
        ),
    )
    return CredentialSeam(provider = provider, executor = executor)
}

/**
 * Release a resource on the way out, ignoring whatever it throws.
 *
 * A failure while releasing must not turn a successful run into a failed one,
 * so the exception is swallowed on this path.
 */
private fun closeQuietly(resource: AutoCloseable) {
    try {
        resource.close()
    } catch (_: Exception) {
        // Shutdown path.
    }
}

/**
 * The masking posture this run applies, read from the environment of the process.
 *
 * Both ways a run gets its credentials ask this, so the posture is decided in ONE place.
 *
 * @throws CommandRefused when the variable names a posture this product does not have.
 */
internal fun resolveRunMaskingStrictness(requested: SecretMaskingStrictness): SecretMaskingStrictness =
    resolveMaskingStrictness(
        requested = requested,
        declared = System.getenv(SecretMaskingStrictness.ENVIRONMENT_VARIABLE),
    ) ?: throw CommandRefused(
        CliExit.InvalidArgument(
            message = "the masking posture named by ${SecretMaskingStrictness.ENVIRONMENT_VARIABLE} " +
                "cannot be read: the postures are '${SecretMaskingStrictness.REQUIRED}' and " +
                "'${SecretMaskingStrictness.BEST_EFFORT}'",
        ),
    )

/**
 * The masking posture this run applies.
 *
 * The branch asks for the posture it needs and the operator may move it with one
 * variable, so the two decisions meet in ONE place instead of one overriding the
 * other somewhere else.
 *
 * A variable that names a posture this product does not have is a decision that
 * cannot be honoured, and reading it as the nearest posture it resembles would be
 * guessing with a security switch. So the answer is `null` and the caller refuses
 * the run.
 *
 * @param requested the posture the branch asks for.
 * @param declared what the operator wrote, or `null` when they wrote nothing.
 * @return the posture to apply, or `null` when the declaration cannot be read.
 */
internal fun resolveMaskingStrictness(
    requested: SecretMaskingStrictness,
    declared: String?,
): SecretMaskingStrictness? =
    if (declared.isNullOrBlank()) {
        requested
    } else {
        SecretMaskingStrictness.fromDeclaration(declared)
    }
