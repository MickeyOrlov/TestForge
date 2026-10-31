package io.testforge.data.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.data.Generators;
import io.testforge.data.RunUniqueValues;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FactoryTest {

    record Customer(String name, String email, String tier, int age) {
    }

    private static final Factory<Customer> CUSTOMERS = Factory.forRecord(Customer.class)
            .field("name", f -> f.label())
            .field("email", f -> f.get("name") + "@example.test")
            .field("tier", "standard")
            .field("age", 30)
            .trait("vip", c -> c.with("tier", "vip"))
            .trait("senior", c -> c.with("age", 70));

    @Test
    void defaultsAreReadableAndDistinctPerBuild() {
        Factory<Customer> customers = Factory.forRecord(Customer.class)
                .field("name", f -> f.label())
                .field("email", f -> f.get("name") + "@example.test")
                .field("tier", "standard")
                .field("age", 30);

        assertThat(customers.build()).isEqualTo(new Customer("customer-1", "customer-1@example.test", "standard", 30));
        assertThat(customers.build().name()).isEqualTo("customer-2");
    }

    @Test
    void anOverrideFlowsIntoTheFieldsDerivedFromIt() {
        Customer anna = CUSTOMERS.with("name", "anna").build();

        assertThat(anna.name()).isEqualTo("anna");
        assertThat(anna.email()).isEqualTo("anna@example.test");
    }

    @Test
    void overridesReturnANewFactoryAndLeaveTheDeclarationAlone() {
        Factory<Customer> vip = CUSTOMERS.with("tier", "vip");

        assertThat(vip.build().tier()).isEqualTo("vip");
        assertThat(CUSTOMERS.build().tier()).isEqualTo("standard");
    }

    @Test
    void copiesShareTheSequenceSoLabelsNeverRepeat() {
        Set<String> names = new HashSet<>();
        names.add(CUSTOMERS.build().name());
        names.add(CUSTOMERS.as("vip").build().name());
        names.add(CUSTOMERS.with("tier", "x").build().name());

        assertThat(names).hasSize(3);
    }

    @Test
    void traitsApplyInOrderAndLaterOnesWin() {
        Customer customer = CUSTOMERS.as("vip", "senior").build();

        assertThat(customer.tier()).isEqualTo("vip");
        assertThat(customer.age()).isEqualTo(70);
        assertThat(CUSTOMERS.as("vip").with("tier", "gold").build().tier()).isEqualTo("gold");
    }

    @Test
    void buildsSeveralAtOnce() {
        List<Customer> customers = CUSTOMERS.as("vip").build(3);

        assertThat(customers).hasSize(3).allMatch(c -> c.tier().equals("vip"));
        assertThat(customers).extracting(Customer::name).doesNotHaveDuplicates();
    }

    @Test
    void aDerivedFieldIsResolvedOncePerBuild() {
        AtomicInteger calls = new AtomicInteger();
        Factory<Map<String, Object>> payloads = Factory.map("order")
                .field("id", f -> "id-" + calls.incrementAndGet())
                .field("ref", f -> "ref-" + f.get("id"))
                .field("copy", f -> f.get("id"));

        assertThat(payloads.build()).containsEntry("ref", "ref-id-1").containsEntry("copy", "id-1");
        assertThat(calls).hasValue(1);
    }

    @Test
    void aMapFactoryKeepsDeclarationOrderAndDropsOmittedKeys() {
        Factory<Map<String, Object>> orders = Factory.map("order")
                .field("item", "book")
                .field("quantity", 1)
                .field("note", null);

        assertThat(orders.build()).containsExactly(
                Map.entry("item", "book"), Map.entry("quantity", 1), new AbstractMap.SimpleEntry<>("note", null));
        assertThat(orders.without("quantity").build()).containsOnlyKeys("item", "note");
    }

    @Test
    void withoutOnARecordGivesNull() {
        assertThat(CUSTOMERS.without("email").build().email()).isNull();
    }

    @Test
    void uniqueValuesNeverRepeatWithinTheRegistry() {
        RunUniqueValues registry = new RunUniqueValues();
        Iterator<String> codes = List.of("a", "a", "b").iterator();
        Factory<Map<String, Object>> coupons = Factory.map("coupon")
                .field("code", f -> f.unique("coupon-code", codes::next))
                .uniqueValues(registry);

        assertThat(coupons.build(2)).extracting(m -> m.get("code")).containsExactly("a", "b");
        assertThat(registry.snapshot().get("coupon-code")).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void parallelBuildsGetDistinctLabelsAndUniqueValues() throws Exception {
        Factory<Map<String, Object>> users = Factory.map("user")
                .field("login", f -> f.label())
                .field("phone", f -> f.unique("phone", Generators.phone("+1555", 7)));
        Set<Object> logins = ConcurrentHashMap.newKeySet();
        Set<Object> phones = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 400; i++) {
                pool.submit(() -> {
                    Map<String, Object> user = users.build();
                    logins.add(user.get("login"));
                    phones.add(user.get("phone"));
                });
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(logins).hasSize(400);
        assertThat(phones).hasSize(400);
    }

    @Test
    void anUnknownFieldOrTraitIsNamedWithTheKnownOnes() {
        assertThatThrownBy(() -> CUSTOMERS.with("emial", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customer has no field 'emial'")
                .hasMessageContaining("email");
        assertThatThrownBy(() -> CUSTOMERS.as("gold"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no trait 'gold'")
                .hasMessageContaining("vip");
        assertThatThrownBy(() -> Factory.map("order").field("a", f -> f.get("b")).build())
                .hasMessageContaining("order has no field 'b'");
    }

    @Test
    void aRecordComponentWithoutAValueFailsBeforeTheConstructor() {
        Factory<Customer> incomplete = Factory.forRecord(Customer.class).field("name", "n").field("email", "e");

        assertThatThrownBy(incomplete::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customer: no value for field 'tier'");
        assertThat(incomplete.with("tier", "t").with("age", 1).build().tier())
                .as("a component can be supplied by an override alone").isEqualTo("t");
    }

    @Test
    void aValueOfTheWrongTypeNamesTheComponent() {
        assertThatThrownBy(() -> CUSTOMERS.with("age", "seventy").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customer.age expects int, got java.lang.String 'seventy'");
        assertThatThrownBy(() -> CUSTOMERS.with("age", (Object) null).build())
                .hasMessageContaining("customer.age is int and cannot be null");
    }

    @Test
    void aCycleBetweenDerivedFieldsShowsThePath() {
        Factory<Map<String, Object>> broken = Factory.map("loop")
                .field("a", f -> f.get("b"))
                .field("b", f -> f.get("a"));

        assertThatThrownBy(broken::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a -> b -> a");
    }

    record Positive(int value) {
        Positive {
            if (value <= 0) {
                throw new IllegalArgumentException("value must be positive");
            }
        }
    }

    @Test
    void aConstructorRejectionKeepsItsReason() {
        assertThatThrownBy(() -> Factory.forRecord(Positive.class).field("value", -1).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive: the constructor rejected the values")
                .hasRootCauseMessage("value must be positive");
    }

    @Test
    void anAssemblerBuildsClassesThatAreNotRecords() {
        Factory<StringBuilder> notes = Factory.of("note", f -> new StringBuilder(f.<String>get("text")))
                .field("text", f -> "note " + f.sequence());

        assertThat(notes.build().toString()).isEqualTo("note 1");
        assertThat(notes.name()).isEqualTo("note");
    }
}
