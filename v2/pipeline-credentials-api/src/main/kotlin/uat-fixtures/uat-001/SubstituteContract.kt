package dev.rubentxu.pipeline.v2.uat_fixtures.uat_001

/**
 * The H0 ephemeral-cycle UAT's substitute contract. This file
 * exists ONLY to satisfy [ContractCatalogue]'s "every named
 * path is a real file" fitness test
 * ([ContractCatalogueFitnessTest]); the UAT copies it into the
 * cycle dir under its project-relative path, and the runner's
 * HF0 path reads the copy. The contract is fixture-only and
 * does not ship as a production property.
 */
class SubstituteContract {
    fun commit(input: String): String = input
}
