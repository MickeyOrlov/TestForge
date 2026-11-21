package io.testforge.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.testforge.security.audit.SecurityAuditPlan.Status;
import io.testforge.security.audit.SecurityAuditProfile.Asset;
import io.testforge.security.audit.SecurityAuditProfile.Budget;
import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditProfile.EnginePolicy;
import io.testforge.security.audit.SecurityAuditProfile.Environment;
import io.testforge.security.audit.SecurityAuditProfile.Layer;
import io.testforge.security.audit.SecurityAuditProfile.TargetKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

class SecurityAuditPlannerTest {
    final SecurityAuditProperties properties =
            new SecurityAuditProperties(true, null, null, null, null, null);

    Asset asset(String id, Layer layer, TargetKind kind, String location) {
        return new Asset(
                id,
                layer,
                kind,
                location,
                kind == TargetKind.HOST ? List.of(443) : null,
                layer == Layer.APPLICATION ? "/does-not-exist/private.json" : null,
                "credential-alias");
    }

    SecurityAuditProfile profile(List<Asset> assets, List<EnginePolicy> engines) {
        return new SecurityAuditProfile(
                "internal",
                Environment.TEST,
                Set.of("api.internal", "db.internal", "registry.internal", "::1"),
                assets,
                engines,
                null);
    }

    List<EnginePolicy> all() {
        return List.of(Engine.values()).stream()
                .map(e -> new EnginePolicy(e, true, "1.2.3"))
                .toList();
    }

    @Test
    void coversAllThreeLayersWithACompleteDeterministicMatrixWithoutInspectingTargets()
            throws Exception {
        var assets =
                new ArrayList<>(
                        List.of(
                                asset(
                                        "api",
                                        Layer.APPLICATION,
                                        TargetKind.URL,
                                        "https://api.internal/private"),
                                asset(
                                        "image",
                                        Layer.SERVICES,
                                        TargetKind.IMAGE,
                                        "registry.internal/shop@sha256:" + "a".repeat(64)),
                                asset("database", Layer.NETWORK, TargetKind.HOST, "db.internal"),
                                asset("tls", Layer.NETWORK, TargetKind.URL, "https://api.internal"),
                                asset(
                                        "repo",
                                        Layer.SERVICES,
                                        TargetKind.REPOSITORY,
                                        "/does-not-exist/private-repo")));
        var planner = new SecurityAuditPlanner(properties);
        var plan = planner.plan(profile(assets, all()));
        assertThat(plan.jobs()).hasSize(35);
        assertThat(plan.jobs().stream().filter(j -> j.status() == Status.PLANNED)).hasSize(7);
        assertThat(plan.runnable()).isTrue();
        assertThat(plan.executed()).isFalse();
        Collections.reverse(assets);
        assertThat(planner.plan(profile(assets, all()))).isEqualTo(plan);
        String json = new ObjectMapper().writeValueAsString(plan);
        assertThat(json)
                .doesNotContain(
                        "api.internal",
                        "db.internal",
                        "registry.internal",
                        "does-not-exist",
                        "credential-alias",
                        "5432");
        assertThat(profile(assets, all()).toString()).doesNotContain("api.internal");
        assertThat(assets.getFirst().toString()).doesNotContain("does-not-exist");
    }

    @Test
    void missingInputAndOutOfScopeHostAreBlockedRatherThanSilentlySkipped() {
        var assets =
                List.of(
                        new Asset(
                                "api",
                                Layer.APPLICATION,
                                TargetKind.URL,
                                "https://api.internal",
                                null,
                                null,
                                null),
                        new Asset(
                                "database",
                                Layer.NETWORK,
                                TargetKind.HOST,
                                "db.internal",
                                null,
                                null,
                                null),
                        asset("outside", Layer.SERVICES, TargetKind.URL, "http://outside.internal"),
                        asset("tls", Layer.NETWORK, TargetKind.URL, "http://api.internal"));
        var plan = new SecurityAuditPlanner(properties).plan(profile(assets, all()));
        assertThat(plan.jobs().stream().filter(j -> j.status() == Status.BLOCKED)).hasSize(5);
        assertThat(plan.runnable()).isFalse();
        assertThat(plan.executed()).isFalse();
    }

    @Test
    void disabledAndUnsupportedJobsRemainVisibleAndNoEmptyPlanCanBeRunnable() {
        var plan =
                new SecurityAuditPlanner(properties)
                        .plan(
                                profile(
                                        List.of(
                                                asset(
                                                        "api",
                                                        Layer.APPLICATION,
                                                        TargetKind.URL,
                                                        "https://api.internal")),
                                        List.of()));
        assertThat(plan.jobs()).hasSize(7);
        assertThat(plan.jobs().stream().filter(j -> j.status() == Status.DISABLED)).hasSize(3);
        assertThat(plan.jobs().stream().filter(j -> j.status() == Status.NOT_APPLICABLE))
                .hasSize(4);
        assertThat(plan.runnable()).isFalse();
        assertThatThrownBy(
                        () ->
                                new SecurityAuditPlanner(
                                                new SecurityAuditProperties(
                                                        null, null, null, null, null, null))
                                        .plan(
                                                profile(
                                                        List.of(
                                                                asset(
                                                                        "api",
                                                                        Layer.APPLICATION,
                                                                        TargetKind.URL,
                                                                        "https://api.internal")),
                                                        all())))
                .hasMessageContaining("enabled must be true");
    }

    @Test
    void
            rejectsCredentialUrlsAmbiguousHostsTraversalAndMutableImageReferencesWithoutEchoingInput() {
        for (String value :
                List.of(
                        "https://user:private-value@api.internal",
                        "https://api.internal/?secret=private-value",
                        "https://api.internal/#private-value",
                        "https://api.internal/a/../private-value",
                        "https://api.internal/%2e/private-value",
                        "https://api.internal:0",
                        "https://api.internal:65536",
                        "ftp://api.internal"))
            assertThatThrownBy(() -> asset("bad", Layer.APPLICATION, TargetKind.URL, value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("private-value");
        for (String host :
                List.of(
                        "*.internal",
                        "10.0.0.0/24",
                        "10.1",
                        "010.0.0.1",
                        "256.0.0.1",
                        "host..internal",
                        "host.internal.",
                        "host:8080"))
            assertThatThrownBy(() -> asset("bad", Layer.NETWORK, TargetKind.HOST, host))
                    .isInstanceOf(IllegalArgumentException.class);
        for (String image :
                List.of(
                        "shop:latest",
                        "registry.internal/shop:1.2",
                        "team/shop@sha256:" + "a".repeat(64)))
            assertThatThrownBy(() -> asset("bad", Layer.SERVICES, TargetKind.IMAGE, image))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                asset(
                                        "bad",
                                        Layer.SERVICES,
                                        TargetKind.REPOSITORY,
                                        "/private/../repo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsExplicitIpv6AndCaseInsensitiveHostAllowlistingWithoutDns() {
        var plan =
                new SecurityAuditPlanner(properties)
                        .plan(
                                profile(
                                        List.of(
                                                asset(
                                                        "api",
                                                        Layer.APPLICATION,
                                                        TargetKind.URL,
                                                        "https://API.INTERNAL"),
                                                asset(
                                                        "tls",
                                                        Layer.NETWORK,
                                                        TargetKind.URL,
                                                        "https://[::1]:8443")),
                                        all()));
        assertThat(plan.runnable()).isTrue();
    }

    @Test
    void validatesDuplicateAliasesPortsPinnedVersionsAndAdministrativeBudgets() {
        var api = asset("api", Layer.APPLICATION, TargetKind.URL, "https://api.internal");
        assertThatThrownBy(() -> profile(List.of(api, api), all())).hasMessageContaining("unique");
        assertThatThrownBy(() -> profile(List.of(api), List.of(all().getFirst(), all().getFirst())))
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new EnginePolicy(Engine.ZAP, true, "latest"))
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new EnginePolicy(Engine.ZAP, true, null))
                .hasMessageContaining("version");
        assertThatThrownBy(
                        () ->
                                new Asset(
                                        "host",
                                        Layer.NETWORK,
                                        TargetKind.HOST,
                                        "db.internal",
                                        List.of(443, 443),
                                        null,
                                        null))
                .hasMessageContaining("ports");
        assertThatThrownBy(
                        () ->
                                new Asset(
                                        "host",
                                        Layer.NETWORK,
                                        TargetKind.HOST,
                                        "db.internal",
                                        List.of(65536),
                                        null,
                                        null))
                .hasMessageContaining("ports");
        var limited = new SecurityAuditPlanner(new SecurityAuditProperties(true, 1, 5, 1, 1, 1));
        assertThatThrownBy(() -> limited.plan(profile(List.of(api), all())))
                .hasMessageContaining("ceilings");
        var requests =
                new SecurityAuditPlanner(
                        new SecurityAuditProperties(true, null, null, 1, null, null));
        assertThatThrownBy(() -> requests.plan(profile(List.of(api), all())))
                .hasMessageContaining("ceilings");
        assertThatThrownBy(() -> new Budget(1, 0, 1)).hasMessageContaining("budget");
    }

    @Test
    void strictJsonRejectsMalformedUnknownDuplicateCoercedAndOversizedProfilesWithoutParserLeaks()
            throws Exception {
        String valid =
                new ObjectMapper()
                        .writeValueAsString(
                                profile(
                                        List.of(
                                                asset(
                                                        "api",
                                                        Layer.APPLICATION,
                                                        TargetKind.URL,
                                                        "https://api.internal")),
                                        all()));
        assertThat(SecurityAuditProfiles.parse(valid).id()).isEqualTo("internal");
        for (String invalid :
                List.of(
                        "null",
                        "{\"password\":\"private-value\"}",
                        valid + " {}",
                        valid.replace(
                                "\"id\":\"internal\"", "\"id\":\"internal\",\"id\":\"duplicate\""),
                        valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
                        valid.replace("\"maxRequests\":1000", "\"maxRequests\":1.5"),
                        valid.replace("\"maxConcurrent\":2", "\"maxConcurrent\":null"),
                        "x".repeat(262145)))
            assertThatThrownBy(() -> SecurityAuditProfiles.parse(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("private-value")
                    .hasCause(null);
    }
}
