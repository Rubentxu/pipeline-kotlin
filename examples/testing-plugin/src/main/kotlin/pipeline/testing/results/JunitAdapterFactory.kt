package pipeline.testing.results

import pipeline.testing.junit.JunitXmlAdapter

/**
 * E3-T1 — GREEN phase entry point.
 *
 * Returns the real JUnit-XML [TestReportAdapter]. The T0 RED marker
 * [NotImplementedInT0] is no longer reachable from this factory.
 *
 * The factory is intentionally a thin object so future families (TAP,
 * xUnit, NUnit, …) can each expose their own entry point without
 * adding new top-level types to this module.
 */
object JunitAdapterFactory {
    /**
     * Returns a JUnit-XML [TestReportAdapter] backed by the pure-JDK
     * `JunitXmlAdapter`. Safe to call multiple times; the adapter is
     * stateless.
     */
    @JvmStatic
    fun junitAdapter(): TestReportAdapter = JunitXmlAdapter()
}
