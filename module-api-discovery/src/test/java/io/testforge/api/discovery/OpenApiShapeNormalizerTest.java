package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiShapeNormalizerTest {

    private final OpenApiShapeNormalizer normalizer = new OpenApiShapeNormalizer();

    @Test
    void normalizesObjectArraysWithoutIndexNoise() {
        ObjectSchema item = new ObjectSchema();
        item.setRequired(List.of("sku"));
        item.addProperty("sku", new StringSchema());
        item.addProperty("quantity", new IntegerSchema());

        ObjectSchema root = new ObjectSchema();
        root.setRequired(List.of("id", "items"));
        root.addProperty("id", new StringSchema());
        root.addProperty("items", new ArraySchema().items(item));

        Map<String, SchemaShapeEntry> shape = normalizer.normalize(root);

        assertThat(shape)
                .containsEntry("$", new SchemaShapeEntry("OBJECT", true, false))
                .containsEntry("$.id", new SchemaShapeEntry("STRING", true, false))
                .containsEntry("$.items", new SchemaShapeEntry("ARRAY", true, false))
                .containsEntry("$.items[]", new SchemaShapeEntry("OBJECT", true, false))
                .containsEntry("$.items[].sku", new SchemaShapeEntry("STRING", true, false))
                .containsEntry("$.items[].quantity", new SchemaShapeEntry("INTEGER", false, false));
        assertThat(shape.keySet()).noneMatch(path -> path.contains("[0]"));
    }

    @Test
    void preservesNullableFields() {
        StringSchema note = new StringSchema();
        note.setNullable(true);
        ObjectSchema root = new ObjectSchema();
        root.addProperty("note", note);

        Map<String, SchemaShapeEntry> shape = normalizer.normalize(root);

        assertThat(shape)
                .containsEntry("$.note", new SchemaShapeEntry("STRING", false, true));
    }
}
