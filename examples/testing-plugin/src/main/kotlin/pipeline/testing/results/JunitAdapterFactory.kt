package pipeline.testing.results

/**
 * E3-T0 — RED phase entry point.
 *
 * RED discipline: every RED test must fail for the EXPECTED reason.
 * In E3-T0 the expected reason is "the JUnit XML adapter does not
 * exist yet". A factory method here is intentionally THROWING —
 * the tests will fail because the parser doesn't exist, not because
 * of a build error, NullPointerException, or a broken fixture.
 *
 * E3-T1 (GREEN) replaces [junitAdapter] with a real implementation.
 * The tests in `JunitXmlAdapterContractTest` and
 * `TestReportDomainContractTest` remain unchanged; they describe the
 * behaviour and the SPI simultaneously.
 */
object JunitAdapterFactory {
    /**
     * Returns a JUnit-XML [TestReportAdapter].
     *
     * RED: throws [NotImplementedInT0]. GREEN (in E3-T1): returns the
     * real parser.
     */
    @JvmStatic
    fun junitAdapter(): TestReportAdapter {
        throw NotImplementedInT0(
            "E3-T0 RED: JUnit XML adapter is intentionally not implemented. " +
                "This factory will return a real parser in E3-T1 (GREEN).",
        )
    }
}

/**
 * E3-T0 RED marker. Carried as a plain [Throwable] (not [NotImplementedError],
 * which is `final`) so callers can `catch` it deterministically. It is a
 * controlled runtime failure: tests assert behaviour; they do not expect a
 * [NotImplementedError] from the standard library (which would also be the
 * wrong type for a future E3-T1 routing decision).
 */
class NotImplementedInT0(message: String) : Error(message)
