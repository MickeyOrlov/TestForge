# Auth layer roadmap

`module-auth` turns a role into a token. What tests still lack is the other
half: checking that roles get *only* what they should, more ways in than
OAuth2 and JWT, and seeing which role a failed request ran as.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Access matrix** — every operation × every role → declared rule; violations fail the job, undeclared cells get a proposal | `module-api-explorer` (it already plans requests from the document), reserved `anonymous` role in `module-auth` | shipped |
| 2 | **More ways in** — API key (header or query) and session-cookie login through a login request, cached like tokens | `module-auth` | planned |
| 3 | **Role in diagnostics** — which role and credential kind each request used, in failed-test HTTP diagnostics; secrets redacted | `module-auth`, `module-http` | planned |
| 4 | **Beta** — close the gaps a review of the module finds, then move it from Experimental to Beta in the roadmap | `module-auth`, `docs/ROADMAP.md` | planned |

Why the matrix lives in the explorer: it needs the document, parameter values
and the method safety policy the explorer already owns. `module-auth` stays
free of OpenAPI; the explorer gains one edge, to `module-auth`, for
`AuthContext`.
