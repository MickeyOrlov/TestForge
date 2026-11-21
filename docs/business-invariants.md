# Business effects and bounded list isolation

Business-security steps can declare typed `invariants` and optional `pagination`.
Existing seven-argument Step constructors and JSON policies remain valid. New
fields default to an empty list/null; old structural review shapes are unchanged
when these features are unused.

## Typed invariants

```java
var rule = new BusinessInvariant("line-total", Compare.NUMERIC_EQUALS,
    Value.response("/total"),
    Value.operation(Source.SUM, Value.response("/items"),
        Value.operation(Source.MULTIPLY, Value.item("/qty"), Value.item("/unitPrice"))));
```

Imports refer to BusinessInvariant.Compare, Source and Value. A rule has a unique
non-sensitive alias within its case. Its result is PASS, FAIL or INCOMPLETE.
There is no expression-language interpreter, scripting, reflection or implicit
conversion of strings to numbers.

| Operand source | Meaning |
|---|---|
| RESPONSE / BINDING | JSON Pointer into this response or an earlier case-local capture/input |
| LITERAL | Explicit JSON value supplied by project policy |
| ADD / SUBTRACT / MULTIPLY | Exactly two numeric operands |
| SUM | Numeric array, or array plus an expression evaluated with ITEM as the current row |
| ITEM | JSON Pointer within a SUM row; invalid outside that scope |
| COUNT | Array size |
| PROJECT | Extract the declared pointer from every array item |
| DECIMAL | Explicit conversion of a plain decimal string; numeric values also supported |

Comparisons: NUMERIC_EQUALS, AT_MOST, AT_LEAST, LESS_THAN, GREATER_THAN,
JSON_EQUALS, ALL_EQUAL and CONTAINS. Numeric comparison ignores decimal scale;
JSON comparison preserves JSON types. ALL_EQUAL/CONTAINS require arrays. Every
live probe/verification invariant must depend on its current response, so a
constant-only tautology cannot earn live rule coverage.

The private executor decodes floating JSON numbers as BigDecimal. Arithmetic is
exact and bounded to precision 64 and scale -32..32. There is no implicit rounding
or division. Construct numeric policy literals with BigDecimal/DecimalNode, or
parse policies with USE_BIG_DECIMAL_FOR_FLOATS: precision already lost in a
caller-created double/JsonNode cannot be recovered. Legacy assertion literals
are canonicalized with the executor's decoder for compatible floating equality.

Expression bounds: depth 32, 512 nodes per operand, 100000 evaluation work units,
10000 array entries and 65536 characters per selected string. Case rule count
uses the existing max-steps bound. Missing fields/bindings, wrong operand types,
unsupported decimals and exhausted work bounds give INCOMPLETE evidence, not
an invented numeric value or a security finding. A failed positive CONTROL also
makes the case incomplete. A violated PROBE/VERIFY rule produces a HIGH finding
with fixed evidence and the rule alias, never either operand's value/hash.

## Related resources

`BUSINESS_INVARIANT` cases use the same controls, captures, probes and verification
as existing business cases. For example:

1. Capture order total, account balance and available stock.
2. Execute the payment as the explicit role.
3. Read account and stock; compare them to captured values minus payment/quantity.
4. Re-read the primary order; check its state and total against line calculations.
5. Remove the disposable fixture using the declared cleanup.

Verification reads of related resources may supplement the final primary-fixture
check; they do not replace its same-fixture guard. Reads/writes retain existing
host, role, environment, timeout and two-write-switch requirements. Project
policy supplies business meaning, amount/currency conventions and fixtures;
OpenAPI cannot infer them.

## Lists, search and pagination

`COLLECTION_ACCESS` requires an actor-role CONTROL on the same collection before
the probe. Its response-backed CONTAINS invariant must find a known owned fixture
using a binding. The matching probe needs a response-backed ALL_EQUAL isolation
rule, for example PROJECT(RESPONSE /items, /tenantId) against BINDING tenant.
This provides a positive fixture control and checks each returned item rather
than accepting an empty response as proof of isolation.

```java
new BusinessPagination("/items", "/next", "cursor", 20, 1000, true)
```

Pagination supports successful GET JSON responses only. It updates the explicit
query parameter on the original fixed origin/path and preserves the other
filters. Cursor values are opaque in-memory data, never navigation URLs, logs,
report fields or hashes. They are encoded as query data; absolute URL/control
characters are rejected. Credential query parameters and method overrides cannot
be used for pagination.

Each page must contain the item array and explicit next-cursor field. Null/empty
next ends the scan; missing next is incomplete. Repeated cursors, malformed
shapes, HTTP/transport errors, time/body/request limits, max-pages or max-items
prevent completion. Defaults: 20 pages, 1000 items, require at least one item.
Hard caps: 100 pages, 10000 items and cursor length 512. Empty-result allowance
must be explicit. The existing max-body-bytes also bounds aggregated item bytes
and the final aggregate, not just each response. Planned request budgets include
the worst-case pages for controls, probes and cleanup before any traffic.

At successful termination, invariants/assertions/captures see the aggregated item
array and the other fields from the final page. CONTAINS can therefore find an
owned item on a later page; SUM/COUNT can assess the complete bounded result.
ALL_EQUAL also runs on each page as it arrives: an observed foreign row remains
a finding even if a later page fails or cycles. Partial aggregation never becomes
a complete check or feeds captures as a complete list.

## Review, evidence and resolution

Structural reviews retain operators, selectors, binding references, rule aliases
and paging policy. Operator/selector/pagination edits require explicit review;
literal values remain type-only and changing them requires a policy version
update. Preflight follows selectors into response arrays and captured-resource
schemas, validates binding order and includes worst-case page budgets. Runtime
values remain case-local and bounded.

Each rule's finding/coverage identity includes case, step, phase, structural
operation, actor and invariant alias. Baselines require exact completed rule
coverage to resolve an old finding. Running only the parent case, removing the
rule or moving it to a control/other actor does not resolve it. Incomplete scopes
remain blocked by the existing security/quality completeness contract.

## Local examples

- BusinessEffectsExampleTest: exact order totals, payment/account/stock deltas,
  refund limits and cleanup. It detects double charging, missing reservation,
  wrong totals, negative stock and over-refunding in intentionally broken APIs.
- BusinessPaginationTest: later-page membership, cross-tenant search leaks,
  cycles, URL cursors, missing termination, empty responses, limits and credential
  collisions; all servers bind owned loopback addresses.
- BusinessInvariantTest: decimal behavior, typed serialization, bounded operands
  and invariant-specific baseline resolution.
- PublishedBusinessSecuritySmokeTest: new rule API and review integration from
  locally published JARs without source-project dependencies.
