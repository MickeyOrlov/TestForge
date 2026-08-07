package io.testforge.api.discovery;

public record ApiSpecSource(String id, String location) {

    public ApiSpecSource {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("API spec id must not be blank");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("API spec location must not be blank for " + id);
        }
    }
}
