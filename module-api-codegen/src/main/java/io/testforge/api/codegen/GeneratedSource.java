package io.testforge.api.codegen;

public record GeneratedSource(String relativePath, String content) {

    public GeneratedSource {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Generated source path must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Generated source content must not be blank for " + relativePath);
        }
    }
}
