# Library consumer smoke test

This is a standalone Gradle build. It is deliberately absent from the root
`settings.gradle`, so it cannot resolve TestForge through `project(...)`.

From the repository root:

```bash
./gradlew publishTestForgeLibraries
./gradlew -p smoke-tests/library-consumer test
```

The tests resolve `io.testforge:module-http` and
`io.testforge:module-api-discovery` from `build/test-maven-repository`. They
load Spring Boot auto-configuration from the published JARs, call WireMock
through `ApiClient`, verify the generated correlation header, and build an
OpenAPI endpoint/shape report from a classpath specification.
