package io.testforge.api.codegen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param compile compile every generated spec right after writing it and fail
 *        the spec on a compile error (default {@code true}); the classpath is
 *        the running JVM's plus wherever {@code module-http}, REST Assured
 *        and Jackson were loaded from
 * @param surfaceBaselineDir where to find the {@code <spec>/surface.json} to
 *        compare a generation with — a CI artifact or a checked-in copy; unset,
 *        the previous run's own output is the baseline
 * @param failOnBreakingChanges fail a spec whose generated surface breaks
 *        tests compiled against the baseline (default {@code false}: report only)
 */
@ConfigurationProperties(prefix = "forge.api-codegen")
public record ApiCodegenProperties(
        Boolean enabled,
        String outputDir,
        String basePackage,
        Boolean compile,
        String surfaceBaselineDir,
        Boolean failOnBreakingChanges) {

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
        if (surfaceBaselineDir != null && surfaceBaselineDir.isBlank()) {
            surfaceBaselineDir = null;
        }
        if (failOnBreakingChanges == null) {
            failOnBreakingChanges = false;
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
        this(enabled, outputDir, basePackage, null, null, null);
    }

    public ApiCodegenProperties(Boolean enabled, String outputDir, String basePackage, Boolean compile) {
        this(enabled, outputDir, basePackage, compile, null, null);
    }
}
