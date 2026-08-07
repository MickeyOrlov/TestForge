# Library Consumption

TestForge remains template-first, but every production module can also be
published and consumed as an independent Maven artifact. The published model
contains transitive TestForge module dependencies and the Spring Boot BOM.

## Coordinates

The default development coordinates are:

```text
group:   io.testforge
version: 1.2.0-SNAPSHOT
```

Artifact ids match module directory names, for example `core`, `module-http`,
`module-kafka`, and `module-api-discovery`. `example-tests` is intentionally
not published.

Both group and version are overridable:

```bash
./gradlew publishTestForgeLibraries \
  -PtestforgeGroup=io.testforge \
  -PtestforgeVersion=1.2.0
```

## Local repository

Without extra properties, publications are written to
`build/test-maven-repository`:

```bash
./gradlew publishTestForgeLibraries
```

An external Gradle project can then resolve a module normally:

```groovy
repositories {
    maven { url = uri("/path/to/TestForge/build/test-maven-repository") }
    mavenCentral()
}

dependencies {
    testImplementation "io.testforge:module-http:1.2.0-SNAPSHOT"
    testImplementation "io.testforge:module-api-discovery:1.2.0-SNAPSHOT"
}
```

The standalone build under `smoke-tests/library-consumer` proves this path for
both a live REST Assured request and offline OpenAPI discovery. It does not
participate in the root multi-project build and cannot fall back to a Gradle
`project(...)` dependency.

## Remote repository

Set the repository URL as a Gradle property. Credentials come from environment
variables so they do not appear in source files or command history:

```bash
export TESTFORGE_REPOSITORY_USERNAME=<repository-user>
export TESTFORGE_REPOSITORY_PASSWORD=<repository-token>

./gradlew publishTestForgeLibraries \
  -PtestforgeRepositoryUrl=https://maven.pkg.github.com/MickeyOrlov/TestForge \
  -PtestforgeVersion=1.2.0
```

This is sufficient for an authenticated Maven repository such as GitHub
Packages. A public Maven Central release still needs a verified namespace,
artifact signing, and Central Portal publication configuration.

## Published artifacts

Each module publication contains:

- binary JAR;
- sources JAR;
- javadocs JAR;
- Maven POM;
- Gradle module metadata.

Spring Boot auto-configuration metadata stays inside the binary JAR, so adding
a module dependency is enough for Spring Boot to discover its beans.
