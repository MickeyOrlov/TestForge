# Real application toolbox check

Standalone opt-in JUnit harness consuming published TestForge artifacts. It launches an unmodified Spring Petclinic JAR on loopback, owns PostgreSQL/Toxiproxy containers, tests the database fault path and scans an existing Playwright Page with axe. No production module depends on this harness. Requires Docker, JDK 21 and the browser matching the published module-a11y dependency.

From the TestForge repository root:

```bash
./gradlew publishTestForgeLibraries
./gradlew :module-web-playwright:playwrightInstall
# In a separate scratch location:
git clone https://github.com/spring-projects/spring-petclinic.git /tmp/testforge-petclinic
git -C /tmp/testforge-petclinic checkout 500158f732419217507c7656904b8e6aa1bcc0d6
(cd /tmp/testforge-petclinic && ./mvnw -q -DskipTests package)
# Back in TestForge:
./gradlew -p tools/toolbox-dogfood test \
  -PpetclinicJar=/tmp/testforge-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar
```

The SUT build skips its own tests; this harness checks only the scoped integrations described in the [report](../../docs/research/toolbox-dogfood-petclinic.md). Outputs stay in this standalone build/evidence directory. `application-private.log` is a local diagnostic, never an artifact to publish blindly. Test HTTP status/timing is not an SLO or a guarantee of graceful recovery under other failures. This project pins the tested source commit; a different input JAR may produce different native findings. Versions of published modules are explicit in build.gradle.
