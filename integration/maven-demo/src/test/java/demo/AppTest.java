package demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AppTest {
    @Test void addWorks() { assertEquals(5, App.add(2, 3)); }

    // WU-LPR-063 failure path: -Ddemo.broken=true requires a deliberate failure.
    @Test void deliberateBreakSwitch() {
        String broken = System.getProperty("demo.broken");
        System.out.println("demo.broken=[" + broken + "]");
        assertTrue(!"true".equals(broken), "demo.broken=true requested a failing build");
    }
}
