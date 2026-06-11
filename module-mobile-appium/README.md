# module-mobile-appium

Appium bootstrap for mobile tests: a configured driver factory and a
try-with-resources session wrapper.

## What's inside

- **`AppiumDriverFactory`** — builds platform-appropriate drivers
  (Android/iOS) from properties; fails fast with a readable message on a
  broken hub URL.
- **`AppiumSession`** — `AutoCloseable` wrapper: one session = one test,
  `close()` quits the driver and releases the device slot even on failure.

## Configuration

```yaml
forge:
  mobile:
    appium:
      enabled: true                      # opt-in
      hub-url: http://localhost:4723    # Appium server / device farm endpoint
      platform-name: Android            # Android | iOS
      device-name: emulator-5554
      app-path: /path/to/app.apk
      automation-name: UiAutomator2
```

Device-farm credentials are environment configuration (env vars / secret
manager), never code.

## Usage

```java
try (AppiumSession session = appiumDriverFactory.startSession()) {
    AppiumDriver driver = session.driver();
    // screen objects work with the driver
}
```

A template cannot assume an Appium server or devices, so example-tests only
verify the wiring (`AppiumWiringTest`); real mobile suites belong to the
adapted project.

## Agent notes

- Keep the module deletable: no other module may depend on it.
- Never start sessions in the default build — there is no server to talk to.
- Screen-object conventions live in the adapted project; this module only
  owns driver lifecycle. Pair each session with try-with-resources.
- java-client major versions track Selenium majors pinned by the Boot BOM —
  bump them together (see gradle/libs.versions.toml note).
