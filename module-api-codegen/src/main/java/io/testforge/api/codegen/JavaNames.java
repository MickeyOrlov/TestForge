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

    private JavaNames() {
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
