package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import io.testforge.contract.shape.PayloadShapeNormalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Projects a declared schema onto the set of paths it mentions, in the same
 * dialect {@code PayloadShapeNormalizer} produces.
 *
 * <p>This exists because schema validation cannot answer the question a
 * discovery run is actually asking. Real documents almost never set
 * {@code additionalProperties: false}, so a response carrying a field the spec
 * never declared validates perfectly — and an undeclared field is exactly the
 * drift worth knowing about. Subtracting declared paths from observed ones
 * finds it.
 *
 * <p>The projection is deliberately shallow: no types, no required-ness, union
 * over {@code oneOf}/{@code anyOf} branches. It feeds an informational list,
 * not a verdict, so it does not need the semantic precision that would make it
 * a second schema engine.
 *
 * <p>Known limit: a recursive schema is expanded once per branch. The recursive
 * field itself is declared, but paths below the second level of recursion are
 * not, so a deeply nested payload can list a few of them as undeclared. That is
 * why undeclared fields are informational by default
 * ({@code fail-on.undeclared-fields}).
 */
public class DeclaredPathProjector {

    private static final int MAX_DEPTH = 20;

    public Set<String> project(OpenApiDocument document, JsonNode schema) {
        Set<String> declared = new TreeSet<>();
        walk(document, schema, PayloadShapeNormalizer.ROOT, declared, new LinkedHashSet<>(), 0);
        return Set.copyOf(declared);
    }

    private void walk(OpenApiDocument document, JsonNode schema, String path,
                      Set<String> declared, Set<String> visitedRefs, int depth) {

        if (!schema.isObject() || depth > MAX_DEPTH) {
            return;
        }

        String ref = schema.path("$ref").asText(null);
        if (ref != null) {
            // a self-referencing schema (a tree node, a threaded comment) would
            // otherwise recurse forever. The path itself is still declared —
            // dropping it would report the recursive field as undeclared, which
            // is the opposite of the truth
            if (!visitedRefs.add(ref)) {
                declared.add(path);
                return;
            }
            walk(document, document.resolve(ref), path, declared, visitedRefs, depth + 1);
            visitedRefs.remove(ref);
            return;
        }

        declared.add(path);

        for (String keyword : new String[] {"allOf", "anyOf", "oneOf"}) {
            schema.path(keyword).forEach(branch -> walk(document, branch, path, declared, visitedRefs, depth + 1));
        }

        JsonNode items = schema.path("items");
        if (items.isObject()) {
            walk(document, items, PayloadShapeNormalizer.elementPath(path), declared, visitedRefs, depth + 1);
        }

        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry -> walk(document, entry.getValue(),
                    PayloadShapeNormalizer.childPath(path, entry.getKey()), declared, visitedRefs, depth + 1));
        }
    }
}
