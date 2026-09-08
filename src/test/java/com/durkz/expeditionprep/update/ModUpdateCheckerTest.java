package com.durkz.expeditionprep.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModUpdateCheckerTest {
    @Test void newerSemanticVersionIsDetected() {
        assertTrue(ModUpdateChecker.isNewer("0.1.1", "0.1.0"));
        assertTrue(ModUpdateChecker.isNewer("1.0.0", "0.9.9"));
        assertTrue(ModUpdateChecker.isNewer("v1.2.0", "1.1.9"));
    }

    @Test void currentOrOlderVersionIsNotReported() {
        assertFalse(ModUpdateChecker.isNewer("1.0.0", "1.0.0"));
        assertFalse(ModUpdateChecker.isNewer("0.9.9", "1.0.0"));
        assertFalse(ModUpdateChecker.isNewer(null, "1.0.0"));
    }
}
