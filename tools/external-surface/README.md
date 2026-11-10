# External web inventory

Start with a DNS domain to collect passive candidates. Add reviewed HTTP(S) origins to probe
reachability and crawl anonymous routes. The three native tools do the discovery and probing.
TestForge supplies scope, bounded execution and a private inventory report.

Install Node.js 22+ and the official native CLIs for Linux or macOS. Verify the release archive
against the checksums published with each release and extract the executables into one directory.

| Executable | Pinned release |
|---|---|
| `subfinder` | [2.17.0](https://github.com/projectdiscovery/subfinder/releases/tag/v2.17.0) |
| `httpx` | [1.12.0](https://github.com/projectdiscovery/httpx/releases/tag/v1.12.0) |
| `katana` | [1.8.0](https://github.com/projectdiscovery/katana/releases/tag/v1.8.0) |

Each requested stage checks its CLI version. Run from the TestForge root.

```bash
./gradlew :example-tests:externalSurface \
  -PsurfaceScope=/path/to/scope.json \
  -PsurfaceToolsDir=/absolute/path/to/tools
```

For a passive pass, use only the domain.

```json
{"domain": "example.test"}
```

Review the candidates and add only the exact origins approved for active GET requests. Scheme,
host and port are part of the approval. Set `discover` to `false` to reuse an approved list without
querying passive sources again.

```json
{
  "domain": "example.test",
  "discover": false,
  "allowedOrigins": ["https://example.test", "https://api.example.test:8443"]
}
```

Subfinder uses its native passive providers, which can receive the selected domain. It uses an
empty provider configuration. Coverage depends on available providers, and an empty candidate
list only says the run returned no names. Candidates never expand the active scope.

httpx probes the root of each approved origin with GET, preserves the specified scheme and
leaves redirects unfollowed. Its HTTPS result means an HTTP response arrived. Use the separate
TLS assessment to verify the server's certificate and protocols.

Katana uses a loopback gateway for each responding origin with a non-redirecting root. The
gateway permits GET and query-free paths and forwards requests to the approved origin. It
forwards only the upstream content type and body for parsing. Response cookies and redirect
locations are discarded. Absolute links to the original origin are outside this first version's
loopback crawl scope. Headless browsing, form filling and JavaScript crawling are left off.
The gateway limits each origin to 40 upstream requests, with four seconds and 256 KiB per
response. Native crawling also has a depth of two, a page cap and a time limit.

Origin scope uses DNS as seen from the runner. Use a runner with the network egress restrictions
your environment requires when enforcing IP-level boundaries. Approve only origins where a
bounded anonymous GET crawl is suitable. `localhost` is supported with passive discovery disabled
for owned fixtures.

Each invocation creates `build/external-surface/<run-id>/surface.json`. It records candidate
hostnames and providers, confirmed origin responses, routes with upstream statuses and the stage
states. `no-response` records a failed probe attempt. `not-measured` marks missing probe evidence.
An incomplete requested stage gives exit 2 while preserving available inventory. A completed
bounded run gives exit 0. Neither result is a vulnerability verdict. Confirmed targets can be
reviewed and supplied separately to the existing security jobs.

Native output, bodies, headers, cookies and query values stay out of the inventory. Literal route
paths can still contain identifiers, so keep the report private and review it before sharing.
Default build and tests leave this workflow idle.

```bash
./gradlew :example-tests:externalSurfaceTest
./gradlew :example-tests:externalSurfaceAcceptance -PsurfaceToolsDir=/absolute/path/to/tools
```

The first task replays passive output and checks scope and normalization. Native acceptance runs
httpx and Katana against owned loopback servers, including a redirect, a same-host link to
another port, an HTMX POST and a query with a synthetic secret.
