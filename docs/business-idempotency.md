# Idempotency and effects after rejection

Business steps accept optional `BusinessIdempotency(header, binding)` metadata.
The seven- and nine-argument Step constructors remain available; old JSON omits
this field and behaves as before. Example:

```java
new BusinessSecuritySuite.Step("repeat", Phase.PROBE, "owner", request,
    Set.of(200), assertions, Map.of(), List.of(), null,
    new BusinessIdempotency("Idempotency-Key", "payment-key"));
```

Supply `payment-key` as a fresh per-case textual input (for example a generated
UUID), or capture it in an earlier successful control. There is no literal key
field, automatic key generation, automatic retry, hidden duplicate request or
shared key cache. Every operation is an explicitly declared step and consumes
the existing request/time budget. The project selects the product's expected
success/conflict/rejection statuses.

## Repeat with the same request

For a REPLAY case, the first operation is a successful CONTROL under the actor
role, followed by a PROBE with the identical PreparedRequest and identical
idempotency metadata. The binding cannot be overwritten later in the case.
Different header/key aliases or omitted metadata do not count as the same replay,
even if two aliases happen to contain the same runtime value.

An HTTP 200 on a repeat is insufficient to establish idempotency. A policy should:

1. Capture the initial account/order state.
2. Perform the first payment/refund with the explicit key; capture its receipt.
3. Read state as CONTROL and prove that exactly one intended effect occurred;
   capture that resulting state.
4. Repeat the same operation and key. Compare the returned receipt with the first
   one where the product promises stable receipts.
5. Read persisted state as VERIFY and compare balance/counts to the captured
   post-first-operation state using typed invariants.
6. Clean up the disposable fixture.

Controls failing to establish the expected first effect make coverage incomplete.
Violations observed by probes or verification produce findings and fail the gate.
The executor cannot infer which side effects represent a correct payment.

## Conflict and rejection

Use BUSINESS_INVARIANT or TRANSITION cases for a changed request body with the
same key: the body is deliberately different, so this is not an identical REPLAY.
Declare the expected conflict status (for example 409), then verify that the
stored balance/counters/order have not changed. A success outside that declared
policy produces a finding; mutation despite the expected rejection is caught by
the subsequent persisted-state checks.

For an invalid operation with a new key, capture the state before the rejected
probe and verify that it remains unchanged afterwards. A 4xx response alone does
not prove rollback. A 429/5xx, transport failure or exhausted budget is incomplete
evidence; it does not establish rejection or rollback. Cleanup is still attempted
within the remaining bounds. Writes still require both write switches, an
explicit test environment, allowlisted hosts and final same-fixture verification.

## Transport, review and evidence

Supported header names are `Idempotency-Key` and `X-Idempotency-Key`, compared
case-insensitively and stored under canonical names. This narrow allowlist does
not permit arbitrary header injection. Headers are allowed only on writes and
must not collide with any configured credential header, including another role's
placement. Collision checks run for the whole suite before traffic or token fetch.
The private transport does not inherit default application headers or HTTP retry
filters.

Keys must be JSON strings of 1..256 printable ASCII characters without whitespace.
Missing/invalid runtime key material makes the step incomplete before its request
is sent. Undeclared binding aliases are rejected during suite validation. Keys
stay in case-local state and the in-memory secret registry; reports, structural
reviews, evidence and baseline IDs contain neither the keys nor their hashes.
The key is never part of a URI or process argument.

Structural review includes header name and binding alias, and omits the new field
when unused so older review shapes remain compatible. Policy preflight requires
that the selected operation declare that header with an explicit string schema.
It checks binding availability and header placement. Changing header/binding or
removing the feature changes the review shape and needs explicit review. Literal
request/assertion value changes retain the existing policy-version requirement.

`BusinessIdempotencyExampleTest` exercises secure payment/refund repeats, body
conflicts, and rejected operations on owned loopback APIs. Broken variants reapply
a payment/refund, change the receipt, accept conflicting bodies or mutate state
while returning a rejection. `BusinessIdempotencyTest` covers preflight, key
identity, credential collisions and serialization. The independent published-JAR
consumer also exercises the new Step API.

These are sequential checks. Concurrent races, crash recovery, delayed effects,
key expiry, cross-user key namespaces and durable event delivery need separate
explicit scenarios and are not established by this block.
