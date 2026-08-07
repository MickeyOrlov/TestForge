package io.testforge.api.codegen;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.api-codegen")
public record ApiCodegenProperties(
        Boolean enabled,
        String outputDir,
        String basePackage) {

    public ApiCodegenProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/generated/testforge";
        }
        if (basePackage == null || basePackage.isBlank()) {
            basePackage = "io.testforge.generated";
        }
        if (!basePackage.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new IllegalArgumentException("forge.api-codegen.base-package is not a valid Java package: "
                    + basePackage);
        }
        for (String segment : basePackage.split("\\.")) {
            if (JavaNames.keyword(segment)) {
                throw new IllegalArgumentException("forge.api-codegen.base-package contains Java keyword: "
                        + segment);
            }
        }
    }
}
