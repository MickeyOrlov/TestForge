package io.testforge.contract.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A contract backed by a full JSON Schema document — the growth path beyond
 * {@link MessageContract}'s field rules: patterns, enums, ranges, conditional
 * branches, {@code additionalProperties} and everything else the schema
 * vocabulary offers.
 */
public record SchemaContract(String name, String schemaJson) {

    public SchemaContract {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("schemaJson must not be blank");
        }
    }

    public static SchemaContract of(String name, String schemaJson) {
        return new SchemaContract(name, schemaJson);
    }

    /** Loads the schema text from the classpath, e.g. {@code contracts/partner-event.schema.json}. */
    public static SchemaContract fromResource(String name, String classpathLocation) {
        try (InputStream stream = SchemaContract.class.getClassLoader()
                .getResourceAsStream(classpathLocation)) {
            if (stream == null) {
                throw new IllegalArgumentException(
                        "Schema resource not found on classpath: " + classpathLocation);
            }
            return new SchemaContract(name, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema resource " + classpathLocation, e);
        }
    }
}
