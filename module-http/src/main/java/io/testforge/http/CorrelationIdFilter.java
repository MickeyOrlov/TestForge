package io.testforge.http;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.util.UUID;

/**
 * Stamps every request of a scenario with the same request id and publishes it
 * to {@link ScenarioContext}.
 *
 * <p>That single value is what makes a failed CI run investigable afterwards:
 * the id in the report is the id in the service logs, traces and audit tables.
 * An id explicitly set on the request wins — a test that asserts on a specific
 * request id keeps control.
 */
public class CorrelationIdFilter implements OrderedFilter {

    static final int ORDER = 100;

    private final String header;

    public CorrelationIdFilter(String header) {
        this.header = header;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {

        if (!requestSpec.getHeaders().hasHeaderWithName(header)) {
            requestSpec.header(header, correlationId());
        }
        return ctx.next(requestSpec, responseSpec);
    }

    private static String correlationId() {
        return ScenarioContext.find(ScenarioKeys.CORRELATION_ID).orElseGet(() -> {
            String generated = "tf-" + UUID.randomUUID();
            ScenarioContext.put(ScenarioKeys.CORRELATION_ID, generated);
            return generated;
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
