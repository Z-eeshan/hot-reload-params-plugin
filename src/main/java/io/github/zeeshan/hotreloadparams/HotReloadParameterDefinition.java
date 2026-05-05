package io.github.zeeshan.hotreloadparams;

import hudson.Extension;
import hudson.model.BooleanParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterValue;
import hudson.model.TextParameterValue;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
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
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        // Return a simple marker value so Jenkins doesn't complain about null.
        return new StringParameterValue(getName(), jo.optString("value", ""));
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req) {
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
        public void doFetchParams(StaplerRequest2 req, StaplerResponse2 rsp,
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
                        if (p.choices != null && !p.choices.isEmpty()) {
                            obj.put("choices", JSONArray.fromObject(p.choices));
                        }
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
        public void doClearCache(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null) {
                jenkins.checkPermission(Jenkins.ADMINISTER);
            }
            ConfigFetcher.clearCache();
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write("{\"status\":\"ok\",\"message\":\"Cache cleared\"}");
        }

        /**
         * Build-submission endpoint that bypasses {@link ParametersDefinitionProperty#_doBuild}.
         * Required because the plugin lets the target branch add/remove parameters; Jenkins'
         * built-in build action rejects any submitted parameter that isn't declared on the job.
         * <p>
         * This endpoint trusts the {@code drpType} hint the plugin's JS puts on every parameter
         * row, constructs the matching {@link ParameterValue}, and schedules the build directly.
         */
        @RequirePOST
        public void doTriggerBuild(StaplerRequest2 req, StaplerResponse2 rsp,
                                   @QueryParameter("drpJobFullName") String jobFullName)
                throws IOException, ServletException {

            if (jobFullName == null || jobFullName.isEmpty()) {
                jobFullName = req.getParameter("drpJobFullName");
            }
            if (jobFullName == null || jobFullName.isEmpty()) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST, "drpJobFullName required");
                return;
            }

            Jenkins jenkins = Jenkins.get();
            Job<?, ?> job = jenkins.getItemByFullName(jobFullName, Job.class);
            if (job == null) {
                rsp.sendError(HttpServletResponse.SC_NOT_FOUND, "Job not found: " + jobFullName);
                return;
            }
            job.checkPermission(Item.BUILD);

            if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob)) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Job is not parameterized: " + jobFullName);
                return;
            }

            ParametersDefinitionProperty pdp = job.getProperty(ParametersDefinitionProperty.class);

            JSONObject formData = req.getSubmittedForm();
            List<JSONObject> entries = extractParameterEntries(formData);

            List<ParameterValue> values = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JSONObject jo : entries) {
                String name = jo.optString("name", null);
                if (name == null || name.isEmpty()) continue;
                if ("HOT_RELOAD_PARAMS".equals(name)) continue;
                if (!seen.add(name)) continue;

                String drpType = jo.optString("drpType", null);
                ParameterValue pv = null;

                if (drpType == null || drpType.isEmpty()) {
                    // No type hint — trust the job's definition if present.
                    ParameterDefinition def = pdp != null ? pdp.getParameterDefinition(name) : null;
                    if (def != null) {
                        try {
                            pv = def.createValue(req, jo);
                        } catch (RuntimeException e) {
                            LOGGER.log(Level.FINE,
                                    "Falling back to string value for " + name + ": " + e.getMessage());
                        }
                    }
                }
                if (pv == null) {
                    pv = createFallbackValue(name, drpType, jo);
                }
                if (pv != null) values.add(pv);
            }

            LOGGER.log(Level.INFO, "Scheduling build of {0} with {1} parameters",
                    new Object[]{jobFullName, values.size()});

            ParameterizedJobMixIn.ParameterizedJob<?, ?> pjob =
                    (ParameterizedJobMixIn.ParameterizedJob<?, ?>) job;
            pjob.scheduleBuild2(pjob.getQuietPeriod(),
                    new CauseAction(new Cause.UserIdCause()),
                    new ParametersAction(values));

            rsp.sendRedirect2(req.getContextPath() + "/" + job.getUrl());
        }

        /**
         * Flatten the submitted form's {@code parameter} slot into a list of JSON objects.
         * Jenkins' form serializer emits either a JSONArray (2+ parameters) or a lone
         * JSONObject (exactly one parameter), so handle both.
         */
        private static List<JSONObject> extractParameterEntries(JSONObject formData) {
            List<JSONObject> out = new ArrayList<>();
            Object raw = formData.opt("parameter");
            if (raw instanceof JSONArray) {
                JSONArray arr = (JSONArray) raw;
                for (int i = 0; i < arr.size(); i++) {
                    Object e = arr.get(i);
                    if (e instanceof JSONObject) out.add((JSONObject) e);
                }
            } else if (raw instanceof JSONObject) {
                out.add((JSONObject) raw);
            }
            return out;
        }

        private static ParameterValue createFallbackValue(String name, String drpType, JSONObject jo) {
            String value = jo.optString("value", "");
            String type = drpType != null ? drpType : "string";
            switch (type) {
                case "boolean":
                    boolean b = "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
                    if (jo.has("value") && jo.get("value") instanceof Boolean) {
                        b = jo.getBoolean("value");
                    }
                    return new BooleanParameterValue(name, b);
                case "password":
                    return new PasswordParameterValue(name, value);
                case "text":
                    return new TextParameterValue(name, value);
                case "choice":
                case "string":
                case "imageTag":
                default:
                    return new StringParameterValue(name, value);
            }
        }
    }
}
