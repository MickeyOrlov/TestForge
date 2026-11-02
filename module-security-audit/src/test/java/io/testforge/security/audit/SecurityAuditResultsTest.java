package io.testforge.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.security.audit.SecurityAuditProfile.Asset;
import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditProfile.EnginePolicy;
import io.testforge.security.audit.SecurityAuditProfile.Environment;
import io.testforge.security.audit.SecurityAuditProfile.Layer;
import io.testforge.security.audit.SecurityAuditProfile.TargetKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

class SecurityAuditResultsTest {
    @TempDir Path dir;

    @org.junit.jupiter.api.BeforeEach
    void canonicalOutputRoot() throws Exception {
        dir = dir.toRealPath();
    }

    final ObjectMapper mapper = new ObjectMapper();
    final SecurityAuditProperties enabled =
            new SecurityAuditProperties(true, null, null, null, null, null);
    final String rawTarget = "GET /private/synthetic-private-identifier",
            privateText = "synthetic-secret-evidence";
    final Map<String, String> targets = Map.of(rawTarget, "resource", "run", "run");

    SecurityAuditResultsProperties config() {
        return new SecurityAuditResultsProperties(dir.resolve("audit").toString(), null, null);
    }

    SecurityAuditRunner runner() {
        return new SecurityAuditRunner(enabled, config(), ArtifactSink.NO_OP);
    }

    SecurityAuditPlan plan(boolean network) {
        var assets = new ArrayList<Asset>();
        assets.add(
                new Asset(
                        "api",
                        Layer.APPLICATION,
                        TargetKind.URL,
                        "https://private.internal",
                        null,
                        null,
                        null));
        if (network)
            assets.add(
                    new Asset(
                            "database",
                            Layer.NETWORK,
                            TargetKind.HOST,
                            "db.internal",
                            List.of(5432),
                            null,
                            null));
        return new SecurityAuditPlanner(enabled)
                .plan(
                        new SecurityAuditProfile(
                                "internal",
                                Environment.TEST,
                                Set.of("private.internal", "db.internal"),
                                assets,
                                network
                                        ? List.of(
                                                new EnginePolicy(Engine.TESTFORGE, true, "1.0.0"),
                                                new EnginePolicy(Engine.NMAP, true, "1.0.0"))
                                        : List.of(
                                                new EnginePolicy(Engine.TESTFORGE, true, "1.0.0")),
                                null));
    }

    ObjectNode source(boolean complete, boolean finding, String status) {
        var root =
                mapper.createObjectNode()
                        .put("formatVersion", 1)
                        .put("enabled", true)
                        .put("measured", true)
                        .put("complete", complete)
                        .put("healthy", complete && (!finding || !status.equals("new")));
        var findings = root.putArray("findings");
        if (finding)
            findings.addObject()
                    .put("id", "missing-auth " + rawTarget)
                    .put("check", "missing-auth")
                    .put("severity", "high")
                    .put("target", rawTarget)
                    .put("evidence", privateText)
                    .put("owasp", "API2:2023")
                    .put("status", status)
                    .put("reason", status.equals("accepted") ? privateText : null);
        var coverage = root.putArray("coverage");
        coverage.addObject()
                .put("check", "missing-auth")
                .put("target", rawTarget)
                .put("role", "owner")
                .put("status", "measured")
                .put("reason", privateText);
        if (!complete)
            coverage.addObject()
                    .put("check", "block-auth")
                    .put("target", "run")
                    .putNull("role")
                    .put("status", "not_measured")
                    .put("reason", privateText);
        var counts = root.putObject("newCounts");
        for (String severity : List.of("critical", "high", "medium", "low", "info"))
            counts.put(
                    severity, finding && status.equals("new") && severity.equals("high") ? 1 : 0);
        return root;
    }

    SecurityAuditEvidence evidence(String asset, Engine engine, String version, ObjectNode source)
            throws Exception {
        return new SecurityAuditImports(config())
                .normalizedReport(
                        asset, engine, version, mapper.writeValueAsBytes(source), targets);
    }

    @Test
    void reusesSecurityV1AndKeepsSameRuleOnDifferentAssetsDistinct() throws Exception {
        var report =
                runner().assess(
                                plan(true),
                                List.of(
                                        evidence(
                                                "api",
                                                Engine.TESTFORGE,
                                                "1.0.0",
                                                source(true, true, "new")),
                                        evidence(
                                                "database",
                                                Engine.NMAP,
                                                "1.0.0",
                                                source(true, true, "new"))));
        assertThat(report.complete()).isTrue();
        assertThat(report.healthy()).isFalse();
        assertThat(
                        report.jobs().stream()
                                .filter(j -> j.status() == SecurityAuditReport.Status.MEASURED))
                .hasSize(2);
        assertThat(
                        report.jobs().stream()
                                .filter(j -> j.security() != null)
                                .map(j -> j.security().path("findings").get(0).path("id").asText()))
                .containsExactly("missing-auth resource", "missing-auth resource");
        String json = mapper.writeValueAsString(report);
        assertThat(json).doesNotContain(rawTarget, privateText, "private.internal", "5432");
    }

    @Test
    void missingPartialAndWrongVersionEvidenceCannotPassAndObservedFindingsRemain()
            throws Exception {
        var partial =
                runner().assess(
                                plan(false),
                                List.of(
                                        evidence(
                                                "api",
                                                Engine.TESTFORGE,
                                                "1.0.0",
                                                source(false, true, "new"))));
        assertThat(partial.measured()).isTrue();
        assertThat(partial.complete()).isFalse();
        assertThat(partial.healthy()).isFalse();
        assertThat(
                        partial.jobs().stream()
                                .filter(j -> j.security() != null)
                                .findFirst()
                                .orElseThrow()
                                .security()
                                .path("findings"))
                .hasSize(1);
        var missing =
                runner().assess(
                                plan(true),
                                List.of(
                                        evidence(
                                                "api",
                                                Engine.TESTFORGE,
                                                "1.0.0",
                                                source(true, false, "new"))));
        assertThat(missing.complete()).isFalse();
        assertThat(missing.healthy()).isFalse();
        var mismatch =
                runner().assess(
                                plan(false),
                                List.of(
                                        evidence(
                                                "api",
                                                Engine.TESTFORGE,
                                                "2.0.0",
                                                source(true, true, "new"))));
        var job =
                mismatch.jobs().stream()
                        .filter(j -> j.engine() == Engine.TESTFORGE)
                        .findFirst()
                        .orElseThrow();
        assertThat(job.status()).isEqualTo(SecurityAuditReport.Status.INCOMPLETE);
        assertThat(job.sourceVersion()).isEqualTo("2.0.0");
        assertThat(job.security().path("findings")).hasSize(1);
    }

    @Test
    void sourceBaselineStatusIsPreservedWithoutCreatingAnAggregateBaseline() throws Exception {
        var report =
                runner().run(
                                plan(false),
                                List.of(
                                        evidence(
                                                "api",
                                                Engine.TESTFORGE,
                                                "1.0.0",
                                                source(true, true, "accepted"))));
        assertThat(report.healthy()).isTrue();
        var finding =
                report.jobs().stream()
                        .filter(j -> j.security() != null)
                        .findFirst()
                        .orElseThrow()
                        .security()
                        .path("findings")
                        .get(0);
        assertThat(finding.path("status").asText()).isEqualTo("accepted");
        assertThat(finding.path("reason").asText())
                .isEqualTo("accepted by upstream security policy");
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("security-audit.json", "security-audit.md");
        }
    }

    @Test
    void noEvidenceAndOnlyDisabledScopeAreNeverMeasuredOrHealthy() {
        var missing = runner().assess(plan(false), List.of());
        assertThat(missing.measured()).isFalse();
        assertThat(missing.healthy()).isFalse();
        var original = plan(false);
        var disabled =
                new SecurityAuditPlan(
                        1,
                        original.profile(),
                        original.environment(),
                        false,
                        false,
                        original.budget(),
                        original.jobs().stream()
                                .map(
                                        j ->
                                                j.status() == SecurityAuditPlan.Status.PLANNED
                                                        ? new SecurityAuditPlan.Job(
                                                                j.asset(),
                                                                j.layer(),
                                                                j.kind(),
                                                                j.engine(),
                                                                j.version(),
                                                                SecurityAuditPlan.Status.DISABLED,
                                                                "private-text")
                                                        : j)
                                .toList());
        var report = runner().assess(disabled, List.of());
        assertThat(report.complete()).isFalse();
        assertThat(report.measured()).isFalse();
    }

    @Test
    void duplicateUnplannedAndIncompleteMatrixInputsFailClosed() throws Exception {
        var e = evidence("api", Engine.TESTFORGE, "1.0.0", source(true, false, "new"));
        assertThat(runner().assess(plan(false), List.of(e, e)).healthy()).isFalse();
        assertThatThrownBy(
                        () ->
                                runner().assess(
                                                plan(false),
                                                List.of(
                                                        evidence(
                                                                "wrong",
                                                                Engine.TESTFORGE,
                                                                "1.0.0",
                                                                source(true, false, "new")))))
                .hasMessageContaining("planned job");
        assertThatThrownBy(
                        () ->
                                runner().assess(
                                                plan(false),
                                                List.of(
                                                        evidence(
                                                                "api",
                                                                Engine.ZAP,
                                                                "1.0.0",
                                                                source(true, false, "new")))))
                .hasMessageContaining("planned job");
        var original = plan(false);
        var truncated =
                new SecurityAuditPlan(
                        1,
                        original.profile(),
                        original.environment(),
                        false,
                        true,
                        original.budget(),
                        original.jobs().subList(0, 1));
        assertThatThrownBy(() -> runner().assess(truncated, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRawFieldsWrongCountsAndMissingMappingsBecomeIncompleteWithoutRetainingInput()
            throws Exception {
        var inputs = new ArrayList<ObjectNode>();
        inputs.add(source(true, true, "new").put("responseBody", privateText));
        inputs.add(source(true, true, "new").put("formatVersion", 2));
        var wrongCounts = source(true, true, "new");
        ((ObjectNode) wrongCounts.path("newCounts")).put("high", 0);
        inputs.add(wrongCounts);
        var badState = source(false, true, "new").put("complete", true);
        inputs.add(badState);
        for (var input : inputs) {
            var evidence = evidence("api", Engine.TESTFORGE, "1.0.0", input);
            assertThat(evidence.failure()).isNotNull();
            assertThat(evidence.securityReport()).isNull();
            var report = runner().assess(plan(false), List.of(evidence));
            assertThat(report.complete()).isFalse();
            assertThat(mapper.writeValueAsString(report)).doesNotContain(privateText, rawTarget);
        }
        var unmapped =
                new SecurityAuditImports(config())
                        .securityReport(
                                "api",
                                "1.0.0",
                                mapper.writeValueAsBytes(source(true, true, "new")),
                                Map.of());
        assertThat(unmapped.failure()).isNotNull();
        var duplicateAliases =
                new SecurityAuditImports(config())
                        .securityReport(
                                "api",
                                "1.0.0",
                                mapper.writeValueAsBytes(source(true, true, "new")),
                                Map.of(rawTarget, "same", "run", "same"));
        assertThat(duplicateAliases.failure()).isNotNull();
    }

    @Test
    void malformedDuplicateTrailingAndOversizedJsonAreRejectedWithFixedDiagnostics()
            throws Exception {
        byte[] normal = mapper.writeValueAsBytes(source(true, false, "new"));
        for (byte[] input :
                List.of(
                        "{".getBytes(),
                        "null".getBytes(),
                        (new String(normal) + " {}").getBytes(),
                        new String(normal)
                                .replace(
                                        "\"formatVersion\":1",
                                        "\"formatVersion\":1,\"formatVersion\":1")
                                .getBytes())) {
            var e =
                    new SecurityAuditImports(config())
                            .securityReport("api", "1.0.0", input, targets);
            assertThat(e.failure()).isNotNull();
            assertThat(e.securityReport()).isNull();
        }
        var bounded = new SecurityAuditImports(new SecurityAuditResultsProperties(null, 10, null));
        assertThat(bounded.securityReport("api", "1.0.0", normal, targets).failure()).isNotNull();
    }

    @Test
    void projectedEvidenceAndReportsDoNotExposeMutableJsonInternals() throws Exception {
        var e = evidence("api", Engine.TESTFORGE, "1.0.0", source(true, true, "new"));
        ((ObjectNode) e.securityReport()).put("healthy", true);
        assertThat(e.securityReport().path("healthy").booleanValue()).isFalse();
        var report = runner().assess(plan(false), List.of(e));
        var job =
                report.jobs().stream().filter(j -> j.security() != null).findFirst().orElseThrow();
        ((ObjectNode) job.security()).put("healthy", true);
        assertThat(job.security().path("healthy").booleanValue()).isFalse();
        assertThat(e.toString()).doesNotContain(rawTarget, privateText);
    }

    @Test
    void outputIsPrivatePerInvocationAndRegistersBothReports() throws Exception {
        var sink = new RecordingSink();
        var writer = new SecurityAuditRunner(enabled, config(), sink);
        var e = evidence("api", Engine.TESTFORGE, "1.0.0", source(true, false, "new"));
        writer.run(plan(false), List.of(e));
        writer.run(plan(false), List.of(e));
        assertThat(sink.files).hasSize(4);
        assertThat(sink.files.stream().map(t -> t.file().getParent()).distinct()).hasSize(2);
        for (var file : sink.files) {
            assertThat(file.source()).isEqualTo("module-security-audit");
            assertThat(file.metadata()).containsEntry("complete", "true");
            assertThat(Files.readString(file.file()))
                    .doesNotContain(rawTarget, privateText, "private.internal");
        }
    }

    @Test
    void symlinkOutputAndRenderedByteOverflowAreRefused() throws Exception {
        var actual = Files.createDirectory(dir.resolve("actual"));
        var link = dir.resolve("link");
        Files.createSymbolicLink(link, actual);
        var writer =
                new SecurityAuditRunner(
                        enabled,
                        new SecurityAuditResultsProperties(link.toString(), null, null),
                        ArtifactSink.NO_OP);
        var e = evidence("api", Engine.TESTFORGE, "1.0.0", source(true, false, "new"));
        assertThatThrownBy(() -> writer.run(plan(false), List.of(e)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(link.toString());
        var tiny =
                new SecurityAuditRunner(
                        enabled,
                        new SecurityAuditResultsProperties(
                                dir.resolve("tiny").toString(), 1000, null),
                        ArtifactSink.NO_OP);
        assertThatThrownBy(() -> tiny.run(plan(false), List.of(e)))
                .hasMessageContaining("byte bound");
        assertThat(Files.exists(dir.resolve("tiny"))).isFalse();
    }

    @Test
    void exportedEnvelopeMatchesItsVersionedSchemaForCompleteAndIncompleteAssessments()
            throws Exception {
        com.networknt.schema.JsonSchema schema;
        try (var input = getClass().getResourceAsStream("/security-audit.schema.json")) {
            schema =
                    com.networknt.schema.JsonSchemaFactory.getInstance(
                                    com.networknt.schema.SpecVersion.VersionFlag.V7)
                            .getSchema(mapper.readTree(input));
        }
        var e = evidence("api", Engine.TESTFORGE, "1.0.0", source(true, true, "new"));
        for (var report :
                List.of(
                        runner().assess(plan(false), List.of(e)),
                        runner().assess(plan(false), List.of())))
            assertThat(schema.validate(mapper.valueToTree(report))).isEmpty();
        var changed = (ObjectNode) mapper.valueToTree(runner().assess(plan(false), List.of(e)));
        changed.put("rawResponse", privateText);
        assertThat(schema.validate(changed)).isNotEmpty();
    }

    @Test
    void upstreamRoleValuesAreWithheldAndAcceptedArtifactLeaksCannotPass() throws Exception {
        var input = source(true, false, "new");
        ((ObjectNode) input.path("coverage").get(0)).put("role", privateText);
        var e = evidence("api", Engine.TESTFORGE, "1.0.0", input);
        assertThat(e.failure()).isNull();
        assertThat(mapper.writeValueAsString(runner().assess(plan(false), List.of(e))))
                .doesNotContain(privateText);
        var leak = source(true, true, "accepted");
        ((ObjectNode) leak.path("findings").get(0))
                .put("check", "artifact-secret")
                .put("id", "artifact-secret " + rawTarget);
        assertThat(evidence("api", Engine.TESTFORGE, "1.0.0", leak).failure()).isNotNull();
    }

    static final class RecordingSink implements ArtifactSink {
        final List<TestArtifact> files = new ArrayList<>();

        public Path directoryFor(String source) {
            return Path.of("unused");
        }

        public void register(TestArtifact artifact) {
            files.add(artifact);
        }

        public TestArtifact write(
                String source, String category, String name, String mediaType, String content) {
            throw new AssertionError("not used");
        }
    }
}
