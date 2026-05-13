package io.github.zeeshan.hotreloadparams;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamConfigParserTest {

    private final ParamConfigParser parser = new ParamConfigParser();

    @Test
    void stringParam() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: 'MY_PARAM', defaultValue: 'hello', description: 'A test param')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(1, defaults.size());
        assertEquals("hello", defaults.get("MY_PARAM"));
    }

    @Test
    void booleanParam() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    booleanParam(name: 'ENABLE_IT', defaultValue: false, description: 'toggle')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(1, defaults.size());
        assertEquals("false", defaults.get("ENABLE_IT"));
    }

    @Test
    void choiceParam() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    choice(name: 'ENV', choices: ['dev', 'staging', 'prod'], description: 'Environment')\n" +
                "  }\n" +
                "}";
        List<ParamConfigParser.ParsedParam> params = parser.parseParams(pipeline);
        assertEquals(1, params.size());
        ParamConfigParser.ParsedParam p = params.get(0);
        assertEquals("choice", p.type);
        assertEquals("dev", p.defaultValue);
        assertEquals(3, p.choices.size());
        assertEquals("dev", p.choices.get(0));
        assertEquals("staging", p.choices.get(1));
        assertEquals("prod", p.choices.get(2));
    }

    @Test
    void choiceParamDoubleQuotedItems() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    choice(name: 'LEVEL', choices: [\"INFO\", \"DEBUG\", \"WARN\"], description: 'log')\n" +
                "  }\n" +
                "}";
        List<ParamConfigParser.ParsedParam> params = parser.parseParams(pipeline);
        ParamConfigParser.ParsedParam p = params.get(0);
        assertEquals("INFO", p.defaultValue);
        assertEquals(3, p.choices.size());
        assertEquals("WARN", p.choices.get(2));
    }

    @Test
    void choiceEmptyList() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    choice(name: 'EMPTY', choices: [], description: 'empty')\n" +
                "  }\n" +
                "}";
        List<ParamConfigParser.ParsedParam> params = parser.parseParams(pipeline);
        ParamConfigParser.ParsedParam p = params.get(0);
        assertEquals("", p.defaultValue);
        assertTrue(p.choices.isEmpty());
    }

    @Test
    void passwordParam() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    password(name: 'API_TOKEN', defaultValue: 'secret', description: 'Token')\n" +
                "  }\n" +
                "}";
        List<ParamConfigParser.ParsedParam> params = parser.parseParams(pipeline);
        assertEquals(1, params.size());
        ParamConfigParser.ParsedParam p = params.get(0);
        assertEquals("password", p.type);
        assertEquals("API_TOKEN", p.name);
        assertEquals("secret", p.defaultValue);
    }

    @Test
    void passwordParamEmptyDefault() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    password(name: 'SECRET', description: 'Secret value')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals("", defaults.get("SECRET"));
    }

    @Test
    void mixedParams() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: 'BRANCH', defaultValue: 'master', description: 'Branch')\n" +
                "    string(name: 'TAG', defaultValue: 'latest', description: 'Tag')\n" +
                "    booleanParam(name: 'DEBUG', defaultValue: true, description: 'Debug')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(3, defaults.size());
        assertEquals("master", defaults.get("BRANCH"));
        assertEquals("latest", defaults.get("TAG"));
        assertEquals("true", defaults.get("DEBUG"));
    }

    @Test
    void skipsHotReloadParams() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: 'BRANCH', defaultValue: 'master', description: 'Branch')\n" +
                "    hotReloadParams(repoUrl: 'https://git.example.com', credentialsId: 'cred')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(1, defaults.size());
        assertEquals("master", defaults.get("BRANCH"));
    }

    @Test
    void doubleQuotes() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: \"MY_PARAM\", defaultValue: \"world\", description: \"desc\")\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals("world", defaults.get("MY_PARAM"));
    }

    @Test
    void emptyContent() {
        assertThrows(ParamConfigParser.ParamConfigParseException.class, () -> parser.parseDefaults(""));
    }

    @Test
    void nullContent() {
        assertThrows(ParamConfigParser.ParamConfigParseException.class, () -> parser.parseDefaults(null));
    }

    @Test
    void noParametersBlock() {
        assertThrows(ParamConfigParser.ParamConfigParseException.class,
                () -> parser.parseDefaults("pipeline {\n  stages {\n  }\n}"));
    }

    @Test
    void emptyDefaultValue() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: 'EMPTY', defaultValue: '', description: 'empty')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals("", defaults.get("EMPTY"));
    }

    @Test
    void commentedOutParamsSkipped() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: 'ACTIVE', defaultValue: 'yes', description: 'active')\n" +
                "    // string(name: 'COMMENTED', defaultValue: 'no', description: 'should be skipped')\n" +
                "    string(name: 'ALSO_ACTIVE', defaultValue: 'yes2', description: 'also active')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(2, defaults.size());
        assertEquals("yes", defaults.get("ACTIVE"));
        assertEquals("yes2", defaults.get("ALSO_ACTIVE"));
        assertNull(defaults.get("COMMENTED"));
    }

    @Test
    void blockCommentedParamsSkipped() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    string(name: 'KEEP', defaultValue: 'val', description: 'keep')\n" +
                "    /*\n" +
                "    string(name: 'REMOVED', defaultValue: 'gone', description: 'removed')\n" +
                "    */\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(1, defaults.size());
        assertEquals("val", defaults.get("KEEP"));
        assertNull(defaults.get("REMOVED"));
    }

    @Test
    void imageTagParam() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    imageTag(name: 'REDIS', description: '', image: 'redis', filter: '.*', defaultTag: '8.0', registry: 'https://registry.example.com', credentialId: '', tagOrder: 'NATURAL')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(1, defaults.size());
        assertEquals("8.0", defaults.get("REDIS"));
    }

    @Test
    void imageTagEmptyDefaultTag() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    imageTag(name: 'CORE', description: '', image: 'core', filter: '.*', defaultTag: '', registry: 'https://registry.example.com', credentialId: '', tagOrder: 'NATURAL')\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals("", defaults.get("CORE"));
    }

    @Test
    void snippetizerStyleBooleanParam() throws Exception {
        String pipeline = "pipeline {\n" +
                "  parameters {\n" +
                "    booleanParam defaultValue: true, description: 'my test', name: 'test'\n" +
                "    string defaultValue: 'test', description: 'my string', name: 'str'\n" +
                "  }\n" +
                "}";
        Map<String, String> defaults = parser.parseDefaults(pipeline);
        assertEquals(2, defaults.size());
        assertEquals("true", defaults.get("test"));
        assertEquals("test", defaults.get("str"));
    }
}
