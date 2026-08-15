package io.testforge.reporting;

import io.qameta.allure.Allure;

/**
 * Attaches collected resource statistics to the current Allure test.
 *
 * <p>Optional dependency: compiled against
 * {@code io.qameta.allure:allure-java-commons}, which must be on the runtime
 * classpath of the test module that calls this. The monitor itself works
 * without Allure.
 */
public final class AllureResourceAttachments {

    private AllureResourceAttachments() {
    }

    public static void attach(ResourceUsageStats stats) {
        if (stats == null) {
            return;
        }
        try {
            Allure.addAttachment("JVM resource usage", "text/plain", stats.toFormattedText(), ".txt");
        } catch (Throwable ignored) {
        }
    }
}
