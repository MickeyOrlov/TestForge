# API codegen roadmap

`module-api-codegen` turns an OpenAPI document into Java records and
`ApiClient` skeletons. Nothing checked that the result compiled: a document
with a schema named `Error` or a property named `hashCode` produced sources
that broke the first build to put them on a classpath, far from the run that
wrote them.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Compile check** — every generated spec compiled right after it is written; errors by file and line in the report; names Java will not take renamed | `module-api-codegen` | shipped |
| 2 | **Documented clients** — the document's summaries, descriptions and expected statuses as Javadoc, with comment-breaking text neutralised | `module-api-codegen` | planned |
| 3 | **What regeneration changed** — the generated surface compared with the previous run; a removed method or a changed signature called out as breaking for the tests that use it | `module-api-codegen` | planned |
| 4 | **Review** — close the gaps a review of the module finds | `module-api-codegen` | planned |

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
