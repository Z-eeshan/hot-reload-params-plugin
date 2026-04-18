package io.github.zeeshan.hotreloadparams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Parses a Jenkins pipeline Groovy file's {@code parameters {}} block
 * by structurally splitting it into individual top-level function calls
 * (tracking parenthesis/bracket/brace depth and quote state), then
 * extracting named arguments from each call.
 * <p>
 * This approach is immune to URLs inside strings, multi-line calls, nested
 * brackets, etc. — no per-type regex needed.
 */
public class ParamConfigParser {
    private static final Logger LOGGER = Logger.getLogger(ParamConfigParser.class.getName());

    private static final Set<String> KNOWN_TYPES = new LinkedHashSet<>();
    static {
        KNOWN_TYPES.add("string");
        KNOWN_TYPES.add("booleanParam");
        KNOWN_TYPES.add("choice");
        KNOWN_TYPES.add("imageTag");
        KNOWN_TYPES.add("activeChoice");
        KNOWN_TYPES.add("separator");
    }

    // ── Data class ─────────────────────────────────────────────────────────

    public static class ParsedParam {
        public final String name;
        public final String type;
        public final String defaultValue;
        public final String description;
        public final String image;
        public final String defaultTag;
        public final String sectionHeader;

        public ParsedParam(String name, String type, String defaultValue, String description,
                           String image, String defaultTag, String sectionHeader) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue != null ? defaultValue : "";
            this.description = description;
            this.image = image;
            this.defaultTag = defaultTag;
            this.sectionHeader = sectionHeader;
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public List<ParsedParam> parseParams(String pipelineContent) throws ParamConfigParseException {
        if (pipelineContent == null || pipelineContent.trim().isEmpty()) {
            throw new ParamConfigParseException("Pipeline file is empty");
        }

        String paramsBlock = extractParametersBlock(pipelineContent);
        if (paramsBlock == null) {
            throw new ParamConfigParseException("No 'parameters {' block found in pipeline file");
        }

        paramsBlock = stripComments(paramsBlock);

        List<String> calls = splitTopLevelCalls(paramsBlock);
        LOGGER.log(Level.FINE, "Found {0} top-level calls in parameters block", calls.size());

        List<ParsedParam> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String call : calls) {
            ParsedParam param = parseCall(call);
            if (param != null && param.name != null && seen.add(param.name)) {
                result.add(param);
            }
        }

        LOGGER.log(Level.FINE, "Parsed {0} parameter definitions", result.size());
        return result;
    }

    public Map<String, String> parseDefaults(String pipelineContent) throws ParamConfigParseException {
        List<ParsedParam> params = parseParams(pipelineContent);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (ParsedParam p : params) {
            if (!"separator".equals(p.type)) {
                defaults.put(p.name, p.defaultValue);
            }
        }
        return defaults;
    }

    // ── Structural splitter ────────────────────────────────────────────────

    /**
     * Splits the parameters block into individual top-level function calls.
     * Tracks paren/bracket/brace depth and quote state so that nested
     * structures (e.g. activeChoice with script blocks) are kept as one call.
     *
     * Each returned string looks like: "imageTag(name: 'X', image: 'img', ...)"
     */
    private List<String> splitTopLevelCalls(String block) {
        List<String> calls = new ArrayList<>();
        int i = 0;
        int len = block.length();

        while (i < len) {
            // Skip whitespace
            while (i < len && Character.isWhitespace(block.charAt(i))) i++;
            if (i >= len) break;

            // Find a call: identifier followed by '('
            int nameStart = i;
            while (i < len && (Character.isLetterOrDigit(block.charAt(i)) || block.charAt(i) == '_')) i++;
            if (i >= len || i == nameStart) { i++; continue; }
            String funcName = block.substring(nameStart, i);

            // Skip whitespace between name and '('
            while (i < len && Character.isWhitespace(block.charAt(i))) i++;
            if (i >= len || block.charAt(i) != '(') continue;

            // Now scan to find matching close paren, respecting depth and quotes
            int callStart = nameStart;
            int depth = 0;
            boolean inSingle = false;
            boolean inDouble = false;

            while (i < len) {
                char c = block.charAt(i);
                if (!inSingle && !inDouble) {
                    if (c == '(') depth++;
                    else if (c == ')') { depth--; if (depth == 0) { i++; break; } }
                    else if (c == '[') depth++;
                    else if (c == ']') depth--;
                    else if (c == '\'') inSingle = true;
                    else if (c == '"') inDouble = true;
                } else if (inSingle) {
                    if (c == '\'') inSingle = false;
                } else { // inDouble
                    if (c == '"') inDouble = false;
                }
                i++;
            }

            String fullCall = block.substring(callStart, i).trim();
            if (!fullCall.isEmpty()) {
                calls.add(fullCall);
            }
        }

        return calls;
    }

    // ── Parse a single call ────────────────────────────────────────────────

    private ParsedParam parseCall(String call) {
        // Extract function name: everything before first '('
        int parenIdx = call.indexOf('(');
        if (parenIdx < 0) return null;
        String funcName = call.substring(0, parenIdx).trim();

        // Skip our own plugin call
        if ("hotReloadParams".equals(funcName)) return null;
        // Skip unknown types
        if (!KNOWN_TYPES.contains(funcName)) return null;

        // Extract the argument body between outer ( and )
        String argBody = call.substring(parenIdx + 1, call.length() - 1);

        // Extract named arguments as key→value map
        Map<String, String> args = extractNamedArgs(argBody);

        String name = args.get("name");
        if (name == null || name.isEmpty()) return null;

        String description = args.get("description");
        String defaultValue;
        String image = null;
        String defaultTag = null;
        String sectionHeader = null;
        String type;

        switch (funcName) {
            case "string":
                type = "string";
                defaultValue = args.getOrDefault("defaultValue", "");
                break;
            case "booleanParam":
                type = "boolean";
                defaultValue = args.getOrDefault("defaultValue", "false");
                break;
            case "imageTag":
                type = "imageTag";
                image = args.getOrDefault("image", "");
                defaultTag = args.getOrDefault("defaultTag", "");
                defaultValue = defaultTag;
                break;
            case "choice":
                type = "choice";
                defaultValue = "";
                break;
            case "activeChoice":
                type = "activeChoice";
                defaultValue = "";
                break;
            case "separator":
                type = "separator";
                defaultValue = "";
                sectionHeader = args.getOrDefault("sectionHeader", name);
                break;
            default:
                return null;
        }

        return new ParsedParam(name, type, defaultValue, description, image, defaultTag, sectionHeader);
    }

    // ── Named argument extractor ───────────────────────────────────────────

    /**
     * Extracts top-level {@code key: value} pairs from a Groovy argument body.
     * Handles quoted strings, nested brackets/parens, and bare identifiers
     * like {@code true}/{@code false}.
     */
    private Map<String, String> extractNamedArgs(String body) {
        Map<String, String> args = new LinkedHashMap<>();
        int i = 0;
        int len = body.length();

        while (i < len) {
            // Skip whitespace and commas
            while (i < len && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) i++;
            if (i >= len) break;

            // Read key: an identifier followed by ':'
            int keyStart = i;
            while (i < len && (Character.isLetterOrDigit(body.charAt(i)) || body.charAt(i) == '_')) i++;
            if (i >= len || i == keyStart) { i++; continue; }
            String key = body.substring(keyStart, i);

            // Skip whitespace
            while (i < len && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= len || body.charAt(i) != ':') continue;
            i++; // skip ':'

            // Skip whitespace
            while (i < len && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= len) break;

            char first = body.charAt(i);
            String value;

            if (first == '\'' || first == '"') {
                // Quoted string — scan to matching close quote
                char quote = first;
                i++; // skip opening quote
                int valStart = i;
                while (i < len && body.charAt(i) != quote) i++;
                value = body.substring(valStart, i);
                if (i < len) i++; // skip closing quote
            } else if (first == '[' || first == '(') {
                // Nested structure — skip entire balanced block
                i = skipBalanced(body, i);
                value = ""; // we don't need the content of complex structures
            } else {
                // Bare value (true, false, number, identifier, method call...)
                int valStart = i;
                // Scan until comma or end, respecting nested parens
                int depth = 0;
                while (i < len) {
                    char c = body.charAt(i);
                    if (c == '(' || c == '[') depth++;
                    else if (c == ')' || c == ']') {
                        if (depth == 0) break;
                        depth--;
                    } else if (c == ',' && depth == 0) break;
                    i++;
                }
                value = body.substring(valStart, i).trim();
            }

            args.put(key, value);
        }

        return args;
    }

    /** Skip past a balanced bracket/paren structure starting at position i. */
    private int skipBalanced(String text, int i) {
        int len = text.length();
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < len) {
            char c = text.charAt(i);
            if (!inSingle && !inDouble) {
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    if (depth == 0) { i++; break; }
                }
                else if (c == '\'') inSingle = true;
                else if (c == '"') inDouble = true;
            } else if (inSingle && c == '\'') inSingle = false;
            else if (inDouble && c == '"') inDouble = false;
            i++;
        }
        return i;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String extractParametersBlock(String content) {
        int idx = content.indexOf("parameters");
        while (idx >= 0) {
            int braceStart = content.indexOf('{', idx + "parameters".length());
            if (braceStart < 0) break;

            String between = content.substring(idx + "parameters".length(), braceStart).trim();
            if (!between.isEmpty()) {
                idx = content.indexOf("parameters", idx + 1);
                continue;
            }

            int depth = 1;
            int pos = braceStart + 1;
            while (pos < content.length() && depth > 0) {
                char c = content.charAt(pos);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                pos++;
            }

            if (depth == 0) {
                return content.substring(braceStart + 1, pos - 1);
            }
            break;
        }
        return null;
    }

    private String stripComments(String text) {
        // Remove block comments
        text = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(text).replaceAll("");

        // Remove // line comments, but NOT // inside quoted strings (e.g. 'https://...')
        StringBuilder sb = new StringBuilder(text.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inSingle && !inDouble && c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                if (i < text.length()) sb.append('\n');
                continue;
            }
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            sb.append(c);
        }
        return sb.toString();
    }

    public static class ParamConfigParseException extends Exception {
        public ParamConfigParseException(String message) {
            super(message);
        }
        public ParamConfigParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
