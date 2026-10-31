package io.testforge.db.schema;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class EntityMappingTest {

    @Entity
    static class LineItem {
        @Id
        private Long id;

        private String productCode;

        @Column(name = "qty")
        private Integer quantity;
    }

    /** Never connected to: resolving a mapping reads annotations only. */
    private final SchemaValidator validator = new SchemaValidator((DataSource) null);

    @Test
    void theMappingFollowsTheNamingStrategyAndExplicitColumnNames() {
        EntityMapping mapping = validator.mapping(LineItem.class);

        assertThat(mapping.entity()).isEqualTo("LineItem");
        assertThat(mapping.table()).isEqualTo("line_item");
        assertThat(mapping.columns()).containsExactly("id", "product_code", "qty");
    }

    @Test
    void matchingIgnoresTheCaseADatabaseReportsIdentifiersIn() {
        EntityMapping mapping = validator.mapping(LineItem.class);

        assertThat(mapping.mapsTable("LINE_ITEM")).isTrue();
        assertThat(mapping.mapsColumn("LINE_ITEM", "QTY")).isTrue();
        assertThat(mapping.mapsColumn("LINE_ITEM", "quantity")).isFalse();
        assertThat(mapping.mapsColumn("orders", "qty")).isFalse();
    }
}
