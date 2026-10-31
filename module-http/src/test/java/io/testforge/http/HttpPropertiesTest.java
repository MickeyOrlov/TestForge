package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpPropertiesTest {

    private static HttpProperties properties(String baseUrl, Map<String, String> headers,
                                             Map<String, HttpProperties.ServiceProperties> services) {
        return new HttpProperties(baseUrl, headers, services, null, null, null, null, null, null, null);
    }

    @Test
    void conservativeDefaults() {
        HttpProperties properties = properties(null, null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.retry().enabled()).isFalse();
        assertThat(properties.retry().methods()).containsExactlyInAnyOrder("GET", "HEAD", "OPTIONS");
        assertThat(properties.retry().statuses()).containsExactlyInAnyOrder(502, 503, 504);
        assertThat(properties.correlation().header()).isEqualTo("X-Request-Id");
        assertThat(properties.scope().enabled()).isTrue();
        assertThat(properties.scope().jsonPath()).as("follows forge.mock.scope-json-path").isNull();
        assertThat(properties.logging().redactHeaders()).contains("authorization", "cookie", "x-api-key");
        assertThat(properties.logging().redactJsonFields()).contains("password", "token", "client_secret");
    }

    @Test
    void configuredRedactionAndRetryValuesAreNormalized() {
        var logging = new HttpProperties.LoggingProperties(null, null, null,
                List.of("X-Api-Key", "x-api-key"), List.of("ClientSecret"));
        var retry = new HttpProperties.RetryProperties(true, null, null, null, Set.of("get", "Put"));

        assertThat(logging.redactHeaders()).containsExactly("x-api-key");
        assertThat(logging.redactJsonFields()).containsExactly("clientsecret");
        assertThat(retry.methods()).containsExactlyInAnyOrder("GET", "PUT");
    }

    @Test
    void nonPositiveBodyLimitFallsBackToTheDefault() {
        assertThat(new HttpProperties.LoggingProperties(null, null, 0, null, null).maxBodyChars()).isEqualTo(2000);
    }

    @Test
    void missingBaseUrlFailsLoudlyInsteadOfSendingSomewhere() {
        assertThatThrownBy(() -> properties(" ", null, null).resolveBaseUrl(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forge.http.base-url");
    }

    @Test
    void unknownServiceListsTheConfiguredOnes() {
        var services = Map.of(
                "payments", new HttpProperties.ServiceProperties("https://pay.example.test", null),
                "accounts", new HttpProperties.ServiceProperties("https://acc.example.test", null));

        assertThatThrownBy(() -> properties("https://api.example.test", null, services).resolveBaseUrl("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'billing'")
                .hasMessageContaining("[accounts, payments]");
    }

    @Test
    void serviceWithoutBaseUrlNamesItsOwnProperty() {
        var services = Map.of("payments", new HttpProperties.ServiceProperties(null, null));

        assertThatThrownBy(() -> properties("https://api.example.test", null, services).resolveBaseUrl("payments"))
                .hasMessageContaining("forge.http.services.payments.base-url");
    }

    @Test
    void serviceHeadersOverrideDefaultsWithoutLosingTheRest() {
        var services = Map.of("payments", new HttpProperties.ServiceProperties(
                "https://pay.example.test", Map.of("X-Tenant", "pay")));
        HttpProperties properties = properties("https://api.example.test",
                Map.of("X-Tenant", "demo", "X-Client", "tests"), services);

        assertThat(properties.resolveHeaders("payments"))
                .containsEntry("X-Tenant", "pay")
                .containsEntry("X-Client", "tests");
        assertThat(properties.resolveHeaders(null)).containsEntry("X-Tenant", "demo");
        assertThat(properties.resolveBaseUrl("payments")).isEqualTo("https://pay.example.test");
    }
}
