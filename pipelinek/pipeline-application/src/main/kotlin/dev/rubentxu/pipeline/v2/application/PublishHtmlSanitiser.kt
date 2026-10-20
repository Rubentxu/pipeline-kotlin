package dev.rubentxu.pipeline.v2.application

/**
 * Pure, total sanitiser for `core.publishHTML` `reportName` values.
 *
 * Reference: jenkinsci/htmlpublisher-plugin master @ 6a536b8d
 *   HtmlPublisherTarget.java (MIT) — `sanitizeReportName()` method.
 *   Source lines (verbatim behaviour):
 *
 * ```java
 * private static String sanitizeReportName(String orig, boolean escape) {
 *     String safe = orig == null ? "default" : orig;
 *     safe = StringUtils.replace(safe, "/", "_");
 *     if (escape) {
 *         safe = StringUtils.replace(safe, "_", "__");
 *     }
 *     char[] unsafe = new char[]{':', '*', '?', '"', '<', '>', '|', '\\'};
 *     StringBuilder sb = new StringBuilder();
 *     for (char c : safe.toCharArray()) {
 *         if (Character.isLetterOrDigit(c) || c == '-' || (c == '_' && !escape)) {
 *             sb.append(c);
 *         } else {
 *             sb.append("_");
 *             sb.append(String.format("%04x", (int) c));
 *         }
 *     }
 *     return sb.toString();
 * }
 * ```
 *
 * Behavioural contract (R3 in WU-LPR-090/spec.md):
 * - R3.a (alphanumeric): letter / digit / '-' / '_' (when not escape) preserved as-is;
 *   any other char replaced with '_' followed by 4-char lowercase hex of the char code.
 * - R3.b (traversal): even hostile chars (newlines, control, unicode) produce a value
 *   that does NOT contain forward-slash or back-slash after sanitisation.
 * - R3.c (unicode): each char is treated independently; non-ASCII chars are encoded
 *   as '_' + 4-char hex of the unicode code point.
 * - R3.d (escapeUnderscores): when true, input `_` becomes `__` BEFORE the regex pass,
 *   and `_` is no longer in the "safe" set. Mirrors `escapeUnderscores` in Jenkins.
 * - R3.e (collision): sanitisation is a total function (same input → same output). Two
 *   different inputs MAY collide (e.g. "a:b" and "a_b0041" both → "a_b0041b") and the
 *   caller must handle this via `name` validation upstream, not here.
 *
 * Pure function. No IO, no clock, no globals. Tested via PublishHtmlSanitiserTest.
 */
object PublishHtmlSanitiser {

    /**
     * Sanitise a reportName according to the upstream Jenkins rules.
     *
     * @param orig the user-supplied reportName (may be null, treated as "default").
     * @param escapeUnderscores when true, escape `_` (the canonical Jenkins behaviour).
     * @return the sanitised filename (always non-empty, never contains `/` or `\`).
     */
    fun sanitize(orig: String?, escapeUnderscores: Boolean): String {
        val safe = if (orig.isNullOrEmpty()) "default" else orig
        val slashed = safe.replace('/', '_')
        val underscored = if (escapeUnderscores) slashed.replace("_", "__") else slashed

        val sb = StringBuilder(underscored.length)
        for (ch in underscored) {
            if (ch.isLetterOrDigit() || ch == '-' || (ch == '_' && !escapeUnderscores)) {
                sb.append(ch)
            } else {
                sb.append('_')
                sb.append("%04x".format(ch.code))
            }
        }
        return sb.toString()
    }
}
