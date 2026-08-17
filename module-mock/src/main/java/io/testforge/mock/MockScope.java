package io.testforge.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.testforge.artifact.ArtifactSink;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scenario-scoped view of a shared WireMock instance.
 *
 * <p>Every stub registered through {@link #stub(MappingBuilder)} is narrowed
 * with a request-body matcher on the scope id and raised to high priority.
 * The result: this stub only fires for requests belonging to this scenario,
 * while all other traffic keeps hitting the low-priority defaults. That is
 * what makes parallel test execution against one shared mock server safe —
 * isolation comes from the matcher, the priority only settles who wins when
 * both the scoped stub and a default match.
 *
 * <p>Always close the scope (try-with-resources or an after-hook) so the
 * shared server does not accumulate dead stubs.
 *
 * <p><strong>Security Note on Diagnostics:</strong> When a scope is closed,
 * diagnostic summary information is published to the configured {@link ArtifactSink}.
 * To prevent credential leakage, raw request/response bodies and headers are
 * explicitly omitted. Only structural metadata (HTTP method, URL path, HTTP status,
 * scope ID, and counts) is published.
 */
public class MockScope implements AutoCloseable {

    private static final int SCOPED_STUB_PRIORITY = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WireMock wireMock;
    private final String scopeJsonPath;
    private final String scopeId;
    private final ArtifactSink artifactSink;
    private final List<StubMapping> stubs = new CopyOnWriteArrayList<>();

    MockScope(WireMock wireMock, String scopeJsonPath, String scopeId) {
        this(wireMock, scopeJsonPath, scopeId, ArtifactSink.NO_OP);
    }

    MockScope(WireMock wireMock, String scopeJsonPath, String scopeId, ArtifactSink artifactSink) {
        this.wireMock = wireMock;
        this.scopeJsonPath = scopeJsonPath;
        this.scopeId = scopeId;
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NO_OP;
    }

    public String scopeId() {
        return scopeId;
    }

    public StubMapping stub(MappingBuilder mapping) {
        StubMapping stubMapping = mapping
                .withRequestBody(WireMock.matchingJsonPath(scopeJsonPath, WireMock.equalTo(scopeId)))
                .atPriority(SCOPED_STUB_PRIORITY)
                .build();

        wireMock.register(stubMapping);
        stubs.add(stubMapping);
        return stubMapping;
    }

    @Override
    public void close() {
        try {
            publishDiagnostics();
        } catch (Throwable t) {
            // Best-effort: diagnostic publishing failures must never fail a test or mask a mock failure.
        } finally {
            stubs.forEach(wireMock::removeStubMapping);
            stubs.clear();
        }
    }

    private void publishDiagnostics() {
        if (artifactSink == null || artifactSink == ArtifactSink.NO_OP) {
            return;
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("scopeId", scopeId);
        root.put("scopeJsonPath", scopeJsonPath);

        ArrayNode stubsArray = root.putArray("stubs");
        for (StubMapping stub : stubs) {
            ObjectNode stubNode = stubsArray.addObject();
            if (stub.getId() != null) {
                stubNode.put("id", stub.getId().toString());
            }
            if (stub.getName() != null) {
                stubNode.put("name", stub.getName());
            }
            stubNode.put("priority", stub.getPriority());

            RequestPattern reqPattern = stub.getRequest();
            if (reqPattern != null) {
                ObjectNode reqNode = stubNode.putObject("request");
                if (reqPattern.getMethod() != null) {
                    reqNode.put("method", reqPattern.getMethod().getName());
                }
                if (reqPattern.getUrl() != null) {
                    reqNode.put("url", reqPattern.getUrl());
                }
                if (reqPattern.getUrlPath() != null) {
                    reqNode.put("urlPath", reqPattern.getUrlPath());
                }
                if (reqPattern.getUrlPattern() != null) {
                    reqNode.put("urlPattern", reqPattern.getUrlPattern());
                }
                if (reqPattern.getUrlPathPattern() != null) {
                    reqNode.put("urlPathPattern", reqPattern.getUrlPathPattern());
                }
            }

            ResponseDefinition response = stub.getResponse();
            if (response != null) {
                ObjectNode respNode = stubNode.putObject("response");
                respNode.put("status", response.getStatus());
            }
        }
        root.put("stubCount", stubs.size());

        publishScopedUnmatchedRequests(root);

        String jsonContent;
        try {
            jsonContent = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            jsonContent = root.toString();
        }

        artifactSink.write(
                "module-mock",
                "mock-diagnostics",
                scopeId + ".json",
                "application/json",
                jsonContent
        );
    }

    /**
     * Adds the unmatched requests that belong to THIS scope.
     *
     * <p>{@code findAllUnmatchedRequests()} is a SERVER-WIDE query against the shared
     * WireMock, so it also returns traffic from other concurrent scopes. An earlier version
     * published that raw list, which meant every scope reported every other scope's unmatched
     * requests; the block was removed rather than shipped wrong.
     *
     * <p>It is restored here by filtering with the scope's OWN matcher — the very same
     * {@code matchingJsonPath(scopeJsonPath, equalTo(scopeId))} expression {@link #stub}
     * uses. No new correlation machinery is involved: the marker already exists in the
     * request body, because that marker is what makes scoped stubbing work at all.
     *
     * <p>Requests carrying no marker, or a malformed/non-JSON/empty body, produce a
     * non-match and are therefore reported in NO scope. That is a deliberate trade: it is
     * strictly more honest than the old behaviour of reporting them in EVERY scope, at the
     * cost of a blind spot for untagged traffic.
     *
     * <p>The body is read only to evaluate the match and mismatch explanation and is never
     * stored — only method, url, loggedDate, and non-sensitive mismatch metadata are
     * recorded, preserving the no-payload guarantee above.
     */
    private void publishScopedUnmatchedRequests(ObjectNode root) {
        ArrayNode unmatchedArray = root.putArray("unmatchedRequests");
        int count = 0;
        try {
            StringValuePattern scopePattern =
                    WireMock.matchingJsonPath(scopeJsonPath, WireMock.equalTo(scopeId));

            for (LoggedRequest req : wireMock.findAllUnmatchedRequests()) {
                // Per-request guard: one unreadable body must never suppress the whole
                // scope's diagnostic.
                try {
                    if (!scopePattern.match(req.getBodyAsString()).isExactMatch()) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }

                ObjectNode reqNode = unmatchedArray.addObject();
                if (req.getMethod() != null) {
                    reqNode.put("method", req.getMethod().getName());
                }
                if (req.getUrl() != null) {
                    reqNode.put("url", req.getUrl());
                }
                if (req.getLoggedDate() != null) {
                    reqNode.put("loggedDate", req.getLoggedDate().toInstant().toString());
                }
                try {
                    reqNode.setAll(StubMismatchAnalyzer.analyze(MAPPER, stubs, req, scopeJsonPath));
                } catch (Throwable ignored) {
                    // Best-effort: an unexplainable request still gets its method/url/loggedDate.
                }
                count++;
            }
        } catch (Throwable ignored) {
            // Best-effort: diagnostics must never fail a test.
        }
        root.put("unmatchedCount", count);
    }
}
