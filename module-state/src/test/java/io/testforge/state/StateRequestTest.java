package io.testforge.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StateRequestTest {

    @Test
    void prefixedTagWinsAmongSeveral() {
        assertThat(new StateRequest(List.of("tenant:demo", "state:approved"), null).targetTag()).contains("approved");
    }

    @Test
    void aSingleBareTagIsTheTarget() {
        assertThat(new StateRequest(List.of("approved"), null).targetTag()).contains("approved");
    }

    @Test
    void severalBareTagsAreAmbiguous() {
        StateRequest request = new StateRequest(List.of("approved", "vip"), null);

        assertThat(request.targetTag()).isEmpty();
        assertThatThrownBy(request::requireTargetTag)
                .isInstanceOf(StateRecipeException.class)
                .hasMessageContaining("state:approved")
                .hasMessageContaining("[approved, vip]");
    }

    @Test
    void customPrefixIsHonoured() {
        assertThat(new StateRequest(List.of("to=closed", "x"), "to=").targetTag()).contains("closed");
    }

    @Test
    void defaultsAreSafe() {
        StateRequest request = new StateRequest(null, " ");

        assertThat(request.tags()).isEmpty();
        assertThat(request.targetTagPrefix()).isEqualTo("state:");
        assertThat(request.hasTag("x")).isFalse();
        assertThat(new StateProperties(null).targetTagPrefix()).isEqualTo("state:");
    }
}
