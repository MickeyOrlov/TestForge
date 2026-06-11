# module-mock

Scenario-scoped stubbing on a **shared** WireMock server — the pattern that
makes parallel test execution safe without giving every test its own mock
instance.

## The idea

Isolation comes from **request matching**, priority only settles ties:

- Default stubs (registered by the environment, priority 10+) answer all
  traffic — the happy path.
- A test that needs a special outcome registers a stub through `MockScope`.
  The scope transparently adds a body matcher on the scenario id
  (`forge.mock.scope-json-path`) and priority 1. The stub fires **only** for
  requests carrying that scenario's id.
- `MockScope` is `AutoCloseable`: closing it deletes its stubs, so the shared
  server never accumulates garbage.

## Configuration

```yaml
forge:
  mock:
    base-url: http://wiremock.staging.example.test:8080
    scope-json-path: "$.metadata.test_scope"  # default: $.testScope
```

The bean is only created when `base-url` is set.

## Usage

```java
try (MockScope scope = mocks.scope(scopeId)) {
    scope.stub(post(urlPathEqualTo("/downstream/status"))
            .willReturn(okJson("{\"result\":\"scenario-specific\"}")));

    // drive the scenario; only requests carrying this scope id hit the stub
}
```

When the scenario itself owns the id, let the client generate and publish it:

```java
try (MockScope scope = mocks.scope()) {   // generated id
    // payload builders read it from the scenario context:
    String scopeId = ScenarioContext.get(ScopedMockClient.TEST_SCOPE);
    ...
}
```

Pair with `ScenarioContextExtension` so the published id never leaks into the
next test on a reused worker thread.

## Adapting to a project

Find the field that uniquely ties a downstream request to one scenario — an
explicit test scope, correlation id or request id that the system under test
echoes into mock-bound calls — and point `scope-json-path` at it. If no such
field exists for some flow, those few tests must run serially; tag them
explicitly rather than serializing the whole suite.
