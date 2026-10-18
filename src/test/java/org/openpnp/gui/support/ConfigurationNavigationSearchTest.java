package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConfigurationNavigationSearchTest {
    public static class Camera {
        public double getExposure() { throw new AssertionError("Search must not invoke a hardware getter"); }
    }
    @Test void matchesParentPathChineseAliasesAndParameterNamesWithoutInvokingGetters() {
        assertTrue(ConfigurationNavigationSearch.matches("相机 exposure", "机器 / Head H1 / Top Camera", Camera.class));
        assertTrue(ConfigurationNavigationSearch.matches("h1", "机器 / Head H1 / Top Camera", Camera.class));
        assertFalse(ConfigurationNavigationSearch.matches("飞达", "机器 / Head H1 / Top Camera", Camera.class));
        assertFalse(ConfigurationNavigationSearch.matches("相机 missing", "机器 / Head H1 / Top Camera", Camera.class));
        assertTrue(ConfigurationNavigationSearch.matches("", "机器 / Head H1 / Top Camera", Camera.class));
    }
}
