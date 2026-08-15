package io.testforge.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScopedMockClientTest {

    private WireMockServer wireMockServer;
    private WireMock wireMock;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        wireMock = WireMock.create().host("localhost").port(wireMockServer.port()).build();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testScopedMockClientPassesSinkToMockScope() {
        RecordingSink sink = new RecordingSink();
        ScopedMockClient client = new ScopedMockClient(wireMock, "$.testScope", sink);

        try (MockScope scope = client.scope()) {
            assertNotNull(scope.scopeId());
            scope.stub(WireMock.get(WireMock.urlPathEqualTo("/api/user")).willReturn(WireMock.ok()));
        }

        assertEquals(1, sink.registered.size());
        assertEquals("module-mock", sink.registered.get(0).source());
    }

    private static class RecordingSink implements ArtifactSink {
        final List<TestArtifact> registered = new ArrayList<>();

        @Override
        public Path directoryFor(String source) {
            return Path.of(System.getProperty("java.io.tmpdir"), source);
        }

        @Override
        public void register(TestArtifact artifact) {
            registered.add(artifact);
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            TestArtifact artifact = new TestArtifact(source, category, name, directoryFor(source).resolve(name), mediaType, Instant.now(), Map.of());
            registered.add(artifact);
            return artifact;
        }
    }
}
