package io.testforge.security;

import io.restassured.filter.*;
import io.restassured.response.Response;
import io.restassured.specification.*;
import java.util.*;

/** Observes the final response and never adds traffic or changes the request/response. */
public class PassiveSecurityFilter implements OrderedFilter {
  private final PassiveSecurityChecks checks;
  private final SecurityCollector collector;

  public PassiveSecurityFilter(PassiveSecurityChecks checks, SecurityCollector collector) {
    this.checks = checks;
    this.collector = collector;
  }

  @Override
  public int getOrder() {
    return 5;
  }

  @Override
  public Response filter(
      FilterableRequestSpecification request,
      FilterableResponseSpecification specification,
      FilterContext context) {
    Response response = context.next(request, specification);
    try {
      Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      response
          .getHeaders()
          .forEach(
              h -> headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue()));
      boolean authenticated =
          request.getHeaders().hasHeaderWithName("Authorization")
              || request.getHeaders().hasHeaderWithName("Cookie")
              || !request.getCookies().asList().isEmpty();
      checks.inspect(
          request.getMethod(),
          request.getURI(),
          authenticated,
          request.getHeaders().getValue("Origin"),
          response.statusCode(),
          headers,
          response.asString());
    } catch (RuntimeException e) {
      collector.covered(
          "passive",
          SecurityTargets.endpoint(request.getMethod(), request.getURI()),
          null,
          "not_measured",
          "response inspection failed");
    }
    return response;
  }
}
