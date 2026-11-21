package io.testforge.smoke;
import static org.assertj.core.api.Assertions.assertThat;
import io.testforge.a11y.A11y;
import io.testforge.a11y.A11yProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
class PublishedA11ySmokeTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration static class Consumer { }
    @Test void publishedA11yCreatesNoBrowser() {
        var context = new ApplicationContextRunner().withUserConfiguration(Consumer.class);
        context.run(c -> assertThat(c).doesNotHaveBean(A11y.class));
        context.withPropertyValues("forge.a11y.enabled=true", "forge.a11y.minimum-impact=critical").run(c -> {
            assertThat(c).hasSingleBean(A11y.class);
            assertThat(c.getBean(A11yProperties.class).minimumImpact()).isEqualTo("critical");
        });
    }
}
