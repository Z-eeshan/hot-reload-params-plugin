package io.github.zeeshan.hotreloadparams;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import hudson.model.Item;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fetches a Groovy parameter definitions file from a Git repository branch or tag.
 * <p>
 * Uses JGit to look up the ref on the remote, perform a shallow (depth 1) fetch of
 * just that ref into a throw-away bare repository, and read the requested file from
 * the resulting tree. Successful results are cached for a short time using Caffeine
 * so that repeated requests from the "Build with Parameters" page do not hit Git
 * every time.
 */
public class ConfigFetcher {
    private static final Logger LOGGER = Logger.getLogger(ConfigFetcher.class.getName());

    // Cache: key = "repoUrl|ref|filePath", value = file content
    private static final Cache<String, String> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1000)
            .build();

    private final String repoUrl;
    private final String credentialsId;
    private final String paramFilePath;
    private final String defaultBranch;
    @CheckForNull
    private final Item context;

    public ConfigFetcher(String repoUrl, String credentialsId, String paramFilePath, String defaultBranch) {
        this(repoUrl, credentialsId, paramFilePath, defaultBranch, null);
    }

    /**
     * @param context the item (job) on whose behalf credentials are looked up; may be {@code null}
     *                to look up credentials at the system scope only
     */
    public ConfigFetcher(String repoUrl, String credentialsId, String paramFilePath, String defaultBranch,
                         @CheckForNull Item context) {
        this.repoUrl = repoUrl;
        this.credentialsId = credentialsId;
        this.paramFilePath = paramFilePath;
        this.defaultBranch = defaultBranch != null ? defaultBranch : "master";
        this.context = context;
    }

    /**
     * Fetch the parameter definitions file content for the given trigger value.
     * <p>
     * Resolution order:
     * <ol>
     * <li>the trigger value as a branch or tag name (e.g. {@code release/v3.5.0})</li>
     * <li>the part of the trigger value after the last {@code /} (e.g. {@code v3.5.0})</li>
     * <li>the configured default branch</li>
     * </ol>
     *
     * @param triggerValue the value of the trigger parameter
     * @return the resolved file content, or {@code null} if it is not available on any candidate ref
     */
    @CheckForNull
    public FetchResult fetch(@CheckForNull String triggerValue) {
        LOGGER.log(Level.FINE, "fetch() triggerValue={0}, defaultBranch={1}, paramFilePath={2}",
                new Object[]{triggerValue, defaultBranch, paramFilePath});

        String content = fetchFromRef(triggerValue);
        if (content != null) {
            return new FetchResult(content, triggerValue, false);
        }

        if (triggerValue != null && triggerValue.contains("/")) {
            String extracted = triggerValue.substring(triggerValue.lastIndexOf('/') + 1);
            content = fetchFromRef(extracted);
            if (content != null) {
                return new FetchResult(content, extracted, false);
            }
        }

        content = fetchFromRef(defaultBranch);
        if (content != null) {
            return new FetchResult(content, defaultBranch, true);
        }

        LOGGER.log(Level.FINE, "Could not fetch {0} from any ref in {1}", new Object[]{paramFilePath, repoUrl});
        return null;
    }

    private String fetchFromRef(String ref) {
        if (ref == null || ref.isEmpty() || repoUrl == null || repoUrl.isEmpty()) {
            return null;
        }

        String cacheKey = repoUrl + "|" + ref + "|" + paramFilePath;
        String cached = CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            LOGGER.log(Level.FINE, "Cache hit for {0}", cacheKey);
            return cached;
        }

        try {
            String content = doFetch(ref);
            if (content != null) {
                CACHE.put(cacheKey, content);
            }
            return content;
        } catch (IOException | GitAPIException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not fetch " + paramFilePath + " from ref " + ref + " of " + repoUrl, e);
            return null;
        }
    }

    /**
     * Resolve {@code ref} against the remote (branches first, then tags), shallow-fetch that
     * single ref into a temporary bare repository and read {@link #paramFilePath} from it.
     */
    private String doFetch(String ref) throws IOException, GitAPIException {
        org.eclipse.jgit.transport.CredentialsProvider gitCredentials = resolveCredentials();

        String fullRef = resolveRemoteRef(ref, gitCredentials);
        if (fullRef == null) {
            LOGGER.log(Level.FINE, "Ref {0} not found in {1}", new Object[]{ref, repoUrl});
            return null;
        }

        Path tempDir = Files.createTempDirectory("hot-reload-params-");
        try (Git git = Git.init().setBare(true).setDirectory(tempDir.toFile()).call()) {
            git.fetch()
                    .setRemote(repoUrl)
                    .setRefSpecs(new RefSpec("+" + fullRef + ":" + fullRef))
                    .setDepth(1)
                    .setTagOpt(TagOpt.NO_TAGS)
                    .setCredentialsProvider(gitCredentials)
                    .call();

            Repository repo = git.getRepository();
            Ref localRef = repo.exactRef(fullRef);
            if (localRef == null || localRef.getObjectId() == null) {
                return null;
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                // parseCommit peels annotated tags for us.
                RevCommit commit = revWalk.parseCommit(localRef.getObjectId());
                try (TreeWalk treeWalk = TreeWalk.forPath(repo, paramFilePath, commit.getTree())) {
                    if (treeWalk == null) {
                        LOGGER.log(Level.FINE, "File {0} not found in {1}", new Object[]{paramFilePath, fullRef});
                        return null;
                    }
                    ObjectId blobId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repo.open(blobId);
                    return new String(loader.getBytes(), StandardCharsets.UTF_8);
                }
            }
        } finally {
            try {
                Util.deleteRecursive(tempDir.toFile());
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Failed to delete temporary directory " + tempDir, e);
            }
        }
    }

    /**
     * @return the fully qualified ref name ({@code refs/heads/...} or {@code refs/tags/...})
     *         that {@code ref} resolves to on the remote, or {@code null} if there is none
     */
    @CheckForNull
    private String resolveRemoteRef(String ref, org.eclipse.jgit.transport.CredentialsProvider gitCredentials)
            throws GitAPIException {
        Map<String, Ref> remoteRefs = Git.lsRemoteRepository()
                .setRemote(repoUrl)
                .setHeads(true)
                .setTags(true)
                .setCredentialsProvider(gitCredentials)
                .callAsMap();
        if (remoteRefs.containsKey(Constants.R_HEADS + ref)) {
            return Constants.R_HEADS + ref;
        }
        if (remoteRefs.containsKey(Constants.R_TAGS + ref)) {
            return Constants.R_TAGS + ref;
        }
        return null;
    }

    /**
     * Resolve the configured Jenkins credentials (if any) to a JGit credentials provider.
     * Credentials are looked up in the scope of {@link #context} so that folder-scoped
     * credentials visible to the job can be used.
     */
    @CheckForNull
    private org.eclipse.jgit.transport.CredentialsProvider resolveCredentials() {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }
        if (Jenkins.getInstanceOrNull() == null) {
            return null;
        }

        StandardUsernamePasswordCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItem(
                        StandardUsernamePasswordCredentials.class,
                        context,
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(repoUrl).build()),
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
     * Clears the fetch cache (used by the admin endpoint and by tests).
     */
    public static void clearCache() {
        CACHE.invalidateAll();
    }

    public static final class FetchResult {
        @NonNull
        public final String content;
        public final String resolvedBranch;
        public final boolean isFallback;

        public FetchResult(@NonNull String content, String resolvedBranch, boolean isFallback) {
            this.content = content;
            this.resolvedBranch = resolvedBranch;
            this.isFallback = isFallback;
        }
    }
}
