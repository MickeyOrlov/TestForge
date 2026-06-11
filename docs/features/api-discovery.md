Вот markdown-блок, который можно положить в docs/ROADMAP.md, docs/ideas.md или прямо в отдельный файл типа docs/features/api-discovery.md.

[FEATURE] API Discovery from Frontend Bundles + Generated API Smoke-Test Skeletons

Status

Backlog / Not for immediate implementation.

Idea

Add a future TestForge feature that can discover backend API endpoints used by a frontend application and generate an initial API test scaffold from them.

The feature should help when a QA/SDET joins a project where:

* Swagger/OpenAPI is missing, outdated, or incomplete;
* frontend already contains real API usage;
* endpoint documentation is weak;
* the team needs a fast first version of API coverage;
* the tester wants to discover what the UI actually calls.

High-level concept

The feature consists of two separate parts:

1. API discovery
    * Open a target frontend URL.
    * Parse the HTML page.
    * Find JavaScript bundles.
    * Download JS assets.
    * Scan bundles for API endpoint candidates.
    * Extract paths, HTTP methods, base URL hints, query params, body hints, and auth hints where possible.
    * Save results into an EndpointCatalog.
2. API test generation
    * Read the EndpointCatalog.
    * Generate initial Rest Assured + JUnit 5 smoke-test skeletons.
    * Group tests by endpoint prefix or domain area.
    * Add TODO comments for auth, request body, test data, and expected statuses.
    * Mark generated tests clearly so they can be reviewed and adapted manually.

Important boundary

This is not a security scanner and must not be positioned as one.

Use only on systems that the team owns or is explicitly allowed to test.

The goal is test automation discovery, not unauthorized endpoint scanning.

Possible modules

module-api-discovery
module-api-testgen

Alternative names:

module-endpoint-discovery
module-api-scaffold

Possible architecture

EndpointSource
├── JsBundleEndpointSource
├── OpenApiEndpointSource
├── HarEndpointSource
├── PostmanEndpointSource
└── ManualEndpointSource
EndpointCatalog
├── endpoints
├── source references
├── confidence level
├── auth hints
└── metadata
ApiTestGenerator
├── RestAssuredJUnitGenerator
├── ContractSkeletonGenerator
└── SmokeTestGenerator

MVP discovery flow

1. Input: frontend URL
2. Download HTML
3. Extract <script src="..."> assets
4. Download JavaScript bundles
5. Search for endpoint-like strings:
    - /api/...
    - /v1/...
    - /v2/...
    - /rest/...
    - /graphql
6. Detect HTTP method when possible:
    - fetch(...)
    - axios.get(...)
    - axios.post(...)
    - axios.put(...)
    - axios.delete(...)
    - client.request(...)
7. Normalize candidates
8. Remove duplicates
9. Assign confidence level
10. Save EndpointCatalog as JSON and Markdown

Example EndpointCatalog

{
"endpoints": [
{
"method": "GET",
"path": "/api/users",
"source": "assets/index-a8f31.js",
"confidence": "HIGH"
},
{
"method": "POST",
"path": "/api/payment/init",
"source": "assets/payment-91c2.js",
"confidence": "HIGH"
},
{
"method": "GET",
"path": "/api/orders/{id}",
"source": "assets/orders-443a.js",
"confidence": "MEDIUM"
}
]
}

Example generated test

@Tag("generated")
class DiscoveredPaymentApiTest {
@Test
void postPaymentInit_shouldBeCovered() {
// TODO: provide authentication
// TODO: provide valid request body
// TODO: replace expected status code with real business expectation
given()
.baseUri(apiBaseUrl)
.contentType(ContentType.JSON)
.body("""
{
"TODO": "fill request body"
}
""")
.when()
.post("/api/payment/init")
.then()
.statusCode(anyOf(is(200), is(400), is(401), is(403)));
}
}

Possible Gradle commands

./gradlew discoverApi \
-Pforge.discovery.url=https://staging.example.test \
-Pforge.discovery.output=build/discovered-endpoints.json
./gradlew generateApiTests \
-Pforge.discovery.catalog=build/discovered-endpoints.json \
-Pforge.testgen.package=io.company.tests.generated

What this feature should NOT do in MVP

* Do not try to generate fully correct business tests.
* Do not guess valid request bodies too aggressively.
* Do not send real modifying requests automatically.
* Do not scan external systems without explicit permission.
* Do not depend on AI for the deterministic MVP.
* Do not mix this feature with Playwright/Appium modules.
* Do not make generated tests part of the default green build without review.

Future improvements

* HAR import.
* OpenAPI import.
* Postman collection import.
* GraphQL operation discovery.
* AI-assisted endpoint classification.
* AI-assisted request body inference.
* Coverage report: discovered endpoints vs existing tests.
* Diff report: newly discovered endpoints compared to previous scan.
* Contract skeleton generation from observed frontend usage.
* Integration with module-contract-monitor.
* Integration with module-data-json for generated request templates.

Why this fits TestForge

This feature supports the main TestForge idea: helping QA/SDET engineers quickly bootstrap useful automation for backend-heavy systems.

Instead of starting from an empty test suite, the team can discover the API surface used by the frontend and generate a first reviewable API test scaffold.

The generated tests are not final tests. They are a starting point for real automation work.