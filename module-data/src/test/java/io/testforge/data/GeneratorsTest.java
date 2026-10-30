package io.testforge.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GeneratorsTest {

    @Test
    void numericHasTheRequestedLengthAndNoLeadingZero() {
        var numeric = Generators.numeric(6);

        IntStream.range(0, 500).mapToObj(i -> numeric.get()).forEach(value -> {
            assertThat(value).hasSize(6).containsOnlyDigits();
            assertThat(value.charAt(0)).isNotEqualTo('0');
        });
    }

    @Test
    void singleDigitNumericIsOneToNine() {
        var numeric = Generators.numeric(1);

        IntStream.range(0, 200).mapToObj(i -> numeric.get())
                .forEach(value -> assertThat(value).matches("[1-9]"));
    }

    @Test
    void phoneIsPrefixPlusDigits() {
        assertThat(Generators.phone("+8499", 7).get()).matches("\\+8499[1-9]\\d{6}");
    }

    @Test
    void alphanumericUsesLowercaseLettersAndDigitsOnly() {
        assertThat(Generators.alphanumeric(32).get()).matches("[a-z0-9]{32}");
    }

    @Test
    void guidIsAParseableUuid() {
        String value = Generators.guid().get();

        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    @Test
    void nonPositiveLengthsAreRejectedWhenTheGeneratorIsBuilt() {
        assertThatThrownBy(() -> Generators.numeric(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Generators.phone("+1", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Generators.alphanumeric(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
