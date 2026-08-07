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
        String error) {

    public ApiSpecReport {
        catalogDiff = catalogDiff == null ? CatalogDiff.noBaseline() : catalogDiff;
        shapes = List.copyOf(shapes == null ? List.of() : shapes);
    }
}
