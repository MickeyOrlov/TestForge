package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.http.Redactor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A test log is an artifact: it gets uploaded from CI, pasted into tickets and
 * kept for weeks. Whatever the HTTP module prints passes through here first.
 */
class HttpRedactionTest {

    private final Redactor redactor = new Redactor(
            new ObjectMapper(),
            List.of("authorization", "x-api-key"),
            List.of("password", "access_token"));

    @Test
    void credentialHeadersNeverReachTheLog() {
        assertThat(redactor.header("Authorization", "Bearer real-token")).isEqualTo("***");
        assertThat(redactor.header("authorization", "Bearer real-token")).isEqualTo("***");
        assertThat(redactor.header("X-Tenant", "demo")).isEqualTo("demo");
    }

    @Test
    void secretsAreMaskedAtAnyDepthOfTheBody() {
        String body = """
                {"user":{"login":"demo","password":"s3cret"},
                 "sessions":[{"access_token":"abc"},{"access_token":"def"}]}""";

        String redacted = redactor.body(body);

        assertThat(redacted).contains("\"login\":\"demo\"");
        assertThat(redacted).doesNotContain("s3cret", "abc", "def");
        assertThat(redacted).contains("***");
    }

    @Test
    void formEncodedBodiesFallBackToATextualPass() {
        String redacted = redactor.body("login=demo&password=s3cret");

        assertThat(redacted).isEqualTo("login=demo&password=***");
    }
}
