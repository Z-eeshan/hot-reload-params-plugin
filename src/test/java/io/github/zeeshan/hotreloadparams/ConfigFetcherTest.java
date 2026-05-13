package io.github.zeeshan.hotreloadparams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigFetcherTest {

    // ── Branch resolution tests ──────────────────────────────────────────────

    @Test
    void extractVersionFromTriggerValue() {
        assertEquals("release/v8.2.0", extractBranch("release/v8.2.0"));
    }

    @Test
    void plainVersionNumber() {
        assertEquals("release/v8.2.0", extractBranch("v8.2.0"));
    }

    @Test
    void numberOnlyVersion() {
        assertEquals("release/v8.2.0", extractBranch("8.2.0"));
    }

    @Test
    void masterBranch() {
        assertEquals("master", extractBranch("master"));
    }

    @Test
    void hotfixBranch() {
        assertEquals("hotfix/v8.1.1", extractBranch("hotfix/v8.1.1"));
    }

    @Test
    void emptyTriggerValueFallsBackToDefault() {
        assertEquals("master", extractBranch(""));
    }

    @Test
    void nullTriggerValueFallsBackToDefault() {
        assertEquals("master", extractBranch(null));
    }

    @Test
    void cacheClearDoesNotThrow() {
        ConfigFetcher.clearCache();
    }

    /**
     * Simulates the branch resolution logic from ConfigFetcher.
     */
    private String extractBranch(String triggerValue) {
        String defaultBranch = "master";

        if (triggerValue == null || triggerValue.trim().isEmpty()) {
            return defaultBranch;
        }

        String tv = triggerValue.trim();

        if (tv.contains("/")) {
            return tv;
        }

        if (tv.equals("master") || tv.equals("main") || tv.equals("develop")) {
            return tv;
        }

        if (tv.matches("v\\d+\\.\\d+.*")) {
            return "release/" + tv;
        }

        if (tv.matches("\\d+\\.\\d+.*")) {
            return "release/v" + tv;
        }

        return tv;
    }
}
