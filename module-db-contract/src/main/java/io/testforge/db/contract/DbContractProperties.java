package io.testforge.db.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the database contract check, under {@code forge.db-contract}.
 *
 * <p>Disabled by default: reading a schema is cheap but not free, and a module
 * that is on the classpath must never start touching a database on its own.
 *
 * @param enabled       whether the check runs at all; the module stays inert until this is true
 * @param datasource    bean name of the datasource to inspect; blank uses the
 *                      default datasource resolved by {@code module-db}
 * @param schema        the schema to inspect
 * @param baselineFile  the committed or CI-provided baseline snapshot to compare against
 * @param outputDir     directory for the current snapshot and the reports
 * @param includeTables full-match regex against plain table names; blank includes everything
 * @param excludeTables full-match regex applied after {@code includeTables}; blank excludes nothing
 * @param failOn        which verdicts fail {@link DbContractRunner#assertCompatible()}
 */
@ConfigurationProperties(prefix = "forge.db-contract")
public record DbContractProperties(
        Boolean enabled,
        String datasource,
        String schema,
        String baselineFile,
        String outputDir,
        String includeTables,
        String excludeTables,
        FailOn failOn) {

    public DbContractProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (schema == null || schema.isBlank()) {
            schema = "public";
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/db-contract";
        }
        if (baselineFile == null || baselineFile.isBlank()) {
            baselineFile = "build/db-contract/baseline/schema-snapshot.json";
        }
        if (failOn == null) {
            failOn = new FailOn(null, null, null);
        }
    }

    /**
     * The CI gate. Breaking changes fail the build by default; risky and
     * un-analysed changes are reported but do not fail until a project opts in,
     * so the first run of a new baseline cannot turn the whole pipeline red.
     *
     * @param breaking fail on {@code BREAKING} changes (default {@code true})
     * @param risky    fail on {@code RISKY} changes (default {@code false})
     * @param unknown  fail on {@code UNKNOWN} changes (default {@code false})
     */
    public record FailOn(Boolean breaking, Boolean risky, Boolean unknown) {

        public FailOn {
            if (breaking == null) {
                breaking = true;
            }
            if (risky == null) {
                risky = false;
            }
            if (unknown == null) {
                unknown = false;
            }
        }
    }
}
