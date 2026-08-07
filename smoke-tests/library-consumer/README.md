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

The same build can verify GitHub Packages after a release:

```bash
export TESTFORGE_REPOSITORY_USERNAME=<github-login>
export TESTFORGE_REPOSITORY_PASSWORD=<classic-pat-with-read-packages>

./gradlew -p smoke-tests/library-consumer test \
  -PtestforgeGroup=io.github.mickeyorlov.testforge \
  -PtestforgeVersion=1.2.0-beta.1 \
  -PtestforgeRepositoryUrl=https://maven.pkg.github.com/mickeyorlov/TestForge
```

This directory can also be opened as an independent Gradle project in IntelliJ
IDEA to demonstrate the consumer boundary without committing `.idea` files.
