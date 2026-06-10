package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
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
 */
public class MockScope implements AutoCloseable {

    private static final int SCOPED_STUB_PRIORITY = 1;

    private final WireMock wireMock;
    private final String scopeJsonPath;
    private final String scopeId;
    private final List<StubMapping> stubs = new CopyOnWriteArrayList<>();

    MockScope(WireMock wireMock, String scopeJsonPath, String scopeId) {
        this.wireMock = wireMock;
        this.scopeJsonPath = scopeJsonPath;
        this.scopeId = scopeId;
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
        stubs.forEach(wireMock::removeStubMapping);
        stubs.clear();
    }
}
