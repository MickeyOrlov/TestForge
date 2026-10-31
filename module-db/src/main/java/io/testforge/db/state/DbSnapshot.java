package io.testforge.db.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Several tables at one moment — the ones an action is allowed to touch and
 * the ones it is not. {@link #diff()} reads them all again; its expectations
 * fail when a table moved that the test did not name: the cascade delete, the
 * audit row written twice, the stray update in a table nobody looked at.
 *
 * <pre>{@code
 * DbSnapshot before = db.watch(
 *         db.table("orders").keyedBy("id").where("customer_id = ?", customer),
 *         db.table("payments").keyedBy("id").where("customer_id = ?", customer),
 *         db.table("loyalty_points").keyedBy("customer_id").where("customer_id = ?", customer));
 * api.pay(order);
 * before.diff().expect()
 *         .table("orders", t -> t.changed(1, "status"))
 *         .table("payments", t -> t.added(1))
 *         .nothingElse();                      // loyalty_points must not have moved
 * }</pre>
 */
public final class DbSnapshot {

    private final Map<String, TableSnapshot> tables;

    DbSnapshot(Map<String, TableSnapshot> tables) {
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    /** The watched tables, by the name each was watched under. */
    public java.util.Set<String> tables() {
        return tables.keySet();
    }

    public DbDiff diff() {
        Map<String, TableDiff> diffs = new LinkedHashMap<>();
        tables.forEach((name, snapshot) -> diffs.put(name, snapshot.diff()));
        return new DbDiff(diffs);
    }
}
