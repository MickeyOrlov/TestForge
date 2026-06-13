package io.testforge.contract.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ShapeDiffTest {

    @Test
    void findsAddedRemovedAndChangedPaths() {
        ShapeDiff diff = ShapeDiff.between(
                Map.of(
                        "$", "OBJECT",
                        "$.eventId", "STRING",
                        "$.payload.status", "INTEGER",
                        "$.payload.legacy", "STRING"),
                Map.of(
                        "$", "OBJECT",
                        "$.eventId", "STRING",
                        "$.payload.status", "STRING",
                        "$.payload.reason", "STRING"));

        assertThat(diff.baselinePresent()).isTrue();
        assertThat(diff.added()).containsEntry("$.payload.reason", "STRING");
        assertThat(diff.removed()).containsEntry("$.payload.legacy", "STRING");
        assertThat(diff.changed())
                .containsExactly(new ShapeDiff.TypeChange("$.payload.status", "INTEGER", "STRING"));
    }
}
