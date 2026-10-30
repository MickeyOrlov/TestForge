package io.testforge.db.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.TestForgeDbAutoConfiguration;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaIndexForeignKeyDriftTest {

    private static DataSource dataSource;
    private static SchemaValidator validator;

    // --- indexes ---------------------------------------------------------------

    @Entity
    @Table(name = "users_ok",
            indexes = @Index(name = "ix_tenant_created", columnList = "tenant_id, created_at DESC"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "login"}))
    static class UserOk {
        @Id
        Long id;

        @Column(unique = true)
        String email;

        Long tenantId;
        String login;
        java.time.Instant createdAt;
    }

    /** Same mapping, against a table whose indexes were dropped or changed. */
    @Entity
    @Table(name = "users_drifted",
            indexes = @Index(columnList = "tenant_id, created_at"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "login"}))
    static class UserDrifted {
        @Id
        Long id;

        @Column(unique = true)
        String email;

        Long tenantId;
        String login;
        java.time.Instant createdAt;
    }

    @Entity
    @Table(name = "events", indexes = @Index(columnList = "kind"))
    static class Event {
        @Id
        Long id;

        String kind;
    }

    @Entity
    static class AuditEvent extends Event {
        String actor;
    }

    // --- foreign keys ----------------------------------------------------------

    @Entity
    @Table(name = "customers")
    static class Customer {
        @Id
        Long id;

        String code;
    }

    @Entity
    @Table(name = "legacy_customers")
    static class LegacyCustomer {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "orders_ok")
    static class OrderOk {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_id", foreignKey = @ForeignKey(name = "fk_customer"))
        Customer customer;
    }

    @Entity
    @Table(name = "orders_no_fk")
    static class OrderWithoutKey {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_id", foreignKey = @ForeignKey(ConstraintMode.CONSTRAINT))
        Customer customer;
    }

    @Entity
    @Table(name = "orders_wrong_target")
    static class OrderWrongTarget {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_id", foreignKey = @ForeignKey(name = "fk_customer"))
        Customer customer;
    }

    @Entity
    @Table(name = "orders_no_fk")
    static class OrderImplicit {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_id")
        Customer customer;
    }

    @Entity
    @Table(name = "orders_no_fk")
    static class OrderOptedOut {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        Customer customer;
    }

    @Entity
    @Table(name = "orders_by_code")
    static class OrderByCode {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "customer_code", referencedColumnName = "code",
                foreignKey = @ForeignKey(ConstraintMode.CONSTRAINT))
        Customer customer;
    }

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:schema_index_fk_test;DB_CLOSE_DELAY=-1");
        validator = new SchemaValidator(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    create table users_ok (id bigint primary key, email varchar(100), tenant_id bigint,
                        login varchar(50), created_at timestamp)""");
            // names deliberately differ from the mapping: matching is by columns
            stmt.execute("create unique index some_generated_name on users_ok (email)");
            stmt.execute("create index ix_other_name on users_ok (tenant_id, created_at, id)");
            stmt.execute("alter table users_ok add constraint uq_login unique (login, tenant_id)");
            stmt.execute("create index extra_index_nobody_declared on users_ok (login)");

            stmt.execute("""
                    create table users_drifted (id bigint primary key, email varchar(100), tenant_id bigint,
                        login varchar(50), created_at timestamp)""");
            stmt.execute("create index email_not_unique on users_drifted (email)");
            stmt.execute("create index wrong_order on users_drifted (created_at, tenant_id)");

            stmt.execute("create table events (id bigint primary key, dtype varchar(31), kind varchar(20), actor varchar(20))");
            stmt.execute("create index ix_kind on events (kind)");

            stmt.execute("create table customers (id bigint primary key, code varchar(20) unique)");
            stmt.execute("create table legacy_customers (id bigint primary key)");
            stmt.execute("create table orders_ok (id bigint primary key, customer_id bigint references customers(id))");
            stmt.execute("create table orders_no_fk (id bigint primary key, customer_id bigint)");
            stmt.execute("create table orders_wrong_target (id bigint primary key, customer_id bigint references legacy_customers(id))");
            stmt.execute("create table orders_by_code (id bigint primary key, customer_code varchar(20) references customers(code))");
        }
    }

    @Test
    void declaredIndexesMatchByColumnsNotNames() {
        assertThat(validator.indexDrift(UserOk.class)).isEmpty();
        assertThat(validator.schemaDrift(UserOk.class)).isEmpty();
    }

    @Test
    void droppedOrWeakenedIndexesAreReported() {
        assertThat(validator.indexDrift(UserDrifted.class)).containsExactlyInAnyOrder(
                "users_drifted: mapping declares an index on (tenant_id, created_at) but the database has none",
                "users_drifted: mapping declares a unique index on (tenant_id, login) but the database has none",
                "users_drifted: mapping declares a unique index on (email) but the database has none");
    }

    @Test
    void indexDriftIsPartOfSchemaDrift() {
        assertThat(validator.schemaDrift(UserDrifted.class)).hasSize(3);
    }

    @Test
    void singleTableSubclassesUseTheRootsIndexes() {
        assertThat(validator.declaredIndexes(AuditEvent.class))
                .containsExactly(new SchemaValidator.DeclaredIndex(java.util.List.of("kind"), false));
        assertThat(validator.indexDrift(AuditEvent.class)).isEmpty();
    }

    @Test
    void anExplicitForeignKeyThatExistsIsClean() {
        assertThat(validator.foreignKeyDrift(OrderOk.class)).isEmpty();
        assertThat(validator.schemaDrift(OrderOk.class)).isEmpty();
    }

    @Test
    void aMissingExplicitForeignKeyIsReported() {
        assertThat(validator.foreignKeyDrift(OrderWithoutKey.class)).containsExactly(
                "orders_no_fk.customer_id: mapping declares a foreign key to customers but the database has none");
    }

    @Test
    void aForeignKeyToTheWrongTableIsReported() {
        assertThat(validator.foreignKeyDrift(OrderWrongTarget.class)).containsExactly(
                "orders_wrong_target.customer_id: foreign key points to legacy_customers.id but the mapping expects customers");
    }

    @Test
    void referencedColumnNameIsCompared() {
        assertThat(validator.foreignKeyDrift(OrderByCode.class)).isEmpty();
    }

    @Test
    void implicitKeysAreOnlyExpectedInStrictMode() {
        assertThat(validator.foreignKeyDrift(OrderImplicit.class)).as("default: declared only").isEmpty();
        assertThat(validator.withImplicitForeignKeys(true).foreignKeyDrift(OrderImplicit.class)).containsExactly(
                "orders_no_fk.customer_id: mapping declares a foreign key to customers but the database has none");
    }

    @Test
    void noConstraintIsNeverExpected() {
        assertThat(validator.withImplicitForeignKeys(true).foreignKeyDrift(OrderOptedOut.class)).isEmpty();
    }

    @Test
    void strictModeIsChosenByProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class,
                        TestForgeDbAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource);

        runner.withPropertyValues("forge.db.schema-foreign-keys=all")
                .run(context -> assertThat(context.getBean(SchemaValidator.class).foreignKeyDrift(OrderImplicit.class))
                        .hasSize(1));
        runner.run(context -> assertThat(context.getBean(SchemaValidator.class).foreignKeyDrift(OrderImplicit.class))
                .isEmpty());
    }
}
