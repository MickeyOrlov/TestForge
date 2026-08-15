package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiFuzzPropertiesTest {

    @Test
    void defaultsAreSafe() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(props.enabled()).isFalse();
        assertThat(props.outputDir()).isEqualTo("build/api-fuzz");
        assertThat(props.specs()).isEmpty();
        assertThat(props.baseUrl()).isNull();
        assertThat(props.methods()).isEqualTo(ApiFuzzProperties.SAFE_METHODS);
        assertThat(props.allowUnsafeMethods()).isFalse();
        assertThat(props.phases()).containsExactly("coverage", "fuzzing");
        assertThat(props.seed()).isNull();
        assertThat(props.maxExamples()).isEqualTo(50);
        assertThat(props.generationMode()).isEqualTo("all");
        assertThat(props.maxFailures()).isNull();
        assertThat(props.timeoutSeconds()).isEqualTo(900);
        assertThat(props.command()).isEqualTo("st");
        assertThat(props.configFile()).isNull();
    }

    @Test
    void permitsGetByDefault() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(props.permits("GET")).isTrue();
        assertThat(props.permits("get")).isTrue();
        assertThat(props.permits("HEAD")).isTrue();
        assertThat(props.permits("OPTIONS")).isTrue();
    }

    @Test
    void permitsPostIsFalseWhenPostIsListedButAllowUnsafeMethodsIsFalse() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, Set.of("GET", "POST"), false, null, null, null, null, null, null, null, null);

        assertThat(props.permits("POST")).isFalse();
    }

    @Test
    void permitsPostIsTrueOnlyWhenPostIsListedAndAllowUnsafeMethodsIsTrue() {
        ApiFuzzProperties props1 = new ApiFuzzProperties(
                null, null, null, null, Set.of("GET", "POST"), true, null, null, null, null, null, null, null, null);
        assertThat(props1.permits("POST")).isTrue();

        ApiFuzzProperties props2 = new ApiFuzzProperties(
                null, null, null, null, Set.of("GET"), true, null, null, null, null, null, null, null, null);
        assertThat(props2.permits("POST")).isFalse();
    }

    @Test
    void unknownPhaseThrows() {
        assertThatThrownBy(() -> new ApiFuzzProperties(
                null, null, null, null, null, null, List.of("coverage", "unknown"), null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown fuzzing phase: 'unknown'");
    }

    @Test
    void unknownGenerationModeThrows() {
        assertThatThrownBy(() -> new ApiFuzzProperties(
                null, null, null, null, null, null, null, null, null, "invalid", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown generationMode: 'invalid'");
    }

    @Test
    void customValidValuesAreRetainedAndNormalized() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                true,
                "custom/dir",
                List.of("demoSpec"),
                "http://localhost:8080",
                Set.of("get", "post"),
                true,
                List.of("EXAMPLES", "STATEFUL"),
                12345L,
                100,
                "POSITIVE",
                10,
                300,
                "schemathesis",
                "custom-st.toml");

        assertThat(props.enabled()).isTrue();
        assertThat(props.outputDir()).isEqualTo("custom/dir");
        assertThat(props.specs()).containsExactly("demoSpec");
        assertThat(props.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(props.methods()).containsExactlyInAnyOrder("GET", "POST");
        assertThat(props.allowUnsafeMethods()).isTrue();
        assertThat(props.phases()).containsExactly("examples", "stateful");
        assertThat(props.seed()).isEqualTo(12345L);
        assertThat(props.maxExamples()).isEqualTo(100);
        assertThat(props.generationMode()).isEqualTo("positive");
        assertThat(props.maxFailures()).isEqualTo(10);
        assertThat(props.timeoutSeconds()).isEqualTo(300);
        assertThat(props.command()).isEqualTo("schemathesis");
        assertThat(props.configFile()).isEqualTo("custom-st.toml");
    }
}
