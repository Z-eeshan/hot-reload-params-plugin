package io.github.zeeshan.hotreloadparams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link ConfigFetcher} against a real (local) Git repository.
 */
class ConfigFetcherTest {

    private static final String FILE = "vars/release-pipeline.groovy";

    private static final String MASTER_CONTENT = "parameters {\n"
            + "  string(name: 'DEPLOY_ENV', defaultValue: 'staging', description: 'env')\n"
            + "}\n";
    private static final String TAGGED_CONTENT = "parameters {\n"
            + "  string(name: 'DEPLOY_ENV', defaultValue: 'prod', description: 'env')\n"
            + "  booleanParam(name: 'mybool', defaultValue: true, description: 'flag')\n"
            + "}\n";
    private static final String RELEASE_CONTENT = "parameters {\n"
            + "  string(name: 'DEPLOY_ENV', defaultValue: 'prod-latest', description: 'env')\n"
            + "  booleanParam(name: 'mybool', defaultValue: true, description: 'flag')\n"
            + "}\n";

    @TempDir
    Path tmp;

    private Path repoDir;
    private String repoUrl;

    @BeforeEach
    void createRepository() throws Exception {
        repoDir = tmp.resolve("origin");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("master").call()) {
            commit(git, FILE, MASTER_CONTENT, "master");
            git.checkout().setCreateBranch(true).setName("release/v1.0.0").call();
            commit(git, FILE, TAGGED_CONTENT, "release candidate");
            git.tag().setName("v2.0.0").setAnnotated(true).setMessage("v2.0.0").setSigned(false).call();
            commit(git, FILE, RELEASE_CONTENT, "release tip");
            git.checkout().setName("master").call();
        }
        repoUrl = repoDir.toUri().toString();
        ConfigFetcher.clearCache();
    }

    private void commit(Git git, String path, String content, String message) throws Exception {
        Path file = repoDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        git.commit().setMessage(message)
                .setAuthor("test", "test@example.com")
                .setCommitter("test", "test@example.com")
                .setSign(false)
                .call();
    }

    private ConfigFetcher fetcher() {
        return new ConfigFetcher(repoUrl, null, FILE, "master");
    }

    @Test
    void exactBranchMatch() {
        ConfigFetcher.FetchResult r = fetcher().fetch("release/v1.0.0");
        assertNotNull(r);
        assertEquals(RELEASE_CONTENT, r.content);
        assertEquals("release/v1.0.0", r.resolvedBranch);
        assertFalse(r.isFallback);
    }

    @Test
    void suffixAfterSlashResolvesTag() {
        // "hotfix/v2.0.0" is not a branch, but "v2.0.0" is an (annotated) tag.
        ConfigFetcher.FetchResult r = fetcher().fetch("hotfix/v2.0.0");
        assertNotNull(r);
        assertEquals(TAGGED_CONTENT, r.content);
        assertEquals("v2.0.0", r.resolvedBranch);
        assertFalse(r.isFallback);
    }

    @Test
    void unknownBranchFallsBackToDefault() {
        ConfigFetcher.FetchResult r = fetcher().fetch("does/not/exist");
        assertNotNull(r);
        assertEquals(MASTER_CONTENT, r.content);
        assertEquals("master", r.resolvedBranch);
        assertTrue(r.isFallback);
    }

    @Test
    void nullTriggerValueFallsBackToDefault() {
        ConfigFetcher.FetchResult r = fetcher().fetch(null);
        assertNotNull(r);
        assertEquals("master", r.resolvedBranch);
        assertTrue(r.isFallback);
    }

    @Test
    void missingFileReturnsNull() {
        assertNull(new ConfigFetcher(repoUrl, null, "does-not-exist.groovy", "master").fetch("master"));
    }

    @Test
    void unreachableRepositoryReturnsNull() {
        String missing = tmp.resolve("nope").toUri().toString();
        assertNull(new ConfigFetcher(missing, null, FILE, "master").fetch("master"));
    }

    @Test
    void resultsAreCached() throws IOException {
        ConfigFetcher.FetchResult first = fetcher().fetch("master");
        assertNotNull(first);

        // Remove the repository; a second fetch must be served from the cache.
        try (Stream<Path> walk = Files.walk(repoDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        ConfigFetcher.FetchResult second = fetcher().fetch("master");
        assertNotNull(second);
        assertEquals(first.content, second.content);

        ConfigFetcher.clearCache();
        assertNull(fetcher().fetch("master"));
    }
}
