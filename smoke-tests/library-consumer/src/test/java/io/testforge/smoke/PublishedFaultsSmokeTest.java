package io.testforge.smoke;
import static org.assertj.core.api.Assertions.assertThat;
import io.testforge.faults.Faults;
import io.testforge.faults.FaultsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
class PublishedFaultsSmokeTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration static class Consumer { }
    @Test void publishedFaultsAreInertAndBindingCreatesOnlyHandles() {
        var context = new ApplicationContextRunner().withUserConfiguration(Consumer.class);
        context.run(c -> assertThat(c).doesNotHaveBean(Faults.class));
        context.withPropertyValues("forge.faults.enabled=true", "forge.faults.proxies.db=db:5432",
                "forge.faults.allowed-upstreams[0]=db:5432").run(c -> {
            assertThat(c).hasSingleBean(Faults.class);
            assertThat(c.getBean(FaultsProperties.class).proxies()).containsEntry("db", "db:5432");
        });
    }
}
