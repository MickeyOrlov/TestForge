package io.testforge.security;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.List;

/** Captures already-used credentials in process memory only; never fetches a new token. */
public class SecurityCredentialCaptureFilter implements OrderedFilter {
    private final SecuritySecretRegistry registry;
    private final List<String> headers;
    private final List<String> query;

    public SecurityCredentialCaptureFilter(
            SecuritySecretRegistry registry, List<String> headers, List<String> query) {
        this.registry = registry;
        this.headers = List.copyOf(headers);
        this.query = List.copyOf(query);
    }

    @Override
    public int getOrder() {
        return 790;
    }

    @Override
    public Response filter(
            FilterableRequestSpecification request,
            FilterableResponseSpecification response,
            FilterContext context) {
        for (String name : headers) {
            String value = request.getHeaders().getValue(name);
            if (value != null) {
                if (name.equalsIgnoreCase("Cookie"))
                    for (String pair : value.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) registry.register(pair.substring(eq + 1).trim());
                    }
                else {
                    registry.register(value);
                    int space = value.indexOf(' ');
                    if (space > 0) registry.register(value.substring(space + 1));
                }
            }
        }
        request.getCookies().forEach(cookie -> registry.register(cookie.getValue()));
        for (String name : query) {
            Object value = request.getQueryParams().get(name);
            if (value instanceof String text) registry.register(text);
        }
        return context.next(request, response);
    }
}
