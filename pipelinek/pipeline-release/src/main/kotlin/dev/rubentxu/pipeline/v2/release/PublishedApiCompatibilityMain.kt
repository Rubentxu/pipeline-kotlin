package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * The interpreter of the second ABI reading: the candidate's published surface
 * against the surface the previous release published.
 *
 * The Gradle task `:pipeline-release:publishedApiCompatibility` shells out to
 * this main, because Gradle cannot call Kotlin project code directly. The
 * classpath is `:pipeline-release`'s own main runtime classpath, so the
 * decision runs as compiled project code and not as a copy.
 *
 * ```text
 * usage: published-api-compatibility <candidateDumpDir> <previousDumpDir> \
 *            <candidateVersion> <previousVersion> <ledgerPath> <module>...
 * exit  0 = every module compatible, 1 = an unauthorized break, 2 = not comparable
 * ```
 *
 * Exit code 2 is a refusal and not a technicality: a comparison that did not
 * happen certifies nothing, and a publication gate that treats "I could not
 * look" as "I looked and it was fine" is the gate this task exists to close.
 */
fun main(args: Array<String>) {
    if (args.size < 6) {
        System.err.println(
            "usage: published-api-compatibility <candidateDumpDir> <previousDumpDir> " +
                "<candidateVersion> <previousVersion> <ledgerPath> <module> [...]",
        )
        exitProcess(2)
    }
    val candidateDir = Paths.get(args[0])
    val previousDir = Paths.get(args[1])
    val candidateVersion = args[2]
    val previousVersion = args[3]
    val ledgerPath = Paths.get(args[4])
    val modules = args.drop(5)

    val ledger = try {
        PublishedContractLedgerCodec.decode(Files.readString(ledgerPath))
    } catch (ex: Exception) {
        System.err.println(
            "[release] FATAL — the exception ledger at $ledgerPath could not be read: " +
                "${ex.message}. Refusing to certify compatibility against an unreadable ledger: " +
                "every break would read as unauthorized, and the reason would be a parse error " +
                "instead of a decision.",
        )
        exitProcess(2)
    }

    println(
        "[release] published API comparison: candidate $candidateVersion against " +
            "previous $previousVersion",
    )

    var unauthorized = 0
    var notComparable = 0
    try {
        for (module in modules) {
            val candidateDump = candidateDir.resolve("$module.api")
            val previousDump = previousDir.resolve("$module.api")
            if (!Files.isRegularFile(candidateDump)) {
                System.err.println(
                    "[release] FATAL — the candidate's API dump for $module is missing at " +
                        "$candidateDump. Refusing to certify a module whose candidate surface was " +
                        "never read.",
                )
                exitProcess(2)
            }
            if (!Files.isRegularFile(previousDump)) {
                System.err.println(
                    "[release] FATAL — the previous release's API dump for $module is missing at " +
                        "$previousDump. Refusing to certify a module whose previous surface was " +
                        "never read: an absent dump is not an empty surface, it is no reading at " +
                        "all.",
                )
                exitProcess(2)
            }
            val previous = readSurface(previousDump)
            val candidate = readSurface(candidateDump)
            val verdict = PublishedApiCompatibility.decide(
                module = module,
                previousVersion = previousVersion,
                previous = previous,
                candidate = candidate,
                ledger = ledger,
            )
            when (verdict) {
                is PublishedApiModuleVerdict.Compatible ->
                    println(
                        "[release] $module: COMPATIBLE — nothing the previous release published is " +
                            "gone" + if (verdict.added.isEmpty()) {
                                "."
                            } else {
                                "; ${verdict.added.size} declaration(s) added."
                            },
                    )

                is PublishedApiModuleVerdict.ApprovedBreaks ->
                    println(
                        "[release] $module: COMPATIBLE WITH APPROVED BREAKS — " +
                            "${verdict.authorized.size} break(s), each approved by an entry of the " +
                            "exception ledger that names its version and its evidence.",
                    )

                is PublishedApiModuleVerdict.UnauthorizedBreaks -> {
                    unauthorized += verdict.unauthorized.size
                    println(
                        "[release] $module: INCOMPATIBLE — ${verdict.unauthorized.size} break(s) " +
                            "with no approval in the exception ledger:",
                    )
                    // The key is printed with the sentence: it is the name the
                    // exception ledger uses, so the operator who has to approve
                    // this break does not have to reconstruct the key by hand from
                    // the sentence.
                    verdict.unauthorized.forEach {
                        println("[release]   - ${it.describe()} [key: ${it.removalKey}]")
                    }
                }

                is PublishedApiModuleVerdict.NotComparable -> {
                    notComparable += 1
                    println("[release] $module: NOT COMPARABLE — ${verdict.reason}")
                }
            }
        }
    } catch (ex: UnreadableSurfaceException) {
        // The instrument failed, and an instrument failure is not a verdict.
        // The decision to refuse belongs to the entry point: it is the only
        // place allowed to end the process, and it is the one that can say
        // what the refusal means for the publication.
        System.err.println("[release] FATAL — ${ex.message}")
        exitProcess(2)
    }

    if (notComparable > 0) {
        exitProcess(2)
    }
    if (unauthorized > 0) {
        System.err.println(
            "[release] FATAL — $unauthorized unauthorized break(s) against the previously " +
                "published artifact. Refusing to publish: a consumer compiled against " +
                "$previousVersion would not find what it linked.",
        )
        exitProcess(1)
    }
    exitProcess(0)
}

/**
 * A surface that could not be read.
 *
 * The reader says so and lets the entry point decide what that means: only
 * the entry point may end the process, and the decision to refuse a
 * publication belongs to whoever holds that decision.
 */
private class UnreadableSurfaceException(message: String) : RuntimeException(message)

/**
 * Read one rendered surface. A dump that cannot be read is an instrument
 * failure, and the instrument failing is not a verdict.
 */
private fun readSurface(dump: Path): PublishedApiSurface =
    try {
        PublishedApiSurfaceReader.read(Files.readString(dump))
    } catch (ex: Exception) {
        throw UnreadableSurfaceException(
            "the API dump at $dump could not be read: ${ex.message}. " +
                "Refusing to certify a surface that was not understood.",
        )
    }
