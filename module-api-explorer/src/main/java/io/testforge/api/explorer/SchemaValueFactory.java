package io.testforge.api.explorer;

import io.swagger.v3.oas.models.media.Schema;
import java.util.Optional;

/**
 * Last resort for a parameter the document describes but never exemplifies.
 *
 * <p>Every value here is a fixed constant chosen from the declared type and
 * format. Nothing is random: two runs against the same document must produce
 * the same requests, or the observations stop being comparable and the report
 * stops being diffable.
 *
 * <p>The values are also obviously synthetic. A run that accidentally reaches a
 * real environment should be recognizable in that environment's logs, not
 * disguised as ordinary traffic.
 */
public class SchemaValueFactory {

    private static final String TEXT = "testforge";

    public Optional<String> generate(Schema<?> schema) {
        String type = Schemas.type(schema);
        if (type == null) {
            return Optional.of(TEXT);
        }

        return switch (type) {
            case "string" -> Optional.of(string(schema));
            case "integer", "number" -> Optional.of("1");
            case "boolean" -> Optional.of("true");
            // an array or object parameter needs a serialization style this
            // module does not model in v1; skipping is better than guessing
            default -> Optional.empty();
        };
    }

    private String string(Schema<?> schema) {
        String format = Schemas.format(schema);
        if (format == null) {
            return TEXT;
        }
        return switch (format) {
            case "uuid" -> "00000000-0000-0000-0000-000000000001";
            case "date" -> "2024-01-01";
            case "date-time" -> "2024-01-01T00:00:00Z";
            case "email" -> "explorer@testforge.invalid";
            case "uri", "url" -> "https://testforge.invalid";
            case "byte" -> "dGVzdGZvcmdl";
            case "hostname" -> "testforge.invalid";
            case "ipv4" -> "192.0.2.1";
            case "ipv6" -> "2001:db8::1";
            default -> TEXT;
        };
    }
}
