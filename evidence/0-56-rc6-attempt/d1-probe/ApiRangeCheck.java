import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange;
import dev.rubentxu.pipeline.v2.domain.step.SemVer;

/**
 * PK-RELEASE-CONSOLIDATION D1: prove the DERIVED range admits the host that loads the plugin
 * and refuses the next minor. This is the check that a ceiling equal to sdkVersion would fail.
 */
public class ApiRangeCheck {
    public static void main(String[] args) {
        PipelineKApiRange derived = new PipelineKApiRange(new SemVer(0, 56, 0), new SemVer(0, 57, 0));
        System.out.println("derived range = " + derived);

        System.out.println("-- accepts() against hosts --");
        int[][] cases = {
            {0, 55, 9},  // below floor: refused
            {0, 56, 0},  // the host that loaded us: admitted
            {0, 56, 7},  // within the same minor: admitted
            {0, 57, 0},  // the ceiling itself: refused (exclusive)
        };
        for (int[] c : cases) {
            SemVer v = new SemVer(c[0], c[1], c[2]);
            System.out.printf("accepts(%s) = %s%n", v, derived.accepts(v));
        }

        System.out.println("-- the mistake D1 rule 2 would produce literally --");
        PipelineKApiRange wrong = new PipelineKApiRange(new SemVer(0, 47, 0), new SemVer(0, 56, 0));
        SemVer host = new SemVer(0, 56, 0);
        System.out.println("ceiling=sdkVersion range = " + wrong);
        System.out.printf("accepts(host %s) = %s  <-- must be true, so this range is WRONG%n",
            host, wrong.accepts(host));

        System.out.println("-- the stale literal that shipped --");
        PipelineKApiRange stale = new PipelineKApiRange(new SemVer(0, 47, 0), new SemVer(0, 53, 0));
        System.out.println("stale literal range = " + stale);
        System.out.printf("accepts(host %s) = %s  <-- this is the 78 failing tests%n",
            host, stale.accepts(host));
    }
}