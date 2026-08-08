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

## GitHub Packages

GitHub Packages is the public distribution target. Publishing is deliberately
manual: a normal push or pull request never deploys artifacts. Run the
`Publish Maven packages` workflow from the Actions tab and provide a version,
or publish locally with a classic personal access token that has
`write:packages`:

```bash
export TESTFORGE_REPOSITORY_USERNAME=MickeyOrlov
export TESTFORGE_REPOSITORY_PASSWORD=<classic-pat>

./gradlew publishTestForgeLibraries \
  -PtestforgeGroup=io.github.mickeyorlov.testforge \
  -PtestforgeRepositoryUrl=https://maven.pkg.github.com/mickeyorlov/TestForge \
  -PtestforgeVersion=1.2.0-beta.2
```

The workflow uses its repository-scoped `GITHUB_TOKEN`; no long-lived secret is
stored in the repository. GitHub's Maven/Gradle registry requires
authentication for downloads even when both the repository and package are
public. Consumers need a classic token with `read:packages`.

### Gradle consumer

Keep credentials in `~/.gradle/gradle.properties`:

```properties
testforgeRepositoryUsername=YOUR_GITHUB_LOGIN
testforgeRepositoryPassword=YOUR_CLASSIC_PAT
```

Then configure the repository and dependency:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/mickeyorlov/TestForge")
        credentials {
            username = providers.gradleProperty("testforgeRepositoryUsername").get()
            password = providers.gradleProperty("testforgeRepositoryPassword").get()
        }
    }
    mavenCentral()
}

dependencies {
    testImplementation "io.github.mickeyorlov.testforge:module-http:1.2.0-beta.2"
    testImplementation "io.github.mickeyorlov.testforge:module-api-codegen:1.2.0-beta.2"
    testImplementation "io.github.mickeyorlov.testforge:module-api-explorer:1.2.0-beta.2"
}
```

The standalone consumer can verify the remote package without a Gradle project
dependency:

```bash
./gradlew -p smoke-tests/library-consumer test \
  -PtestforgeGroup=io.github.mickeyorlov.testforge \
  -PtestforgeVersion=1.2.0-beta.2 \
  -PtestforgeRepositoryUrl=https://maven.pkg.github.com/mickeyorlov/TestForge
```

### Maven consumer and `settings.xml`

Maven users can start from
[`docs/examples/github-packages-settings.xml`](examples/github-packages-settings.xml).
It reads credentials from `GITHUB_ACTOR` and `GITHUB_TOKEN`; the token needs
`read:packages`.

```xml
<repositories>
  <repository>
    <id>github-testforge</id>
    <url>https://maven.pkg.github.com/mickeyorlov/TestForge</url>
  </repository>
</repositories>

<dependency>
  <groupId>io.github.mickeyorlov.testforge</groupId>
  <artifactId>module-http</artifactId>
  <version>1.2.0-beta.2</version>
  <scope>test</scope>
</dependency>
```

### Direct artifact URL

GitHub Packages exposes the standard Maven layout, so an individual artifact
has a stable URL. Authentication is still mandatory:

```bash
curl --fail --location \
  --user "$GITHUB_ACTOR:$GITHUB_TOKEN" \
  --output module-http-1.2.0-beta.2.jar \
  https://maven.pkg.github.com/mickeyorlov/TestForge/io/github/mickeyorlov/testforge/module-http/1.2.0-beta.2/module-http-1.2.0-beta.2.jar
```

For anonymous downloads, use Maven Central or attach the JARs to a GitHub
Release. GitHub's Maven registry does not allow anonymous package downloads.

## IntelliJ IDEA

Open the repository root as a Gradle project and select JDK 21. IntelliJ imports
every TestForge module as a Java library module. To inspect the real external
consumer boundary separately, open `smoke-tests/library-consumer` in a second
IDEA window; that build resolves Maven coordinates and cannot fall back to
`project(...)` dependencies. Do not commit `.idea` files.

A public Maven Central release still needs a verified namespace, artifact
signing, and Central Portal publication configuration.

## Published artifacts

Each module publication contains:

- binary JAR;
- sources JAR;
- javadocs JAR;
- Maven POM;
- Gradle module metadata.

Spring Boot auto-configuration metadata stays inside the binary JAR, so adding
a module dependency is enough for Spring Boot to discover its beans.
