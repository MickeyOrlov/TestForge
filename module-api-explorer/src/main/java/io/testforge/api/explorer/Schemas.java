package io.testforge.api.explorer;

import io.swagger.v3.oas.models.media.Schema;
import java.util.Locale;
import java.util.Set;

/**
 * The handful of schema questions this module asks, in one place.
 *
 * <p>OpenAPI 3.0 declares {@code type} as a single string; 3.1 declares
 * {@code types} as a set, because it is JSON Schema 2020-12. Every call site
 * would otherwise have to remember that.
 */
final class Schemas {

    private Schemas() {
    }

    /** The declared type, ignoring a {@code null} member of a 3.1 union. */
    static String type(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        if (schema.getType() != null) {
            return schema.getType().toLowerCase(Locale.ROOT);
        }

        Set<String> types = schema.getTypes();
        if (types == null || types.isEmpty()) {
            return null;
        }
        return types.stream()
                .filter(type -> type != null && !"null".equalsIgnoreCase(type))
                .findFirst()
                .map(type -> type.toLowerCase(Locale.ROOT))
                .orElse("null");
    }

    /** True when the schema permits an explicit JSON null. */
    static boolean nullable(Schema<?> schema) {
        if (schema == null) {
            return true;
        }
        if (Boolean.TRUE.equals(schema.getNullable())) {
            return true;
        }
        Set<String> types = schema.getTypes();
        return types != null && types.stream().anyMatch("null"::equalsIgnoreCase);
    }

    static String format(Schema<?> schema) {
        return schema == null || schema.getFormat() == null
                ? null
                : schema.getFormat().toLowerCase(Locale.ROOT);
    }
}
