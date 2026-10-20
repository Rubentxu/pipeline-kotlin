package dev.rubentxu.pipeline.v2.artefacts.local

/**
 * One Ant glob pattern.
 *
 * The pattern is kept as the operator wrote it — it is what error messages quote — and it is
 * compiled ONCE, into the form [AntPathMatching] matches against many paths.
 *
 * WHY THE MATCHING IS NOT IN HERE. The dialect is Ant, and Ant's semantics are the ones a
 * Jenkins user writes: `**` spans any number of path segments, `*` and `?` stay inside one
 * segment, `[...]` is a character class and `{a,b}` is an alternation. The two constructs that
 * an Ant user writes most were, until this cycle, read by an engine whose dialect disagreed
 * with Ant's on exactly those two, and both disagreements were measured:
 *
 * - `build/**/*.{jar,war}` matched `build/secret.pem` and `build/app.zip`, because the engine
 *   read a colon-free `{...}` as a wildcard. An operator who wrote "a jar or a war" archived
 *   everything under the directory, secrets included.
 * - `[a-c].txt` matched a file literally named `[a-c].txt` and nothing else, because the engine
 *   read the brackets as literal text. The operator got an empty archive, which
 *   `allowEmptyArchive` reports as a success.
 *
 * So the dialect is now implemented here, and it is Ant's. The engine that used to interpret it
 * is gone, and so is the translation that used to reconcile the two: there is one dialect now,
 * and it is the one an operator writes.
 *
 * COMPILED ONCE. The pattern is asked about every file under the root, so it is compiled when
 * the pattern is created and the compiled form is what every answer comes from. Compiling per
 * file would re-parse the same pattern once per file, which is the difference between a glob
 * that walks a tree and one that re-reads its own rules at every step.
 *
 * PURE BY CONSTRUCTION. No filesystem, no clock, no I/O: this is the seam AGENTS.md section 2
 * allows a unit test to touch. The file WALK stays in [AntStyleGlob.match], where it belongs.
 */
internal class AntGlobPattern(source: String) {

    /** The pattern as the operator wrote it. Never rewritten: it is what error messages quote. */
    val source: String = source

    /** The pattern, compiled once into the form that is matched against many paths. */
    private val compiled: AntPathMatching.CompiledPattern = AntPathMatching.compile(source)

    /**
     * True when [path] — a workspace-relative path — is matched by this pattern.
     *
     * @param path the path to test, relative to the root the glob walks
     */
    fun matches(path: String): Boolean = compiled.matches(path)
}
