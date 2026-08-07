package io.testforge.http;

import io.restassured.specification.RequestSpecification;

/**
 * Extension point for everything that must be applied to every request but
 * does not belong to this module: authentication headers, tenant selection,
 * signed payloads.
 *
 * <p>Declare customizers as beans; they run in Spring's usual
 * {@code @Order} sequence when a specification is built. {@code service} is
 * the name passed to {@code ApiClient.request(service)}, or {@code null} for
 * the default base URL.
 *
 * <pre>{@code
 * @Bean
 * ApiRequestCustomizer bearerToken(TokenProvider tokens) {
 *     return (request, service) -> request.header("Authorization", "Bearer " + tokens.forRole("admin"));
 * }
 * }</pre>
 *
 * <p>Use a REST Assured {@code Filter} bean instead when the value has to be
 * computed per request rather than per specification.
 */
@FunctionalInterface
public interface ApiRequestCustomizer {

    void customize(RequestSpecification request, String service);
}
