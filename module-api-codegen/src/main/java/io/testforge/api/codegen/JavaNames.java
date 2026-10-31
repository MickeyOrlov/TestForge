package io.testforge.api.codegen;

import java.util.Locale;
import java.util.Set;

final class JavaNames {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while", "yield", "true", "false", "null");

    /** {@code Object}'s methods: a record accessor or client method by these names does not compile. */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "clone", "equals", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");

    /**
     * Simple names the generated code itself uses: a model by one of these
     * names would shadow or collide with it.
     */
    private static final Set<String> RESERVED_TYPES = Set.of(
            "ApiClient", "RequestSpecification", "Response", "JsonProperty",
            "Object", "String", "Integer", "Long", "Float", "Double", "Boolean");

    private JavaNames() {
    }

    /** A model type name that does not collide with a type the generated code refers to by simple name. */
    static String model(String value) {
        String result = type(value);
        return RESERVED_TYPES.contains(result) ? result + "Model" : result;
    }

    /** A record component name; one clashing with an {@code Object} method gets a suffix. */
    static String component(String value) {
        String result = member(value);
        return OBJECT_METHODS.contains(result) ? result + "Value" : result;
    }

    /** A client method name; one clashing with an {@code Object} method gets a suffix. */
    static String method(String value) {
        String result = member(value);
        return OBJECT_METHODS.contains(result) ? result + "Call" : result;
    }

    static String type(String value) {
        String result = words(value, true);
        if (result.isBlank()) {
            result = "GeneratedType";
        }
        if (!Character.isJavaIdentifierStart(result.charAt(0))) {
            result = "N" + result;
        }
        return KEYWORDS.contains(result.toLowerCase(Locale.ROOT)) ? result + "Type" : result;
    }

    static String member(String value) {
        String type = type(value);
        String result = Character.toLowerCase(type.charAt(0)) + type.substring(1);
        return KEYWORDS.contains(result) ? result + "Value" : result;
    }

    static String packageSegment(String value) {
        String result = value == null ? "api" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("(^_+|_+$)", "");
        if (result.isBlank()) {
            result = "api";
        }
        if (!Character.isJavaIdentifierStart(result.charAt(0))) {
            result = "api_" + result;
        }
        return KEYWORDS.contains(result) ? result + "_api" : result;
    }

    static String stringLiteral(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /**
     * Document text made safe inside a Javadoc comment. The document is input,
     * not code: {@code *}{@code /} would end the comment and let the rest of a
     * description compile as Java, a backslash could spell the same thing as a
     * unicode escape (which the compiler decodes inside comments too), and a
     * leading {@code @} would read as a tag. HTML is escaped so the text shows
     * as written.
     */
    static String javadocText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\\", "&#92;")
                .replace("*/", "*&#47;")
                .replace("/*", "&#47;*")
                .replace("@", "&#64;")
                .replace("{", "&#123;")
                .replace("}", "&#125;")
                .replace("\r", "");
    }

    static boolean keyword(String value) {
        return KEYWORDS.contains(value);
    }

    private static String words(String value, boolean upperFirst) {
        if (value == null) {
            return "";
        }
        String[] parts = value.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .split("[^A-Za-z0-9]+|\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        if (!upperFirst && !out.isEmpty()) {
            out.setCharAt(0, Character.toLowerCase(out.charAt(0)));
        }
        return out.toString();
    }
}
