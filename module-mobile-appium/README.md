# module-mobile-appium

Appium lifecycle for mobile tests: device matrix, fixture injection, optional
local node startup and failure artifacts.

## What's inside

- **`AppiumDeviceRegistry` / `ResolvedAppiumDevice`** — resolves the selected
  device from `devices.<id>`, `default-device`, or legacy flat properties.
- **`AppiumCapabilitiesMapper`** — maps YAML properties to W3C/Appium
  capabilities and preserves raw `extra-capabilities` for remote providers.
- **`AppiumDriverFactory`** — starts Android/iOS sessions from resolved
  devices.
- **`AppiumSession`** — one session = one test; stores driver, device metadata
  and artifact root, then quits the driver on close.
- **`AppiumExtension` / `@MobileDevice`** — JUnit fixture injection for
  `AppiumSession` or `AppiumDriver`.
- **`AppiumArtifactCollector`** — captures `screenshot.png` and
  `page-source.xml` on failure when enabled.
- **`AppiumNodeManager`** — optional local `appium` process lifecycle; disabled
  unless `node.auto-start=true`.

`forge.mobile.appium.enabled=true` only creates beans. A real session starts
only when a test requests `AppiumSession`/`AppiumDriver` or calls the factory.

## Configuration

```yaml
forge:
  mobile:
    appium:
      enabled: true
      hub-url: http://localhost:4723
      default-device: android-local
      artifacts-on-failure: true
      artifacts-dir: build/appium-artifacts
      node:
        auto-start: true
        command: appium
        args: ["--base-path", "/"]
        startup-timeout: 30s
        status-path: /status
      devices:
        android-local:
          platform-name: Android
          device-name: emulator-5554
          automation-name: UiAutomator2
          app-path: /apps/demo.apk
        remote-android:
          platform-name: Android
          device-name: Google Pixel 8
          automation-name: UiAutomator2
          app-path: storage:filename=demo.apk
          extra-capabilities:
            appium:platformVersion: "15"
            provider:options:
              projectName: TestForge
              buildName: mobile-ci
```

Legacy flat properties still work for simple projects:

```yaml
forge:
  mobile:
    appium:
      enabled: true
      hub-url: http://localhost:4723
      platform-name: Android
      device-name: emulator-5554
      app-path: /apps/demo.apk
      automation-name: UiAutomator2
```

## Usage

```java
@SpringBootTest(properties = "forge.mobile.appium.enabled=true")
@ExtendWith(AppiumExtension.class)
class LoginMobileIT {

    @Test
    void logsIn(@MobileDevice("android-local") AppiumSession session) {
        AppiumDriver driver = session.driver();
        // screen objects work with the driver
    }

    @Test
    void opensHome(@MobileDevice("android-local") AppiumDriver driver) {
        // direct driver injection is also supported
    }
}
```

For explicit lifecycle control:

```java
try (AppiumSession session = appiumDriverFactory.startSession("android-local")) {
    AppiumDriver driver = session.driver();
}
```

## Validation

- Android requires `app-path` or `app-package` + `app-activity`.
- iOS requires `app-path` or `bundle-id`.
- `app-path` is a string on purpose: remote providers often use values such as
  `storage:filename=demo.apk`.

## Agent notes

- Keep the module deletable: no other module may depend on it.
- Real devices belong in explicit mobile profiles/tags, not the default build.
- `enabled=true` does not open a session; tests do that lazily.
- Local node startup is opt-in (`node.auto-start=true`) and never installs
  Appium/npm.
- Screen-object conventions live in the adapted project; do not add base
  screen/page classes to the template.
- Remote providers use `extra-capabilities`; do not add provider-specific
  clients in v1.
