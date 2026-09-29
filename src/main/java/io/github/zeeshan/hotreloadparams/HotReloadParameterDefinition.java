package io.github.zeeshan.hotreloadparams;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.util.TimeDuration;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

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
 * Every endpoint resolves the job from the request, checks {@link Item#BUILD},
 * and reads the Git configuration from the job's own {@code HotReloadParameterDefinition}.
 * Nothing about the repository, credentials or accepted parameter names is taken
 * from the client.
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

    /** Fixed name of the marker parameter this definition registers on the job. */
    public static final String PARAMETER_NAME = "HOT_RELOAD_PARAMS";

    public static final String DEFAULT_PARAM_FILE_PATH = "vars/release-pipeline.groovy";
    public static final String DEFAULT_TRIGGER_PARAM_NAME = "RELEASE_BRANCH";
    public static final String DEFAULT_BRANCH = "master";

    private String repoUrl;
    private String credentialsId;
    private String paramFilePath;
    private String triggerParamName;
    private String defaultBranch;

    @DataBoundConstructor
    public HotReloadParameterDefinition() {
        super(PARAMETER_NAME);
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
        this.paramFilePath = paramFilePath != null ? paramFilePath : DEFAULT_PARAM_FILE_PATH;
    }

    public String getTriggerParamName() { return triggerParamName; }
    @DataBoundSetter
    public void setTriggerParamName(String triggerParamName) {
        this.triggerParamName = triggerParamName != null ? triggerParamName : DEFAULT_TRIGGER_PARAM_NAME;
    }

    public String getDefaultBranch() { return defaultBranch; }
    @DataBoundSetter
    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch != null ? defaultBranch : DEFAULT_BRANCH;
    }

    @Override
    public String getDescription() {
        return "Watches the trigger parameter and reloads parameter defaults from a Groovy DSL file";
    }

    // ── Effective values (used by the view and the endpoints) ──────────────

    @NonNull
    public String getEffectiveParamFilePath() {
        return paramFilePath != null && !paramFilePath.isEmpty() ? paramFilePath : DEFAULT_PARAM_FILE_PATH;
    }

    @NonNull
    public String getEffectiveTriggerParamName() {
        return triggerParamName != null && !triggerParamName.isEmpty() ? triggerParamName : DEFAULT_TRIGGER_PARAM_NAME;
    }

    @NonNull
    public String getEffectiveDefaultBranch() {
        return defaultBranch != null && !defaultBranch.isEmpty() ? defaultBranch : DEFAULT_BRANCH;
    }

    /**
     * Full name of the job currently being rendered. Used by the Jelly view to
     * emit a data attribute the client-side JS sends back to the endpoints.
     */
    public String getCurrentJobFullName() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req != null) {
            Job<?, ?> job = req.findAncestorObject(Job.class);
            if (job != null) {
                return job.getFullName();
            }
        }
        return "";
    }

    /**
     * Absolute path (including the servlet context path) of this descriptor's
     * URL space, e.g. {@code /jenkins/descriptorByName/io.github....HotReloadParameterDefinition}.
     */
    public String getEndpointUrl() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String contextPath = req != null ? req.getContextPath() : "";
        return contextPath + "/" + getDescriptor().getDescriptorUrl();
    }

    // ── Parameter value creation ───────────────────────────────────────────
    // This plugin contributes NO build parameter values of its own.
    // All real parameters are native Jenkins params and handled by Jenkins core.

    @Override
    @CheckForNull
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        return null;
    }

    @Override
    @CheckForNull
    public ParameterValue createValue(StaplerRequest2 req) {
        return null;
    }

    // ── Branch contract ────────────────────────────────────────────────────

    /**
     * The set of parameters defined by the parameter file of the branch that
     * {@code triggerValue} resolves to. This is computed on the server from the
     * job's own configuration and is the only source of truth for which
     * parameter names (and types) a build submission may contain beyond the
     * parameters declared on the job itself.
     */
    static final class BranchContract {
        final String resolvedBranch;
        final boolean isFallback;
        final Map<String, ParamConfigParser.ParsedParam> params;

        BranchContract(String resolvedBranch, boolean isFallback, List<ParamConfigParser.ParsedParam> parsed) {
            this.resolvedBranch = resolvedBranch;
            this.isFallback = isFallback;
            Map<String, ParamConfigParser.ParsedParam> map = new LinkedHashMap<>();
            for (ParamConfigParser.ParsedParam p : parsed) {
                map.put(p.name, p);
            }
            this.params = Collections.unmodifiableMap(map);
        }
    }

    /**
     * Fetch and parse the parameter file for {@code triggerValue} using this
     * definition's configuration.
     *
     * @return the contract, or {@code null} if the file could not be fetched from any candidate ref
     */
    @CheckForNull
    BranchContract loadBranchContract(@CheckForNull Item context, @CheckForNull String triggerValue)
            throws ParamConfigParser.ParamConfigParseException {
        ConfigFetcher fetcher = new ConfigFetcher(repoUrl, credentialsId, getEffectiveParamFilePath(),
                getEffectiveDefaultBranch(), context);
        ConfigFetcher.FetchResult result = fetcher.fetch(triggerValue);
        if (result == null) {
            return null;
        }
        List<ParamConfigParser.ParsedParam> parsed = new ParamConfigParser().parseParams(result.content);
        return new BranchContract(result.resolvedBranch, result.isFallback, parsed);
    }

    // ── Descriptor ─────────────────────────────────────────────────────────

    @Extension
    @Symbol("hotReloadParams")
    public static class DescriptorImpl extends ParameterDefinition.ParameterDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "Hot Reload Parameters";
        }

        /** Everything the endpoints need to know about the job they act on. */
        private static final class JobContext {
            final Job<?, ?> job;
            final ParameterizedJobMixIn.ParameterizedJob<?, ?> parameterizedJob;
            final ParametersDefinitionProperty property;
            final HotReloadParameterDefinition definition;

            JobContext(Job<?, ?> job, ParametersDefinitionProperty property, HotReloadParameterDefinition definition) {
                this.job = job;
                this.parameterizedJob = (ParameterizedJobMixIn.ParameterizedJob<?, ?>) job;
                this.property = property;
                this.definition = definition;
            }
        }

        /**
         * Resolve the job named by the {@code job} query parameter, enforce
         * {@link Item#BUILD} and locate the job's {@link HotReloadParameterDefinition}.
         * Sends an error response and returns {@code null} when that is not possible.
         */
        @CheckForNull
        private static JobContext resolveJob(StaplerResponse2 rsp, @CheckForNull String jobFullName) throws IOException {
            if (jobFullName == null || jobFullName.isEmpty()) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Query parameter 'job' is required");
                return null;
            }
            Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
            if (job == null) {
                rsp.sendError(HttpServletResponse.SC_NOT_FOUND, "No such job: " + jobFullName);
                return null;
            }
            job.checkPermission(Item.BUILD);

            if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob)) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Job is not parameterizable: " + jobFullName);
                return null;
            }
            ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
            HotReloadParameterDefinition definition = null;
            if (property != null) {
                for (ParameterDefinition d : property.getParameterDefinitions()) {
                    if (d instanceof HotReloadParameterDefinition) {
                        definition = (HotReloadParameterDefinition) d;
                        break;
                    }
                }
            }
            if (definition == null) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Job does not define a Hot Reload Parameters entry: " + jobFullName);
                return null;
            }
            return new JobContext(job, property, definition);
        }

        /**
         * AJAX endpoint used by the "Build with Parameters" page when the trigger
         * parameter changes. Fetches and parses the parameter file for the given
         * trigger value using the job's own configuration and returns the resulting
         * parameter list as JSON.
         */
        @RequirePOST
        public void doFetchParams(StaplerRequest2 req, StaplerResponse2 rsp,
                                  @QueryParameter String job,
                                  @QueryParameter String triggerValue) throws IOException {
            JobContext ctx = resolveJob(rsp, job);
            if (ctx == null) {
                return;
            }

            JSONObject response = new JSONObject();
            try {
                BranchContract contract = ctx.definition.loadBranchContract(ctx.job, triggerValue);
                if (contract == null) {
                    response.put("error", "Could not fetch the parameter file from any branch");
                    response.put("params", new JSONArray());
                } else {
                    response.put("resolvedBranch", contract.resolvedBranch);
                    response.put("isFallback", contract.isFallback);
                    JSONArray paramsArray = new JSONArray();
                    for (ParamConfigParser.ParsedParam p : contract.params.values()) {
                        JSONObject obj = new JSONObject();
                        obj.put("name", p.name);
                        obj.put("type", p.type);
                        obj.put("defaultValue", p.defaultValue);
                        if (p.description != null) obj.put("description", p.description);
                        if (p.image != null) obj.put("image", p.image);
                        if (p.defaultTag != null) obj.put("defaultTag", p.defaultTag);
                        if (p.sectionHeader != null) obj.put("sectionHeader", p.sectionHeader);
                        if (!p.choices.isEmpty()) obj.put("choices", JSONArray.fromObject(p.choices));
                        paramsArray.add(obj);
                    }
                    response.put("params", paramsArray);
                }
            } catch (ParamConfigParser.ParamConfigParseException e) {
                LOGGER.log(Level.FINE, "Failed to parse parameter file for " + ctx.job.getFullName(), e);
                response.put("error", "Parse error: " + e.getMessage());
                response.put("params", new JSONArray());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Unexpected error fetching parameters for " + ctx.job.getFullName(), e);
                response.put("error", "Unexpected error: " + e.getMessage());
                response.put("params", new JSONArray());
            }

            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write(response.toString());
        }

        /**
         * Administrative endpoint that drops the fetch cache.
         */
        @RequirePOST
        public void doClearCache(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ConfigFetcher.clearCache();
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.getWriter().write("{\"status\":\"ok\",\"message\":\"Cache cleared\"}");
        }

        /**
         * Build-submission endpoint that replaces {@link ParametersDefinitionProperty#_doBuild}.
         * Jenkins' built-in build action rejects any submitted parameter that isn't declared on
         * the job, which would make it impossible to submit parameters a branch introduced.
         * <p>
         * Parameters declared on the job are handled by their own {@link ParameterDefinition},
         * exactly as core does. Any other submitted parameter is accepted <em>only</em> if it is
         * defined in the parameter file of the branch the trigger value resolves to (fetched and
         * parsed server-side), and is typed according to that definition. Everything else is
         * rejected with {@code 400}, so a user with {@link Item#BUILD} cannot inject arbitrary
         * names into the build environment (SECURITY-170).
         */
        @RequirePOST
        public void doTriggerBuild(StaplerRequest2 req, StaplerResponse2 rsp,
                                   @QueryParameter String job,
                                   @QueryParameter TimeDuration delay)
                throws IOException, ServletException {
            JobContext ctx = resolveJob(rsp, job);
            if (ctx == null) {
                return;
            }
            if (delay == null) {
                delay = new TimeDuration(TimeUnit.MILLISECONDS.convert(
                        ctx.parameterizedJob.getQuietPeriod(), TimeUnit.SECONDS));
            }

            JSONObject formData = req.getSubmittedForm();
            List<JSONObject> entries = extractParameterEntries(formData);

            String triggerParamName = ctx.definition.getEffectiveTriggerParamName();
            String triggerValue = null;
            for (JSONObject jo : entries) {
                if (triggerParamName.equals(jo.optString("name", null))) {
                    triggerValue = jo.optString("value", "");
                    break;
                }
            }
            if (triggerValue == null || triggerValue.trim().isEmpty()) {
                triggerValue = ctx.definition.getEffectiveDefaultBranch();
            }

            BranchContract contract;
            try {
                contract = ctx.definition.loadBranchContract(ctx.job, triggerValue.trim());
            } catch (ParamConfigParser.ParamConfigParseException e) {
                rsp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Could not parse the parameter file for '" + triggerValue + "': " + e.getMessage());
                return;
            }
            Map<String, ParamConfigParser.ParsedParam> branchParams =
                    contract != null ? contract.params : Collections.emptyMap();

            List<ParameterValue> values = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            // Names of parameters not declared on the job must be explicitly marked "safe"
            // so SECURITY-170 filtering still lets them propagate to the build environment.
            // Only names taken from the server-side parsed branch contract ever end up here.
            Set<String> additionalSafeParameters = new LinkedHashSet<>();

            for (JSONObject jo : entries) {
                String name = jo.optString("name", null);
                if (name == null || name.isEmpty() || !seen.add(name)) {
                    continue;
                }
                if (PARAMETER_NAME.equals(name)) {
                    continue; // our own marker contributes no value
                }

                ParameterDefinition def = ctx.property.getParameterDefinition(name);
                if (def != null && !(def instanceof HotReloadParameterDefinition)) {
                    ParameterValue pv = def.createValue(req, jo);
                    if (pv == null) {
                        rsp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Cannot retrieve the parameter value: " + name);
                        return;
                    }
                    values.add(pv);
                    continue;
                }

                ParamConfigParser.ParsedParam branchParam = branchParams.get(name);
                if (branchParam == null) {
                    LOGGER.log(Level.WARNING,
                            "Rejected build submission for {0}: parameter ''{1}'' is neither declared on the job "
                                    + "nor defined in {2} of branch ''{3}''",
                            new Object[]{ctx.job.getFullName(), name, ctx.definition.getEffectiveParamFilePath(),
                                    contract != null ? contract.resolvedBranch : triggerValue});
                    rsp.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Parameter '" + name + "' is not defined for this job or branch");
                    return;
                }
                if ("separator".equals(branchParam.type)) {
                    continue;
                }
                ParameterValue pv = createBranchValue(branchParam, jo);
                if (pv == null) {
                    rsp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid value for parameter '" + name + "'");
                    return;
                }
                values.add(pv);
                additionalSafeParameters.add(name);
            }

            LOGGER.log(Level.FINE, "Scheduling build of {0} with {1} parameters ({2} from branch contract)",
                    new Object[]{ctx.job.getFullName(), values.size(), additionalSafeParameters.size()});

            ctx.parameterizedJob.scheduleBuild2(delay.getTimeInSeconds(),
                    new ParametersAction(values, additionalSafeParameters),
                    new CauseAction(new Cause.UserIdCause()));

            rsp.sendRedirect(HttpServletResponse.SC_SEE_OTHER, req.getContextPath() + '/' + ctx.job.getUrl());
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

        /**
         * Build a {@link ParameterValue} for a parameter that exists only in the branch's
         * parameter file, typed according to the <em>server-side parsed</em> definition.
         *
         * @return the value, or {@code null} if the submitted value is not acceptable
         */
        @CheckForNull
        private static ParameterValue createBranchValue(ParamConfigParser.ParsedParam param, JSONObject jo) {
            String name = param.name;
            Object rawValue = jo.opt("value");
            String value = rawValue == null ? "" : String.valueOf(rawValue);
            switch (param.type) {
                case "boolean":
                    boolean b = rawValue instanceof Boolean
                            ? (Boolean) rawValue
                            : "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
                    return new BooleanParameterValue(name, b);
                case "password":
                    return new PasswordParameterValue(name, value);
                case "text":
                    return new TextParameterValue(name, value);
                case "choice":
                    if (!param.choices.contains(value)) {
                        return null;
                    }
                    return new StringParameterValue(name, value);
                default:
                    // string, imageTag, activeChoice
                    return new StringParameterValue(name, value);
            }
        }
    }
}
