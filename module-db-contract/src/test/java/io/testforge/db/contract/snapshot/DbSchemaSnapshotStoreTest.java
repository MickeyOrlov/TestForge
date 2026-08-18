package io.testforge.db.contract.snapshot;

import static io.testforge.db.contract.TestSchemas.column;
import static io.testforge.db.contract.TestSchemas.id;
import static io.testforge.db.contract.TestSchemas.schema;
import static io.testforge.db.contract.TestSchemas.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.contract.model.DbForeignKey;
import io.testforge.db.contract.model.DbIndex;
import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.schema.ColumnTypeFamily;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbSchemaSnapshotStoreTest {

    private final DbSchemaSnapshotStore store = new DbSchemaSnapshotStore();

    private static final DbSchemaSnapshot SNAPSHOT = schema(table("orders",
            List.of(id(), column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true, true)),
            new DbPrimaryKey("orders_pkey", List.of("id")),
            List.of(new DbForeignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
            List.of(new DbIndex("idx_orders_status", List.of("status"), false))));

    @Test
    void roundTrip_preservesEveryModelledDetail(@TempDir Path dir) {
        Path file = dir.resolve("snapshot.json");

        store.write(file, SNAPSHOT);

        assertThat(store.read(file)).isEqualTo(SNAPSHOT);
    }

    @Test
    void snapshotsAreWrittenIntoDirectoriesThatDoNotExistYet(@TempDir Path dir) {
        Path file = dir.resolve("nested/deeper/snapshot.json");

        store.write(file, SNAPSHOT);

        assertThat(file).exists();
    }

    @Test
    void repeatedCapturesOfTheSameSchema_areByteIdentical(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.json");
        Path second = dir.resolve("second.json");

        store.write(first, SNAPSHOT);
        // the same schema read in a different order must still serialize identically
        store.write(second, schema(table("orders",
                List.of(column("status", ColumnTypeFamily.CHARACTER, "varchar(32)", true, true), id()),
                new DbPrimaryKey("orders_pkey", List.of("id")),
                List.of(new DbForeignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
                List.of(new DbIndex("idx_orders_status", List.of("status"), false)))));

        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(first));
    }

    @Test
    void snapshotJson_carriesNoTimestampThatWouldChurnGitDiffs() {
        String json = store.toJson(SNAPSHOT);

        assertThat(json)
                .doesNotContain("generatedAt")
                .doesNotContain("timestamp")
                .contains("\"formatVersion\" : 1")
                .endsWith("\n");
    }

    @Test
    void snapshotsUseLfLineEndingsOnEveryPlatform(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("snapshot.json");
        store.write(file, SNAPSHOT);

        String json = store.toJson(SNAPSHOT);
        String written = Files.readString(file);

        // Jackson's default indenter and System.lineSeparator() both follow the
        // host OS. If either creeps back in, a baseline captured on Linux and
        // re-captured on Windows differs on every single line.
        assertThat(json).doesNotContain("\r");
        assertThat(written).doesNotContain("\r");
        assertThat(json).endsWith("}\n");
        assertThat(json.lines().count()).isGreaterThan(1);
    }

    @Test
    void readingAMissingSnapshot_failsWithThePath(@TempDir Path dir) {
        Path missing = dir.resolve("absent.json");

        assertThatThrownBy(() -> store.read(missing))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("absent.json");
    }
}
