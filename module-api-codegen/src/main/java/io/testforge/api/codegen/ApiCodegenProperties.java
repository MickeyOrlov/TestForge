package io.testforge.api.codegen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param compile compile every generated spec right after writing it and fail
 *        the spec on a compile error (default {@code true}); the classpath is
 *        the running JVM's, which inside a test task already holds
 *        {@code module-http}, REST Assured and Jackson
 */
@ConfigurationProperties(prefix = "forge.api-codegen")
public record ApiCodegenProperties(
        Boolean enabled,
        String outputDir,
        String basePackage,
        Boolean compile) {

    @ConstructorBinding
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
        if (compile == null) {
            compile = true;
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

    public ApiCodegenProperties(Boolean enabled, String outputDir, String basePackage) {
        this(enabled, outputDir, basePackage, null);
    }
}
