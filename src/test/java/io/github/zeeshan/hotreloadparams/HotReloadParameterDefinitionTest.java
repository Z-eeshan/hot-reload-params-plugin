package io.github.zeeshan.hotreloadparams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.BooleanParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterDefinition;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.eclipse.jgit.api.Git;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HotReloadParameterDefinitionTest {

    private static final String FILE = "vars/release-pipeline.groovy";
    private static final String ENDPOINT =
            "descriptorByName/" + HotReloadParameterDefinition.class.getName() + "/";

    private static final String MASTER_CONTENT = "parameters {\n"
            + "  string(name: 'DEPLOY_ENV', defaultValue: 'staging', description: 'env')\n"
            + "}\n";
    private static final String FEATURE_CONTENT = "parameters {\n"
            + "  string(name: 'DEPLOY_ENV', defaultValue: 'dev', description: 'env')\n"
            + "  booleanParam(name: 'mybool', defaultValue: true, description: 'flag')\n"
            + "  choice(name: 'LEVEL', choices: ['INFO', 'DEBUG'], description: 'log level')\n"
            + "}\n";

    private String repoUrl;

    @BeforeEach
    void createRepository(@TempDir Path tmp) throws Exception {
        Path repoDir = tmp.resolve("origin");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("master").call()) {
            commit(git, repoDir, MASTER_CONTENT, "master");
            git.checkout().setCreateBranch(true).setName("feature").call();
            commit(git, repoDir, FEATURE_CONTENT, "feature");
            git.checkout().setName("master").call();
        }
        repoUrl = repoDir.toUri().toString();
        ConfigFetcher.clearCache();
    }

    private static void commit(Git git, Path repoDir, String content, String message) throws Exception {
        Path file = repoDir.resolve(FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(FILE).call();
        git.commit().setMessage(message)
                .setAuthor("test", "test@example.com")
                .setCommitter("test", "test@example.com")
                .setSign(false)
                .call();
    }

    private ParametersDefinitionProperty parameters() {
        HotReloadParameterDefinition def = new HotReloadParameterDefinition();
        def.setRepoUrl(repoUrl);
        def.setParamFilePath(FILE);
        def.setTriggerParamName("RELEASE_BRANCH");
        def.setDefaultBranch("master");
        return new ParametersDefinitionProperty(
                new StringParameterDefinition("RELEASE_BRANCH", "master"),
                new StringParameterDefinition("DEPLOY_ENV", "staging"),
                def);
    }

    private FreeStyleProject createFreeStyleJob(JenkinsRule j, String name) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        p.addProperty(parameters());
        return p;
    }

    private static WebRequest post(JenkinsRule j, JenkinsRule.WebClient wc, String path, List<NameValuePair> params)
            throws Exception {
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setRequestParameters(params);
        wc.addCrumb(req);
        return req;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JSONObject entry(String name, Object value) {
        JSONObject jo = new JSONObject();
        jo.put("name", name);
        jo.put("value", value);
        return jo;
    }

    private static String form(JSONObject... entries) {
        JSONObject form = new JSONObject();
        JSONArray arr = new JSONArray();
        for (JSONObject e : entries) arr.add(e);
        form.put("parameter", arr);
        return form.toString();
    }

    private static Page triggerBuild(JenkinsRule j, JenkinsRule.WebClient wc, String job, String query, String json)
            throws Exception {
        String path = ENDPOINT + "triggerBuild?job=" + enc(job) + (query != null ? "&" + query : "");
        return wc.getPage(post(j, wc, path, List.of(new NameValuePair("json", json))));
    }

    private static JSONObject fetchParams(JenkinsRule j, JenkinsRule.WebClient wc, String job, String triggerValue)
            throws Exception {
        Page page = wc.getPage(post(j, wc, ENDPOINT + "fetchParams",
                List.of(new NameValuePair("job", job), new NameValuePair("triggerValue", triggerValue))));
        assertEquals(200, page.getWebResponse().getStatusCode());
        return JSONObject.fromObject(page.getWebResponse().getContentAsString());
    }

    // ── fetchParams ────────────────────────────────────────────────────────

    @Test
    void fetchParamsReturnsBranchContract(JenkinsRule j) throws Exception {
        createFreeStyleJob(j, "p");
        JSONObject data = fetchParams(j, j.createWebClient(), "p", "feature");

        assertEquals("feature", data.getString("resolvedBranch"));
        assertFalse(data.getBoolean("isFallback"));
        JSONArray params = data.getJSONArray("params");
        assertEquals(3, params.size());
        assertEquals("DEPLOY_ENV", params.getJSONObject(0).getString("name"));
        assertEquals("dev", params.getJSONObject(0).getString("defaultValue"));
        assertEquals("mybool", params.getJSONObject(1).getString("name"));
        assertEquals("boolean", params.getJSONObject(1).getString("type"));
        assertEquals("LEVEL", params.getJSONObject(2).getString("name"));
        assertEquals(2, params.getJSONObject(2).getJSONArray("choices").size());
    }

    @Test
    void fetchParamsFallsBackToDefaultBranch(JenkinsRule j) throws Exception {
        createFreeStyleJob(j, "p");
        JSONObject data = fetchParams(j, j.createWebClient(), "p", "no-such-branch");

        assertEquals("master", data.getString("resolvedBranch"));
        assertTrue(data.getBoolean("isFallback"));
        assertEquals(1, data.getJSONArray("params").size());
    }

    @Test
    void fetchParamsRequiresBuildPermission(JenkinsRule j) throws Exception {
        createFreeStyleJob(j, "p");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("builder"));

        JenkinsRule.WebClient reader = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("reader");
        Page denied = reader.getPage(post(j, reader, ENDPOINT + "fetchParams",
                List.of(new NameValuePair("job", "p"), new NameValuePair("triggerValue", "feature"))));
        assertEquals(403, denied.getWebResponse().getStatusCode());

        JenkinsRule.WebClient builder = j.createWebClient().login("builder");
        assertEquals("feature", fetchParams(j, builder, "p", "feature").getString("resolvedBranch"));
    }

    @Test
    void fetchParamsRejectsUnknownJob(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        Page page = wc.getPage(post(j, wc, ENDPOINT + "fetchParams",
                List.of(new NameValuePair("job", "nope"), new NameValuePair("triggerValue", "feature"))));
        assertEquals(404, page.getWebResponse().getStatusCode());
    }

    // ── triggerBuild ───────────────────────────────────────────────────────

    @Test
    void triggerBuildAcceptsBranchParametersAndExposesThemToTheEnvironment(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "p");
        CaptureEnvironmentBuilder env = new CaptureEnvironmentBuilder();
        p.getBuildersList().add(env);

        Page page = triggerBuild(j, j.createWebClient(), "p", null, form(
                entry("RELEASE_BRANCH", "feature"),
                entry("DEPLOY_ENV", "dev"),
                entry("mybool", Boolean.TRUE),
                entry("LEVEL", "DEBUG")));
        assertEquals(200, page.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();

        FreeStyleBuild b = p.getLastBuild();
        assertNotNull(b, "a build should have been scheduled");
        j.assertBuildStatusSuccess(b);

        ParametersAction pa = b.getAction(ParametersAction.class);
        assertNotNull(pa);
        assertEquals("feature", pa.getParameter("RELEASE_BRANCH").getValue());
        assertEquals("dev", pa.getParameter("DEPLOY_ENV").getValue());
        assertTrue(pa.getParameter("mybool") instanceof BooleanParameterValue);
        assertEquals(Boolean.TRUE, pa.getParameter("mybool").getValue());

        // Parameters only defined on the branch must reach the build environment (SECURITY-170 allow-list).
        assertEquals("dev", env.getEnvVars().get("DEPLOY_ENV"));
        assertEquals("true", env.getEnvVars().get("mybool"));
        assertEquals("DEBUG", env.getEnvVars().get("LEVEL"));
    }

    @Test
    void triggerBuildRejectsParametersOutsideTheBranchContract(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "p");
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);

        Page page = triggerBuild(j, wc, "p", null, form(
                entry("RELEASE_BRANCH", "feature"),
                entry("DEPLOY_ENV", "dev"),
                entry("PATH", "/tmp/evil")));
        assertEquals(400, page.getWebResponse().getStatusCode());

        j.waitUntilNoActivity();
        assertNull(p.getLastBuild(), "no build must be scheduled");
        assertTrue(j.jenkins.getQueue().isEmpty());
    }

    @Test
    void triggerBuildRejectsParametersNotOnTheResolvedBranch(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "p");
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);

        // "mybool" exists on "feature" but not on "master"; the trigger value selects master.
        Page page = triggerBuild(j, wc, "p", null, form(
                entry("RELEASE_BRANCH", "master"),
                entry("mybool", Boolean.TRUE)));
        assertEquals(400, page.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
    }

    @Test
    void triggerBuildRejectsChoiceValueOutsideTheList(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "p");
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);

        Page page = triggerBuild(j, wc, "p", null, form(
                entry("RELEASE_BRANCH", "feature"),
                entry("LEVEL", "TRACE")));
        assertEquals(400, page.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
    }

    @Test
    void triggerBuildHonoursDelay(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "p");

        Page page = triggerBuild(j, j.createWebClient(), "p", "delay=120sec", form(
                entry("RELEASE_BRANCH", "master"),
                entry("DEPLOY_ENV", "staging")));
        assertEquals(200, page.getWebResponse().getStatusCode());

        Queue.Item item = j.jenkins.getQueue().getItem(p);
        assertNotNull(item, "build should be waiting in the queue");
        assertTrue(item instanceof Queue.WaitingItem);
        assertTrue(((Queue.WaitingItem) item).timestamp.getTimeInMillis() - System.currentTimeMillis() > 60_000L,
                "quiet period from ?delay= should be applied");
        j.jenkins.getQueue().cancel(item);
    }

    @Test
    void triggerBuildRequiresBuildPermission(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "p");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader"));

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("reader");
        Page page = triggerBuild(j, wc, "p", null, form(entry("RELEASE_BRANCH", "master")));
        assertEquals(403, page.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
    }

    @Test
    void pipelineBuildSeesBranchParametersInEnvironment(JenkinsRule j) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "pipe");
        p.addProperty(parameters());
        p.setDefinition(new CpsFlowDefinition(
                "echo \"env mybool=${env.mybool} DEPLOY_ENV=${env.DEPLOY_ENV}\"\n"
                        + "echo \"params mybool=${params.mybool}\"\n", true));

        Page page = triggerBuild(j, j.createWebClient(), "pipe", null, form(
                entry("RELEASE_BRANCH", "feature"),
                entry("DEPLOY_ENV", "dev"),
                entry("mybool", Boolean.TRUE)));
        assertEquals(200, page.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();

        WorkflowRun r = p.getLastBuild();
        assertNotNull(r);
        j.assertBuildStatusSuccess(r);
        j.assertLogContains("env mybool=true DEPLOY_ENV=dev", r);
        j.assertLogContains("params mybool=true", r);
    }

    // ── Build page rendering ───────────────────────────────────────────────

    @Test
    void buildPageExposesEndpointAndJobToTheScript(JenkinsRule j) throws Exception {
        FreeStyleProject p = createFreeStyleJob(j, "folder-less");
        // The "Build with Parameters" page deliberately answers 405 to discourage scripted GETs.
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        String html = wc.getPage(p, "build").getWebResponse().getContentAsString();

        assertTrue(html.contains("data-endpoint-url=\"" + j.contextPath + "/" + ENDPOINT.substring(0, ENDPOINT.length() - 1) + "\""),
                "endpoint URL should include the context path: " + html);
        assertTrue(html.contains("data-job-full-name=\"folder-less\""));
        assertTrue(html.contains("data-trigger-param-name=\"RELEASE_BRANCH\""));
        assertTrue(html.contains("data-default-branch=\"master\""));
        assertTrue(html.contains("hot-reload-params.js"));
    }
}
