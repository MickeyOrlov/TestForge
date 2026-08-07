# module-api-codegen

OpenAPI-first Java records and typed TestForge API client skeletons.

## What it generates

For every spec configured under `forge.api-discovery.specs`, the module writes:

```text
build/generated/testforge/<spec>/src/main/java/
  <base-package>.<spec>.model/
    CreateTaskRequest.java
    Task.java
  <base-package>.<spec>.client/
    TasksApiClient.java
```

Models come from OpenAPI components and inline request/response schemas. API
clients are grouped by the operation's first tag, depend on `ApiClient`, and
return the normal REST Assured `Response`. The generated layer owns transport
details; assertions and business workflows remain in the adapted project.

Generated files carry a regeneration warning and live under `build/` by
default. The runner replaces only its `<output-dir>/<spec>` directory, never a
project's hand-written source tree.

## Configuration

```yaml
forge:
  api-discovery:
    specs:
      demo:
        location: classpath:/openapi/demo-api.yaml
  api-codegen:
    enabled: true
    output-dir: build/generated/testforge
    base-package: com.example.tests.generated
```

The spec registry is shared with `module-api-discovery`; locations are not
configured twice. Keep default specs on the classpath or filesystem so the
default build remains offline-first.

## Usage

```java
@SpringBootTest
class GenerateApiSourcesTest {

    @Autowired ApiCodegenRunner codegen;

    @Test
    void generate() {
        codegen.assertGenerated();
    }
}
```

`assertGenerated()` writes `report.json` and `report.md` and fails with a
readable error when a spec cannot be parsed or generation fails. Running it
again removes stale files only for the generated spec.

V1 does not attach its output to a Gradle source set automatically. Generation
and compilation are intentionally separate steps until a dedicated TestForge
Gradle plugin exists. A project that wants generated clients on its test
classpath can add the chosen spec source root explicitly after generation.

## Supported in v1

- OpenAPI component object schemas;
- inline object request and response schemas;
- nested objects, arrays, maps, `$ref`, and `allOf` properties;
- primitive required fields and boxed optional fields;
- `date`, `date-time`, `uuid`, numeric, and binary formats;
- path, query, header, cookie, and request-body parameters;
- clients grouped by tag with stable names and deterministic output.

`oneOf` and `anyOf` currently become `Object` and produce a report warning.
Enums remain their wire scalar type. These fallbacks are explicit so v1 does
not generate a false type contract.

## Out of scope

- runtime API calls or response observations;
- safe endpoint exploration;
- request example synthesis;
- schema-aware fuzzing;
- generated smoke assertions;
- automatic source promotion or overwriting `src/test/java`;
- a TestForge Gradle plugin or `testForgeBootstrap` task.

Those are separate stages. This module keeps one direction only:
`OpenAPI -> records and client skeletons`.
