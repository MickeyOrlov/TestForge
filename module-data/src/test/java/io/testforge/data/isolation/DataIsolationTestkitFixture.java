package io.testforge.data.isolation;

import io.testforge.data.cleanup.CleanupExtension;
import io.testforge.data.factory.Factory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Run only through EngineTestKit by {@link DataIsolationExtensionTest}. */
@ExtendWith({DataIsolationExtension.class, CleanupExtension.class})
class DataIsolationTestkitFixture {

    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static final List<String> UNDONE = new CopyOnWriteArrayList<>();

    static final Factory<Map<String, Object>> ORDERS = Factory.map("order")
            .field("reference", f -> f.tagged());

    @Test
    void first() {
        SEEN.add(DataIsolation.scopeTag());
    }

    @Test
    void second() {
        SEEN.add(DataIsolation.scopeTag());
    }

    @Test
    void createsAnOrder() {
        String reference = ORDERS.create(order -> (String) order.get("reference"),
                created -> UNDONE.add(created + " undone in " + DataIsolation.scopeTag()));
        SEEN.add(reference);
    }

    @Test
    void persistingFails() {
        ORDERS.create(order -> {
            throw new IllegalStateException("backend down");
        }, created -> UNDONE.add("never"));
    }
}
