package io.testforge.data.factory;

import io.testforge.data.RunUniqueValues;
import io.testforge.data.cleanup.Cleanup;
import io.testforge.data.isolation.DataIsolation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Builds test objects and payloads with readable, unique defaults that a test
 * overrides only where it cares:
 *
 * <pre>{@code
 * static final Factory<Customer> CUSTOMERS = Factory.forRecord(Customer.class)
 *         .field("name", f -> f.label())                       // customer-1, customer-2, ...
 *         .field("email", f -> f.get("name") + "@example.test") // follows an overridden name
 *         .field("tier", "standard")
 *         .trait("vip", c -> c.with("tier", "vip"));
 *
 * Customer anna = CUSTOMERS.with("name", "anna").build();   // anna@example.test
 * List<Customer> vips = CUSTOMERS.as("vip").build(3);
 * }</pre>
 *
 * A factory is immutable: {@link #with}, {@link #without} and {@link #as}
 * return a new one, so a shared static declaration is safe in parallel tests.
 * Copies share the sequence behind {@link Fields#label()} and the uniqueness
 * registry, so values stay distinct across the whole family. Mistakes fail
 * loudly: an unknown field or trait, a missing value, a cycle between derived
 * fields, a value of the wrong type for a record component.
 *
 * @param <T> what the factory builds
 */
public final class Factory<T> {

    private static final int UNIQUE_ATTEMPTS = 50;

    /** Turns resolved fields into the object. */
    @FunctionalInterface
    public interface Assembler<T> {
        T assemble(Fields fields);
    }

    /** A field value computed per build; may read other fields. */
    @FunctionalInterface
    public interface Value {
        Object of(Fields fields);
    }

    /** The fields of one build, resolved lazily and at most once each. */
    public interface Fields {

        /** The value of a field in this build; derived fields see overrides. */
        <V> V get(String name);

        /** Number of this build within the factory family, starting at 1. */
        long sequence();

        /** A readable default: the factory name and the sequence, e.g. {@code customer-7}. */
        String label();

        /**
         * The label behind the current test's isolation tag, e.g.
         * {@code tf-k3x9q-t4-customer-7}: what parallel tests and runs filter by.
         */
        String tagged();

        /** A value the generator produced that no earlier build of the run used for {@code type}. */
        String unique(String type, Supplier<String> generator);

        /** The fields present in this build, in declaration order. */
        Set<String> names();
    }

    /** Undoes what {@link #create} persisted. */
    @FunctionalInterface
    public interface Undo<R> {
        void undo(R created) throws Exception;
    }

    private static final Value OMITTED = fields -> null;

    private final String name;
    private final Assembler<T> assembler;
    private final Set<String> required;
    private final Map<String, Value> fields;
    private final Map<String, UnaryOperator<Factory<T>>> traits;
    private final AtomicLong sequence;
    private final RunUniqueValues uniqueValues;

    private Factory(String name, Assembler<T> assembler, Set<String> required, Map<String, Value> fields,
                    Map<String, UnaryOperator<Factory<T>>> traits, AtomicLong sequence,
                    RunUniqueValues uniqueValues) {
        this.name = name;
        this.assembler = assembler;
        this.required = required;
        this.fields = fields;
        this.traits = traits;
        this.sequence = sequence;
        this.uniqueValues = uniqueValues;
    }

    /** A factory whose assembler builds the object from the declared fields. */
    public static <T> Factory<T> of(String name, Assembler<T> assembler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("factory name must not be blank");
        }
        Objects.requireNonNull(assembler, "assembler");
        return new Factory<>(name, assembler, Set.of(), Map.of(), Map.of(), new AtomicLong(),
                new RunUniqueValues());
    }

    /**
     * A factory for request payloads: every declared field becomes a map entry,
     * in declaration order; {@link #without} leaves the key out entirely.
     */
    public static Factory<Map<String, Object>> map(String name) {
        return of(name, fields -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            fields.names().forEach(field -> payload.put(field, fields.get(field)));
            return payload;
        });
    }

    /**
     * A factory for a record: each component is a field that needs a default or
     * an override before {@link #build()}. Named after the record, e.g.
     * {@code customer} for {@code Customer}.
     */
    public static <R extends Record> Factory<R> forRecord(Class<R> type) {
        Objects.requireNonNull(type, "type");
        RecordComponent[] components = type.getRecordComponents();
        Constructor<R> constructor;
        try {
            constructor = type.getDeclaredConstructor(
                    Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            constructor.trySetAccessible();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no canonical constructor on " + type.getName(), e);
        }
        String simple = type.getSimpleName();
        String name = simple.substring(0, 1).toLowerCase(Locale.ROOT) + simple.substring(1);
        Set<String> required = new LinkedHashSet<>();
        Arrays.stream(components).forEach(component -> required.add(component.getName()));
        Assembler<R> assembler = fields -> {
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                args[i] = checked(name, components[i], fields.get(components[i].getName()));
            }
            try {
                return constructor.newInstance(args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(name + ": the constructor rejected the values: "
                        + e.getCause(), e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(name + ": cannot call the constructor of " + type.getName(), e);
            }
        };
        return new Factory<>(name, assembler, Collections.unmodifiableSet(required), Map.of(), Map.of(),
                new AtomicLong(), new RunUniqueValues());
    }

    /** Declares a field with a constant default. */
    public Factory<T> field(String field, Object value) {
        return field(field, constant(value));
    }

    /** Declares a field computed per build, e.g. {@code f -> f.label()}. */
    public Factory<T> field(String field, Value value) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(name + ": field name must not be blank");
        }
        Map<String, Value> copy = new LinkedHashMap<>(fields);
        copy.put(field, value == null ? constant(null) : value);
        return copyWith(Collections.unmodifiableMap(copy), traits);
    }

    /** Overrides a declared field with a constant. */
    public Factory<T> with(String field, Object value) {
        return with(field, constant(value));
    }

    /** Overrides a declared field with a value computed per build. */
    public Factory<T> with(String field, Value value) {
        requireKnown(field);
        return field(field, value);
    }

    /** Leaves a field out: a payload map drops the key, a record or assembler gets {@code null}. */
    public Factory<T> without(String field) {
        requireKnown(field);
        Map<String, Value> copy = new LinkedHashMap<>(fields);
        copy.put(field, OMITTED);
        return copyWith(Collections.unmodifiableMap(copy), traits);
    }

    /** Names a reusable set of overrides, applied with {@link #as}. */
    public Factory<T> trait(String trait, UnaryOperator<Factory<T>> change) {
        if (trait == null || trait.isBlank()) {
            throw new IllegalArgumentException(name + ": trait name must not be blank");
        }
        Objects.requireNonNull(change, "change");
        Map<String, UnaryOperator<Factory<T>>> copy = new LinkedHashMap<>(traits);
        copy.put(trait, change);
        return copyWith(fields, Collections.unmodifiableMap(copy));
    }

    /** Applies traits in the given order; a later one wins on the same field. */
    public Factory<T> as(String... traitNames) {
        Factory<T> result = this;
        for (String trait : traitNames) {
            UnaryOperator<Factory<T>> change = traits.get(trait);
            if (change == null) {
                throw new IllegalArgumentException(name + " has no trait '" + trait + "'; known: " + traits.keySet());
            }
            result = Objects.requireNonNull(change.apply(result), () -> name + ": trait '" + trait + "' returned null");
        }
        return result;
    }

    /** Shares a uniqueness registry, typically the {@link RunUniqueValues} bean, with other factories. */
    public Factory<T> uniqueValues(RunUniqueValues registry) {
        return new Factory<>(name, assembler, required, fields, traits, sequence,
                Objects.requireNonNull(registry, "registry"));
    }

    public T build() {
        return assembler.assemble(new Build(sequence.incrementAndGet()));
    }

    /**
     * Builds an object, persists it on the environment and registers its undo
     * with {@link Cleanup} as {@code "<factory> <result>"}, so it is removed after
     * the test even when the test fails:
     *
     * <pre>{@code
     * String id = CUSTOMERS.create(c -> api.request().body(c).post("/customers").path("id"),
     *         created -> api.request().delete("/customers/{id}", created));
     * }</pre>
     *
     * Nothing is registered when persisting throws.
     */
    public <R> R create(Function<? super T, R> persist, Undo<? super R> undo) {
        Objects.requireNonNull(persist, "persist");
        Objects.requireNonNull(undo, "undo");
        R created = persist.apply(build());
        Cleanup.register(name + " " + created, () -> undo.undo(created));
        return created;
    }

    public List<T> build(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        List<T> built = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            built.add(build());
        }
        return built;
    }

    public String name() {
        return name;
    }

    private Factory<T> copyWith(Map<String, Value> newFields, Map<String, UnaryOperator<Factory<T>>> newTraits) {
        return new Factory<>(name, assembler, required, newFields, newTraits, sequence, uniqueValues);
    }

    private void requireKnown(String field) {
        if (!fields.containsKey(field) && !required.contains(field)) {
            throw new IllegalArgumentException(name + " has no field '" + field + "'; known: " + known());
        }
    }

    private Set<String> known() {
        Set<String> known = new LinkedHashSet<>(required);
        known.addAll(fields.keySet());
        return known;
    }

    private static Value constant(Object value) {
        return fields -> value;
    }

    private static Object checked(String factory, RecordComponent component, Object value) {
        Class<?> type = component.getType();
        if (value == null) {
            if (type.isPrimitive()) {
                throw new IllegalStateException(factory + "." + component.getName() + " is " + type.getName()
                        + " and cannot be null");
            }
            return null;
        }
        Class<?> boxed = MethodType.methodType(type).wrap().returnType();
        if (!boxed.isInstance(value)) {
            throw new IllegalStateException(factory + "." + component.getName() + " expects " + type.getName()
                    + ", got " + value.getClass().getName() + " '" + value + "'");
        }
        return value;
    }

    private final class Build implements Fields {

        private final long number;
        private final Map<String, Object> resolved = new HashMap<>();
        private final Set<String> resolving = new LinkedHashSet<>();

        private Build(long number) {
            this.number = number;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> V get(String field) {
            if (resolved.containsKey(field)) {
                return (V) resolved.get(field);
            }
            Value value = fields.get(field);
            if (value == null) {
                if (required.contains(field)) {
                    throw new IllegalStateException(name + ": no value for field '" + field
                            + "'; declare it with field(\"" + field + "\", ...) or override it with with(...)");
                }
                throw new IllegalArgumentException(name + " has no field '" + field + "'; known: " + known());
            }
            if (!resolving.add(field)) {
                throw new IllegalStateException(name + ": fields depend on each other in a cycle: "
                        + String.join(" -> ", resolving) + " -> " + field);
            }
            Object result = value.of(this);
            resolving.remove(field);
            resolved.put(field, result);
            return (V) result;
        }

        @Override
        public long sequence() {
            return number;
        }

        @Override
        public String label() {
            return name + "-" + number;
        }

        @Override
        public String tagged() {
            return DataIsolation.tag(label());
        }

        @Override
        public String unique(String type, Supplier<String> generator) {
            return uniqueValues.generate(type, generator, UNIQUE_ATTEMPTS);
        }

        @Override
        public Set<String> names() {
            Set<String> present = new LinkedHashSet<>();
            fields.forEach((field, value) -> {
                if (value != OMITTED) {
                    present.add(field);
                }
            });
            return Collections.unmodifiableSet(present);
        }
    }
}
