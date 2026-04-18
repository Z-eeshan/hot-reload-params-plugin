package io.github.zeeshan.hotreloadparams;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.ParameterValue;
import hudson.model.Run;
import hudson.util.VariableResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Composite parameter value that holds all dynamic sub-parameter values.
 * <p>
 * When injected into a build, every sub-parameter becomes available as
 * both an environment variable and a pipeline {@code params.*} entry.
 */
public class HotReloadParameterValue extends ParameterValue {
    private static final long serialVersionUID = 1L;

    private final Map<String, String> paramValues;
    private final String resolvedBranch;

    public HotReloadParameterValue(String name, Map<String, String> paramValues, String resolvedBranch) {
        super(name);
        this.paramValues = paramValues != null ? new LinkedHashMap<>(paramValues) : Collections.emptyMap();
        this.resolvedBranch = resolvedBranch;
    }

    /**
     * Get all sub-parameter values.
     */
    public Map<String, String> getParamValues() {
        return Collections.unmodifiableMap(paramValues);
    }

    /**
     * Get a specific sub-parameter value.
     */
    public String getSubValue(String paramName) {
        return paramValues.get(paramName);
    }

    /**
     * Get the Git branch from which the config was resolved.
     */
    public String getResolvedBranch() {
        return resolvedBranch;
    }

    /**
     * Inject all sub-parameters as environment variables.
     * This makes them accessible as both env.PARAM_NAME and params.PARAM_NAME in pipelines.
     */
    @Override
    public void buildEnvironment(Run<?, ?> build, EnvVars env) {
        for (Map.Entry<String, String> entry : paramValues.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        // Also store metadata about which config branch was used
        if (resolvedBranch != null) {
            env.put("DYNAMIC_PARAMS_BRANCH", resolvedBranch);
        }
    }

    /**
     * Support legacy AbstractBuild variable resolution.
     */
    @Override
    public VariableResolver<String> createVariableResolver(AbstractBuild<?, ?> build) {
        return name -> paramValues.get(name);
    }

    /**
     * Return the composite value — useful for build description / logging.
     */
    @Override
    public Object getValue() {
        return paramValues;
    }

    @Override
    public String toString() {
        return "HotReloadParameterValue{" +
                "resolvedBranch='" + resolvedBranch + '\'' +
                ", paramCount=" + paramValues.size() +
                '}';
    }
}
