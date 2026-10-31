package io.testforge.api.discovery;

import java.util.List;

public record ApiSpecReport(
        String specId,
        String location,
        boolean failed,
        int endpoints,
        CatalogDiff catalogDiff,
        List<ApiShapeReport> shapes,
        String catalogArtifact,
        String error,
        List<ApiChange> changes) {

    public ApiSpecReport(String specId, String location, boolean failed, int endpoints, CatalogDiff catalogDiff,
                         List<ApiShapeReport> shapes, String catalogArtifact, String error) {
        this(specId, location, failed, endpoints, catalogDiff, shapes, catalogArtifact, error, List.of());
    }

    public ApiSpecReport {
        catalogDiff = catalogDiff == null ? CatalogDiff.noBaseline() : catalogDiff;
        shapes = List.copyOf(shapes == null ? List.of() : shapes);
        changes = List.copyOf(changes == null ? List.of() : changes);
    }

    /** Changes a client of the baseline breaks on. */
    public List<ApiChange> breakingChanges() {
        return changes.stream().filter(change -> change.impact() == ApiChange.Impact.BREAKING).toList();
    }
}
