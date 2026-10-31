# Mobile (Appium) roadmap

`module-mobile-appium` resolves a device matrix, opens sessions lazily, injects
them into tests and captures a screenshot and page source on failure. A red
mobile test still left the important questions open — which device and which
capabilities, did the app crash (that is in `logcat`, not the screenshot) —
and the optional local node had a pipe nobody read and no word on why it did
not come up.

| Step | What | Where | Status |
|---|---|---|---|
| 1 | **Which session, and what the device said** — `session.md` with redacted capabilities, the device log's tail, grouped with the test's other reports | `module-mobile-appium` | shipped |
| 2 | **A local node that cannot hang or die silently** — its output to a log file, an early exit reported at once with the log's tail | `module-mobile-appium` | planned |
| 3 | **Review** — close the gaps a review of the module finds | `module-mobile-appium` | planned |

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
