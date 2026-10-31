package io.testforge.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.admin.model.ServeEventQuery;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.testforge.artifact.ArtifactSink;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
    private final ScopeMarker marker;
    private final String scopeId;
    private final ArtifactSink artifactSink;
    private final List<StubMapping> stubs = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequences = new AtomicInteger();
    private final boolean failOnUnusedStubs;

    MockScope(WireMock wireMock, String scopeJsonPath, String scopeId) {
        this(wireMock, scopeJsonPath, scopeId, ArtifactSink.NO_OP);
    }

    MockScope(WireMock wireMock, String scopeJsonPath, String scopeId, ArtifactSink artifactSink) {
        this(wireMock, ScopeMarker.jsonPath(scopeJsonPath), scopeId, artifactSink);
    }

    MockScope(WireMock wireMock, ScopeMarker marker, String scopeId, ArtifactSink artifactSink) {
        this(wireMock, marker, scopeId, artifactSink, false);
    }

    MockScope(WireMock wireMock, ScopeMarker marker, String scopeId, ArtifactSink artifactSink,
              boolean failOnUnusedStubs) {
        this.wireMock = wireMock;
        this.marker = marker;
        this.scopeId = scopeId;
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NO_OP;
        this.failOnUnusedStubs = failOnUnusedStubs;
    }

    public String scopeId() {
        return scopeId;
    }

    public StubMapping stub(MappingBuilder mapping) {
        StubMapping stubMapping = marker.applyTo(mapping, scopeId)
                .atPriority(SCOPED_STUB_PRIORITY)
                .build();

        wireMock.register(stubMapping);
        stubs.add(stubMapping);
        return stubMapping;
    }

    /**
     * Answers the same request differently each time, in order, then keeps
     * answering with the last response — the retry test:
     *
     * <pre>{@code
     * scope.sequence(post(urlPathEqualTo("/payments")),
     *         aResponse().withStatus(503),
     *         aResponse().withStatus(503),
     *         okJson("{\"status\":\"accepted\"}"));
     * }</pre>
     *
     * Built on a WireMock scenario whose name carries the scope id, so two
     * parallel scopes walking the same sequence never advance each other's
     * state. Every step is a scoped stub like {@link #stub}, removed on close.
     *
     * @return the step stubs, in order
     */
    public List<StubMapping> sequence(MappingBuilder request, ResponseDefinitionBuilder... responses) {
        if (responses == null || responses.length == 0) {
            throw new IllegalArgumentException("A sequence needs at least one response");
        }
        String template = StubMapping.buildJsonStringFor(marker.applyTo(request, scopeId)
                .atPriority(SCOPED_STUB_PRIORITY)
                .build());
        String scenario = "testforge-" + scopeId + "-" + sequences.incrementAndGet();
        List<StubMapping> steps = new java.util.ArrayList<>();
        for (int i = 0; i < responses.length; i++) {
            StubMapping step = StubMapping.buildFrom(template);
            UUID id = UUID.randomUUID();
            step.setId(id);
            step.setUuid(id);
            step.setScenarioName(scenario);
            step.setRequiredScenarioState(i == 0 ? Scenario.STARTED : "step-" + (i + 1));
            if (i < responses.length - 1) {
                step.setNewScenarioState("step-" + (i + 2));
            }
            step.setResponse(responses[i].build());
            wireMock.register(step);
            stubs.add(step);
            steps.add(step);
        }
        return List.copyOf(steps);
    }

    /**
     * Every request in the WireMock journal that carries this scope, matched by a
     * stub or not — the shared server's journal filtered to this scenario.
     */
    public List<LoggedRequest> requests() {
        return requests(RequestPatternBuilder.allRequests());
    }

    /**
     * This scope's requests that also match {@code pattern}, e.g.
     * {@code postRequestedFor(urlPathEqualTo("/payments"))}. The scope matcher is
     * added to {@code pattern}, so other scenarios' traffic never counts.
     */
    public List<LoggedRequest> requests(RequestPatternBuilder pattern) {
        return wireMock.find(marker.applyTo(pattern, scopeId));
    }

    /**
     * Asserts this scope sent {@code count} requests matching {@code pattern}.
     * Fails with WireMock's own report of the closest requests on a mismatch.
     */
    public void verify(int count, RequestPatternBuilder pattern) {
        wireMock.verifyThat(count, marker.applyTo(pattern, scopeId));
    }

    public ScopeMarker marker() {
        return marker;
    }

    /**
     * Stubs of this scope that no request has hit yet, e.g.
     * {@code POST /payments → 503}. A stub the scenario never called means it
     * took another path — and passed on the defaults, which is the dangerous
     * kind of green. A sequence step not reached says the system stopped
     * retrying early. Empty when the server keeps no request journal.
     */
    public List<String> unusedStubs() {
        List<String> unused = new java.util.ArrayList<>();
        try {
            for (StubMapping stub : stubs) {
                if (wireMock.getServeEvents(ServeEventQuery.forStubMapping(stub.getId())).isEmpty()) {
                    unused.add(describe(stub));
                }
            }
        } catch (RuntimeException e) {
            // journal disabled or unreachable: nothing can be said, so nothing is claimed
            return List.of();
        }
        return unused;
    }

    private static String describe(StubMapping stub) {
        RequestPattern request = stub.getRequest();
        String method = request == null || request.getMethod() == null ? "ANY" : request.getMethod().getName();
        String url = request == null ? "?" : Stream.of(request.getUrl(), request.getUrlPath(), request.getUrlPattern(),
                        request.getUrlPathPattern())
                .filter(java.util.Objects::nonNull).findFirst().orElse("(any url)");
        String step = stub.getScenarioName() == null ? ""
                : ", sequence " + (Scenario.STARTED.equals(stub.getRequiredScenarioState())
                        ? "step-1" : stub.getRequiredScenarioState());
        int status = stub.getResponse() == null ? 0 : stub.getResponse().getStatus();
        return method + " " + url + step + " → " + status;
    }

    /**
     * Publishes diagnostics and removes this scope's stubs. With
     * {@code forge.mock.fail-on-unused-stubs}, then fails if any stub was never
     * called — after the stubs are gone, so the shared server stays clean, and
     * as a suppressed exception when the test body already failed.
     */
    @Override
    public void close() {
        List<String> unused = failOnUnusedStubs ? unusedStubs() : List.of();
        try {
            publishDiagnostics();
        } catch (Throwable t) {
            // Best-effort: diagnostic publishing failures must never fail a test or mask a mock failure.
        } finally {
            stubs.forEach(wireMock::removeStubMapping);
            stubs.clear();
        }
        if (!unused.isEmpty()) {
            throw new AssertionError("Scope " + scopeId + ": stubs the scenario never called — it took another "
                    + "path than the test assumed: " + unused);
        }
    }

    private void publishDiagnostics() {
        if (artifactSink == null || artifactSink == ArtifactSink.NO_OP) {
            return;
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("scopeId", scopeId);
        if (marker.jsonPathOrNull() != null) {
            root.put("scopeJsonPath", marker.jsonPathOrNull());
        }
        ObjectNode markerNode = root.putObject("scopeMarker");
        markerNode.put("kind", marker.kind().name());
        markerNode.put("name", marker.name());

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
        ArrayNode unusedArray = root.putArray("unusedStubs");
        unusedStubs().forEach(unusedArray::add);

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
     * {@link ScopeMarker} {@link #stub} uses. No new correlation machinery is involved: the marker already exists in the
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
            for (LoggedRequest req : wireMock.findAllUnmatchedRequests()) {
                // Per-request guard: one unreadable body must never suppress the whole
                // scope's diagnostic.
                if (!marker.carries(req, scopeId)) {
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
                    reqNode.setAll(StubMismatchAnalyzer.analyze(MAPPER, stubs, req, marker.jsonPathOrNull()));
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
