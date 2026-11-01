package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpTrafficSummaryTest {

    @Test
    void pathsAreShapedSoRunsAndIdsCompare() {
        assertThat(HttpTrafficSummary.shape("http://localhost:8080/orders/42/items/3f2b9c1e-8a4d-4f7e-9b0a-1c2d3e4f5a6b?x=1"))
                .isEqualTo("/orders/{n}/items/{uuid}");
        assertThat(HttpTrafficSummary.shape("http://h/tokens/a8f3k2m9q7z4x1c6v5b0")).isEqualTo("/tokens/{id}");
        assertThat(HttpTrafficSummary.shape("http://h/health")).isEqualTo("/health");
        assertThat(HttpTrafficSummary.shape("http://h")).isEqualTo("/");
    }

    @Test
    void statusClassesTransportErrorsAndPercentilesArePerOperation() {
        HttpTrafficSummary summary = new HttpTrafficSummary(null);
        for (int i = 1; i <= 20; i++) {
            summary.record("GET /orders/{n}", i == 20 ? 503 : 200, i * 10L);
        }
        summary.record("POST /orders", -1, 5);

        assertThat(summary.operations()).hasSize(2);
        Map<String, Object> orders = summary.operations().get(0);
        assertThat(orders).containsEntry("operation", "GET /orders/{n}").containsEntry("requests", 20L)
                .containsEntry("p50Millis", 100L).containsEntry("p95Millis", 190L).containsEntry("maxMillis", 200L);
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) orders.get("status");
        assertThat(status).containsEntry("2xx", 19L).containsEntry("5xx", 1L);
        assertThat(summary.operations().get(1)).containsEntry("transportErrors", 1L);
    }

    @Test
    void operationsAreBounded() {
        HttpTrafficSummary summary = new HttpTrafficSummary(null);
        for (int i = 0; i < HttpTrafficSummary.MAX_OPERATIONS + 5; i++) {
            summary.record("GET /op" + i, 200, 1);
        }
        assertThat(summary.operations()).hasSize(HttpTrafficSummary.MAX_OPERATIONS + 1)
                .anyMatch(operation -> operation.get("operation").equals(HttpTrafficSummary.OTHER));
    }
}
