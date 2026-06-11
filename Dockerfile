# CI runner image: JDK + warm Gradle/dependency caches + Chromium for prewarm.
# Build it once, point CI at it, and test jobs stop paying the dependency and
# browser download cost on every run.

FROM eclipse-temurin:26-jdk

ENV GRADLE_USER_HOME=/opt/gradle-home \
    GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.console=plain" \
    PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright

WORKDIR /opt/warmup

# Copy Gradle metadata first so dependency resolution can be cached while
# source files keep changing.
COPY gradle ./gradle
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY core/build.gradle core/build.gradle
COPY module-contract/build.gradle module-contract/build.gradle
COPY module-data/build.gradle module-data/build.gradle
COPY module-db/build.gradle module-db/build.gradle
COPY module-flow/build.gradle module-flow/build.gradle
COPY module-kafka/build.gradle module-kafka/build.gradle
COPY module-mock/build.gradle module-mock/build.gradle
COPY module-reporting/build.gradle module-reporting/build.gradle
COPY module-web/build.gradle module-web/build.gradle
COPY example-tests/build.gradle example-tests/build.gradle

RUN ./gradlew --no-daemon help

COPY . .

# testClasses resolves compile/test configurations into GRADLE_USER_HOME;
# playwrightInstall bakes Chromium and Linux packages used by PrewarmRunner.
RUN ./gradlew --no-daemon testClasses :module-web:playwrightInstall \
    && chmod -R a+rX /opt/gradle-home /opt/ms-playwright \
    && cd / \
    && rm -rf /opt/warmup

WORKDIR /workspace

CMD ["./gradlew", "build"]
