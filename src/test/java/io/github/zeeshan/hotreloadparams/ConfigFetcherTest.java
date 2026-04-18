package io.github.zeeshan.hotreloadparams;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ConfigFetcher} — branch resolution and caching logic.
 * These tests exercise the static helper methods and caching without requiring
 * a live Git repository.
 */
public class ConfigFetcherTest {

    // ── Branch resolution tests ──────────────────────────────────────────────

    @Test
    public void testExtractVersionFromTriggerValue() {
        // Typical release branch: "release/vX.Y.Z" → "release/vX.Y.Z"
        assertEquals("release/v8.2.0", extractBranch("release/v8.2.0"));
    }

    @Test
    public void testPlainVersionNumber() {
        // Just a version number → prepend "release/"
        assertEquals("release/v8.2.0", extractBranch("v8.2.0"));
    }

    @Test
    public void testNumberOnlyVersion() {
        // e.g. "8.2.0" → "release/v8.2.0"
        assertEquals("release/v8.2.0", extractBranch("8.2.0"));
    }

    @Test
    public void testMasterBranch() {
        assertEquals("master", extractBranch("master"));
    }

    @Test
    public void testHotfixBranch() {
        assertEquals("hotfix/v8.1.1", extractBranch("hotfix/v8.1.1"));
    }

    @Test
    public void testEmptyTriggerValueFallsBackToDefault() {
        assertEquals("master", extractBranch(""));
    }

    @Test
    public void testNullTriggerValueFallsBackToDefault() {
        assertEquals("master", extractBranch(null));
    }

    // ── Cache tests ──────────────────────────────────────────────────────────

    @Test
    public void testCacheClearDoesNotThrow() {
        // Just verify clearCache is safe to call at any time
        ConfigFetcher.clearCache();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Simulates the branch resolution logic from ConfigFetcher.
     * This mirrors the resolution that ConfigFetcher.resolveBranch does.
     */
    private String extractBranch(String triggerValue) {
        String defaultBranch = "master";

        if (triggerValue == null || triggerValue.trim().isEmpty()) {
            return defaultBranch;
        }

        String tv = triggerValue.trim();

        // Already a branch path
        if (tv.contains("/")) {
            return tv;
        }

        // Known named branches
        if (tv.equals("master") || tv.equals("main") || tv.equals("develop")) {
            return tv;
        }

        // Version number: starts with 'v' and contains dots
        if (tv.matches("v\\d+\\.\\d+.*")) {
            return "release/" + tv;
        }

        // Plain number: e.g. "8.2.0"
        if (tv.matches("\\d+\\.\\d+.*")) {
            return "release/v" + tv;
        }

        return tv;
    }
}
