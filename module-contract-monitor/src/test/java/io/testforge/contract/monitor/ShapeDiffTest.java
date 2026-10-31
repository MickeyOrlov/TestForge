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

    @Test
    void eachDriftIsJudgedForConsumersWorstFirst() {
        ShapeDiff diff = ShapeDiff.between(
                java.util.Map.of("$.id", "STRING", "$.legacy", "STRING", "$.amount", "NUMBER|NULL", "$.status", "STRING"),
                java.util.Map.of("$.id", "STRING|NULL", "$.amount", "NUMBER", "$.status", "STRING", "$.channel", "STRING"));

        org.assertj.core.api.Assertions.assertThat(diff.impact()).extracting(Object::toString).containsExactly(
                "BREAKING $.id: may now arrive as [NULL] (was STRING)",
                "BREAKING $.legacy: no sampled message has it any more",
                "SAFE $.amount: narrowed NUMBER|NULL → NUMBER",
                "SAFE $.channel: new STRING");
        org.assertj.core.api.Assertions.assertThat(diff.breaking()).isTrue();
        org.assertj.core.api.Assertions.assertThat(ShapeDiff.between(
                java.util.Map.of("$.id", "STRING"), java.util.Map.of("$.id", "STRING", "$.extra", "BOOLEAN"))
                .breaking()).isFalse();
    }
}
