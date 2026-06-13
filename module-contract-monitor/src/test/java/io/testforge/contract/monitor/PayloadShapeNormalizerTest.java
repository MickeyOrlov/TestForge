package io.testforge.contract.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadShapeNormalizerTest {

    private final PayloadShapeNormalizer normalizer = new PayloadShapeNormalizer();

    @Test
    void normalizesJsonShapeWithoutValues() {
        assertThat(normalizer.normalize("""
                {
                  "eventId": "evt-1",
                  "payload": {
                    "status": "accepted",
                    "count": 2,
                    "active": true
                  }
                }
                """))
                .containsEntry("$", "OBJECT")
                .containsEntry("$.eventId", "STRING")
                .containsEntry("$.payload", "OBJECT")
                .containsEntry("$.payload.status", "STRING")
                .containsEntry("$.payload.count", "INTEGER")
                .containsEntry("$.payload.active", "BOOLEAN");
    }

    @Test
    void normalizesArraysWithoutIndexNoise() {
        assertThat(normalizer.normalize("""
                {
                  "items": [
                    { "sku": "A", "qty": 1 },
                    { "sku": "B", "qty": 2 }
                  ]
                }
                """))
                .containsEntry("$.items", "ARRAY")
                .containsEntry("$.items[]", "OBJECT")
                .containsEntry("$.items[].sku", "STRING")
                .containsEntry("$.items[].qty", "INTEGER")
                .doesNotContainKeys("$.items[0].sku", "$.items[1].sku");
    }
}
