# module-api-discovery

OpenAPI catalog and schema shape snapshots for CI drift checks.

## What's inside

- **`ApiDiscoveryRunner`** — reads configured OpenAPI specs, writes artifacts
  and fails from JUnit through `assertHealthy()`.
- **`EndpointCatalog`** — stable list of operations: method, path, operationId,
  tags, request content types, response status codes and content types.
- **`OpenApiShapeNormalizer`** — converts OpenAPI request/response schemas into
  `jsonPath -> type/required/nullable` snapshots. Values are never stored.
- **`ApiDiscoveryReport`** — structured result with catalog diffs, shape diffs
  and artifact paths.

This module is an inventory/drift layer. It does not send HTTP requests and it
does not generate client code or tests.

## Configuration

```yaml
forge:
  api-discovery:
    enabled: true
    output-dir: build/api-discovery/current
    baseline-dir: build/api-discovery/baseline
    fail-on-catalog-diff: true
    fail-on-shape-diff: true
    specs:
      billing:
        location: classpath:/openapi/billing.yaml
      public-api:
        location: file:specs/public-api.yaml
```

Prefer `classpath:` or `file:` locations in the default build. HTTP locations
belong in an explicit environment profile that has network access.

## Usage

```java
@SpringBootTest
class ApiDiscoveryIT {

    @Autowired ApiDiscoveryRunner discovery;

    @Test
    void apiCatalogDidNotDrift() {
        discovery.assertHealthy();
    }
}
```

Artifacts are written under `forge.api-discovery.output-dir`:

- `report.json`
- `report.md`
- `<spec>/catalog.json`
- `<spec>/shapes/<operation>.shape.json`

If no baseline exists, current artifacts are written and the first run does not
fail on diffs. Parser errors still fail the report.

## What this is — and is not

This is a QA-side OpenAPI inventory check. It helps reviewers notice endpoint
and schema drift in CI artifacts. It is not Swagger UI, OpenAPI Generator,
REST Assured execution, live API crawling, API coverage tracking, or
consumer-driven contract testing.

## Agent notes

- Keep default specs local (`classpath:`/`file:`) so the build stays
  offline-first.
- Do not store sample payload values in snapshots; schema shape only.
- Keep generated baselines as CI artifacts or explicit project inputs, not
  files rewritten under `src/test/resources` by the runner.
- Runtime API probing and smoke-test generation are separate future modules or
  stages, not v1 scope.
