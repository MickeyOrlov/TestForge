package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.AccessMatrixReport;
import io.testforge.api.explorer.AccessMatrixRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Who may call what, checked in one test: every read operation of
 * {@code access-demo.yaml} is called as admin, as member and without a token,
 * and each status is held to the declared rule. The second test is the bug the
 * matrix exists for — the audit log forgot its role check.
 */
@SpringBootTest(properties = {
        "forge.api-discovery.specs.access.location=classpath:/openapi/access-demo.yaml",
        "forge.api-explorer.parameters.defaults.taskId=t-1",
        "forge.auth.enabled=true",
        "forge.auth.roles.admin.token=admin-token",
        "forge.auth.roles.member.token=member-token",
        "forge.access-matrix.enabled=true",
        "forge.access-matrix.specs=access",
        "forge.access-matrix.output-dir=build/access-matrix-example",
        "forge.access-matrix.expect.admin=allow",
        "forge.access-matrix.expect.member=allow",
        "forge.access-matrix.expect.anonymous=401",
        "forge.access-matrix.operations.readAuditLog.member=deny"
})
class AccessMatrixExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    AccessMatrixRunner matrix;

    @BeforeEach
    void product() {
        server.resetAll();
        server.stubFor(get(anyUrl()).withHeader("Authorization", absent()).atPriority(1)
                .willReturn(aResponse().withStatus(401)));
        server.stubFor(get(anyUrl()).atPriority(5).willReturn(okJson("{}")));
    }

    @Test
    void everyRoleGetsWhatItShould() {
        server.stubFor(get(urlPathEqualTo("/api/v1/admin/audit"))
                .withHeader("Authorization", equalTo("Bearer member-token")).atPriority(2)
                .willReturn(aResponse().withStatus(403)));

        AccessMatrixReport report = matrix.assertHealthy();

        assertThat(report.rows()).hasSize(3);
        assertThat(report.violations()).isZero();
    }

    @Test
    void aMissingRoleCheckIsCaught() {
        // no 403 stub for member: the audit log answers anyone with a token

        assertThatThrownBy(matrix::assertHealthy)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET /api/v1/admin/audit (readAuditLog) as member: got 200, expected deny");
    }
}
