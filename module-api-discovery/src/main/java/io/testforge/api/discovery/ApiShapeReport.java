package io.testforge.api.discovery;

public record ApiShapeReport(
        String name,
        boolean failed,
        String operationKey,
        String direction,
        String statusCode,
        String contentType,
        ApiShapeDiff shapeDiff,
        String shapeArtifact) {

    public ApiShapeReport {
        shapeDiff = shapeDiff == null ? ApiShapeDiff.noBaseline() : shapeDiff;
    }
}
