package dev.rubentxu.pipeline.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PluginCompatibilityPolicyTest {

    @Test
    void upperBoundIsNextMinor() {
        // The rule. 0.56.0 -> [0.47.0, 0.57.0). The same train at 0.56.2
        // produces the same upper bound: the next minor, not the next patch.
        assertEquals("[0.47.0, 0.57.0)", new PluginCompatibilityPolicy("0.56.0").apiRange());
        assertEquals("[0.47.0, 0.57.0)", new PluginCompatibilityPolicy("0.56.2").apiRange());
        assertEquals("[0.47.0, 0.49.0)", new PluginCompatibilityPolicy("0.48.5").apiRange());
    }

    @Test
    void jsonShapeIsStable() {
        String json = new PluginCompatibilityPolicy("0.56.0").toJson();
        assertTrue(json.contains("\"schema\": \"pipelinek-plugin-compatibility/v1\""), json);
        assertTrue(json.contains("\"productVersion\": \"0.56.0\""), json);
        assertTrue(json.contains("\"minInclusive\": \"0.47.0\""), json);
        assertTrue(json.contains("\"untilExclusive\": \"0.57.0\""), json);
        assertTrue(json.contains("\"apiRange\": \"[0.47.0, 0.57.0)\""), json);
        assertTrue(json.contains("\"hostAuthority\": \"rootProject.version\""), json);
        assertTrue(json.contains("\"generatedBy\": \"PluginCompatibilityPolicy\""), json);
    }

    @Test
    void rejectsMalformedProductVersion() {
        assertThrows(IllegalArgumentException.class, () -> new PluginCompatibilityPolicy("0.56"));
        assertThrows(IllegalArgumentException.class, () -> new PluginCompatibilityPolicy("0.56.0-rc1"));
        assertThrows(IllegalArgumentException.class, () -> new PluginCompatibilityPolicy(""));
        assertThrows(IllegalArgumentException.class, () -> new PluginCompatibilityPolicy("a.b.c"));
        assertThrows(IllegalArgumentException.class, () -> new PluginCompatibilityPolicy("-1.0.0"));
    }

    @Test
    void customLowerBoundRequiresExplicitOverride() {
        // A backport of a public API to an earlier train would widen the
        // lower bound explicitly. The default constructor does not allow this;
        // the overload is the only path.
        PluginCompatibilityPolicy widened = new PluginCompatibilityPolicy("0.56.0", 0, 46, 0);
        assertEquals("[0.46.0, 0.57.0)", widened.apiRange());
    }

    @Test
    void zeroMinorBumpKeepsTheRangeOpen() {
        // 0.56.0 -> [0.47.0, 0.57.0). The minor grows by one regardless of
        // patch; a future 0.56.x bugfix release does not need a manual edit.
        assertEquals("0.57.0", new PluginCompatibilityPolicy("0.56.0").untilExclusive());
        assertEquals("0.57.0", new PluginCompatibilityPolicy("0.56.7").untilExclusive());
        // A patch-only bump (hypothetical 0.56.0 -> 0.56.1) does not change the
        // upper bound, so the same apiRange covers both; this is the property
        // that makes manual edits unnecessary across patch releases.
        assertEquals(
            new PluginCompatibilityPolicy("0.56.0").apiRange(),
            new PluginCompatibilityPolicy("0.56.1").apiRange()
        );
    }
}
