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
    compile: true                       # compile each spec right after writing it
    surface-baseline-dir:               # <spec>/surface.json to compare with; unset = previous run
    fail-on-breaking-changes: false     # fail a spec that would break tests built on the baseline
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

## Javadoc from the document

Clients and records carry the document's own words: a tag's description on the
client, an operation's summary and description on its method, followed by the
call and what it responds with —

```java
/**
 * One task by id.
 *
 * <code>GET /tasks/&#123;id&#125;</code> — responds <code>200 Task</code>, <code>404</code>
 *
 * @param id The task's id.
 */
public Response getTask(String id) {
```

— and a schema's description on its record, with each property's description
as a `@param`. The text is the document's, so it is treated as input: `*/`,
backslashes (a unicode escape decodes inside comments too), a leading `@`,
braces and HTML are escaped. A description cannot end the comment and add
code to a generated class.

## What regeneration changed

Each generation writes `<output-dir>/<spec>/surface.json` — every client method
with its parameters, every record with its components, types only — and
compares itself with the previous one. `report.md` gets a section per spec:

```text
### What changed for tests

- BREAKING `CreateTaskRequest.priority`: component added Integer: the record is built by tests, and every new CreateTaskRequest(...) call needs one more argument
- BREAKING `TasksApiClient.listTasks`: method removed: tests that use it no longer compile
- RISKY `TasksApiClient.search`: parameters renamed or reordered (String owner, String status) -> (String status, String owner): calls still compile and may now pass values to the wrong parameter
- SAFE `Task.status`: component added String
```

A record tests build — a request body and whatever sits inside one — breaks on
a new component; a response-only record does not, because tests only read it.
The same changes are on `ApiCodegenSpecReport.changes()` and
`breakingChanges()`.

By default the baseline is the previous run's own output, which helps locally
after pulling a new document. A clean CI checkout has none, so point
`forge.api-codegen.surface-baseline-dir` at a directory holding
`<spec>/surface.json` from the last accepted generation (a CI artifact or a
checked-in copy), and set `forge.api-codegen.fail-on-breaking-changes=true`
to fail a spec whose new sources would break tests compiled against it.
Promoting a new baseline is copying the new `surface.json` there in a reviewed
change, never a side effect of the check. A failed generation keeps the old
`surface.json`, so the next successful run still compares with it.

## Compile check

Every spec is compiled right after it is written, into
`<output-dir>/<spec>/classes`, so a document that produces broken Java fails
the run that generated it — not the build that first uses the sources. The
report says `compiled: yes/no/not checked` per spec and lists up to 20 errors
as `File.java:line: message`.

The classpath is the running JVM's: inside a Gradle test task it already holds
`module-http`, REST Assured and Jackson. Turn the check off with
`forge.api-codegen.compile=false` (for example when generating outside a test
JVM); on a JRE without a compiler it is skipped with a warning.

Names Java will not take are changed, never dropped, and the wire name is kept:

| In the document | Generated |
|---|---|
| schema `String`, `Response`, `ApiClient`, `Object` … | `StringModel`, `ResponseModel` … |
| property `hashCode`, `toString`, `getClass`, `wait` … | `hashCodeValue` with `@JsonProperty("hashCode")` |
| operation id `toString` | `toStringCall(...)` |
| parameter `api` or `requestSpec` | `api2`, `requestSpec2` |

Clients import each model by name, so a schema named after a `java.lang`
type (`Error`, `Record`) stays usable as it is.

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
