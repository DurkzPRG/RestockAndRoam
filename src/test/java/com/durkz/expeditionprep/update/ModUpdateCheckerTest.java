package com.durkz.expeditionprep.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModUpdateCheckerTest {
    @Test void checksCanonicalModPageAndCurseForgeProject() {
        assertEquals("https://durkzprgmods.pages.dev/mods/restock-and-roam/", ModUpdateChecker.PAGE_URL);
        assertEquals("https://www.curseforge.com/hytale/mods/restock-and-roam", ModUpdateChecker.DOWNLOAD_URL);
        assertFalse(ModUpdateChecker.DOWNLOAD_URL.contains("/files"));
    }

    @Test void parsesJarPinFromLiveInstallationHtml() {
        String html = """
                <h2>Installation</h2>
                <ol>
                <li>Requires <strong>Hytale 0.6.0+</strong></li>
                <li>Download <strong>RestockAndRoam-1.0.0.jar</strong> from the CurseForge Files tab</li>
                </ol>
                """;
        assertEquals("1.0.0", ModUpdateChecker.parseLatestJarVersion(html));
    }

    @Test void missingJarPinIsIgnored() {
        assertNull(ModUpdateChecker.parseLatestJarVersion("<p>Restock &amp; Roam</p>"));
        assertNull(ModUpdateChecker.parseLatestJarVersion(""));
        assertNull(ModUpdateChecker.parseLatestJarVersion(null));
    }

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
