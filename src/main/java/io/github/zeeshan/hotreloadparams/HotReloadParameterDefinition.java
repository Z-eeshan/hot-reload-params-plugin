package io.github.zeeshan.hotreloadparams;

import hudson.Extension;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.GET;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A lightweight Jenkins {@link ParameterDefinition} that acts as a "defaults
 * reloader". It does NOT create its own sub-parameters. Instead, the real
 * parameters are defined normally in the pipeline's {@code parameters{}} block.
 * <p>
 * On the "Build with Parameters" page, JavaScript watches the trigger parameter
 * (e.g., RELEASE_BRANCH). When the user changes it, the plugin fetches the DSL
 * file from the matching Git branch, parses it, and <b>updates the default
 * values of existing parameter fields</b> on the page via AJAX.
 * <p>
 * Usage in a Jenkinsfile:
 * <pre>
 * parameters {
 *     string(name: 'RELEASE_BRANCH', ...)
 *     string(name: 'DEPLOY_ENV', defaultValue: 'staging', ...)
 *     string(name: 'API_VERSION', defaultValue: 'v1', ...)
 *     // ... all params defined normally ...
 *     hotReloadParams(
 *         repoUrl: '...',
 *         credentialsId: '...',
 *         paramFilePath: 'vars/release-pipeline.groovy',
 *         triggerParamName: 'RELEASE_BRANCH',
 *         defaultBranch: 'master'
 *     )
 * }
 * </pre>
 */
public class HotReloadParameterDefinition extends ParameterDefinition {
    private static final long serialVersionUID = 2L;
    private static final Logger LOGGER = Logger.getLogger(HotReloadParameterDefinition.class.getName());

    private String repoUrl;
    private String credentialsId;
    private String paramFilePath;
    private String triggerParamName;
    private String defaultBranch;

    @DataBoundConstructor
    public HotReloadParameterDefinition() {
        super("HOT_RELOAD_PARAMS", "Watches trigger param and reloads defaults from a Groovy DSL file");
    }

    // ── Getters & Setters ──────────────────────────────────────────────────

    public String getRepoUrl() { return repoUrl; }
    @DataBoundSetter
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getCredentialsId() { return credentialsId; }
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) { this.credentialsId = credentialsId; }

    public String getParamFilePath() { return paramFilePath; }
    @DataBoundSetter
    public void setParamFilePath(String paramFilePath) {
        this.paramFilePath = paramFilePath != null ? paramFilePath : "vars/release-pipeline.groovy";
    }

    public String getTriggerParamName() { return triggerParamName; }
    @DataBoundSetter
    public void setTriggerParamName(String triggerParamName) {
        this.triggerParamName = triggerParamName != null ? triggerParamName : "RELEASE_BRANCH";
    }

    public String getDefaultBranch() { return defaultBranch; }
    @DataBoundSetter
    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch != null ? defaultBranch : "master";
    }

    // ── Parameter value creation ───────────────────────────────────────────
    // This plugin contributes NO build parameter values of its own.
    // All real parameters are native Jenkins params and handled by Jenkins core.

    @Override
    public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
        // Return a simple marker value so Jenkins doesn't complain about null.
        return new StringParameterValue(getName(), jo.optString("value", ""));
    }

    @Override
    public ParameterValue createValue(StaplerRequest req) {
        return new StringParameterValue(getName(), "");
    }

    // ── Descriptor ─────────────────────────────────────────────────────────

    @Extension
    @Symbol("hotReloadParams")
    public static class DescriptorImpl extends ParameterDefinition.ParameterDescriptor {

        @Override
        public String getDisplayName() {
            return "Hot Reload Parameters";
        }

        /**
         * AJAX endpoint: Fetch parameter defaults from a pipeline file on a given branch.
         * <p>
         * Called from the "Build with Parameters" page when the user changes the trigger param.
         * Returns JSON with {@code params} as a simple array of {@code {name, defaultValue}} objects.
         */
        @GET
        public void doFetchParams(StaplerRequest req, StaplerResponse rsp,
                                  @QueryParameter String triggerValue,
                                  @QueryParameter String repoUrl,
                                  @QueryParameter String credentialsId,
                                  @QueryParameter String paramFilePath,
                                  @QueryParameter String defaultBranch) throws IOException, ServletException {

            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null) {
                jenkins.checkPermission(Jenkins.READ);
            }

            JSONObject response = new JSONObject();

            LOGGER.log(Level.INFO, "doFetchParams called: triggerValue={0}, paramFilePath={1}, defaultBranch={2}",
                    new Object[]{triggerValue, paramFilePath, defaultBranch});

            try {
                ConfigFetcher fetcher = new ConfigFetcher(repoUrl, credentialsId,
                        paramFilePath != null ? paramFilePath : "vars/release-pipeline.groovy",
                        defaultBranch != null ? defaultBranch : "master");

                ConfigFetcher.FetchResult fetchResult = fetcher.fetch(triggerValue);

                if (fetchResult == null) {
                    LOGGER.log(Level.WARNING, "fetchResult is null for triggerValue={0}", triggerValue);
                    response.put("error", "Could not fetch pipeline file from any branch");
                    response.put("params", new JSONArray());
                } else {
                    LOGGER.log(Level.INFO, "Fetched from branch={0}, isFallback={1}, contentLength={2}",
                            new Object[]{fetchResult.resolvedBranch, fetchResult.isFallback,
                                    fetchResult.content != null ? fetchResult.content.length() : 0});

                    ParamConfigParser parser = new ParamConfigParser();
                    List<ParamConfigParser.ParsedParam> parsedParams = parser.parseParams(fetchResult.content);

                    LOGGER.log(Level.INFO, "Parsed {0} params from branch={1}",
                            new Object[]{parsedParams.size(), fetchResult.resolvedBranch});

                    response.put("resolvedBranch", fetchResult.resolvedBranch);
                    response.put("isFallback", fetchResult.isFallback);

                    JSONArray paramsArray = new JSONArray();
                    for (ParamConfigParser.ParsedParam p : parsedParams) {
                        JSONObject obj = new JSONObject();
                        obj.put("name", p.name);
                        obj.put("type", p.type);
                        obj.put("defaultValue", p.defaultValue != null ? p.defaultValue : "");
                        if (p.description != null) obj.put("description", p.description);
                        if (p.image != null) obj.put("image", p.image);
                        if (p.defaultTag != null) obj.put("defaultTag", p.defaultTag);
                        if (p.sectionHeader != null) obj.put("sectionHeader", p.sectionHeader);
                        paramsArray.add(obj);
                    }
                    response.put("params", paramsArray);
                }
            } catch (ParamConfigParser.ParamConfigParseException e) {
                LOGGER.log(Level.WARNING, "Failed to parse pipeline params", e);
                response.put("error", "Parse error: " + e.getMessage());
                response.put("params", new JSONArray());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Unexpected error in doFetchParams", e);
                response.put("error", "Unexpected error: " + e.getMessage());
                response.put("params", new JSONArray());
            }

            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write(response.toString());
        }

        /**
         * AJAX endpoint: Force-clear the config cache.
         */
        @GET
        public void doClearCache(StaplerRequest req, StaplerResponse rsp) throws IOException {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null) {
                jenkins.checkPermission(Jenkins.ADMINISTER);
            }
            ConfigFetcher.clearCache();
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write("{\"status\":\"ok\",\"message\":\"Cache cleared\"}");
        }
    }
}
