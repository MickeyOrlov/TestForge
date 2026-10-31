package io.testforge.api.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Judges a catalog or shape diff from the client's side, so CI can fail on
 * what breaks clients instead of on every drift — a gate that fails on a new
 * endpoint gets switched off.
 *
 * <p>Requests and responses are judged in opposite directions: a client
 * <em>sends</em> a request, so a newly required field breaks it; it
 * <em>reads</em> a response, so a field that may now be missing breaks it.
 */
public final class ApiChangeClassifier {

    private static final Set<String> OPAQUE_TYPES = Set.of("UNKNOWN", "ONE_OF", "ANY_OF");

    private ApiChangeClassifier() {
    }

    public static List<ApiChange> catalog(CatalogDiff diff) {
        List<ApiChange> changes = new ArrayList<>();
        diff.removed().forEach((key, endpoint) ->
                changes.add(new ApiChange(key, ApiChange.Impact.BREAKING, "operation removed")));
        diff.added().forEach((key, endpoint) ->
                changes.add(new ApiChange(key, ApiChange.Impact.SAFE, "new operation")));
        for (CatalogDiff.EndpointChange change : diff.changed()) {
            ApiEndpoint was = change.baseline();
            ApiEndpoint now = change.current();
            Set<String> removedRequestTypes = minus(was.requestContentTypes(), now.requestContentTypes());
            if (!removedRequestTypes.isEmpty()) {
                changes.add(new ApiChange(change.key(), ApiChange.Impact.BREAKING,
                        "request content types no longer accepted " + removedRequestTypes));
            }
            Set<String> removedStatuses = minus(was.responses().keySet(), now.responses().keySet());
            if (!removedStatuses.isEmpty()) {
                changes.add(new ApiChange(change.key(), ApiChange.Impact.SAFE,
                        "responses no longer documented " + removedStatuses));
            }
            Set<String> addedStatuses = minus(now.responses().keySet(), was.responses().keySet());
            if (!addedStatuses.isEmpty()) {
                changes.add(new ApiChange(change.key(), ApiChange.Impact.RISKY,
                        "new responses " + addedStatuses + ": a client may not handle them"));
            }
            for (Map.Entry<String, List<String>> response : now.responses().entrySet()) {
                List<String> before = was.responses().get(response.getKey());
                if (before != null) {
                    Set<String> removedTypes = minus(before, response.getValue());
                    if (!removedTypes.isEmpty()) {
                        changes.add(new ApiChange(change.key(), ApiChange.Impact.BREAKING,
                                "response " + response.getKey() + " no longer sent as " + removedTypes));
                    }
                }
            }
            if (!was.deprecated() && now.deprecated()) {
                changes.add(new ApiChange(change.key(), ApiChange.Impact.RISKY, "now deprecated"));
            }
        }
        return sorted(changes);
    }

    public static List<ApiChange> shape(ApiShapeReport report) {
        boolean request = "request".equals(report.direction());
        String prefix = report.operationKey() + (request ? " request " : " response " + report.statusCode() + " ");
        ApiShapeDiff diff = report.shapeDiff();
        List<ApiChange> changes = new ArrayList<>();
        diff.added().forEach((path, entry) -> changes.add(request && entry.required()
                ? new ApiChange(prefix + path, ApiChange.Impact.BREAKING, "new required field: clients do not send it")
                : new ApiChange(prefix + path, ApiChange.Impact.SAFE, "new field")));
        diff.removed().forEach((path, entry) -> changes.add(request
                ? new ApiChange(prefix + path, ApiChange.Impact.RISKY, "field no longer described: clients still send it")
                : new ApiChange(prefix + path, entry.required() ? ApiChange.Impact.BREAKING : ApiChange.Impact.RISKY,
                        entry.required() ? "required field removed" : "optional field removed")));
        for (ApiShapeDiff.FieldChange change : diff.changed()) {
            String location = prefix + change.path();
            SchemaShapeEntry was = change.baseline();
            SchemaShapeEntry now = change.current();
            if (!was.type().equals(now.type())) {
                boolean opaque = OPAQUE_TYPES.contains(was.type()) || OPAQUE_TYPES.contains(now.type());
                changes.add(new ApiChange(location, opaque ? ApiChange.Impact.UNKNOWN : ApiChange.Impact.BREAKING,
                        "type " + was.type() + " → " + now.type()));
            }
            if (was.required() != now.required()) {
                boolean breaks = request ? now.required() : was.required();
                changes.add(new ApiChange(location, breaks ? ApiChange.Impact.BREAKING : ApiChange.Impact.SAFE,
                        now.required() ? "now required" : "no longer required"));
            }
            if (was.nullable() != now.nullable()) {
                boolean breaks = request ? was.nullable() : now.nullable();
                changes.add(new ApiChange(location, breaks ? ApiChange.Impact.BREAKING : ApiChange.Impact.SAFE,
                        now.nullable() ? "may now be null" : "no longer nullable"));
            }
        }
        return sorted(changes);
    }

    private static Set<String> minus(java.util.Collection<String> from, java.util.Collection<String> remove) {
        Set<String> result = new TreeSet<>(from);
        result.removeAll(remove);
        return result;
    }

    private static List<ApiChange> sorted(List<ApiChange> changes) {
        changes.sort(Comparator.comparing(ApiChange::impact).thenComparing(ApiChange::location));
        return List.copyOf(changes);
    }
}
