package dev.rubentxu.pipeline.v2.application

// The rule for a value that NAMES something.
//
// A value that names a location or an identity is a REQUEST for that thing. A
// blank value is not "no request": the filesystem resolves an empty path to the
// working directory, the storage engine reads an empty name as a temporary
// database, and a store reads an empty run id as a run nobody has heard of. The
// product then answers the request it resolved rather than the request that was
// made, and the operator reads a path or an identity that they never wrote.
//
// The rule is here, and only here, because a rule that is written once per verb
// is a rule that the next verb forgets.

// WHY THE REFUSAL NAMES THE VALUE
//
// The operator has to change exactly ONE thing: the value they wrote. So the
// refusal says which value, and says the fact about it. The words are the
// shared vocabulary's — `CliError.InvalidOptionValue` already renders as
// "invalid value for --db: must not be blank" — so no new sentence is
// introduced, and every verb of this CLI says the same thing about the same
// mistake.
//
// WHY IT IS DECIDED WHERE THE VALUE IS READ
//
// A refusal decided downstream is a fact about the storage engine or about the
// filesystem, and it sends the operator to look at the wrong thing: the run
// that says "no event history" when the operator wrote an empty `--db`, or the
// filesystem that names the working directory when the operator wrote no path
// at all. Decided at the boundary that read the value, it is a fact about the
// REQUEST, and it lands before any store, journal, process or file is touched.

/**
 * The refusal for a value that names something and is blank.
 *
 * @param name how the operator named the value: the option token (`--db`) or the
 *   argument (`run id`, `script path`).
 */
internal fun blankNamedValueRefusal(name: String): CliError =
    CliError.InvalidOptionValue(name, "must not be blank")

/**
 * The options whose value NAMES A LOCATION, and that nothing refuses yet.
 *
 * ## What is in here
 *
 * The value of one of these options is a path: a directory, a file, or a
 * directory the product is going to create. An empty path is not "the current
 * directory", it is a path the operator did not name, and the filesystem will
 * resolve it to the working directory without saying so.
 *
 * The set spans the tables that own the options: `--db`, `--control-root`,
 * `--workspace`, `--plugin-jar` and `--plugin-trust` are in the shared option
 * table, and `--control-dir` belongs to the console verb alone. The refusal
 * lives at the ONE owner of an option's value ([readOptionValue]), so a set
 * that spans tables is not a problem: the owner does not need to know which
 * table an option came from.
 *
 * ## What is NOT in here, and why
 *
 * `--receipt` and `--contract` name a location too, and the table that owns
 * them already refuses a blank value — `--receipt` as "option --receipt
 * requires a value", `--contract` as "--contract is required". A value that is
 * already refused does not need a second refusal with a different wording: the
 * operator is told, and told the same way they were told before.
 *
 * ## What is not a location
 *
 * The options of a CLOSED VOCABULARY (`--view`, `--format`, `--channel`,
 * `--outcome`, `--tail-bytes`, `--limit`, `--sandbox-profile`) name one thing
 * out of a published set, and an empty value is not one of them: each of them
 * is already refused by the vocabulary it names. The free-text filters
 * (`--grep`, `--stage`, `--step`, `--kind`) refuse an empty value for a
 * reason of their own — an empty pattern under substring semantics matches
 * EVERY line, so it is a typo promoted to a filter that keeps everything.
 */
internal val LOCATION_NAMING_OPTIONS: Set<String> = setOf(
    "--db",
    "--control-root",
    "--control-dir",
    "--workspace",
    "--plugin-jar",
    "--plugin-trust",
)
