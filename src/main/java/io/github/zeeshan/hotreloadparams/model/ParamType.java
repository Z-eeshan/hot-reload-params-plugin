package io.github.zeeshan.hotreloadparams.model;

/**
 * Supported parameter types in the Groovy DSL.
 */
public enum ParamType {
    STRING("string"),
    BOOLEAN("boolean"),
    PASSWORD("password"),
    IMAGE_TAG("imageTag"),
    ACTIVE_CHOICE("activeChoice"),
    CHOICE("choice"),
    SEPARATOR("separator");

    private final String dslName;

    ParamType(String dslName) {
        this.dslName = dslName;
    }

    public String getDslName() {
        return dslName;
    }

    /**
     * Resolve a DSL type string to the enum value.
     */
    public static ParamType fromDslName(String name) {
        for (ParamType t : values()) {
            if (t.dslName.equalsIgnoreCase(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown parameter type: " + name);
    }
}
