package io.github.zeeshan.hotreloadparams;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fetches a Groovy parameter definitions file from a Git repository branch.
 * <p>
 * Uses JGit to clone (shallow, bare) or fetch the repo, then reads the specific file
 * from the requested branch. Includes in-memory caching with TTL.
 */
public class ConfigFetcher {
    private static final Logger LOGGER = Logger.getLogger(ConfigFetcher.class.getName());
    private static final long CACHE_TTL_MS = 60_000; // 60 seconds

    // Cache: key = "repoUrl|branch|filePath", value = CacheEntry
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private final String repoUrl;
    private final String credentialsId;
    private final String paramFilePath;
    private final String defaultBranch;

    public ConfigFetcher(String repoUrl, String credentialsId, String paramFilePath, String defaultBranch) {
        this.repoUrl = repoUrl;
        this.credentialsId = credentialsId;
        this.paramFilePath = paramFilePath;
        this.defaultBranch = defaultBranch != null ? defaultBranch : "master";
    }

    /**
     * Fetch the parameter definitions file content for the given trigger value.
     * <p>
     * Resolution order:
     * 1. Try the trigger value as a branch name (e.g., "release/experienceai-v3.5.0")
     * 2. If not found, try the trigger value after "/" (e.g., "experienceai-v3.5.0") as a branch/tag
     * 3. Fall back to defaultBranch (e.g., "master")
     *
     * @param triggerValue the value from the trigger parameter (e.g., RELEASE_BRANCH)
     * @return the file content, or null if completely unavailable
     */
    public FetchResult fetch(String triggerValue) {
        LOGGER.log(Level.INFO, "fetch() triggerValue={0}, defaultBranch={1}, paramFilePath={2}",
                new Object[]{triggerValue, defaultBranch, paramFilePath});

        // Try exact branch match first
        String content = fetchFromBranch(triggerValue);
        if (content != null) {
            LOGGER.log(Level.INFO, "Matched exact branch: {0}", triggerValue);
            return new FetchResult(content, triggerValue, false);
        }

        // Try extracted version as branch
        if (triggerValue != null && triggerValue.contains("/")) {
            String extracted = triggerValue.substring(triggerValue.lastIndexOf('/') + 1);
            LOGGER.log(Level.INFO, "Trying extracted branch name: {0}", extracted);
            content = fetchFromBranch(extracted);
            if (content != null) {
                return new FetchResult(content, extracted, false);
            }
        }

        // Fallback to default branch
        LOGGER.log(Level.INFO, "Falling back to defaultBranch: {0}", defaultBranch);
        content = fetchFromBranch(defaultBranch);
        if (content != null) {
            return new FetchResult(content, defaultBranch, true);
        }

        LOGGER.log(Level.WARNING, "Could not fetch {0} from any branch in {1}",
                new Object[]{paramFilePath, repoUrl});
        return null;
    }

    /**
     * Fetch file content from a specific branch, with caching.
     */
    private String fetchFromBranch(String branch) {
        if (branch == null || branch.isEmpty()) {
            return null;
        }

        String cacheKey = repoUrl + "|" + branch + "|" + paramFilePath;
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            LOGGER.log(Level.FINE, "Cache hit for {0}", cacheKey);
            return cached.content;
        }

        try {
            String content = doFetch(branch);
            if (content != null) {
                CACHE.put(cacheKey, new CacheEntry(content));
            }
            return content;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not fetch from branch " + branch + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Actually fetch the file from Git using JGit.
     * Uses 'git archive' approach via a temporary bare clone.
     */
    private String doFetch(String branch) throws IOException, GitAPIException {
        Path tempDir = Files.createTempDirectory("hot-reload-params-");
        try {
            org.eclipse.jgit.transport.CredentialsProvider gitCredentials = resolveCredentials();

            // Clone bare, single-branch, depth 1
            Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempDir.toFile())
                    .setBare(true)
                    .setCloneAllBranches(false)
                    .setBranchesToClone(Collections.singletonList("refs/heads/" + branch))
                    .setBranch("refs/heads/" + branch)
                    .setDepth(1)
                    .setCredentialsProvider(gitCredentials)
                    .call();

            try (Repository repo = git.getRepository()) {
                // Try to resolve as branch first, then as tag
                Ref ref = repo.exactRef("refs/heads/" + branch);
                if (ref == null) {
                    ref = repo.exactRef("refs/tags/" + branch);
                }
                if (ref == null) {
                    // Try remote refs
                    ref = repo.exactRef("refs/remotes/origin/" + branch);
                }
                if (ref == null) {
                    return null;
                }

                ObjectId commitId = ref.getObjectId();
                try (RevWalk revWalk = new RevWalk(repo)) {
                    RevCommit commit = revWalk.parseCommit(commitId);
                    RevTree tree = commit.getTree();

                    try (TreeWalk treeWalk = new TreeWalk(repo)) {
                        treeWalk.addTree(tree);
                        treeWalk.setRecursive(true);
                        treeWalk.setFilter(PathFilter.create(paramFilePath));

                        if (!treeWalk.next()) {
                            LOGGER.log(Level.FINE, "File {0} not found in branch {1}",
                                    new Object[]{paramFilePath, branch});
                            return null;
                        }

                        ObjectId blobId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repo.open(blobId);
                        return new String(loader.getBytes(), StandardCharsets.UTF_8);
                    }
                }
            } finally {
                git.close();
            }
        } finally {
            // Clean up temp directory
            deleteRecursively(tempDir.toFile());
        }
    }

    /**
     * Resolve Jenkins credentials to JGit credentials provider.
     */
    private org.eclipse.jgit.transport.CredentialsProvider resolveCredentials() {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }

        StandardUsernamePasswordCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        jenkins,
                        ACL.SYSTEM,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));

        if (creds != null) {
            return new UsernamePasswordCredentialsProvider(
                    creds.getUsername(),
                    creds.getPassword().getPlainText());
        }

        LOGGER.log(Level.WARNING, "Credentials not found: {0}", credentialsId);
        return null;
    }

    /**
     * Recursively delete a directory.
     */
    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            LOGGER.log(Level.FINE, "Failed to delete: {0}", file.getAbsolutePath());
        }
    }

    /**
     * Clears the fetch cache (useful for testing or manual refresh).
     */
    public static void clearCache() {
        CACHE.clear();
    }

    // ── Inner classes ──────────────────────────────────────────────────────

    /**
     * Result of a config fetch operation.
     */
    public static class FetchResult {
        public final String content;
        public final String resolvedBranch;
        public final boolean isFallback;

        public FetchResult(String content, String resolvedBranch, boolean isFallback) {
            this.content = content;
            this.resolvedBranch = resolvedBranch;
            this.isFallback = isFallback;
        }
    }

    /**
     * Cache entry with TTL.
     */
    private static class CacheEntry {
        final String content;
        final long timestamp;

        CacheEntry(String content) {
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_TTL_MS;
        }
    }
}
