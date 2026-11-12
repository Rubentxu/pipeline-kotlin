package dev.rubentxu.pipeline.v2.application

import java.nio.file.Path

/**
 * The directory a relative path is resolved against in this process.
 *
 * The working directory of the process, read once. It cannot change while the process lives —
 * Java has no portable way to change it — and a name that resolved against one directory and then
 * against another would make the same request mean two different things.
 */
internal val PROCESS_WORKING_DIRECTORY: Path = Path.of("").toAbsolutePath()

/**
 * The jars the operator named with `--plugin-jar`, as the paths they are AT.
 *
 * ## Why the paths are absolute
 *
 * Two verbs read this option with the same meaning — `run` and `ide init` — and a refusal that
 * named the artifact one way in one of them and another way in the other would be two refusals
 * for one artifact. A relative name is not the name of a file: it is a name RELATIVE TO the
 * working directory of a process that has already ended, and the operator who reads the refusal
 * later cannot resolve it. The absolute path is the one name that says where the artifact is,
 * and it is the same for both verbs because both resolve against the same working directory.
 *
 * The classpath of the script and the admission of its plugins carry these paths, so the file
 * the compiler compiles against and the file the admission measured are the same file by name
 * and not by an assumption about how a relative entry is resolved elsewhere.
 *
 * ## What is NOT done to the path
 *
 * The path is not normalised lexically. `..` is resolved by the file system, which follows the
 * links a lexical normalisation walks straight past: when a directory of the path is a link to
 * somewhere else, removing `..` by hand names a file that is not the one the operator named. The
 * path that is measured is therefore the path the file system resolves, and it is named as the
 * operator wrote it, against the working directory they wrote it in.
 *
 * @param named the names the operator wrote, in the order they wrote them.
 * @param workingDirectory the directory a relative name is resolved against.
 * @throws java.nio.file.InvalidPathException when a name is not a path at all.
 */
internal fun operatorPluginJars(named: List<String>, workingDirectory: Path): List<Path> =
    named.map { workingDirectory.resolve(it).toAbsolutePath() }
