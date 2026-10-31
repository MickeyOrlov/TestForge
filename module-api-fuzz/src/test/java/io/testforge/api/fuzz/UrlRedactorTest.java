package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlRedactorTest {

    @Test
    void userInfoAndEveryQueryValueAreRemoved() {
        assertThat(UrlRedactor.redact("https://user:pass@api.example.test:8443/v1?api_key=abc&region=eu#top"))
                .isEqualTo("https://api.example.test:8443/v1?api_key=***&region=***#top");
        assertThat(UrlRedactor.redact("https://api.example.test/v1?sig=xyz")).isEqualTo("https://api.example.test/v1?sig=***");
    }

    @Test
    void aPlainUrlIsUntouched() {
        assertThat(UrlRedactor.redact("http://localhost:8080/api")).isEqualTo("http://localhost:8080/api");
        assertThat(UrlRedactor.redact("http://localhost:8080/api?flag")).isEqualTo("http://localhost:8080/api?flag");
    }

    @Test
    void evidenceAndTheReproduceCommandCarryTheRedactedForm() {
        assertThat(FuzzMarkdownReport.reproduce(java.util.List.of("st", "run", "s.yaml", "-u",
                "https://api.example.test?token=t0p"), null))
                .isEqualTo("st run s.yaml -u 'https://api.example.test?token=***'");
    }
}
