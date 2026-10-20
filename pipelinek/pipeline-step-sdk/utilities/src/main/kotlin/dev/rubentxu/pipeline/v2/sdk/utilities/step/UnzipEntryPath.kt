package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Where an archive entry may land, or a refusal that says why it may not.
 *
 * ## Why this is not inside the Step
 *
 * The rule that decides whether an entry may be written lived next to the code that writes
 * files, and there was no way to ask it a question without an archive on disk. That is the
 * wrong shape for a rule whose whole content is "does this NAME point outside the
 * destination": the answer is a property of two strings, not of a filesystem.
 *
 * `core-utils.unzip` creates directories and writes files, and AGENTS.md section 2 forbids
 * both in a unit test. A guard whose only path runs through a file write is a guard with no
 * coverage. So the decision was lifted out and the Step asks it; the same names are refused,
 * with the same messages, for the same reasons.
 *
 * ## The rules, and what replaced what
 *
 * The Step used to refuse any entry name CONTAINING `..`, and then — in the extracting mode
 * only — resolve the name and check that it stayed inside the destination. Two things were
 * wrong with that:
 *
 *  - **The substring rule refused legitimate archives.** `release..tar.gz` and `v1..2.txt`
 *    are legal file names; they contain two dots and never leave the destination. The Step
 *    rejected the whole archive for them.
 *  - **The containment check was unreachable.** A name can only leave the destination by
 *    climbing (`..`) or by being absolute, and both were already refused by the rules that
 *    ran first. The check that actually proves the guarantee could never decide anything.
 *
 * So the decision is now ONE rule, and it is the semantic one: **the entry lands inside the
 * destination, or it is refused.** A name that climbs out and comes back (`certs/../x.json`)
 * lands inside and is allowed; a name that climbs out (`../x`) does not and is refused. This
 * is the same rule, and the same shape, that `:pipeline-credentials-multipart` already uses
 * for the entry names of a credential archive (`resolveZipEntryPath`).
 *
 * Two name-level rules remain, because neither is about containment:
 *
 *  - a **backslash** is refused: on a POSIX filesystem it is a legal character in a file
 *    name, but it is the separator Windows uses, and a zip written on Windows may carry
 *    `dir\file.txt` where the intent was a directory. Refusing it is the documented,
 *    fail-closed choice;
 *  - a name the **platform cannot turn into a path** is refused as a USER failure rather
 *    than crashing the Step with an `InvalidPathException` that says nothing about the
 *    archive.
 *
 * ## What this does not claim
 *
 * It does not claim that the write happened, nor that the destination contains no symlink
 * pointing outside it. The lexical rule cannot see a symlink; the Step's own KDoc records
 * that limitation as tracked debt.
 *
 * @throws PluginStepException with a USER failure when the entry may not land there.
 */
internal fun resolveUnzipEntryPath(destRoot: Path, entryName: String): Path {
    if (entryName.contains('\\')) {
        throw PluginStepException(
            failure = PipelineFailure(
                kind = FailureKind.USER,
                message = "core-utils.unzip: entry name contains backslash (Zip Slip): $entryName",
            ),
        )
    }
    val relative = try {
        Path.of(entryName)
    } catch (e: InvalidPathException) {
        throw PluginStepException(
            failure = PipelineFailure(
                kind = FailureKind.USER,
                message = "core-utils.unzip: entry name is not a usable path: ${e.reason}",
            ),
        )
    }
    // Both ends are normalised: the destination as the caller named it and the target as the
    // filesystem would read it. `Path.startsWith` compares name ELEMENTS, so a sibling whose
    // name begins with the destination's name (`dest-evil`) is outside, not inside.
    val root = destRoot.toAbsolutePath().normalize()
    val target = root.resolve(relative).normalize()
    if (!target.startsWith(root)) {
        throw PluginStepException(
            failure = PipelineFailure(
                kind = FailureKind.USER,
                message = "core-utils.unzip: entry escapes destination (Zip Slip / CVE-2023-32981): $entryName",
            ),
        )
    }
    return target
}
