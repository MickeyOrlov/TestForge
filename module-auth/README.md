# module-auth

Tokens per role, so a test says *who* it acts as instead of *how* to log in.
Every adapted project otherwise writes the same token cache — and usually leaks
the token into a log on the first try.

## What's inside

- **`TokenProvider`** — `forRole("admin")` returns a cached token, fetched on
  first use and refreshed shortly before it expires (`refresh-skew`). One fetch
  per role even when parallel tests ask at once. No retry on a 401: a request
  rejected with a fresh token is a finding.
- **Token sources** per role (`type`):
  - `static` — a fixed token from configuration (an environment variable);
  - `client-credentials` / `password` — OAuth2 token endpoint
    (`client_secret_post`), JDK HTTP client, no extra dependency;
  - `local-jwt` — HS256 JWT signed with a shared test secret, for systems under
    test that accept one; no identity provider involved.
- **`@AsUser("admin")`** — on a test method or class (`@Nested` classes inherit
  the outer one). **`AuthContext.runAs` / `callAs`** switch role for a block.
- **`AuthFilter`** — REST Assured filter that adds `Authorization: Bearer …`
  for the current role. The TestForge `ApiClient` applies every `Filter` bean,
  so nothing else is needed. Created only when REST Assured is on the classpath.

## Configuration

```yaml
forge:
  auth:
    enabled: true
    default-role: user          # optional; without it, requests outside @AsUser are anonymous
    header: Authorization       # default
    scheme: Bearer              # default; "" sends the bare token
    refresh-skew: 30s           # default
    request-timeout: 10s        # token endpoint timeout, default
    roles:
      admin:
        type: client-credentials
        token-url: https://idp.staging.example.test/oauth/token
        client-id: tests-admin
        client-secret: ${TESTS_ADMIN_SECRET}
        scope: admin
      user:
        type: local-jwt
        subject: user-1
        claims:
          roles: user
      partner:
        type: static
        token: ${PARTNER_TOKEN}
    local-jwt:
      issuer: testforge         # default
      secret: ${LOCAL_JWT_SECRET}  # at least 32 bytes
      ttl: 15m                  # default
```

Every role is validated at startup: a missing `token-url`, `client-id`,
`username`, `password`, `token` or a short JWT secret fails the context with
one message listing all problems, never naming the secret values.

## Usage

```java
@SpringBootTest
class AccountsTest {

    @Autowired ApiClient api;

    @Test
    @AsUser("admin")
    void adminSeesAllAccounts() {
        api.request().get("/accounts").then().statusCode(200);
    }

    @Test
    @AsUser("user")
    void userCannotCloseSomeoneElsesAccount() {
        String id = AuthContext.callAs("admin", this::createAccount);   // setup as admin
        api.request().delete("/accounts/{id}", id).then().statusCode(403);
    }
}
```

A header the test sets itself is never replaced, so negative tests with an
expired or foreign token stay in the test's control.

`anonymous` is a reserved role: `@AsUser("anonymous")` or
`AuthContext.runAs(AuthContext.ANONYMOUS, ...)` sends no token even when
`default-role` is set — the way to ask what a caller without credentials gets.
Configuring a role with that name fails at startup.

To check every operation × every role at once, use the access matrix in
`module-api-explorer` (`forge.access-matrix`).

## Safety

- `AccessToken.toString()` prints `***`, never the value.
- Token endpoint errors show the status and the standard OAuth2 `error` /
  `error_description`, never the request body (it carries the secret).
- `Authorization` is masked in HTTP logs by `module-http`
  (`forge.http.logging.redact-headers`); the filter runs before logging, so the
  logged request shows the header masked rather than missing.
- Inert unless `forge.auth.enabled=true`.

## Limits

- HS256 only for local JWTs, flat string claims. A JOSE library is the right
  tool for anything more.
- `client_secret_post` only; identity providers that require
  `client_secret_basic` or mTLS need a custom `TokenSource` bean or a project
  `TokenProvider`.
- Role selection is thread-bound, like `ScenarioContext`: code that sends
  requests from another thread must call `AuthContext.runAs` there.

Example: `example-tests/.../AuthExampleTest.java`.
