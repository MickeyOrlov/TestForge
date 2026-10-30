package io.testforge.db.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.TestForgeDbAutoConfiguration;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaInheritanceTest {

    private static DataSource dataSource;
    private static SchemaValidator validator;

    // --- mapped superclass -------------------------------------------------

    @MappedSuperclass
    static class Audited {
        @Id
        Long id;

        @Column(nullable = false)
        Instant createdAt;
    }

    @Entity
    @Table(name = "orders")
    static class Order extends Audited {
        String number;
    }

    @Entity
    @Table(name = "orders_without_audit")
    static class OrderMissingAudit extends Audited {
        String number;
    }

    @Entity
    @Table(name = "renamed_audit")
    @AttributeOverride(name = "createdAt", column = @Column(name = "created", nullable = false))
    static class RenamedAudit extends Audited {
        String number;
    }

    // --- single table --------------------------------------------------------

    @Entity
    @Table(name = "vehicles")
    @DiscriminatorColumn(name = "kind")
    static class Vehicle {
        @Id
        Long id;

        Integer wheels;
    }

    @Entity
    static class Truck extends Vehicle {
        Integer payload;
    }

    @Entity
    @Table(name = "vehicles_no_kind")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    static class LegacyVehicle {
        @Id
        Long id;
    }

    @Entity
    static class LegacyTruck extends LegacyVehicle {
        Integer payload;
    }

    // --- joined --------------------------------------------------------------

    @Entity
    @Table(name = "accounts")
    @Inheritance(strategy = InheritanceType.JOINED)
    static class Account {
        @Id
        Long id;

        String owner;
    }

    @Entity
    @Table(name = "savings_accounts")
    static class Savings extends Account {
        Double rate;
    }

    @Entity
    @Table(name = "checking_accounts")
    @PrimaryKeyJoinColumn(name = "account_id")
    static class Checking extends Account {
        Integer overdraft;
    }

    // --- table per class -----------------------------------------------------

    @Entity
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    static class Shape {
        @Id
        Long id;

        String color;
    }

    @Entity
    @Table(name = "circles")
    static class Circle extends Shape {
        Double radius;
    }

    // --- naming --------------------------------------------------------------

    @Entity
    static class LineItem {
        @Id
        Long id;

        String orderNumber;
    }

    @Entity(name = "Invoice")
    static class InvoiceEntity {
        @Id
        Long id;
    }

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:schema_inheritance_test;DB_CLOSE_DELAY=-1");
        validator = new SchemaValidator(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement()) {
            stmt.execute("create table orders (id bigint not null primary key, created_at timestamp not null, number varchar(20))");
            stmt.execute("create table orders_without_audit (id bigint not null primary key, number varchar(20))");
            stmt.execute("create table renamed_audit (id bigint not null primary key, created timestamp not null, number varchar(20))");
            stmt.execute("create table vehicles (id bigint not null primary key, kind varchar(31), wheels int, payload int)");
            stmt.execute("create table vehicles_no_kind (id bigint not null primary key, payload int)");
            stmt.execute("create table accounts (id bigint not null primary key, owner varchar(50))");
            stmt.execute("create table savings_accounts (id bigint not null primary key, rate double)");
            stmt.execute("create table checking_accounts (account_id bigint not null primary key, overdraft int)");
            stmt.execute("create table circles (id bigint not null primary key, color varchar(20), radius double)");
            stmt.execute("create table LineItem (id bigint not null primary key, orderNumber varchar(20))");
            stmt.execute("create table invoice (id bigint not null primary key)");
        }
    }

    @Test
    void mappedSuperclassFieldsBelongToTheEntityTable() {
        assertThat(validator.schemaDrift(Order.class)).isEmpty();
        assertThat(validator.missingColumns(OrderMissingAudit.class))
                .containsExactly("orders_without_audit.created_at");
    }

    @Test
    void inheritedMappingsKeepTheirNullability() {
        assertThat(validator.expectedColumns(Order.class))
                .filteredOn(column -> column.name().equals("created_at"))
                .singleElement()
                .satisfies(column -> assertThat(column.declaredNotNull()).isTrue());
    }

    @Test
    void aClassLevelAttributeOverrideRenamesAnInheritedColumn() {
        assertThat(validator.schemaDrift(RenamedAudit.class)).isEmpty();
    }

    @Test
    void singleTableSubclassesValidateAgainstTheRootTableWithTheDiscriminator() {
        assertThat(validator.tableName(Truck.class)).isEqualTo("vehicles");
        assertThat(validator.schemaDrift(Truck.class)).isEmpty();
        assertThat(validator.schemaDrift(Vehicle.class)).isEmpty();
    }

    @Test
    void aMissingDiscriminatorColumnIsReported() {
        assertThat(validator.missingColumns(LegacyTruck.class)).containsExactly("vehicles_no_kind.DTYPE");
    }

    @Test
    void joinedSubclassesValidateOnlyTheirOwnTablePlusTheKey() {
        assertThat(validator.tableName(Savings.class)).isEqualTo("savings_accounts");
        assertThat(validator.schemaDrift(Savings.class)).as("owner lives in accounts").isEmpty();
        assertThat(validator.schemaDrift(Account.class)).isEmpty();
    }

    @Test
    void aPrimaryKeyJoinColumnNamesTheSubclassKey() {
        assertThat(validator.schemaDrift(Checking.class)).isEmpty();
        assertThat(validator.expectedColumns(Checking.class)).extracting(column -> column.name())
                .containsExactlyInAnyOrder("overdraft", "account_id");
    }

    @Test
    void tablePerClassCarriesEveryInheritedColumn() {
        assertThat(validator.schemaDrift(Circle.class)).isEmpty();
        assertThat(validator.expectedColumns(Circle.class)).extracting(column -> column.name())
                .containsExactlyInAnyOrder("id", "color", "radius");
    }

    @Test
    void namingStrategyResolvesImplicitNames() {
        assertThat(validator.missingColumns(LineItem.class))
                .as("snake_case expects line_item")
                .containsExactly("table 'line_item' not found in database");

        SchemaValidator asIs = validator.withNaming(SchemaNamingStrategy.AS_IS);
        assertThat(asIs.tableName(LineItem.class)).isEqualTo("LineItem");
        assertThat(asIs.schemaDrift(LineItem.class)).isEmpty();
    }

    @Test
    void theEntityNameIsTheDefaultTableName() {
        assertThat(validator.tableName(InvoiceEntity.class)).isEqualTo("invoice");
        assertThat(validator.schemaDrift(InvoiceEntity.class)).isEmpty();
    }

    @Test
    void namingIsChosenByPropertyOrBean() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class, TestForgeDbAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource);

        runner.withPropertyValues("forge.db.schema-naming=as-is")
                .run(context -> assertThat(context.getBean(SchemaValidator.class).schemaDrift(LineItem.class)).isEmpty());
        runner.withBean(SchemaNamingStrategy.class, () -> SchemaNamingStrategy.AS_IS)
                .run(context -> assertThat(context.getBean(SchemaValidator.class).schemaDrift(LineItem.class)).isEmpty());
        runner.run(context -> assertThat(context.getBean(SchemaValidator.class).missingColumns(LineItem.class))
                .containsExactly("table 'line_item' not found in database"));
    }

    @Test
    void anUnknownNamingNameIsRejected() {
        assertThatThrownBy(() -> SchemaNamingStrategy.named("kebab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snake-case or as-is");
        assertThat(SchemaNamingStrategy.named("SNAKE_CASE")).isSameAs(SchemaNamingStrategy.SNAKE_CASE);
    }
}
