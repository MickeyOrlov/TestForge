package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FuzzSafetyPolicyTest {

    @Test
    void defaultPropertiesPermitOnlySafeMethodsAndSuppressUnexpectedMethods() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);

        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);

        assertThat(policy.permittedMethods()).containsExactly("GET", "HEAD", "OPTIONS");
        assertThat(policy.suppressesUnexpectedMethods()).isTrue();
    }

    @Test
    void listingPostWithoutAllowUnsafeMethodsExcludesPost() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, Set.of("GET", "POST"), false, null, null, null, null, null, null, null, null,
                null);

        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);

        assertThat(policy.permittedMethods()).containsExactly("GET");
        assertThat(policy.permittedMethods()).doesNotContain("POST");
        assertThat(policy.suppressesUnexpectedMethods()).isTrue();
    }

    @Test
    void listingPostWithAllowUnsafeMethodsIncludesPostAndDoesNotSuppressUnexpectedMethods() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, Set.of("GET", "POST"), true, null, null, null, null, null, null, null, null,
                null);

        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);

        assertThat(policy.permittedMethods()).containsExactly("GET", "POST");
        assertThat(policy.suppressesUnexpectedMethods()).isFalse();
    }

    @Test
    void emptyPermittedMethodsSetThrowsApiFuzzException() {
        // Only POST is listed, but allowUnsafeMethods is false -> permits("POST") is false -> 0 permitted methods
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, Set.of("POST"), false, null, null, null, null, null, null, null, null,
                null);

        assertThatThrownBy(() -> FuzzSafetyPolicy.from(props))
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("No HTTP methods are permitted under the current safety policy");
    }

    @Test
    void permittedMethodsAreReturnedInStableSortedOrder() {
        ApiFuzzProperties props = new ApiFuzzProperties(
                null, null, null, null, Set.of("POST", "GET", "DELETE"), true, null, null, null, null, null, null, null, null,
                null);

        FuzzSafetyPolicy policy = FuzzSafetyPolicy.from(props);

        assertThat(policy.permittedMethods()).containsExactly("DELETE", "GET", "POST");
    }
}
