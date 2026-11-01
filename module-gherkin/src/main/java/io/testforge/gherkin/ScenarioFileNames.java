package io.testforge.gherkin;

import java.util.Locale;

/** {@code paying-an-order-emits-one-payment-event-L5-kafka.md}: a file name per failed scenario. */
public final class ScenarioFileNames {

    private ScenarioFileNames() {
    }

    /** The line keeps two outline rows of one scenario apart. */
    public static String of(String scenario, Integer line, String suffix) {
        String name = scenario == null ? "" : scenario.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (name.length() > 80) {
            name = name.substring(0, 80).replaceAll("-+$", "");
        }
        return (name.isEmpty() ? "scenario" : name) + (line == null ? "" : "-L" + line) + "-" + suffix + ".md";
    }
}
