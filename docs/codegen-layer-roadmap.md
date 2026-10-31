# API codegen roadmap

`module-api-codegen` turns an OpenAPI document into Java records and
`ApiClient` skeletons. Nothing checked that the result compiled: a document
with a schema named `Error` or a property named `hashCode` produced sources
that broke the first build to put them on a classpath, far from the run that
wrote them.

All four steps shipped (PRs #86–#89).

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Compile check** — every generated spec compiled right after it is written; errors by file and line in the report; names Java will not take renamed | `module-api-codegen` | shipped |
| 2 | **Documented clients** — the document's summaries, descriptions and expected statuses as Javadoc, with comment-breaking text neutralised | `module-api-codegen` | shipped |
| 3 | **What regeneration changed** — the generated surface compared with the previous run; a removed method or a changed signature called out as breaking for the tests that use it | `module-api-codegen` | shipped |
| 4 | **Review** — close the gaps a review of the module finds | `module-api-codegen` | shipped |

## Step 1 — compile check

`forge.api-codegen.compile` (default `true`) compiles each spec's sources with
the JDK compiler into `<output-dir>/<spec>/classes`, on the running JVM's
classpath. A spec that does not compile fails, and the report lists up to 20
errors as `File.java:line: message`. A JRE without a compiler skips the check
with a warning instead of failing.

Running it against awkward but legal documents found what it was built for:

- a schema named `Error` (or any other `java.lang` name) made the client's
  wildcard model import ambiguous — clients now import each model by name;
- a schema named `String`, `Response`, `ApiClient` … shadowed a type the
  generated code uses — such models get a `Model` suffix;
- properties named `hashCode`, `toString`, `getClass`, `wait` … are illegal
  record components — they get a `Value` suffix and keep the wire name through
  `@JsonProperty`;
- an operation id like `toString` gets a `Call` suffix; parameters named `api`
  or `requestSpec` no longer collide with the generated method's own names.

## Step 2 — documented clients

The tag description goes on the client; summary and description on each
method, then `<code>GET /tasks/{id}</code> — responds <code>200 Task</code>,
<code>404</code>`; the schema description on each record, with property and
parameter descriptions as `@param`.

Document text is input that ends up inside generated Java. Left as it was, a
description containing `*/` closes the comment and the rest compiles as code
— a test shows a hostile document that would add a field and a static
initialiser. `*/`, `/*`, backslashes (the compiler decodes `\u002a/` inside
comments), `@`, braces and HTML are escaped; the hostile document compiles to
classes with only their generated members.

## Step 3 — what regeneration changed

A new document version regenerates sources the project's tests compile
against, and the first sign of a removed operation used to be a compile error
somewhere in the test tree. Each generation now writes `surface.json` — client
methods with parameters, records with components, types only — and compares
itself with the baseline, judged for those tests:

| Change | Impact |
|---|---|
| type, method or component removed; signature or component type changed | `BREAKING` |
| component added to a record tests build (a request body or inside one) | `BREAKING` |
| parameters renamed or reordered, same types | `RISKY` — compiles, may pass values to the wrong parameter |
| type or method added; component added to a response-only record | `SAFE` |

The baseline is the previous run's output by default and
`forge.api-codegen.surface-baseline-dir` in CI;
`fail-on-breaking-changes` (default `false`) makes it a gate. A failed
generation restores the old `surface.json` so the comparison survives it.

## Step 4 — review

Probing the generator with awkward documents found:

- a `$ref` to an undefined schema crashed generation with a
  `ConcurrentModificationException` — models were registered while they were
  being written — and the report said `error: null`. Unresolved references
  now map to `Object` with a warning, clients are rendered before models so
  nothing is named after the models are written, and a failure without a
  message is reported by its type;
- the compile check trusted `java.class.path`; from a Spring Boot fat jar that
  names one archive javac cannot look into, and every spec would have failed.
  The classpath now adds where the imported classes were loaded from, and a
  non-file location skips the check with the reason;
- names were built from parts, so keywords and status codes leaked suffixes:
  `DefaultTypeApiClient`, `ListTasksN200Response`. Names are now built whole:
  `DefaultApiClient`, `ListTasks200Response`.

Running the library smoke test for the renamed client also showed the
Javadoc task failing on `module-db` — `DbWaiter` still linked the pre-#58
`awaitRowCount` signature. The default build skips Javadoc, so only
`publishTestForgeLibraries` caught it.
