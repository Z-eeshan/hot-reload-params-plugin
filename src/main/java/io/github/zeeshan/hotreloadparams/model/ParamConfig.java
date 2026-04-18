package io.github.zeeshan.hotreloadparams.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single parameter definition parsed from a Groovy DSL file.
 * <p>
 * For separators, only {@code section} is set and {@code type} is {@link ParamType#SEPARATOR}.
 * For all other types, {@code name} and {@code type} are required; remaining fields
 * are type-specific.
 */
public class ParamConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    // ── Common fields ──────────────────────────────────────────────────────
    private ParamType type;
    private String name;
    private String description;

    // ── Separator ──────────────────────────────────────────────────────────
    private String section;  // non-null only for SEPARATOR type

    // ── string / boolean ───────────────────────────────────────────────────
    private String defaultValue;

    // ── imageTag ───────────────────────────────────────────────────────────
    private String image;
    private String registry;
    private String defaultTag;
    private String filter;

    // ── activeChoice ───────────────────────────────────────────────────────
    private String scriptResource;

    // ── choice ─────────────────────────────────────────────────────────────
    private List<String> choices;

    // ── Constructors ───────────────────────────────────────────────────────

    /** For separator entries. */
    public static ParamConfig separator(String sectionName) {
        ParamConfig pc = new ParamConfig();
        pc.type = ParamType.SEPARATOR;
        pc.section = sectionName;
        return pc;
    }

    /** For string parameters. */
    public static ParamConfig string(String name, String defaultValue, String description) {
        ParamConfig pc = new ParamConfig();
        pc.type = ParamType.STRING;
        pc.name = name;
        pc.defaultValue = defaultValue != null ? defaultValue : "";
        pc.description = description != null ? description : "";
        return pc;
    }

    /** For boolean parameters. */
    public static ParamConfig bool(String name, String defaultValue, String description) {
        ParamConfig pc = new ParamConfig();
        pc.type = ParamType.BOOLEAN;
        pc.name = name;
        pc.defaultValue = defaultValue != null ? defaultValue : "false";
        pc.description = description != null ? description : "";
        return pc;
    }

    /** For imageTag parameters. */
    public static ParamConfig imageTag(String name, String image, String registry,
                                        String defaultTag, String filter, String description) {
        ParamConfig pc = new ParamConfig();
        pc.type = ParamType.IMAGE_TAG;
        pc.name = name;
        pc.image = image;
        pc.registry = registry != null ? registry : "";
        pc.defaultTag = defaultTag != null ? defaultTag : "";
        pc.filter = filter != null ? filter : ".*";
        pc.description = description != null ? description : "";
        return pc;
    }

    /** For activeChoice parameters. */
    public static ParamConfig activeChoice(String name, String scriptResource, String description) {
        ParamConfig pc = new ParamConfig();
        pc.type = ParamType.ACTIVE_CHOICE;
        pc.name = name;
        pc.scriptResource = scriptResource;
        pc.description = description != null ? description : "";
        return pc;
    }

    /** For choice parameters. */
    public static ParamConfig choice(String name, List<String> choices, String defaultValue, String description) {
        ParamConfig pc = new ParamConfig();
        pc.type = ParamType.CHOICE;
        pc.name = name;
        pc.choices = choices != null ? choices : Collections.emptyList();
        pc.defaultValue = defaultValue;
        pc.description = description != null ? description : "";
        return pc;
    }

    // ── Getters ────────────────────────────────────────────────────────────

    public ParamType getType()          { return type; }
    public String getName()             { return name; }
    public String getDescription()      { return description; }
    public String getSection()          { return section; }
    public String getDefaultValue()     { return defaultValue; }
    public String getImage()            { return image; }
    public String getRegistry()         { return registry; }
    public String getDefaultTag()       { return defaultTag; }
    public String getFilter()           { return filter; }
    public String getScriptResource()   { return scriptResource; }
    public List<String> getChoices()    { return choices; }

    public boolean isSeparator() {
        return type == ParamType.SEPARATOR;
    }

    @Override
    public String toString() {
        if (isSeparator()) {
            return "ParamConfig{section='" + section + "'}";
        }
        return "ParamConfig{name='" + name + "', type=" + type + '}';
    }
}
