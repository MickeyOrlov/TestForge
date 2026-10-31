package io.testforge.db.contract.usage;

import io.testforge.db.contract.diff.DbChange;
import io.testforge.db.schema.SchemaValidator;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default usage: every entity the context's JPA persistence units know,
 * read from their metamodel the first time a check needs it — not at startup,
 * so the bean costs nothing in a context that never runs the check.
 */
public final class JpaEntityUsage implements DbSchemaUsage {

    private static final Logger log = LoggerFactory.getLogger(JpaEntityUsage.class);

    private final Supplier<List<EntityManagerFactory>> factories;
    private final Supplier<SchemaValidator> validator;
    private volatile DbSchemaUsage resolved;

    public JpaEntityUsage(Supplier<List<EntityManagerFactory>> factories, Supplier<SchemaValidator> validator) {
        this.factories = factories;
        this.validator = validator;
    }

    @Override
    public List<String> usersOf(DbChange change) {
        DbSchemaUsage usage = resolved;
        if (usage == null) {
            usage = resolve();
            resolved = usage;
        }
        return usage.usersOf(change);
    }

    private DbSchemaUsage resolve() {
        Set<Class<?>> entities = new LinkedHashSet<>();
        for (EntityManagerFactory factory : factories.get()) {
            for (EntityType<?> type : factory.getMetamodel().getEntities()) {
                Class<?> javaType = type.getJavaType();
                if (javaType != null && javaType.isAnnotationPresent(Entity.class)) {
                    entities.add(javaType);
                }
            }
        }
        if (entities.isEmpty()) {
            return NONE;
        }
        SchemaValidator schemaValidator = Objects.requireNonNullElseGet(validator.get(),
                () -> new SchemaValidator((DataSource) null));
        List<io.testforge.db.schema.EntityMapping> mappings = new ArrayList<>();
        for (Class<?> entity : entities) {
            try {
                mappings.add(schemaValidator.mapping(entity));
            } catch (RuntimeException e) {
                // one entity the validator cannot resolve must not hide the others
                log.warn("Could not resolve the mapping of {} for the database contract report: {}",
                        entity.getName(), e.getMessage());
            }
        }
        return DbSchemaUsage.ofMappings(mappings);
    }
}
