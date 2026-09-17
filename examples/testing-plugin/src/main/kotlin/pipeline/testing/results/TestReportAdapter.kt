package pipeline.testing.results

/**
 * E3-T0 — Test report adapter SPI.
 *
 * An adapter takes the raw bytes of ONE report document (or multiple that
 * the parser can read as a unit) and produces a [TestReport] result.
 *
 * Adapters DO NOT leak parser / XML / framework specifics into the
 * domain: they convert from one wire shape to [TestReport]. The plugin
 * supplies one adapter per framework family (e.g. JUnit XML today);
 * future families would supply their own (TAP, xUnit, NUnit, …).
 *
 * The SPI is intentionally narrow:
 *
 *   bytes  ->  validate(adapter-specific)  ->  TestReport
 *
 * The same SPI will host the JUnit XML adapter in E3-T1 and any other
 * future adapter. Adapters are isolated per-family; failure modes are
 * a closed [ParseFailureReason] ADT so the downstream policy can route
 * them differently without depending on framework-specific types.
 */
fun interface TestReportAdapter {
    /**
     * Parse the raw bytes of one or more report documents and produce
     * a typed [TestReport].
     *
     * Implementations:
     *   - MUST be deterministic with respect to input bytes
     *     (replay-friendly).
     *   - MUST return [TestReport.Unparseable] for any failure case;
     *     never throw to encode a parse failure.
     *   - MUST NOT silently drop test cases; if a case has a malformed
     *     assertion block, the case is recorded with [TestStatus.Failed]
     *     carrying the underlying failure — not omitted.
     */
    fun parse(bytes: ByteArray, source: String): TestReport
}
