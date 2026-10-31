package io.testforge.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Redactor redactor(List<String> headers, List<String> fields) {
        return new Redactor(mapper, headers, fields);
    }

    @Test
    void headerNamesMatchCaseInsensitively() {
        Redactor redactor = redactor(List.of("authorization"), List.of());

        assertThat(redactor.header("Authorization", "Bearer abc")).isEqualTo("***");
        assertThat(redactor.header("AUTHORIZATION", "Bearer abc")).isEqualTo("***");
        assertThat(redactor.header("Accept", "application/json")).isEqualTo("application/json");
    }

    @Test
    void configuredNamesAreNormalizedEvenWhenTheRedactorIsBuiltDirectly() {
        // Redactor is a public @ConditionalOnMissingBean bean: a project replacing it
        // passes names as it writes them, not pre-lowercased like HttpProperties does
        Redactor redactor = redactor(List.of("X-Api-Key"), List.of("clientSecret"));

        assertThat(redactor.header("X-Api-Key", "k-123")).isEqualTo("***");
        assertThat(redactor.body("{\"clientSecret\":\"s\"}")).isEqualTo("{\"clientSecret\":\"***\"}");
    }

    @Test
    void jsonFieldsAreMaskedInsideArraysAndNestedObjects() {
        Redactor redactor = redactor(List.of(), List.of("token"));

        String body = redactor.body("{\"items\":[{\"token\":\"a\"},{\"meta\":{\"Token\":\"b\"}}],\"id\":7}");

        assertThat(body).isEqualTo("{\"items\":[{\"token\":\"***\"},{\"meta\":{\"Token\":\"***\"}}],\"id\":7}");
    }

    @Test
    void aMaskedFieldHidesAWholeObjectValue() {
        Redactor redactor = redactor(List.of(), List.of("secret"));

        assertThat(redactor.body("{\"secret\":{\"nested\":\"x\"}}")).isEqualTo("{\"secret\":\"***\"}");
    }

    @Test
    void topLevelJsonArraysAreRedacted() {
        Redactor redactor = redactor(List.of(), List.of("password"));

        assertThat(redactor.body("[{\"password\":\"p\"}]")).isEqualTo("[{\"password\":\"***\"}]");
    }

    @Test
    void nonJsonBodiesFallBackToTheTextualPass() {
        Redactor redactor = redactor(List.of(), List.of("password"));

        assertThat(redactor.body("user=a&password=hunter2&x=1")).isEqualTo("user=a&password=***&x=1");
        assertThat(redactor.body("broken {\"password\": \"hunter2\"")).isEqualTo("broken {\"password\": \"***\"");
    }

    @Test
    void textualPassDoesNotMaskFieldsThatOnlyEndWithTheName() {
        Redactor redactor = redactor(List.of(), List.of("token"));

        assertThat(redactor.body("csrftoken=keep&token=drop")).isEqualTo("csrftoken=keep&token=***");
    }

    @Test
    void emptyInputsPassThrough() {
        Redactor redactor = redactor(List.of(), List.of("password"));

        assertThat(redactor.body(null)).isNull();
        assertThat(redactor.body("  ")).isEqualTo("  ");
        assertThat(redactor(List.of(), List.of()).body("{\"password\":\"p\"}")).isEqualTo("{\"password\":\"p\"}");
    }

    @Test
    void secretsInAQueryStringAreMaskedAndTheRestOfTheUriKept() {
        Redactor redactor = redactor(List.of("api-key"), List.of("token"));

        assertThat(redactor.uri("http://api.test/reports?api_key=k-1&page=2&token=t&flag"))
                .isEqualTo("http://api.test/reports?api_key=***&page=2&token=***&flag");
        assertThat(redactor.uri("http://api.test/reports")).isEqualTo("http://api.test/reports");
        assertThat(redactor.uri(null)).isNull();
    }
}
