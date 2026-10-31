package io.testforge.db.contract.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.contract.diff.DbChangeType;
import io.testforge.db.schema.EntityMapping;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DbSchemaUsageTest {

    private final DbSchemaUsage usage = DbSchemaUsage.ofMappings(List.of(
            new EntityMapping("Order", "orders", List.of("id", "amount", "status")),
            new EntityMapping("OrderSummary", "orders", List.of("id", "status")),
            new EntityMapping("Customer", "customers", List.of("id"))));

    @Test
    void aColumnChangeReachesTheEntitiesMappingThatColumn() {
        assertThat(usage.usersOf(new DbChange(DbChangeType.COLUMN_REMOVED, "ORDERS", "AMOUNT", "x", null)))
                .containsExactly("Order");
        assertThat(usage.usersOf(new DbChange(DbChangeType.COLUMN_NULLABILITY_TIGHTENED, "orders", "status", "a", "b")))
                .containsExactly("Order", "OrderSummary");
        assertThat(usage.usersOf(new DbChange(DbChangeType.COLUMN_REMOVED, "orders", "legacy", "x", null)))
                .isEmpty();
    }

    @Test
    void aNewColumnOrAnyOtherChangeReachesEveryEntityOfTheTable() {
        // a NOT NULL column without a default breaks every insert an entity of the table makes
        assertThat(usage.usersOf(new DbChange(DbChangeType.COLUMN_ADDED, "orders", "tenant", null, "x")))
                .containsExactly("Order", "OrderSummary");
        assertThat(usage.usersOf(DbChange.ofTable(DbChangeType.TABLE_REMOVED, "customers", "1 column(s)", null)))
                .containsExactly("Customer");
        assertThat(usage.usersOf(new DbChange(DbChangeType.FOREIGN_KEY_ADDED, "orders", "fk", null, "x")))
                .containsExactly("Order", "OrderSummary");
        assertThat(usage.usersOf(DbChange.ofTable(DbChangeType.TABLE_ADDED, "invoices", null, "1 column(s)")))
                .isEmpty();
    }

    @Entity
    @Table(name = "orders")
    static class OrderEntity {
        @Id
        private Long id;

        @Column(name = "amount")
        private Long amount;
    }

    @Test
    void theJpaUsageReadsTheMetamodelOnceAndOnlyWhenAsked() {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        Metamodel metamodel = mock(Metamodel.class);
        @SuppressWarnings("unchecked")
        EntityType<OrderEntity> type = mock(EntityType.class);
        when(type.getJavaType()).thenReturn(OrderEntity.class);
        when(metamodel.getEntities()).thenReturn(Set.of(type));
        when(factory.getMetamodel()).thenReturn(metamodel);

        JpaEntityUsage jpa = new JpaEntityUsage(() -> List.of(factory), () -> null);
        verify(factory, never()).getMetamodel();

        DbChange removed = new DbChange(DbChangeType.COLUMN_REMOVED, "ORDERS", "AMOUNT", "x", null);
        assertThat(jpa.usersOf(removed)).containsExactly("OrderEntity");
        assertThat(jpa.usersOf(removed)).containsExactly("OrderEntity");
        verify(factory, times(1)).getMetamodel();
    }

    @Test
    void withoutAPersistenceUnitNothingIsReported() {
        JpaEntityUsage jpa = new JpaEntityUsage(List::of, () -> null);

        assertThat(jpa.usersOf(DbChange.ofTable(DbChangeType.TABLE_REMOVED, "orders", "x", null))).isEmpty();
    }
}
