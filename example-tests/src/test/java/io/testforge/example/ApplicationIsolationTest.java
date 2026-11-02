package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.restassured.http.ContentType;
import io.testforge.core.context.ScenarioContextExtension;
import io.testforge.http.ApiClient;
import io.testforge.mock.MockScope;
import io.testforge.mock.ScopedMockClient;
import io.testforge.sampleapp.orders.OrdersApplication;
import io.testforge.sampleapp.ownbeans.OwnBeansApplication;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

/**
 * TestForge is on the classpath of the project that also starts the service
 * under test, so every module's auto-configuration runs in that service's
 * context. This boots a real Spring MVC application next to all of them and
 * checks the modules leave it as it would be without TestForge: Spring Boot's
 * {@code ObjectMapper} with the application's {@code spring.jackson.*}
 * settings, request bodies exactly as the test sent them, and the
 * application's own beans where it declared them.
 */
@ExtendWith(ScenarioContextExtension.class)
class ApplicationIsolationTest {

    static final String SCOPE_HEADER = "X-Test-Scope";

    /** The downstream the service would call; module-mock scopes its stubs there. */
    static WireMockServer downstream = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        downstream.start();
    }

    static void adaptedProjectProperties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        // Spring Boot's own default, written out: a module that brings a plain mapper breaks it
        registry.add("spring.jackson.deserialization.fail-on-unknown-properties", () -> "false");
        registry.add("forge.mock.base-url", () -> "http://127.0.0.1:" + downstream.port());
        registry.add("forge.mock.scope-header", () -> SCOPE_HEADER);
        // the port is the server's, known only once it listens; requests set it themselves
        registry.add("forge.http.base-url", () -> "http://127.0.0.1");
    }

    /** The class of the configuration that declared a bean — an auto-configuration or the application. */
    static String origin(ConfigurableApplicationContext context, String beanName) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        String factoryBean = beanFactory.getMergedBeanDefinition(beanName).getFactoryBeanName();
        Class<?> declaring = factoryBean == null ? beanFactory.getType(beanName) : beanFactory.getType(factoryBean);
        return ClassUtils.getUserClass(declaring).getName();
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OrdersApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
    class AnApplicationThatLeavesJacksonToSpringBoot {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            adaptedProjectProperties(registry);
        }

        @LocalServerPort
        int port;

        @Autowired
        ConfigurableApplicationContext context;

        @Autowired
        ApiClient api;

        @Autowired
        ScopedMockClient mocks;

        @Autowired
        OrdersApplication.Received received;

        @Test
        void keepsSpringBootsObjectMapper() {
            List<String> mappers = Arrays.asList(context.getBeanNamesForType(ObjectMapper.class));

            assertThat(mappers).hasSize(1);
            assertThat(origin(context, mappers.getFirst()))
                    .as("the ObjectMapper the application serves its requests with")
                    .startsWith("org.springframework.boot.autoconfigure.jackson.");
            assertThat(context.getBean(ObjectMapper.class)
                    .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        }

        @Test
        void acceptsAFieldTheRecordDoesNotHave() {
            String item = api.request().port(port)
                    .contentType(ContentType.JSON)
                    .body("{\"item\":\"book\",\"quantity\":2,\"giftWrap\":true}")
                    .post("/orders")
                    .then().statusCode(200)
                    .extract().jsonPath().getString("item");

            assertThat(item).isEqualTo("book");
        }

        @Test
        void receivesTheBodyAsSentWhenTheMockIsScopedByHeader() {
            String sent = "{\"item\":\"pen\",\"quantity\":3}";

            try (MockScope scope = mocks.scope()) {
                api.request().port(port)
                        .contentType(ContentType.JSON)
                        .body(sent)
                        .post("/orders")
                        .then().statusCode(200);

                assertThat(received.body()).isEqualTo(sent);
                assertThat(received.headers().getFirst(SCOPE_HEADER)).isEqualTo(scope.scopeId());
            }
        }
    }

    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = OwnBeansApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
    class AnApplicationThatDeclaresItsOwnBeans {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            adaptedProjectProperties(registry);
        }

        @Autowired
        ConfigurableApplicationContext context;

        @Test
        void keepsTheApplicationsObjectMapper() {
            assertOnlyTheApplicationDeclares(ObjectMapper.class, "applicationObjectMapper");
        }

        @Test
        void keepsTheApplicationsRestClientBuilder() {
            assertOnlyTheApplicationDeclares(RestClient.Builder.class, "applicationRestClientBuilder");
        }

        private void assertOnlyTheApplicationDeclares(Class<?> type, String beanName) {
            assertThat(context.getBeanNamesForType(type)).containsExactly(beanName);
            assertThat(origin(context, beanName)).isEqualTo(OwnBeansApplication.class.getName());
            assertThat(context.getBean(type)).isSameAs(context.getBean(beanName));
        }
    }
}
