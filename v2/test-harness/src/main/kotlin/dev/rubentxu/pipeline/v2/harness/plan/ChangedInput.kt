package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue

/**
 * The typed classification of a single path the resolver saw
 * as a change. H1 TODO 1 (Loop rápido, 2026-10-16) replaces
 * the resolver's `isProductionPath(path: String): Boolean`
 * substring chain with a sealed ADT that classifies by
 * *semantic effect*:
 *
 * ```text
 *   Production          — the change invalidates a product
 *                         guarantee. The plan MUST select
 *                         the contract(s) that cover the
 *                         path; an unclassified Production
 *                         is a refusal, not a no-op.
 *   NonProduction       — the change does not invalidate a
 *                         product guarantee. The plan can
 *                         ignore it.
 *   UnknownProduction   — the change has the surface of a
 *                         production change but the
 *                         classifier could not place it
 *                         into a known Production case.
 *                         The resolver refuses with a
 *                         proposed owner/capability so the
 *                         operator can add a rule, instead
 *                         of silently selecting nothing.
 * ```
 *
 * Each [Production] case carries the path AND a
 * `contractCapability: String?` resolved against
 * [ContractCatalogue]. The catalogue is the source of truth
 * for "this path is owned by this contract"; the classifier
 * is the source of truth for "this path has the shape of a
 * product change". The resolver joins the two: a path that
 * has the shape but no contract is `UnknownProduction` (or
 * the catalogue's empty-contract `UnclassifiedPath`
 * refusal, depending on the variant).
 *
 * The variants are exhaustive: any future production-shape
 * not covered by the current five cases will be classified
 * as [UnknownProduction] and the operator will see a refusal
 * with a proposed capability. There is no `else` branch to
 * hide a missing case.
 *
 * BQ1 RC2 (2026-10-16 audit): the prior shape was a chain
 * of `path.startsWith/endsWith/contains` on a single
 * `Boolean`. The audit named this as a class of defect —
 * a missing clause silently filters the change out, the
 * catalogue is never consulted, the contract never fires.
 * The ADT makes the same chain typed; the `when` in
 * [classify] is exhaustive; the catalogue refusal is no
 * longer a silent no-op.
 */
sealed interface ChangedInput {
    val path: String

    /**
     * The change invalidates a product guarantee. The
     * resolver's plan MUST name the contract(s) that
     * cover this path; a [Production] with no contract
     * in the catalogue is a refusal, not a no-op.
     */
    sealed interface Production : ChangedInput

    /**
     * A `.kt` or `.java` source under `src/main/`, or a
     * `.proto` schema anywhere in the project. Both are
     * the executable surface of the product: a `.kt`
     * change is a code change, a `.proto` change is a
     * wire-contract change.
     */
    data class SourceCode(override val path: String) : Production {
        // BQ4 v10 (plan carrier self-protect, 2026-10-17):
        // a blank path would silently never match any
        // catalogue row, and the classifier would return
        // the typed refusal for a real change as
        // UnknownProduction (or worse, a no-op). The
        // carrier now refuses blanks at construction.
        init { require(path.isNotBlank()) { "SourceCode.path must not be blank" } }
    }

    /**
     * A `META-INF/services/...` resource. The
     * ServiceLoader mechanism reads this at runtime
     * to wire implementations to interfaces; a
     * renamed line changes which implementation is
     * chosen without changing any `.kt` file. The
     * catalogue has no row for ServiceLoader paths
     * because each resource is unique to one product
     * surface, but a contract may match it via
     * `pathPatterns` (e.g. the Step SDK's
     * `StepDefinitionContributor` SPI).
     */
    data class ServiceLoader(override val path: String) : Production {
        init { require(path.isNotBlank()) { "ServiceLoader.path must not be blank" } }
    }

    /**
     * A build-composition resource. The whole build is
     * the product: `*.gradle.kts` (modules, plugins,
     * dependencies), `libs.versions.toml` (version
     * catalog), `gradle.properties` (daemon, parallel,
     * JVM flags), `gradle-wrapper.{jar,properties}` and
     * `gradlew*` (the wrapper itself), and any other
     * file under `v2/gradle/`. The audit's RC4 follow-up
     * named the missing `v2/gradle/wrapper/...` clause
     * a "silent green" — the path was filtered out
     * before the catalogue was consulted.
     */
    data class BuildComposition(override val path: String) : Production {
        init { require(path.isNotBlank()) { "BuildComposition.path must not be blank" } }
    }

    /**
     * A distribution or install script. `scripts/release*`
     * and `scripts/install*` ship as part of the
     * consumer's install path; a change to them is a
     * product change because the consumer runs them.
     * The `scriptName` is the basename of the script
     * (e.g. `release-pipelinek.sh`); the resolver uses
     * it to propose an owner/capability when a
     * catalogue row is missing.
     */
    data class DistributionScript(
        override val path: String,
        val scriptName: String,
    ) : Production {
        init {
            require(path.isNotBlank()) { "DistributionScript.path must not be blank" }
            require(scriptName.isNotBlank()) { "DistributionScript.scriptName must not be blank" }
        }
    }

    /**
     * The classifier could not place the path into any
     * known [Production] case. The path looks like a
     * product change (it is not under `v2/test-harness/`,
     * not a `.md`, not a test source), but the existing
     * rules did not match. The resolver refuses with
     * the proposed capability and owner so the
     * operator can add a rule.
     *
     * `proposedCapability` and `proposedOwner` are
     * heuristics, not authoritative. They give the
     * operator a starting point: a `v2/<module>/...`
     * path proposes the module name as the owner and
     * `Unknown.<module>` as the capability. The
     * operator replaces the heuristic by adding a
     * row to the catalogue or a clause to
     * [classify].
     *
     * The audit's H1 mandate says "evitar tabla por
     * fichero" — a per-file string table is exactly
     * what we are not building. The proposal is
     * derived, not enumerated.
     */
    data class UnknownProduction(
        override val path: String,
        // BQ4 v24 (typed proposal pair, 2026-10-17):
        // the previous shape carried the two fields as
        // parallel `String` declarations
        // (`proposedCapability`, `proposedOwner`). The
        // new shape uses the shared [Proposal] carrier
        // — a single concept (the operator's
        // starting-point proposal) as a single
        // self-protected typed value. A consumer reads
        // the pair atomically via `proposal.capability`
        // and `proposal.owner` rather than via two
        // parallel fields. The [Proposal] carrier
        // self-protects: a blank `capability` or `owner`
        // is refused at construction.
        val proposal: Proposal,
    ) : ChangedInput {
        init {
            require(path.isNotBlank()) { "UnknownProduction.path must not be blank" }
        }
    }

    /**
     * The change does not invalidate a product
     * guarantee. The plan can ignore it (or treat it
     * as a legitimate "nothing to certify" outcome).
     */
    sealed interface NonProduction : ChangedInput

    /**
     * RESERVED and, as of BQ4 P0 (2026-10-17), NOT CONSTRUCTED by the
     * classifier. Kept so a future narrower self-owned exemption has a
     * home, and so the decision that removed the old one stays legible.
     *
     * It previously carried a `v2/test-harness/` prefix exemption, and
     * the audit that removed it was explicit: a self-owned prefix makes
     * a defect in the harness's own `src/main` a SILENT GREEN. The gate
     * saw "no production change" and discharged. That variant's
     * description below therefore describes what the exemption WAS, not
     * what the classifier does — and a KDoc that describes the wiring
     * the code no longer has is the same defect class as a test that
     * asserts it.
     *
     * Read the current rule instead: a path under
     * `v2/test-harness/src/main` falls through to [SourceCode]
     * (Production), and one under its `src/test` falls through to
     * [TestSource].
     *
     * (Written without a trailing glob on purpose: Kotlin block comments
     * NEST, so a slash followed by a star inside one opens a nested
     * comment, and a single closing delimiter then leaves the outer one
     * open. `src/test` with no glob states the same fact and cannot
     * close the file's comment. This cost two build failures before it
     * was noticed, and the lesson is worth the four lines.)
     */
    data class TestHarnessInternal(override val path: String) : NonProduction {
        init { require(path.isNotBlank()) { "TestHarnessInternal.path must not be blank" } }
    }

    /**
     * A test source. Two shapes reach this variant, and the
     * distinction is incidental to what the variant MEANS:
     *
     * 1. a `.kt`/`.java` file under `src/test/` (step 6), and
     * 2. a repository-owned test script, `scripts/test_*.py` and
     *    `scripts/test_*.sh` (step 6b).
     *
     * Test changes are not product changes; the catalogue does not
     * bind test sources and the resolver ignores them. The second
     * shape was added on 2026-10-18 because a test script that is
     * not recognised as a test source is refused as an
     * [UnknownProduction], and the remedy that refusal names — a
     * catalogue row — is a lie for a test harness. See step 6b for
     * the measurement.
     *
     * A test source under `v2/test-harness/` is NOT this variant: it
     * falls through to [SourceCode], because the harness IS the
     * product (BQ4 P0).
     */
    data class TestSource(override val path: String) : NonProduction {
        init { require(path.isNotBlank()) { "TestSource.path must not be blank" } }
    }

    /**
     * A documentation or tooling change (a `.md`, a
     * `docs/...` path, a `.txt`, etc.). Documentation
     * cannot invalidate a product guarantee; the plan
     * ignores it.
     */
    data class Documentation(override val path: String) : NonProduction {
        init { require(path.isNotBlank()) { "Documentation.path must not be blank" } }
    }
}

/**
 * The classifier. Pure function: path in, [ChangedInput] out.
 * No I/O, no catalogue reads, no clock. The catalogue lookup
 * happens later in the resolver; the classifier's job is
 * "what shape is this path?".
 *
 * The classifier is total over `String`: every path is
 * classified. The `when` chain is exhaustive by
 * construction (the last branch is [ChangedInput.UnknownProduction]
 * with a heuristic proposed capability/owner). A future
 * variant of [ChangedInput.Production] requires updating
 * this function AND the resolver, so the change is visible
 * in both places.
 *
 * Test surface: `ChangedInputClassifierTest`. The 22
 * adversarial tests in `ImpactResolverAdversarialTest` for
 * the old `isProductionPath` are preserved as a thin
 * delegate (`ChangedInputClassifier.isProductionPath`)
 * that returns `true` iff the path classifies as
 * [ChangedInput.Production]. A future variant of
 * [ChangedInput] that should be Production but is not
 * flagged by `isProductionPath` will fail those tests.
 */
object ChangedInputClassifier {

    /**
     * The catalogue-free shape classification. Returns
     * the [ChangedInput] for the path. A `Production`
     * variant is the trigger for the resolver to ask
     * the catalogue "which contract claims this?".
     */
    fun classify(path: String): ChangedInput {
        // BQ4 P0 (HITO 1 "selección y evidencia vinculadas",
        // 2026-10-17): the previous shape classified any path
        // under `v2/test-harness/` as `TestHarnessInternal`
        // (NonProduction) and the resolver ignored it. A
        // defect in the harness's production code (anything
        // under `src/main/`) was therefore a SILENT GREEN: the
        // gate saw no production change and discharged on
        // the plan. The audit's RC4 follow-up named this the
        // most expensive defect class in this repository: the
        // harness IS the test-harness product, and changes to
        // its `.kt` production sources are product changes
        // the gate MUST verify. The exemption is removed.
        //
        // The test-harness's `src/main/**/*.kt` now falls
        // through to the standard `SourceCode` rule (step 5
        // below) and is classified as Production. A harness
        // `src/test/**/*.kt` falls through to the
        // `TestSource` rule (step 7) and is NonProduction, as
        // for any other module. The catalogue's contract
        // lookup is the only authority for "is this path
        // covered by a known contract"; the prefix exclusion
        // was bypassing it.
        //
        // The `TestHarnessInternal` data class is preserved
        // as a reserved variant — it is no longer constructed
        // by the classifier but may be reintroduced in the
        // future for a narrower self-owned exemption (e.g.
        // tooling/metadata, not source code) if the
        // catalogue cannot absorb one.

        // 1. Build composition: extension-based rules.
        //    The wrapper and version-catalog rules cover
        //    well-known filenames anywhere in the tree;
        //    the `v2/gradle/` rule covers everything else
        //    under that root (per the rc4 follow-up audit
        //    that found the missing `wrapper/` clause).
        if (path.endsWith(".gradle.kts")) {
            return ChangedInput.BuildComposition(path)
        }
        if (path.endsWith("/libs.versions.toml") ||
            path.endsWith("/gradle.properties") ||
            path.endsWith("/gradlew") ||
            path.endsWith("/gradlew.bat")
        ) {
            return ChangedInput.BuildComposition(path)
        }

        // 2. Build composition: location-based rules.
        if (path.startsWith("v2/gradle/wrapper/") &&
            (path.endsWith(".properties") || path.endsWith(".jar"))
        ) {
            return ChangedInput.BuildComposition(path)
        }
        if (path.startsWith("v2/gradle/")) {
            return ChangedInput.BuildComposition(path)
        }

        // 3. ServiceLoader bindings. The path is
        //    under `META-INF/services/`. The catalogue
        //    has rows that match by `pathPatterns`;
        //    the resolver joins the two.
        if (path.contains("/META-INF/services/") && !path.endsWith("/")) {
            return ChangedInput.ServiceLoader(path)
        }

        // 4. Source code. `.kt` and `.java` under
        //    `*/src/main/` are the executable surface.
        //    `.proto` files are wire contracts and are
        //    production regardless of location (a
        //    protobuf in any folder changes the wire
        //    format).
        if (path.endsWith(".proto")) {
            return ChangedInput.SourceCode(path)
        }
        if (path.contains("/src/main/") &&
            (path.endsWith(".kt") || path.endsWith(".java"))
        ) {
            return ChangedInput.SourceCode(path)
        }

        // 5. Distribution / install scripts. The
        //    consumer runs these, so a change is a
        //    product change. The script name is the
        //    basename; the resolver uses it for the
        //    proposed capability on the refusal path.
        if (path.startsWith("scripts/release") || path.startsWith("scripts/install")) {
            val name = path.substringAfterLast('/')
            return ChangedInput.DistributionScript(path, name)
        }

        // 6. Test sources (NonProduction). The
        //    `v2/test-harness/` prefix check above
        //    already classified those; this branch
        //    catches the rest.
        if (path.contains("/src/test/") &&
            (path.endsWith(".kt") || path.endsWith(".java"))
        ) {
            return ChangedInput.TestSource(path)
        }

        // 6b. Repository-owned test scripts (NonProduction).
        //
        //     Measured 2026-10-18: the `scripts/` distribution rule
        //     above matched 2 of the 31 files in `scripts/`. The other
        //     29 fell through to [ChangedInput.UnknownProduction],
        //     and the resolver REFUSES on that — so a change to
        //     `scripts/test_install_pipelinek.py`,
        //     `scripts/test_release_publisher.sh` or
        //     `scripts/test_release_publisher_assets.sh` made
        //     `scripts/validation-gate.py` refuse to certify it, with
        //     "add a catalogue row or remove the file". A gate that
        //     cannot certify a change to itself is not a gate.
        //
        //     These are TEST SOURCES: they do not ship (measured —
        //     `unzip -l` on the 0.62.0 distribution lists zero
        //     `scripts/` entries), and they are not consumer-facing.
        //     The refusal's own remedy is the tell: binding a test
        //     harness as a product contract would buy a green by
        //     writing a lie into the catalogue.
        //
        //     `test_` is the repository's naming convention across 11
        //     files, so this is a convention and not a per-file table
        //     (ADR-PK-QV-002 D2 forbids the table). It is deliberately
        //     NOT [ChangedInput.TestHarnessInternal]: BQ4 P0 removed a
        //     self-owned prefix exemption because it read as coverage
        //     while being a silent green, and re-adding one under a
        //     second name would restore the defect, not fix it.
        if (path.startsWith("scripts/")) {
            val fileName = path.substringAfterLast('/')
            if (fileName.startsWith("test_") &&
                (fileName.endsWith(".py") || fileName.endsWith(".sh"))
            ) {
                return ChangedInput.TestSource(path)
            }
        }

        // 7. Documentation. `.md` and `docs/` paths are
        //    not product guarantees. The classifier does
        //    NOT claim `.txt` (LICENSE.txt, README.txt,
        //    NOTES.txt) or other broad extensions because
        //    a `.txt` at the top of an unknown module
        //    could be a config or a manifest; the H1
        //    mandate says "evitar tabla por fichero",
        //    and a permissive extension rule is exactly
        //    that. Unknown `.txt` paths fall through to
        //    [ChangedInput.UnknownProduction] and the
        //    operator gets a refusal with a proposed
        //    owner/capability.
        if (path.endsWith(".md") || path.startsWith("docs/")) {
            return ChangedInput.Documentation(path)
        }

        // 8. UnknownProduction. The path is not under
        //    the self-owned prefix, is not a test
        //    source, is not a doc, but the rules above
        //    did not match. The resolver refuses with
        //    the proposed owner/capability so the
        //    operator can add a rule.
        val (proposedOwner, proposedCapability) = proposeOwnerAndCapability(path)
        return ChangedInput.UnknownProduction(
            path = path,
            // BQ4 v24 (typed proposal pair, 2026-10-17):
            // the proposal is now a single [Proposal]
            // carrier — the two fields are wrapped
            // atomically rather than passed as parallel
            // arguments. The [Proposal] carrier
            // self-protects: a blank `capability` or
            // `owner` is refused at construction, so the
            // typed shape is the structural invariant.
            proposal = Proposal(
                capability = proposedCapability,
                owner = proposedOwner,
            ),
        )
    }

    /**
     * Heuristic proposal for an [ChangedInput.UnknownProduction]
     * refusal. The proposal is derived from path
     * conventions, not enumerated — the H1 mandate says
     * "evitar tabla por fichero". A `v2/<module>/...`
     * path proposes the module name as the owner and
     * `Unknown.<module>` as the capability; a
     * `scripts/<name>` path proposes the script name;
     * everything else proposes `v2` and the first
     * path segment.
     */
    private fun proposeOwnerAndCapability(path: String): Pair<String, String> {
        val parts = path.split('/')
        return when {
            parts[0] == "v2" && parts.size > 1 -> {
                val module = parts[1]
                Pair(":${module}", "Unknown.${module}")
            }
            parts[0] == "scripts" && parts.size > 1 -> {
                val script = parts[1].substringBefore('.')
                Pair("scripts", "Unknown.${script}")
            }
            else -> Pair("v2", "Unknown.${parts.first()}")
        }
    }

    /**
     * The backwards-compatible Boolean projection. Returns
     * `true` iff the path classifies as
     * [ChangedInput.Production] (any variant). Kept so the
     * 22 adversarial tests for `isProductionPath` continue
     * to assert the same property on the same paths, and
     * so the existing catalogue/refusal pipeline keeps
     * working without a call-site rewrite.
     *
     * The H1 mandate says the ADT is the source of truth;
     * this function is a derived view. The tests pin both
     * sides: the ADT test pins the variants, this
     * projection is the migration seam.
     */
    fun isProductionPath(path: String): Boolean =
        classify(path) is ChangedInput.Production
}
