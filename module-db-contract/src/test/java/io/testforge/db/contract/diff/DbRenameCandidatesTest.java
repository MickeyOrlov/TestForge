package io.testforge.db.contract.diff;

import static io.testforge.db.contract.TestSchemas.column;
import static io.testforge.db.contract.TestSchemas.foreignKey;
import static io.testforge.db.contract.TestSchemas.id;
import static io.testforge.db.contract.TestSchemas.index;
import static io.testforge.db.contract.TestSchemas.schema;
import static io.testforge.db.contract.TestSchemas.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.contract.model.DbPrimaryKey;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.schema.ColumnTypeFamily;
import java.util.List;
import org.junit.jupiter.api.Test;

class DbRenameCandidatesTest {

    private static final DbPrimaryKey PK = new DbPrimaryKey("pk", List.of("id"));

    @Test
    void aRemovedAndAnAddedObjectWithTheSameShapeArePaired() {
        DbSchemaSnapshot baseline = schema(
                table("orders", List.of(id(),
                        column("amount", ColumnTypeFamily.DECIMAL, "numeric(10,2)", false),
                        column("customer_id", ColumnTypeFamily.INTEGER, "int8", false)), PK,
                        List.of(foreignKey("fk_orders_customer", List.of("customer_id"), "customers", List.of("id"))),
                        List.of(index("idx_orders_customer", List.of("customer_id"), false))),
                table("customers", List.of(id())),
                table("audit", List.of(id(), column("note", ColumnTypeFamily.CHARACTER, "text", true))));
        DbSchemaSnapshot current = schema(
                table("orders", List.of(id(),
                        column("total", ColumnTypeFamily.DECIMAL, "numeric(10,2)", false),
                        column("customer_id", ColumnTypeFamily.INTEGER, "int8", false)), PK,
                        List.of(foreignKey("orders_customer_id_fkey", List.of("customer_id"), "customers", List.of("id"))),
                        List.of(index("orders_customer_id_idx", List.of("customer_id"), false))),
                table("customers", List.of(id())),
                table("audit_log", List.of(id(), column("note", ColumnTypeFamily.CHARACTER, "text", true))));

        assertThat(DbRenameCandidates.find(baseline, current))
                .extracting(candidate -> candidate.kind() + " " + candidate.describe())
                .containsExactly(
                        "TABLE audit -> audit_log",
                        "COLUMN orders.amount -> orders.total",
                        "INDEX orders.idx_orders_customer -> orders.orders_customer_id_idx",
                        "FOREIGN_KEY orders.fk_orders_customer -> orders.orders_customer_id_fkey");
    }

    @Test
    void aDifferentShapeOrAnAmbiguousPairIsNotGuessed() {
        DbSchemaSnapshot baseline = schema(table("orders", List.of(id(),
                column("a", ColumnTypeFamily.CHARACTER, "text", true),
                column("b", ColumnTypeFamily.CHARACTER, "text", true),
                column("price", ColumnTypeFamily.DECIMAL, "numeric(10,2)", false))));
        DbSchemaSnapshot current = schema(table("orders", List.of(id(),
                column("c", ColumnTypeFamily.CHARACTER, "text", true),
                column("cost", ColumnTypeFamily.DECIMAL, "numeric(12,2)", false))));

        assertThat(DbRenameCandidates.find(baseline, current)).isEmpty();
    }
}
