package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.PreparedRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reduces a reproducing request to the smallest one that still shows the same
 * finding.
 *
 * <p>A finding on {@code $.profile.age} inside an eighteen-field payload is a
 * fact; the same finding inside a three-field payload is a bug report someone
 * can act on within a minute. That is the entire purpose.
 *
 * <p>Three rules keep it honest. Only fields that are <em>not</em> the target
 * are touched, so the case never quietly becomes a different case. Only
 * <em>optional</em> fields are removed and arrays only shrink to their declared
 * {@code minItems}, so every candidate stays "the valid baseline except the one
 * mutation" — the invariant the whole differential model rests on. And every
 * candidate is judged by {@link FindingSignature}, not by status code, so a
 * shrink that accidentally destroys the finding is rejected rather than
 * celebrated.
 *
 * <p>Greedy and bounded: each candidate is one request, the order is fixed, and
 * the budget is a hard stop. There is no search here and deliberately so — a
 * minimization pass that costs hundreds of requests is one nobody enables.
 */
public class RequestShrinker {

    /** Lengths tried for an oversized probe value, longest first. */
    private static final List<Integer> LENGTH_LADDER = List.of(512, 64, 16);

    private final ObjectMapper objectMapper;
    private final ConstraintInventory inventory;
    private final JsonBodyFactory bodyFactory;
    private final FindingConfirmer confirmer;
    private final ApiFuzzProperties properties;

    public RequestShrinker(ObjectMapper objectMapper, ConstraintInventory inventory,
                           JsonBodyFactory bodyFactory, FindingConfirmer confirmer,
                           ApiFuzzProperties properties) {
        this.objectMapper = objectMapper;
        this.inventory = inventory;
        this.bodyFactory = bodyFactory;
        this.confirmer = confirmer;
        this.properties = properties;
    }

    public ShrinkOutcome shrink(ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
                                PreparedRequest original, BodyPlan bodyPlan, FindingSignature signature) {

        if (properties.maxShrinkAttempts() <= 0) {
            return ShrinkOutcome.notAttempted();
        }
        if (!confirmer.repeatable(operation)) {
            return ShrinkOutcome.refused(
                    "%s is not safe to repeat; set forge.api-fuzz.allow-unsafe-confirmation to minimize it"
                            .formatted(operation.method()));
        }

        Budget budget = new Budget(properties.maxShrinkAttempts());
        PreparedRequest best = original;
        List<String> removed = new ArrayList<>();
        int originalSize = size(original);

        best = removeOptionalQueryParameters(operation, control, fuzzCase, best, signature, budget, removed);
        if (bodyPlan.usable() && best.body() != null) {
            best = removeOptionalBodyFields(operation, control, fuzzCase, best, bodyPlan, signature,
                    budget, removed);
            best = shrinkArrays(operation, control, fuzzCase, best, bodyPlan, signature, budget, removed);
            best = minimizeUnrelatedValues(operation, control, fuzzCase, best, bodyPlan, signature, budget);
        }
        best = shrinkTargetValue(operation, control, fuzzCase, best, bodyPlan, signature, budget);

        return ShrinkOutcome.of(budget.used(), originalSize, size(best), best.body(), removed);
    }

    // --- structural shrinking ------------------------------------------------

    private PreparedRequest removeOptionalQueryParameters(
            ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
            PreparedRequest request, FindingSignature signature, Budget budget, List<String> removed) {

        Set<String> required = requiredLocations(operation, BodyPlan.none());
        PreparedRequest best = request;

        for (String name : new TreeSet<>(request.queryParameters().keySet())) {
            if (budget.exhausted() || name.equals(target(fuzzCase))
                    || required.contains("query:" + name)) {
                continue;
            }
            Map<String, String> candidate = new LinkedHashMap<>(best.queryParameters());
            candidate.remove(name);

            PreparedRequest attempt = new PreparedRequest(best.method(), best.pathTemplate(),
                    best.pathParameters(), candidate, best.body(), best.contentType());
            if (stillReproduces(operation, control, fuzzCase, attempt, signature, budget)) {
                best = attempt;
                removed.add("query:" + name);
            }
        }
        return best;
    }

    /**
     * Optional fields are tried shallowest first, so removing one branch can
     * take a whole subtree in a single request rather than a field at a time.
     */
    private PreparedRequest removeOptionalBodyFields(
            ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
            PreparedRequest request, BodyPlan bodyPlan, FindingSignature signature,
            Budget budget, List<String> removed) {

        Set<String> required = requiredLocations(operation, bodyPlan);
        PreparedRequest best = request;

        for (String path : removablePaths(best.body(), fuzzCase, required)) {
            if (budget.exhausted()) {
                break;
            }
            if (underComposition(path, bodyPlan)) {
                continue;
            }
            JsonNode candidateBody = parse(best.body());
            if (candidateBody == null || !BodyPaths.remove(candidateBody, path)) {
                continue;
            }

            PreparedRequest attempt = withBody(best, candidateBody.toString());
            if (stillReproduces(operation, control, fuzzCase, attempt, signature, budget)) {
                best = attempt;
                removed.add(path);
            }
        }
        return best;
    }

    private PreparedRequest shrinkArrays(
            ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
            PreparedRequest request, BodyPlan bodyPlan, FindingSignature signature,
            Budget budget, List<String> removed) {

        PreparedRequest best = request;
        for (String path : arrayPaths(parse(best.body()), "$", new ArrayList<>())) {
            if (budget.exhausted() || onTargetPath(path, fuzzCase) || underComposition(path, bodyPlan)) {
                continue;
            }

            int minItems = minItems(bodyPlan, path);
            JsonNode candidateBody = parse(best.body());
            JsonNode array = candidateBody == null ? null : BodyPaths.resolve(candidateBody, path).orElse(null);
            if (!(array instanceof ArrayNode node) || node.size() <= minItems) {
                continue;
            }

            // never below the declared minimum: a candidate that breaks another
            // constraint is not "the baseline except one mutation" any more
            while (node.size() > minItems) {
                node.remove(node.size() - 1);
            }
            PreparedRequest attempt = withBody(best, candidateBody.toString());
            if (stillReproduces(operation, control, fuzzCase, attempt, signature, budget)) {
                best = attempt;
                removed.add(path + " → " + minItems + " items");
            }
        }
        return best;
    }

    /**
     * Replaces the values of unrelated string fields with the shortest the
     * schema still allows. Structure is what makes a payload hard to read, but
     * a forty-character filler string next to the one field that matters is
     * noise too — and unlike removal this keeps the field, so a required one
     * can be shortened rather than left as is.
     */
    private PreparedRequest minimizeUnrelatedValues(
            ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
            PreparedRequest request, BodyPlan bodyPlan, FindingSignature signature, Budget budget) {

        PreparedRequest best = request;
        for (String path : removablePaths(best.body(), fuzzCase, Set.of())) {
            if (budget.exhausted() || underComposition(path, bodyPlan)) {
                continue;
            }

            JsonNode candidateBody = parse(best.body());
            JsonNode current = candidateBody == null ? null : BodyPaths.resolve(candidateBody, path).orElse(null);
            if (current == null || !current.isTextual()) {
                continue;
            }

            Schema<?> schema = schemaAt(bodyFactory.effective(bodyPlan.schema()), path);
            String smallest = smallestValid(schema, current.asText());
            if (smallest == null || smallest.equals(current.asText())) {
                continue;
            }

            BodyPaths.set(candidateBody, path, objectMapper.getNodeFactory().textNode(smallest));
            PreparedRequest attempt = withBody(best, candidateBody.toString());
            if (stillReproduces(operation, control, fuzzCase, attempt, signature, budget)) {
                best = attempt;
            }
        }
        return best;
    }

    /** The shortest string this schema still accepts, or null when nothing shorter is provable. */
    private String smallestValid(Schema<?> schema, String current) {
        if (schema == null || schema.getEnum() != null || schema.getFormat() != null) {
            // an enum member or a formatted value is already as small as it is
            // allowed to be; shortening it would break a second constraint
            return null;
        }
        int minLength = schema.getMinLength() == null ? 1 : Math.max(schema.getMinLength(), 1);
        if (current.length() <= minLength) {
            return null;
        }
        String candidate = "a".repeat(minLength);
        return SchemaFacts.satisfiesString(schema, candidate) ? candidate : null;
    }

    // --- value shrinking -----------------------------------------------------

    /**
     * Only worth doing when this module chose an arbitrary size. A case derived
     * from a declared bound is already the smallest value that violates it —
     * {@code maxLength + 1} cannot be improved on — so those are left alone.
     */
    private PreparedRequest shrinkTargetValue(
            ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
            PreparedRequest request, BodyPlan bodyPlan, FindingSignature signature, Budget budget) {

        if (fuzzCase.constraint() != null || fuzzCase.value() == null) {
            return request;
        }
        if (fuzzCase.kind() != FuzzCaseKind.TOO_LONG && fuzzCase.kind() != FuzzCaseKind.HUGE_NUMBER) {
            return request;
        }

        PreparedRequest best = request;
        for (String smaller : ladder(fuzzCase)) {
            if (budget.exhausted()) {
                break;
            }
            PreparedRequest attempt = withValue(best, bodyPlan, fuzzCase, smaller);
            if (attempt != null && stillReproduces(operation, control, fuzzCase, attempt, signature, budget)) {
                best = attempt;
            }
        }
        return best;
    }

    private List<String> ladder(FuzzCase fuzzCase) {
        if (fuzzCase.kind() == FuzzCaseKind.HUGE_NUMBER) {
            return List.of("1000000", "1000", "100");
        }
        int current = rawLength(fuzzCase.value());
        return LENGTH_LADDER.stream()
                .filter(length -> length < current)
                .map("a"::repeat)
                .toList();
    }

    private int rawLength(String value) {
        String unquoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
        return unquoted.length();
    }

    private PreparedRequest withValue(PreparedRequest request, BodyPlan bodyPlan,
                                      FuzzCase fuzzCase, String value) {
        if (!fuzzCase.bodyCase()) {
            Map<String, String> query = new LinkedHashMap<>(request.queryParameters());
            Map<String, String> path = new LinkedHashMap<>(request.pathParameters());
            if ("path".equals(fuzzCase.in())) {
                path.put(fuzzCase.parameterName(), value);
            } else {
                query.put(fuzzCase.parameterName(), value);
            }
            return new PreparedRequest(request.method(), request.pathTemplate(), path, query,
                    request.body(), request.contentType());
        }

        JsonNode body = parse(request.body());
        if (body == null || !BodyPaths.set(body, fuzzCase.parameterName(),
                objectMapper.getNodeFactory().textNode(value))) {
            return null;
        }
        return withBody(request, body.toString());
    }

    // --- helpers -------------------------------------------------------------

    private boolean stillReproduces(ExplorableOperation operation, ControlResult control, FuzzCase fuzzCase,
                                    PreparedRequest candidate, FindingSignature signature, Budget budget) {
        budget.spend();
        return FindingSignature.of(confirmer.observe(operation, control, fuzzCase, candidate))
                .matches(signature);
    }

    /**
     * A path inside a {@code oneOf}/{@code anyOf} subtree is left alone. The
     * baseline picked one branch there, and removing a field that branch needs
     * would silently turn the request into something the document never
     * described — a candidate that is no longer "valid except the mutation".
     */
    private boolean underComposition(String path, BodyPlan bodyPlan) {
        // strictly inside, not the node itself: dropping an optional oneOf
        // wholesale is a legitimate simplification, reaching into the branch
        // the baseline happened to pick is not
        return bodyPlan.unfuzzablePaths().stream()
                .anyMatch(prefix -> path.startsWith(prefix + ".") || path.startsWith(prefix + "["));
    }

    /** Optional leaves and branches, shallowest first, never the target or its ancestors. */
    private List<String> removablePaths(String body, FuzzCase fuzzCase, Set<String> required) {
        JsonNode root = parse(body);
        if (root == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        collectPaths(root, "$", paths);

        return paths.stream()
                .filter(path -> !required.contains(path))
                .filter(path -> !onTargetPath(path, fuzzCase))
                .sorted((left, right) -> {
                    int byDepth = Integer.compare(depth(left), depth(right));
                    return byDepth != 0 ? byDepth : left.compareTo(right);
                })
                .toList();
    }

    private void collectPaths(JsonNode node, String path, List<String> paths) {
        if (!node.isObject()) {
            return;
        }
        node.properties().forEach(entry -> {
            String child = BodyPaths.child(path, entry.getKey());
            paths.add(child);
            collectPaths(entry.getValue(), child, paths);
        });
    }

    private List<String> arrayPaths(JsonNode node, String path, List<String> found) {
        if (node == null) {
            return found;
        }
        if (node.isArray()) {
            found.add(path);
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                    arrayPaths(entry.getValue(), BodyPaths.child(path, entry.getKey()), found));
        }
        return found;
    }

    /** A path is off limits when it is the target, contains it, or lives inside it. */
    private boolean onTargetPath(String path, FuzzCase fuzzCase) {
        if (!fuzzCase.bodyCase()) {
            return false;
        }
        String target = fuzzCase.parameterName();
        return target.equals(path) || target.startsWith(path + ".") || target.startsWith(path + "[")
                || path.startsWith(target + ".") || path.startsWith(target + "[");
    }

    private String target(FuzzCase fuzzCase) {
        return fuzzCase.parameterName();
    }

    private Set<String> requiredLocations(ExplorableOperation operation, BodyPlan bodyPlan) {
        Set<String> required = new TreeSet<>();
        inventory.of(operation, bodyPlan).stream()
                .filter(constraint -> "required".equals(constraint.constraint()))
                .forEach(constraint -> required.add(constraint.location()));
        return required;
    }

    private int minItems(BodyPlan bodyPlan, String path) {
        Schema<?> schema = schemaAt(bodyFactory.effective(bodyPlan.schema()), path);
        return schema == null || schema.getMinItems() == null ? 0 : schema.getMinItems();
    }

    /** Follows a body path through the schema; null when the path is not modelled. */
    private Schema<?> schemaAt(Schema<?> root, String path) {
        Schema<?> current = root;
        for (String segment : path.replace("$", "").split("\\.")) {
            if (current == null || segment.isBlank()) {
                continue;
            }
            Map<String, Schema> properties = current.getProperties();
            if (properties == null) {
                return null;
            }
            current = bodyFactory.effective(properties.get(segment));
        }
        return current;
    }

    private int depth(String path) {
        return (int) path.chars().filter(character -> character == '.').count();
    }

    /** Object fields plus query parameters — what a reader counts when scanning a payload. */
    private int size(PreparedRequest request) {
        int fields = request.queryParameters().size();
        JsonNode body = parse(request.body());
        if (body != null) {
            List<String> paths = new ArrayList<>();
            collectPaths(body, "$", paths);
            fields += paths.size();
        }
        return fields;
    }

    private PreparedRequest withBody(PreparedRequest request, String body) {
        return new PreparedRequest(request.method(), request.pathTemplate(), request.pathParameters(),
                request.queryParameters(), body, request.contentType());
    }

    private JsonNode parse(String body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** A hard stop, counted in requests rather than in time. */
    private static final class Budget {

        private final int limit;
        private int used;

        Budget(int limit) {
            this.limit = limit;
        }

        void spend() {
            used++;
        }

        boolean exhausted() {
            return used >= limit;
        }

        int used() {
            return used;
        }
    }
}
