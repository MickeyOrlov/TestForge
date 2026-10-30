package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TestForgeAuthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestForgeAuthAutoConfiguration.class));

    @Test
    void inertByDefault() {
        runner.withPropertyValues("forge.auth.roles.admin.token=t")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TokenProvider.class);
                    assertThat(context).doesNotHaveBean(AuthFilter.class);
                });
    }

    @Test
    void enabledWiresProviderAndFilter() {
        runner.withPropertyValues(
                        "forge.auth.enabled=true",
                        "forge.auth.roles.partner.type=static",
                        "forge.auth.roles.partner.token=partner-token",
                        "forge.auth.roles.user.type=local-jwt",
                        "forge.auth.roles.user.subject=user-1",
                        "forge.auth.local-jwt.secret=0123456789abcdef0123456789abcdef")
                .run(context -> {
                    TokenProvider tokens = context.getBean(TokenProvider.class);
                    assertThat(tokens.roles()).containsExactly("partner", "user");
                    assertThat(tokens.forRole("partner")).isEqualTo("partner-token");
                    assertThat(tokens.forRole("user")).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");
                    assertThat(context).hasSingleBean(AuthFilter.class);
                });
    }

    @Test
    void everyMisconfiguredRoleIsReportedAtStartup() {
        runner.withPropertyValues(
                        "forge.auth.enabled=true",
                        "forge.auth.roles.a.type=static",
                        "forge.auth.roles.b.type=client-credentials",
                        "forge.auth.roles.c.type=password",
                        "forge.auth.roles.c.token-url=http://idp.example.test/token",
                        "forge.auth.roles.c.client-id=web",
                        "forge.auth.roles.d.type=local-jwt",
                        "forge.auth.local-jwt.secret=short-secret-value")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause()
                        .isInstanceOf(AuthException.class)
                        .hasMessageContaining("a: static role needs 'token'")
                        .hasMessageContaining("b: needs 'token-url'")
                        .hasMessageContaining("b: needs 'client-id'")
                        .hasMessageContaining("c: needs 'username'")
                        .hasMessageContaining("c: needs 'password'")
                        .hasMessageContaining("d: forge.auth.local-jwt.secret must be at least 32 bytes")
                        .hasMessageNotContaining("short-secret-value"));
    }

    @Test
    void propertyDefaults() {
        AuthProperties properties = new AuthProperties(false, null, null, null, null, null, null, null);

        assertThat(properties.header()).isEqualTo("Authorization");
        assertThat(properties.scheme()).isEqualTo("Bearer");
        assertThat(properties.refreshSkew()).hasSeconds(30);
        assertThat(properties.localJwt().issuer()).isEqualTo("testforge");
        assertThat(properties.localJwt().ttl()).hasMinutes(15);
        assertThat(new AuthProperties.RoleProperties(null, "t", null, null, null, null, null, null, null, null).type())
                .isEqualTo(TokenType.STATIC);
    }
}
