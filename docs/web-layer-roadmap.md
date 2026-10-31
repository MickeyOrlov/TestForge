# Web prewarm roadmap

`module-web` visits the heaviest pages once before the suite, so UI tests do
not meet a cold environment. It said nothing about what it found: a page
answering 502, or bouncing to a sign-in page, counted as warmed, and the suite
then failed for half an hour on the reason prewarm had already seen. It also
always needed a Chromium download, even where an HTTP request would warm the
caches that matter, and no test exercised the real visiting path.

All three steps shipped (PRs #96–#98).

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **What each page answered** — status, redirect, time per page in the log and `prewarm.md`; opt-in stop when not one page answers | `module-web` | shipped |
| 2 | **HTTP mode** — warm with plain GETs, no browser download; exercised offline end to end | `module-web`, `example-tests` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-web` | shipped |

## Step 1 — what each page answered

Each visit records its status, where it ended (a redirect to a sign-in page
shows as "ended at"), its time and its error; the log has one line per page
and `prewarm.md` goes to the run index. URLs are redacted first — basic-auth
credentials in a staging URL are a common shortcut, signed links carry tokens
in the query. Prewarm still never fails the suite by default.
`forge.prewarm.fail-when-unreachable=true` stops it at startup when not one
page answered (every visit failed or got a 5xx): the environment is down, and
every test would otherwise fail slowly for that one reason. A browser that
cannot start says nothing about the environment and never trips it.

## Step 2 — HTTP mode

`forge.prewarm.mode=http` (`HttpPrewarmEngine`) sends one GET per page with
the JDK client, following redirects, and records the same status, final URL
and time. Server-side rendering, CDN and application caches and connection
pools warm; the page's scripts do not run, so lazily compiled bundles stay
cold — which is why `browser` stays the default. It needs no Chromium, and it
is what lets `PrewarmExampleTest` run the real path offline against WireMock,
including a page that redirects to a sign-in wall.

## Step 3 — review

- **Credentials in error messages.** URLs were redacted before reporting, but
  a failed navigation's error is Playwright's own text, and it quotes the URL
  it was navigating to — `qa:s3cret@` included. Errors are scrubbed to the
  redacted URL before they reach the log, `prewarm.md` or the exception.
- **Echoes read as redirects.** A browser reports `https://s.test` back as
  `https://s.test/`; the report said the page ended somewhere else. URLs are
  compared normalized (scheme and host case, default port, empty path).
- **A second context skipped silently.** The guard was "once per JVM", so a
  context for another profile or environment never warmed its own pages. It
  is now each page once per JVM.
