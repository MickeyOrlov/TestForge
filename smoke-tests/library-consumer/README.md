# Library consumer smoke test

This is a standalone Gradle build. It is deliberately absent from the root
`settings.gradle`, so it cannot resolve TestForge through `project(...)`.

From the repository root:

```bash
./gradlew publishTestForgeLibraries
./gradlew -p smoke-tests/library-consumer test
```

The tests resolve `io.testforge:module-http`,
`io.testforge:module-api-discovery`, and `io.testforge:module-api-codegen` from
`build/test-maven-repository`. They load Spring Boot auto-configuration from
the published JARs, call WireMock through `ApiClient`, verify the generated
correlation header, build an OpenAPI endpoint/shape report, and generate Java
records plus a typed API client from a classpath specification.

`PublishedLoadSmokeTest` also resolves `module-load` from Maven, verifies that it is
inert by default and that its auto-configuration respects a consumer-supplied process
bean. No k6 binary is needed for this consumer test.

The same build can verify GitHub Packages after a release:

```bash
export TESTFORGE_REPOSITORY_USERNAME=<github-login>
export TESTFORGE_REPOSITORY_PASSWORD=<classic-pat-with-read-packages>

./gradlew -p smoke-tests/library-consumer test \
  -PtestforgeGroup=io.github.mickeyorlov.testforge \
  -PtestforgeVersion=1.2.0 \
  -PtestforgeRepositoryUrl=https://maven.pkg.github.com/mickeyorlov/TestForge
```

This directory can also be opened as an independent Gradle project in IntelliJ
IDEA to demonstrate the consumer boundary without committing `.idea` files.

`PublishedA11ySmokeTest` consumes the published module-a11y JAR/POM and verifies opt-in
auto-configuration and impact binding without launching a browser.
`PublishedFaultsSmokeTest` consumes the published module-faults JAR/POM and verifies opt-in
auto-configuration and named upstream binding without starting Docker or a proxy.

`PublishedMailSmokeTest` consumes the published module-mail JAR/POM and verifies opt-in binding without connecting to Mailpit.
