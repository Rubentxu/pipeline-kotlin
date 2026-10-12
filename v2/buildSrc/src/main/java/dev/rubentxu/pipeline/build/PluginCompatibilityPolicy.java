package dev.rubentxu.pipeline.build;

import java.util.Objects;

/**
 * PK-RC-PLUGIN-001 — the single source of truth for bundled-plugin compatibility ranges.
 *
 * <h2>Why this class exists</h2>
 *
 * Before this class, the four official plugins each carried a hand-typed
 * {@code PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 53, 0))} literal in their
 * declaration, plus a separate {@code inputs.property("apiRange", "[0.47.0, 0.52.0)")}
 * literal in their Gradle build. Two sources of truth, both frozen, neither
 * tracking the product version. A bump from {@code 0.53.0} to {@code 0.54.0} shipped
 * with apiRange {@code [0.47.0, 0.53.0)} and a host at {@code 0.54.0}: the host
 * is OUTSIDE the range, and {@code PluginAdmissionGate} refuses every official
 * plugin on the host's own distribution. The candidate was published, the gate
 * existed, the gate was never invoked against the published ZIP.
 *
 * <h2>What this class is</h2>
 *
 * A pure function {@code (productVersion) -> (apiRange)}. No I/O, no Gradle, no
 * reflection. The function lives in {@code buildSrc/} so every plugin's build
 * script and the loaders inside the JARs read the SAME implementation, and the
 * policy is testable as a normal unit. A change to the rule is a change to one
 * file, observable from a test that runs without a Gradle build.
 *
 * <h2>The rule (the only rule)</h2>
 *
 * The exclusive upper bound is the next minor of the product version: a
 * {@code 0.56.0} host is admitted by {@code [0.47.0, 0.57.0)}. The lower bound
 * is the minimum API range the maintainers have certified; bumping it requires
 * a deliberate edit to this file and a corresponding edit to the tests.
 *
 * <h2>What this class is NOT</h2>
 *
 * It does not invent a default. The constructor refuses a malformed
 * {@code productVersion} or a {@code minInclusive} greater than or equal to the
 * derived upper bound. A build that somehow asks for {@code apiRange=[X, X)} or
 * an empty range is a build defect; the fail-closed error is thrown from the
 * call site, not swallowed.
 *
 * External plugins (any plugin not in {@code :pipeline-step-sdk/}) are
 * unaffected. Their {@code apiRange} is theirs to declare; this policy does
 * not broadcast to them.
 */
public final class PluginCompatibilityPolicy {

    /**
     * The minimum inclusive lower bound the maintainers have certified. The
     * value is 0.47.0 because that is the lowest API the contract has
     * committed to since the 0.47 train. Bumping this is a deliberate
     * policy change, not a build-time calculation.
     */
    public static final int DEFAULT_MIN_MAJOR = 0;
    public static final int DEFAULT_MIN_MINOR = 47;
    public static final int DEFAULT_MIN_PATCH = 0;

    private final int minMajor;
    private final int minMinor;
    private final int minPatch;
    private final int hostMajor;
    private final int hostMinor;
    private final int hostPatch;

    /**
     * @param productVersion the product version string (e.g. {@code "0.56.0"}).
     *                       Must be exactly {@code MAJOR.MINOR.PATCH} with
     *                       non-negative components.
     * @throws IllegalArgumentException if the version is malformed or the
     *                                  derived upper bound is not strictly
     *                                  greater than the lower bound.
     */
    public PluginCompatibilityPolicy(String productVersion) {
        this(productVersion, DEFAULT_MIN_MAJOR, DEFAULT_MIN_MINOR, DEFAULT_MIN_PATCH);
    }

    /**
     * Overload with explicit lower bound. Reserved for certifiers that need to
     * widen the range (a backport of a public API to an earlier train).
     */
    public PluginCompatibilityPolicy(String productVersion, int minMajor, int minMinor, int minPatch) {
        Objects.requireNonNull(productVersion, "productVersion");
        int[] host = parseSemVer(productVersion);
        this.hostMajor = host[0];
        this.hostMinor = host[1];
        this.hostPatch = host[2];
        if (minMajor < 0 || minMinor < 0 || minPatch < 0) {
            throw new IllegalArgumentException(
                "minInclusive negative: " + minMajor + "." + minMinor + "." + minPatch);
        }
        this.minMajor = minMajor;
        this.minMinor = minMinor;
        this.minPatch = minPatch;
    }

    /** The inclusive lower bound as {@code "MAJOR.MINOR.PATCH"}. */
    public String minInclusive() {
        return minMajor + "." + minMinor + "." + minPatch;
    }

    /**
     * The exclusive upper bound as {@code "MAJOR.MINOR.PATCH"}: the next
     * minor of the product version. A {@code 0.56.0} host produces
     * {@code "0.57.0"}; a {@code 0.56.2} host also produces {@code "0.57.0"}
     * (the upper bound is the next minor, not the next patch).
     */
    public String untilExclusive() {
        return hostMajor + "." + (hostMinor + 1) + ".0";
    }

    /** The canonical apiRange literal, e.g. {@code "[0.47.0, 0.57.0)"}. */
    public String apiRange() {
        return "[" + minInclusive() + ", " + untilExclusive() + ")";
    }

    /**
     * Render the policy as a JSON object ready to be embedded as
     * {@code META-INF/pipelinek/plugin-compatibility.json}. The shape is
     * stable; the resource is the contract the four declarations read.
     */
    public String toJson() {
        return "{\n" +
            "  \"schema\": \"pipelinek-plugin-compatibility/v1\",\n" +
            "  \"productVersion\": \"" + hostMajor + "." + hostMinor + "." + hostPatch + "\",\n" +
            "  \"minInclusive\": \"" + minInclusive() + "\",\n" +
            "  \"untilExclusive\": \"" + untilExclusive() + "\",\n" +
            "  \"apiRange\": \"" + apiRange() + "\",\n" +
            "  \"hostAuthority\": \"rootProject.version\",\n" +
            "  \"generatedBy\": \"PluginCompatibilityPolicy\"\n" +
            "}\n";
    }

    private static int[] parseSemVer(String raw) {
        String[] parts = raw.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                "productVersion must be MAJOR.MINOR.PATCH, got: " + raw);
        }
        int[] numbers = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "productVersion component not numeric: " + raw, e);
            }
            if (numbers[i] < 0) {
                throw new IllegalArgumentException(
                    "productVersion component negative: " + raw);
            }
        }
        return numbers;
    }
}
