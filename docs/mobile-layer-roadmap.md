# Mobile (Appium) roadmap

`module-mobile-appium` resolves a device matrix, opens sessions lazily, injects
them into tests and captures a screenshot and page source on failure. A red
mobile test still left the important questions open — which device and which
capabilities, did the app crash (that is in `logcat`, not the screenshot) —
and the optional local node had a pipe nobody read and no word on why it did
not come up.

All three steps shipped (PRs #102–#104).

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Which session, and what the device said** — `session.md` with redacted capabilities, the device log's tail, grouped with the test's other reports | `module-mobile-appium` | shipped |
| 2 | **A local node that cannot hang or die silently** — its output to a log file, an early exit reported at once with the log's tail | `module-mobile-appium` | shipped |
| 3 | **Review** — close the gaps a review of the module finds | `module-mobile-appium` | shipped |

## Step 1 — which session, and what the device said

On failure the collector now writes, next to the screenshot and page source:

- `session.md` — device id, platform and version, device name, automation,
  hub (user info dropped), session id, and the capabilities sent with every
  value under a secret-looking key masked (`provider:options.accessKey` and
  the like). Written first: it needs nothing from a device that may be gone.
- `device.log` — the last 200 lines of `logcat` (Android) or `syslog` (iOS),
  where a crash or ANR behind a frozen screen shows up.

Every artifact carries the test's `testKey` (`<Class>-<method>`), so the run
summary groups it with the same test's HTTP, SQL and Kafka reports.

## Step 2 — a local node that cannot hang or die silently

`DefaultProcessLauncher` merged the node's stderr into stdout and never read
either. A pipe holds a few dozen kilobytes; Appium logs every command, so a
longer suite filled it and the node blocked on its next write — sessions then
hung mid-run. The node now writes to `<artifacts-dir>/appium-node.log`
(`ProcessLauncher.launch(command, log)`; custom launchers keep working through
the default method), and the log joins the run index when the node stops.

The readiness wait also watches the process: a node that exits (port taken,
driver missing, wrong Node version) fails the start at once with its exit code
and the last 20 lines of its log, and a timeout carries the same tail.

## Step 3 — review

- **Secrets in session errors.** Selenium's `SessionNotCreatedException`
  quotes every capability it sent, so a device cloud's `accessKey` landed in
  the test report of any session that failed to start — the case where it is
  read most. The factory now reports the device, the redacted hub, the
  exception type and its first line with every secret capability value and
  the hub's user info replaced, and does not attach the original exception.
- **Hub URLs echoed.** "Invalid Appium hub URL: …" repeated the value as
  configured, credentials included, and so did the parser's own message in
  the cause. The error now states the parser's reason only.
- **Lifecycle failures left nothing.** A sign-in through an injected session
  failing in `@BeforeEach` produced no screenshot, page source or session
  summary; before/after-each failures are captured too, once per test
  (`AppiumLifecycleFailureExampleTest`).
