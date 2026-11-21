package io.testforge.smoke;
import static org.assertj.core.api.Assertions.assertThat;
import io.testforge.mail.Mailpit;
import io.testforge.mail.MailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
class PublishedMailSmokeTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration static class Consumer { }
    @Test void publishedMailIsInertAndEnablingDoesNotConnect() {
        var context = new ApplicationContextRunner().withUserConfiguration(Consumer.class);
        context.run(c -> assertThat(c).doesNotHaveBean(Mailpit.class));
        context.withPropertyValues("forge.mail.enabled=true", "forge.mail.api-url=http://127.0.0.1:1")
                .run(c -> {
                    assertThat(c).hasSingleBean(Mailpit.class);
                    assertThat(c.getBean(MailProperties.class).maxMessages()).isEqualTo(100);
                });
    }
}
